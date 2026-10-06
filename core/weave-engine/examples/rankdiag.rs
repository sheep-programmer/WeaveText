//! Explain exact vs speculative decoding: rankdiag DATA_DIR KEYS...
use std::path::Path;
use weave_dict::{gram::Gram, lexicon::Lexicon, syllable};
use weave_engine::{
    decoder::{Decoder, LmParams},
    graph::{build_full_pinyin, build_full_pinyin_near, FuzzyOptions, Letters},
    userdict::UserDict,
};

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let data = Path::new(args.first().expect("data dir"));
    let lex = Lexicon::open(&data.join("pinyin.wvl")).unwrap();
    let gram = Gram::open(&data.join("grammar.wvg")).unwrap();
    let user = UserDict::in_memory();
    for input in args.iter().skip(1) {
        let graph = build_full_pinyin(&Letters::parse(input), &FuzzyOptions::default());
        let full = graph.full_reading();
        let typo =
            build_full_pinyin_near(&Letters::parse(input), &FuzzyOptions::default(), &[], true);
        for (label, graph) in [("all", &graph), ("full", &full), ("typo", &typo)] {
            let decoder = Decoder {
                lex: Some(&lex),
                packs: &[],
                user: &user,
                graph,
                context: None,
                lm: Some(LmParams {
                    gram: &gram,
                    weight: 0.25,
                    baseline: 12.0,
                }),
            };
            let lat = decoder.decode();
            let parts: Vec<_> = lat
                .best
                .iter()
                .map(|(i, t)| {
                    let span = &lat.spans[*i];
                    let reading = span
                        .key
                        .iter()
                        .map(|&s| syllable::spelling(s))
                        .collect::<Vec<_>>()
                        .join("'");
                    format!("{t}({reading},pen={})", span.penalty)
                })
                .collect();
            println!(
                "{input} {label}: cost={} {}",
                lat.best_cost,
                parts.join(" + ")
            );
        }
    }
}
