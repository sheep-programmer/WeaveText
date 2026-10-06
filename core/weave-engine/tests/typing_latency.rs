//! 乱打一分钟：组合串的上限生效（能上屏的先上屏），每键耗时不再随长度一路涨上去。
//! 上限前后的实测曲线见 examples/mashcurve。
//! A minute of mashing: the composition cap kicks in (what can be committed is committed) and the per-key
//! cost stops climbing. The measured curves before/after are in examples/mashcurve.
use std::path::PathBuf;
use std::time::Instant;
use weave_engine::session::{paths_in, Engine};

fn data() -> Option<PathBuf> {
    let d = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../data/build");
    d.join("pinyin.wvz").exists().then_some(d)
}

fn engine(name: &str) -> Option<Engine> {
    let user = std::env::temp_dir().join(format!("weave-latency-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&user);
    std::fs::create_dir_all(&user).unwrap();
    Some(Engine::new(&paths_in(&data()?, &user)))
}

/// 随便敲一串字母，一个字都不选：组合串到上限就该把能上屏的先上屏，不能一直堆着。
/// Mash letters without ever picking a candidate: past the limit what can be committed is committed.
#[test]
fn mashing_never_piles_up_in_the_composition() {
    let Some(mut e) = engine("pile") else { return };
    let mash = "qazwsxedcrfvtgbyhnujmikolpqazwsxedcrfvtgbyhnujmikolpqazwsxedcrfvtgbyhnujmikolp";
    let mut typed = 0usize;
    let mut committed = 0usize;
    let mut run = 0usize;
    let mut longest_run = 0usize;
    for _ in 0..6 {
        for c in mash.chars() {
            e.input_char(c);
            typed += 1;
            let s = e.snapshot();
            let n = s.commit.chars().count();
            committed += n;
            if n == 0 {
                run += 1;
                longest_run = longest_run.max(run);
            } else {
                run = 0;
            }
        }
    }
    eprintln!("typed={typed} committed={committed} longest-commit-free-run={longest_run}");
    assert!(committed > 0, "乱打了一整串，一个字都没有上屏");
    // 上限 96 生效后，连着多少键一个字都没上是有限的（顶屏会把能上屏的先上屏）。
    // With the cap on, a commit-free run is bounded; without it the whole mash stays composed forever.
    assert!(longest_run <= 200, "连着 {longest_run} 键没有上屏，组合串一直在长");
}

/// 每按一键的耗时按组合串长度分桶：上限生效后不该再随长度一路涨上去。
/// Per-key cost bucketed by composition length: with the cap on it must not keep climbing.
/// 机器忙的时候单次测量噪声很大，所以比的是整段平均，阈值放松到只抓「一路涨上去」这一种毛病。
/// The machine is often busy, so the segments are averaged and the bound only catches a climbing trend.
#[test]
fn per_key_cost_does_not_climb_forever() {
    let Some(mut e) = engine("salad") else { return };
    let mash = "qazwsxedcrfvtgbyhnujmikolpqazwsxedcrfvtgbyhnujmikolpqazwsxedcrfvtgbyhnujmikolp";
    let mut bucket: Vec<Vec<u128>> = vec![Vec::new(); 8];
    let buckets = bucket.len();
    let mut at = 0usize;
    for _ in 0..4 {
        for c in mash.chars() {
            let t = Instant::now();
            e.input_char(c);
            let b = (at / 16).min(buckets - 1);
            bucket[b].push(t.elapsed().as_micros());
            at += 1;
        }
    }
    let mean = |v: &[u128]| if v.is_empty() { 0 } else { v.iter().sum::<u128>() / v.len() as u128 };
    let early = mean(&bucket[1]);
    let mid = mean(&bucket[3]);
    let late = mean(&bucket[6]);
    eprintln!("per-key mean: early={early}us mid={mid}us late={late}us");
    // 上限生效后后段与中段是同一量级；没有上限时随长度一路涨，后段会是中段的好几倍。
    // With the cap the tail matches the middle; uncapped it is several times the middle.
    assert!(late <= mid * 3 + 4000, "末段比中段贵得多：{mid}us → {late}us");
}

/// 正常的长句拼音不受上限影响：整串都留在组合里，等用户挑词。
/// A real long sentence is untouched by the cap: the whole string stays composed, waiting for a pick.
#[test]
fn a_real_sentence_is_not_pushed_out() {
    let Some(mut e) = engine("real") else { return };
    // 评测集里最长的句子（84 个字母）。 The longest sentence in the eval set (84 letters).
    let keys = "yishengshuozhongyaobachangjiangtuidongtaizhiliyoushijiediyizhandedifangdouyaozoubian";
    let mut composed = 0usize;
    for c in keys.chars() {
        e.input_char(c);
        let s = e.snapshot();
        composed = composed.max(s.preedit.chars().count());
    }
    eprintln!("longest real composition={composed}");
    // 整句还在组合里等你挑词：太短了才是被顶屏。 The whole sentence is still composed, waiting for a pick.
    assert!(composed >= 80, "正常长句被提前收尾了，只剩 {composed} 个字母");
}

/// 真句复读：组合串到限就顶屏，长度必须循环（锯齿），不能一直涨——这是「越打越卡」的根子。
/// Repeating a real sentence: the cap must make the composition cycle (a saw-tooth), never grow without
/// bound. This is the thing behind "the longer I type the laggier it gets".
#[test]
fn an_endlessly_growing_composition_stays_bounded() {
    let Some(mut e) = engine("grow") else { return };
    let unit = "jintiantianqizenmeyangwomenyiqiquchifanba";
    let keys: String = unit.repeat(4);
    let mut peak = 0usize;
    let mut last = 0usize;
    let mut resets = 0;
    for c in keys.chars() {
        e.input_char(c);
        let n = e.snapshot().preedit.chars().count();
        if n + 10 < last {
            resets += 1; // 顶屏把组合串收回去了。 The push took the composition back down.
        }
        peak = peak.max(n);
        last = n;
        assert!(n <= 160, "组合串涨到 {n} 个字母，顶屏没有生效");
    }
    eprintln!("峰值={peak} 顶屏次数={resets}");
    assert!(resets >= 1, "打了 {} 个字母，顶屏一次都没发生", keys.len());
}
