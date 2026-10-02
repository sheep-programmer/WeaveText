//! Repeated selections against installed data in an isolated user directory.
//! cargo run --release -p weave-engine --example learncheck -- DATA_DIR USER_DIR CODE WORD [PICKS] [SEED_TSV]
use std::path::Path;
use weave_engine::{session::paths_in, Engine};
fn main() {
    let a: Vec<String> = std::env::args().skip(1).collect();
    assert!(a.len() >= 4, "DATA_DIR USER_DIR CODE WORD [PICKS] [SEED_TSV]");
    let paths = paths_in(Path::new(&a[0]), Path::new(&a[1]));
    let mut e = Engine::new(&paths);
    if let Some(seed) = a.get(5) {
        e.import_user_words(&std::fs::read_to_string(seed).expect("seed TSV"));
    }
    let rounds: usize = a.get(4).and_then(|s| s.parse().ok()).unwrap_or(5);
    for i in 0..=rounds {
        e.clear();
        e.set_context(None);
        for c in a[2].chars() {
            e.input_char(c);
        }
        let all = e.candidates(0, 800);
        let rank = all.iter().position(|c| c.text == a[3]);
        println!(
            "after {i} picks: rank={:?} top={:?}",
            rank.map(|r| r + 1),
            all.iter().take(8).map(|c| &c.text).collect::<Vec<_>>()
        );
        if i == rounds {
            break;
        }
        if let Some(index) = rank {
            assert!(e.select(index));
            e.snapshot();
        } else {
            panic!("requested word absent");
        }
    }
    e.flush();
    drop(e);
    let mut e = Engine::new(&paths);
    for c in a[2].chars() {
        e.input_char(c);
    }
    println!(
        "reloaded: {:?}",
        e.snapshot()
            .candidates
            .iter()
            .map(|c| &c.text)
            .collect::<Vec<_>>()
    );
}
