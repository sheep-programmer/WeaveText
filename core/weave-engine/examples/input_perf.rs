//! Reproducible input-core timing, allocation counts and candidate fingerprints.
//! Run in release mode with --data DIR [--eval FILE] [--limit 100] [--rounds 3].
//! Timing runs with allocation counting disabled; a separate warmed pass counts
//! allocation/reallocation requests and requested bytes (not peak/live memory).

use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;
use std::hint::black_box;
use std::path::PathBuf;
use std::time::Instant;

use weave_dict::{gram::Gram, lexicon::Lexicon, syllable};
use weave_engine::{
    decoder::{Decoder, LmParams},
    graph::{self, FuzzyOptions, Letters},
    session::paths_in,
    shuangpin::{self, SchemeId},
    t9::{self, Grouping, T9Input, T9Unit},
    userdict::UserDict,
    Engine, Schema, Snapshot,
};

#[derive(Clone, Copy, Default)]
struct Allocations {
    enabled: bool,
    calls: u64,
    bytes: u64,
}

thread_local! {
    static ALLOCATIONS: Cell<Allocations> = const { Cell::new(Allocations {
        enabled: false, calls: 0, bytes: 0,
    }) };
}

struct CountingAllocator;

fn record(bytes: usize) {
    ALLOCATIONS.with(|cell| {
        let mut stats = cell.get();
        if stats.enabled {
            stats.calls += 1;
            stats.bytes += bytes as u64;
            cell.set(stats);
        }
    });
}

unsafe impl GlobalAlloc for CountingAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        record(layout.size());
        System.alloc(layout)
    }
    unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
        record(layout.size());
        System.alloc_zeroed(layout)
    }
    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        System.dealloc(ptr, layout)
    }
    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, size: usize) -> *mut u8 {
        record(size);
        System.realloc(ptr, layout, size)
    }
}

#[global_allocator]
static ALLOCATOR: CountingAllocator = CountingAllocator;

fn arg(args: &[String], name: &str) -> Option<String> {
    args.iter()
        .position(|a| a == name)
        .and_then(|i| args.get(i + 1))
        .cloned()
}

fn counted<T>(f: impl FnOnce() -> T) -> (T, Allocations) {
    ALLOCATIONS.with(|c| {
        c.set(Allocations {
            enabled: true,
            ..Default::default()
        })
    });
    let result = f();
    let stats = ALLOCATIONS.with(|c| c.replace(Allocations::default()));
    (result, stats)
}

fn fingerprint(hash: &mut u64, text: &str) {
    for b in text.bytes().chain(std::iter::once(0xff)) {
        *hash = (*hash ^ u64::from(b)).wrapping_mul(0x100000001b3);
    }
}

fn snapshot_fingerprint(hash: &mut u64, snapshot: &Snapshot) {
    fingerprint(hash, &snapshot.preedit);
    fingerprint(hash, &snapshot.commit);
    for mark in &snapshot.marks {
        fingerprint(hash, mark.kind.key());
        fingerprint(hash, &mark.start.to_string());
        fingerprint(hash, &mark.end.to_string());
        fingerprint(hash, &mark.removed);
    }
    for candidate in &snapshot.candidates {
        fingerprint(hash, &candidate.text);
        fingerprint(hash, &candidate.pinyin);
        fingerprint(hash, &candidate.comment);
        fingerprint(hash, if candidate.user { "user" } else { "system" });
    }
}

fn report(label: &str, mut times: Vec<f64>, allocations: Allocations, count: usize, hash: u64) {
    times.sort_by(f64::total_cmp);
    let percentile = |p: f64| times[((times.len() - 1) as f64 * p) as usize];
    println!(
        "{label},{},{:.3},{:.3},{:.3},{:.3},{:.1},{:.1},{hash:016x}",
        times.len(),
        times.iter().sum::<f64>() / times.len() as f64,
        percentile(0.50),
        percentile(0.95),
        percentile(0.99),
        allocations.calls as f64 / count as f64,
        allocations.bytes as f64 / count as f64,
    );
}

fn type_pass(engine: &mut Engine, inputs: &[String], timing: bool) -> Vec<f64> {
    let mut times = Vec::with_capacity(inputs.iter().map(String::len).sum());
    for input in inputs {
        engine.clear();
        engine.set_context(None);
        for key in input.chars() {
            let start = Instant::now();
            assert!(engine.input_char(key), "unsupported key {key} in {input}");
            black_box(engine.snapshot());
            if timing {
                times.push(start.elapsed().as_secs_f64() * 1e6);
            }
        }
    }
    times
}

fn typing_report(label: &str, engine: &mut Engine, inputs: &[String], rounds: usize) {
    type_pass(engine, inputs, false);
    let mut times = Vec::new();
    for _ in 0..rounds {
        times.extend(type_pass(engine, inputs, true));
    }
    let (_, allocations) = counted(|| type_pass(engine, inputs, false));
    let count = inputs.iter().map(String::len).sum();
    let mut hash = 0xcbf29ce484222325;
    for input in inputs {
        engine.clear();
        engine.set_context(None);
        for key in input.chars() {
            engine.input_char(key);
            snapshot_fingerprint(&mut hash, &engine.snapshot());
        }
        // Exercise the on-demand full candidate list as well as the first page.
        for candidate in engine.candidates(0, 800) {
            fingerprint(&mut hash, &candidate.text);
            fingerprint(&mut hash, &candidate.pinyin);
        }
    }
    report(label, times, allocations, count, hash);
}

