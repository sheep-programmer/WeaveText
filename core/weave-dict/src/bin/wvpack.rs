//! 把数据文件压成分块压缩格式（WVPK），供 APK 直接读取。
//! Pack a data file into the block-compressed WVPK format read straight from the APK.
//!
//! 用法 / Usage: `wvpack <in> <out> [block_kib]`

use std::process::ExitCode;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.len() < 2 {
        eprintln!("usage: wvpack <in> <out> [block_kib]");
        return ExitCode::from(2);
    }
    let block = args
        .get(2)
        .and_then(|v| v.parse::<usize>().ok())
        .map_or(weave_dict::blob::DEFAULT_BLOCK, |k| k << 10);
    let raw = match std::fs::read(&args[0]) {
        Ok(b) => b,
        Err(e) => {
            eprintln!("read {}: {e}", args[0]);
            return ExitCode::FAILURE;
        }
    };
    let packed = weave_dict::blob::pack(&raw, block);
    let tmp = format!("{}.tmp", args[1]);
    if let Err(e) = std::fs::write(&tmp, &packed).and_then(|_| std::fs::rename(&tmp, &args[1])) {
        eprintln!("write {}: {e}", args[1]);
        return ExitCode::FAILURE;
    }
    eprintln!(
        "{} → {}: {:.2} MB → {:.2} MB",
        args[0],
        args[1],
        raw.len() as f64 / 1e6,
        packed.len() as f64 / 1e6
    );
    ExitCode::SUCCESS
}
