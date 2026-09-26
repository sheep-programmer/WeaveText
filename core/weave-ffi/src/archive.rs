//! 模型包解压：`.tar.bz2` → 目录，只保留白名单里的文件，去掉顶层目录，拒绝路径穿越。
//! Model archive extraction: `.tar.bz2` → directory, keeping only whitelisted files, stripping the
//! top-level folder and rejecting path traversal.

use std::fs;
use std::io::{self, BufReader};
use std::path::{Component, Path, PathBuf};

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;

/// 单个文件上限，防止异常包写满存储。 Per-file cap.
const MAX_FILE: u64 = 1024 * 1024 * 1024;

/// 解出 `keep` 中列出的文件名（按文件名匹配，忽略所在目录）；`keep` 为空则全部解出（仍去掉顶层目录）。
/// 先写入 `dest` 下的临时目录，全部成功后再逐个改名，失败时不留残缺文件。
/// Extract files named in `keep` (matched by file name) or everything when empty. Writes into a temp
/// dir under `dest` and renames on success, so failures leave nothing half-written.
pub fn extract_tar_bz2(archive: &Path, dest: &Path, keep: &[String]) -> Result<Vec<String>, String> {
    let file = fs::File::open(archive).map_err(|e| format!("{}: {e}", archive.display()))?;
    let decoder = bzip2::read::BzDecoder::new(BufReader::with_capacity(1 << 16, file));
    let mut tar = tar::Archive::new(decoder);
    fs::create_dir_all(dest).map_err(|e| e.to_string())?;
    let tmp = dest.join(".extracting");
    let _ = fs::remove_dir_all(&tmp);
    fs::create_dir_all(&tmp).map_err(|e| e.to_string())?;
    let result = (|| -> Result<Vec<String>, String> {
        let mut written = Vec::new();
        for entry in tar.entries().map_err(|e| format!("tar: {e}"))? {
            let mut entry = entry.map_err(|e| format!("tar: {e}"))?;
            if !entry.header().entry_type().is_file() {
                continue;
            }
            let path = entry.path().map_err(|e| e.to_string())?.into_owned();
            let rel = strip_top(&path).ok_or_else(|| format!("unsafe path in archive: {}", path.display()))?;
            let name = rel.file_name().and_then(|n| n.to_str()).unwrap_or("").to_string();
            let target = if keep.is_empty() {
                rel.clone()
            } else if keep.iter().any(|k| k == &name) {
                PathBuf::from(&name)
            } else {
                continue;
            };
            if entry.header().size().unwrap_or(0) > MAX_FILE {
                return Err(format!("file too large: {name}"));
            }
            let out = tmp.join(&target);
            if let Some(parent) = out.parent() {
                fs::create_dir_all(parent).map_err(|e| e.to_string())?;
            }
            let mut f = fs::File::create(&out).map_err(|e| format!("{}: {e}", out.display()))?;
            io::copy(&mut (&mut entry).take_limited(MAX_FILE), &mut f).map_err(|e| format!("{name}: {e}"))?;
            written.push(target.to_string_lossy().into_owned());
        }
        if let Some(missing) = keep.iter().find(|k| !written.iter().any(|w| w == *k)) {
            return Err(format!("archive is missing {missing}"));
        }
        for w in &written {
            let from = tmp.join(w);
            let to = dest.join(w);
            if let Some(parent) = to.parent() {
                fs::create_dir_all(parent).map_err(|e| e.to_string())?;
            }
            let _ = fs::remove_file(&to);
            fs::rename(&from, &to).map_err(|e| format!("{}: {e}", to.display()))?;
        }
        Ok(written)
    })();
    let _ = fs::remove_dir_all(&tmp);
    result
}

/// 去掉第一层目录并校验每一段都是普通名字。 Strip the first component; every part must be normal.
fn strip_top(p: &Path) -> Option<PathBuf> {
    let parts: Vec<&str> = p
        .components()
        .map(|c| match c {
            Component::Normal(s) => s.to_str(),
            Component::CurDir => Some("."),
            _ => None,
        })
        .collect::<Option<Vec<_>>>()?;
    let parts: Vec<&str> = parts.into_iter().filter(|s| *s != ".").collect();
    let rest = if parts.len() > 1 { &parts[1..] } else { &parts[..] };
    (!rest.is_empty()).then(|| rest.iter().collect())
}

trait TakeLimited: io::Read + Sized {
    fn take_limited(self, n: u64) -> io::Take<Self> {
        self.take(n)
    }
}
impl<R: io::Read> TakeLimited for R {}

/// JNI：`keep` 为换行分隔的文件名。成功返回 null，失败返回错误信息。
/// JNI: `keep` is newline-separated file names; null on success, else the error.
#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_models_NativeArchive_nativeExtractTarBz2(
    mut env: JNIEnv,
    _c: JClass,
    archive: JString,
    dest: JString,
    keep: JString,
) -> jstring {
    let get = |env: &mut JNIEnv, s: &JString| -> Option<String> {
        if s.is_null() {
            None
        } else {
            env.get_string(s).ok().map(Into::into)
        }
    };
    let (Some(a), Some(d)) = (get(&mut env, &archive), get(&mut env, &dest)) else {
        return std::ptr::null_mut();
    };
    let keep: Vec<String> = get(&mut env, &keep)
        .unwrap_or_default()
        .lines()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_owned)
        .collect();
    let r = std::panic::catch_unwind(|| extract_tar_bz2(Path::new(&a), Path::new(&d), &keep))
        .unwrap_or_else(|_| Err("extraction panicked".into()));
    match r {
        Ok(_) => std::ptr::null_mut(),
        Err(e) => env.new_string(e).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extracts_whitelisted_files_from_real_archive() {
        // 用系统 tar 造一个带顶层目录的 .tar.bz2。 Build a real archive with the system tar.
        let root = std::env::temp_dir().join(format!("weave-arch-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        let src = root.join("src/model-dir");
        fs::create_dir_all(src.join("test_wavs")).unwrap();
        fs::write(src.join("model.int8.onnx"), b"onnx").unwrap();
        fs::write(src.join("tokens.txt"), b"a 1\n").unwrap();
        fs::write(src.join("test_wavs/0.wav"), b"wav").unwrap();
        let archive = root.join("m.tar.bz2");
        let ok = std::process::Command::new("tar")
            .args(["cjf", archive.to_str().unwrap(), "-C", root.join("src").to_str().unwrap(), "model-dir"])
            .status()
            .unwrap()
            .success();
        assert!(ok);
        let dest = root.join("out");
        let keep = vec!["model.int8.onnx".to_string(), "tokens.txt".to_string()];
        let got = extract_tar_bz2(&archive, &dest, &keep).unwrap();
        assert_eq!(got.len(), 2);
        assert_eq!(fs::read(dest.join("tokens.txt")).unwrap(), b"a 1\n");
        assert!(!dest.join("test_wavs").exists());
        let missing = extract_tar_bz2(&archive, &root.join("out2"), &["nope.onnx".to_string()]);
        assert!(missing.unwrap_err().contains("missing"));
        assert!(!root.join("out2/.extracting").exists());
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn rejects_traversal() {
        assert!(strip_top(Path::new("dir/../../etc/passwd")).is_none());
        assert_eq!(strip_top(Path::new("dir/a/b.txt")).unwrap(), PathBuf::from("a/b.txt"));
        assert_eq!(strip_top(Path::new("./dir/x")).unwrap(), PathBuf::from("x"));
    }
}
