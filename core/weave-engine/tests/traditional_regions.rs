use std::path::PathBuf;
use weave_dict::blob::Source;
use weave_engine::convert::{Region, Traditional};

/// 把 OpenCC 格式的小表写到临时目录；行为与真实的 STCharacters、TWVariants、HKVariants 相同。
/// Write small OpenCC-format tables; the lines are the same as the real STCharacters, TWVariants and HKVariants.
fn tables() -> (Source, Source, Source, Source) {
    // 每次调用用独立目录：测试在同一进程里并行运行，共用目录会互相覆盖文件。
    // A directory per call: tests run in parallel in one process, and a shared directory let them overwrite each other.
    static NEXT: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
    let dir: PathBuf = std::env::temp_dir().join(format!(
        "weave-traditional-{}-{}",
        std::process::id(),
        NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
    ));
    std::fs::create_dir_all(&dir).unwrap();
    let write = |name: &str, body: &str| {
        let path = dir.join(name);
        std::fs::write(&path, body).unwrap();
        Source::file(path)
    };
    (
        write("STPhrases.txt", "时间\t時間\n"),
        write("STCharacters.txt", "吃\t喫\n叁\t叄\n"),
        write("TWVariants.txt", "喫\t吃\n"),
        write("HKVariants.txt", "喫\t吃\n叄\t叁\n"),
    )
}

fn traditional() -> Traditional {
    let (phrases, chars, tw, hk) = tables();
    Traditional::load(&phrases, &chars).with_region_tables(&tw, &hk)
}

#[test]
fn standard_traditional_is_unchanged_by_default() {
    let t = traditional();
    assert_eq!(t.convert_in(Region::Standard, "吃叁"), "喫叄");
}

#[test]
fn taiwan_uses_its_own_character_forms() {
    let t = traditional();
    assert_eq!(t.convert_in(Region::Taiwan, "吃叁"), "吃叄");
}

#[test]
fn hong_kong_uses_its_own_character_forms() {
    let t = traditional();
    assert_eq!(t.convert_in(Region::HongKong, "吃叁"), "吃叁");
}

#[test]
fn phrases_still_take_priority_and_regions_apply_after_them() {
    let t = traditional();
    assert_eq!(t.convert_in(Region::HongKong, "时间吃"), "時間吃");
}
