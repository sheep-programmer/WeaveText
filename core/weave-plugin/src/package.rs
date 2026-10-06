//! 插件包：按目录内容或 ZIP 内容识别，不依赖文件后缀。
//! Plugin packages are identified by directory/archive contents, regardless of filename.

use std::fs;
use std::io::Read;
use std::path::{Component, Path, PathBuf};

use crate::manifest::Manifest;

/// 解包上限（防 zip 炸弹与存储耗尽）。 Extraction caps (zip bombs, storage exhaustion).
/// 单个文件 / Per entry.
const MAX_ENTRY_BYTES: u64 = 64 * 1024 * 1024;
/// 全部文件合计 / All entries together.
const MAX_TOTAL_BYTES: u64 = 256 * 1024 * 1024;
/// 条目数 / Entry count.
const MAX_ENTRIES: usize = 4096;

/// 读取目录里的 manifest.yaml。Read manifest.yaml from an unpacked plugin directory.
pub fn read_dir_manifest(dir: &Path) -> Result<Manifest, String> {
    let text = fs::read_to_string(dir.join("manifest.yaml"))
        .map_err(|e| format!("{}: {e}", dir.join("manifest.yaml").display()))?;
    let m = Manifest::parse(&text)?;
    let entry = safe_relative(&m.entry).ok_or("plugin: unsafe entry script")?;
    if !dir.join(entry).is_file() {
        return Err(format!("{}: entry {} not found", dir.display(), m.entry));
    }
    Ok(m)
}

/// 把 zip 里的条目名规整为安全的相对路径；含 `..`、绝对路径的条目返回 None。
/// Sanitises an entry name into a safe relative path; rejects `..` and absolute paths.
pub fn safe_relative(name: &str) -> Option<PathBuf> {
    let name = name.replace('\\', "/");
    let p = Path::new(&name);
    let mut out = PathBuf::new();
    for c in p.components() {
        match c {
            Component::Normal(s) => out.push(s),
            Component::CurDir => {}
            _ => return None,
        }
    }
    (!out.as_os_str().is_empty()).then_some(out)
}

fn open_zip(xipk: &Path) -> Result<zip::ZipArchive<fs::File>, String> {
    let f = fs::File::open(xipk).map_err(|e| format!("{}: {e}", xipk.display()))?;
    zip::ZipArchive::new(f).map_err(|e| format!("{}: not a valid plugin archive: {e}", xipk.display()))
}

pub fn is_archive(path: &Path) -> bool {
    let Ok(mut f) = fs::File::open(path) else { return false };
    let mut signature = [0; 4];
    f.read_exact(&mut signature).is_ok() && signature == *b"PK\x03\x04"
}

/// 找出包内公共前缀：有的打包工具会把所有文件放进一层目录。
/// Some packers wrap everything in one top-level folder; find that prefix.
fn manifest_prefix(names: &[String]) -> Option<String> {
    let mut best: Option<String> = None;
    for n in names {
        let n = n.replace('\\', "/");
        if let Some(prefix) = n.strip_suffix("manifest.yaml") {
            if (prefix.is_empty() || prefix.ends_with('/'))
                && best.as_ref().is_none_or(|b| prefix.len() < b.len())
            {
                best = Some(prefix.to_string());
            }
        }
    }
    best
}

/// 只读 `.xipk` 里的 manifest。Read just the manifest out of a `.xipk`.
pub fn read_xipk_manifest(xipk: &Path) -> Result<Manifest, String> {
    let mut z = open_zip(xipk)?;
    if z.len() > MAX_ENTRIES { return Err("plugin archive: too many entries".into()); }
    let names: Vec<String> = z.file_names().map(str::to_string).collect();
    let prefix = manifest_prefix(&names).ok_or("plugin archive: manifest.yaml not found")?;
    let mut text = String::new();
    z.by_name(&format!("{prefix}manifest.yaml"))
        .map_err(|e| e.to_string())?
        .take(1024 * 1024)
        .read_to_string(&mut text)
        .map_err(|e| format!("plugin manifest: {e}"))?;
    let manifest = Manifest::parse(&text)?;
    let entry = safe_relative(&manifest.entry).ok_or("plugin archive: unsafe entry script")?;
    let name = format!("{prefix}{}", entry.to_string_lossy().replace('\\', "/"));
    if z.by_name(&name).is_err() { return Err(format!("plugin archive: entry {} not found", manifest.entry)); }
    Ok(manifest)
}