fn stage_report<T>(label: &str, rounds: usize, mut run: impl FnMut() -> T) {
    black_box(run());
    let repeats = rounds * 20;
    let times = (0..repeats)
        .map(|_| {
            let start = Instant::now();
            black_box(run());
            start.elapsed().as_secs_f64() * 1e6
        })
        .collect();
    let (_, allocations) = counted(|| {
        for _ in 0..20 {
            black_box(run());
        }
    });
    report(label, times, allocations, 20, 0);
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let data = PathBuf::from(arg(&args, "--data").expect("--data DIR is required"));
    let eval = PathBuf::from(arg(&args, "--eval").unwrap_or_else(|| {
        format!(
            "{}/../../data/eval/sentences.tsv",
            env!("CARGO_MANIFEST_DIR")
        )
    }));
    let limit = arg(&args, "--limit")
        .map(|s| s.parse::<usize>().unwrap())
        .unwrap_or(100);
    let rounds = arg(&args, "--rounds")
        .map(|s| s.parse::<usize>().unwrap())
        .unwrap_or(3);
    assert!(rounds > 0 && limit > 0);
    let text = std::fs::read_to_string(eval).expect("read eval set");
    let rows: Vec<_> = text
        .lines()
        .filter_map(|line| {
            let mut fields = line.split('\t');
            let _sentence = fields.next()?;
            let spelling = fields.next()?;
            Some((
                syllable::parse_seq(spelling).expect("valid syllables"),
                fields.next().map(str::to_owned),
            ))
        })
        .take(limit)
        .collect();
    assert!(!rows.is_empty());
    let mut paths = paths_in(&data, &std::env::temp_dir());
    // In-memory user state: do not inherit or write any real user's words/packs.
    paths.user_dir = None;
    paths.packs.clear();
    let lex =
        Lexicon::open_source(paths.pinyin_lexicon.as_ref().expect("pinyin dictionary")).unwrap();
    let gram = Gram::open_source(paths.gram_model.as_ref().expect("grammar model")).unwrap();
    let english = paths
        .english_lexicon
        .as_ref()
        .map(|s| Lexicon::open_source(s).unwrap());
    println!(
        "workload,samples,mean_us,p50_us,p95_us,p99_us,alloc_calls,requested_bytes,fingerprint"
    );
    for (label, schema) in [
        ("pinyin", Schema::Pinyin),
        ("xiaohe", Schema::Shuangpin(SchemeId::Xiaohe)),
        ("t9", Schema::Keypad(Grouping::Nine)),
    ] {
        let inputs: Vec<String> = rows
            .iter()
            .map(|(ids, supplied)| match schema {
                Schema::Shuangpin(id) => ids
                    .iter()
                    .flat_map(|&s| shuangpin::table(id).encode(s).unwrap())
                    .map(char::from)
                    .collect(),
                Schema::Keypad(grouping) => ids
                    .iter()
                    .flat_map(|&s| syllable::spelling(s).bytes())
                    .map(|c| char::from(grouping.code(c)))
                    .collect(),
                _ => supplied
                    .clone()
                    .unwrap_or_else(|| ids.iter().map(|&s| syllable::spelling(s)).collect()),
            })
            .collect();
        let mut engine = Engine::new(&paths);
        assert!(engine.has_lexicon(Schema::Pinyin));
        engine.options.emoji = false;
        engine.set_schema(schema);
        typing_report(&format!("{label}/empty"), &mut engine, &inputs, rounds);
        engine.set_learning(true);
        for input in &inputs {
            engine.clear();
            engine.set_context(None);
            for key in input.chars() {
                engine.input_char(key);
            }
            engine.commit_first();
            black_box(engine.snapshot());
        }
        typing_report(&format!("{label}/learned"), &mut engine, &inputs, rounds);
    }

    let fuzzy = FuzzyOptions::default();
    let user = UserDict::default();
    for (label, input, corrections, keypad) in [
        (
            "sentence",
            "jintiantianqizenmeyangwomenyiqiquchifanba",
            false,
            false,
        ),
        ("abbrev", "zgrm", false, false),
        ("correction", "mingtinajian", true, false),
        ("keypad", "946644867366", false, true),
    ] {
        let graph = if keypad {
            t9::build_graph(&T9Input::from_units(
                &input.bytes().map(T9Unit::Digit).collect::<Vec<_>>(),
                Grouping::Nine,
            ))
        } else {
            graph::build_full_pinyin_near(&Letters::parse(input), &fuzzy, &[], corrections)
        };
        let decoder = Decoder {
            lex: Some(&lex),
            packs: &[],
            user: &user,
            graph: &graph,
            context: Some("今天"),
            lm: Some(LmParams {
                gram: &gram,
                weight: 1.0,
                baseline: 12.0,
            }),
        };
        let decode = || {
            if keypad {
                decoder.decode()
            } else {
                decoder.decode_with_latin(
                    english.as_ref(),
                    input.as_bytes(),
                    input,
                    None,
                    corrections,
                )
            }
        };
        stage_report(&format!("decode/{label}"), rounds, decode);
        let lattice = decode();
        let raw = |start, end| input[start..end].to_string();
        stage_report(&format!("candidates/{label}"), rounds, || {
            decoder.candidates(&lattice, &raw, 40)
        });
        let candidates = decoder.candidates(&lattice, &raw, 40);
        stage_report(&format!("hints/{label}"), rounds, || {
            candidates
                .iter()
                .map(|c| weave_engine::tones::pinyin(&c.text, Some(&c.key), true))
                .collect::<Vec<_>>()
        });
    }
}