/// 把 `.xipk` 解包到 `plugins_dir/<id>/`（先解到临时目录再替换，失败不留半截）。
/// Unpack a `.xipk` into `plugins_dir/<id>/` (via a temp dir, so failures leave nothing behind).
pub fn install_xipk(xipk: &Path, plugins_dir: &Path) -> Result<(Manifest, PathBuf), String> {
    let manifest = read_xipk_manifest(xipk)?;
    let mut z = open_zip(xipk)?;
    if z.len() > MAX_ENTRIES {
        return Err(format!(
            "plugin archive: too many entries ({} > {MAX_ENTRIES})",
            z.len()
        ));
    }
    let names: Vec<String> = z.file_names().map(str::to_string).collect();
    let prefix = manifest_prefix(&names).unwrap_or_default();

    fs::create_dir_all(plugins_dir).map_err(|e| format!("{}: {e}", plugins_dir.display()))?;
    let dest = plugins_dir.join(&manifest.id);
    let tmp = plugins_dir.join(format!(".{}.installing", manifest.id));
    let _ = fs::remove_dir_all(&tmp);
    fs::create_dir_all(&tmp).map_err(|e| e.to_string())?;

    let result = (|| -> Result<(), String> {
        let mut total = 0u64;
        for i in 0..z.len() {
            let mut entry = z.by_index(i).map_err(|e| e.to_string())?;
            let name = entry.name().replace('\\', "/");
            let Some(rel_name) = name.strip_prefix(&prefix) else {
                continue;
            };
            if entry.is_dir() || rel_name.is_empty() {
                continue;
            }
            let rel =
                safe_relative(rel_name).ok_or_else(|| format!("plugin archive: unsafe entry {name}"))?;
            let out = tmp.join(rel);
            if let Some(parent) = out.parent() {
                fs::create_dir_all(parent).map_err(|e| e.to_string())?;
            }
            // 流式写盘并计数：不信任 zip 头里声明的大小。 Stream to disk; don't trust declared sizes.
            let mut file = fs::File::create(&out).map_err(|e| format!("{}: {e}", out.display()))?;
            let limit = MAX_ENTRY_BYTES.min(MAX_TOTAL_BYTES - total);
            let n = std::io::copy(&mut (&mut entry).take(limit + 1), &mut file)
                .map_err(|e| format!("{name}: {e}"))?;
            if n > limit {
                return Err(if limit < MAX_ENTRY_BYTES {
                    format!("plugin archive: package too large (> {} MiB)", MAX_TOTAL_BYTES >> 20)
                } else {
                    format!(
                        "plugin archive: entry {name} too large (> {} MiB)",
                        MAX_ENTRY_BYTES >> 20
                    )
                });
            }
            total += n;
        }
        read_dir_manifest(&tmp).map(|_| ())
    })();
    if let Err(e) = result {
        let _ = fs::remove_dir_all(&tmp);
        return Err(e);
    }
    let _ = fs::remove_dir_all(&dest);
    fs::rename(&tmp, &dest).map_err(|e| format!("{}: {e}", dest.display()))?;
    Ok((manifest, dest))
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use std::io::Write;

    pub fn temp_dir(tag: &str) -> PathBuf {
        let d =
            std::env::temp_dir().join(format!("weave-plugin-{tag}-{}", crate::crypto::uuid_v4()));
        fs::create_dir_all(&d).unwrap();
        d
    }

    pub fn make_xipk(path: &Path, files: &[(&str, &[u8])]) {
        let f = fs::File::create(path).unwrap();
        let mut w = zip::ZipWriter::new(f);
        let opts = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Deflated);
        for (name, data) in files {
            w.start_file(*name, opts).unwrap();
            w.write_all(data).unwrap();
        }
        w.finish().unwrap();
    }

    #[test]
    fn safe_paths() {
        assert_eq!(
            safe_relative("resources/icon.png"),
            Some(PathBuf::from("resources/icon.png"))
        );
        assert_eq!(safe_relative("./a/b"), Some(PathBuf::from("a/b")));
        assert_eq!(safe_relative("../x"), None);
        assert_eq!(safe_relative("/etc/passwd"), None);
        assert_eq!(safe_relative("a/../../x"), None);
        assert_eq!(safe_relative(""), None);
    }

    #[test]
    fn install_roundtrip() {
        let dir = temp_dir("pkg");
        let xipk = dir.join("t.xipk");
        make_xipk(
            &xipk,
            &[
                (
                    "manifest.yaml",
                    b"id: com.t.x\nname: T\nversion: 1.0.0\ntype: speech\n",
                ),
                ("main.lua", b"return {}"),
                ("resources/icon.png", b"\x89PNG"),
                ("libs/proto.lua", b"return {}"),
            ],
        );
        let plugins = dir.join("plugins");
        let (m, dest) = install_xipk(&xipk, &plugins).unwrap();
        assert_eq!(m.id, "com.t.x");
        assert_eq!(dest, plugins.join("com.t.x"));
        assert_eq!(
            fs::read(dest.join("resources/icon.png")).unwrap(),
            b"\x89PNG"
        );
        assert!(dest.join("libs/proto.lua").is_file());
        // 再装一次覆盖
        install_xipk(&xipk, &plugins).unwrap();

        let evil = dir.join("evil.xipk");
        make_xipk(
            &evil,
            &[
                ("manifest.yaml", b"id: com.t.evil\n"),
                ("main.lua", b""),
                ("../../x", b""),
            ],
        );
        assert!(install_xipk(&evil, &plugins).is_err());
        assert!(!plugins.join("com.t.evil").exists());
        fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn prefixed_archive() {
        let dir = temp_dir("pfx");
        let xipk = dir.join("p.xipk");
        make_xipk(
            &xipk,
            &[
                ("pkg/manifest.yaml", b"id: com.t.p\n"),
                ("pkg/main.lua", b"return {}"),
            ],
        );
        let (_, dest) = install_xipk(&xipk, &dir.join("plugins")).unwrap();
        assert!(dest.join("main.lua").is_file());
        fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn names_do_not_determine_whether_a_file_is_a_plugin() {
        let dir = temp_dir("content-detection");
        for (i, name) in ["plugin.zip", "plugin.custom", "plugin", "plugin.xipk"].iter().enumerate() {
            let archive = dir.join(name);
            let manifest = format!("id: org.example.content{i}\ntype: speech\nentry: start.lua\n");
            make_xipk(&archive, &[("manifest.yaml", manifest.as_bytes()), ("start.lua", b"return {}")]);
            assert!(is_archive(&archive));
            assert_eq!(crate::inspect_package(&archive).unwrap().id, format!("org.example.content{i}"));
        }
        let fake = dir.join("fake.xipk");
        fs::write(&fake, b"ordinary text").unwrap();
        assert!(!is_archive(&fake));
        assert!(crate::inspect_package(&fake).is_err());
        let missing = dir.join("missing.zip");
        make_xipk(&missing, &[("manifest.yaml", b"id: missing\ntype: speech\nentry: absent.lua\n")]);
        assert!(crate::inspect_package(&missing).is_err());
        let mut manager = crate::PluginManager::new(&dir, dir.join("config"));
        assert_eq!(manager.scan().len(), 4);
        assert_eq!(manager.scan().len(), 4);
        fs::remove_dir_all(dir).unwrap();
    }
}

#[cfg(test)]
mod limit_tests {
    use super::tests::{make_xipk, temp_dir};
    use super::*;

    const M: &[u8] = b"id: org.example.big\nname: big\nversion: '1'\ntype: speech\n";

    #[test]
    fn oversized_entry_is_rejected_and_nothing_is_left() {
        let dir = temp_dir("big");
        let xipk = dir.join("big.xipk");
        let huge = vec![0u8; (MAX_ENTRY_BYTES + 1) as usize];
        make_xipk(
            &xipk,
            &[
                ("manifest.yaml", M),
                ("main.lua", b"return {}"),
                ("resources/blob", &huge),
            ],
        );
        let plugins = dir.join("plugins");
        let err = install_xipk(&xipk, &plugins).unwrap_err();
        assert!(err.contains("too large"), "{err}");
        assert!(!plugins.join("org.example.big").exists());
        assert!(!plugins.join(".org.example.big.installing").exists());
        let _ = fs::remove_dir_all(&dir);
    }
}
