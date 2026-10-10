# Rust input-core performance measurements

Measured on 2026-10-10, Apple M4 / arm64, Rust 1.97.1. The baseline was
the engine at `2878db5` with the existing uncommitted calculator option in
`session.rs`, plus the same `input_perf` harness. Its release executable was
saved before changing engine code. The calculator changes were preserved
byte for byte.

## Changes

Three bounded costs were removed without changing scoring, beam width,
correction thresholds, candidate limits or dictionary formats:

1. **Span enumeration (`decoder.rs`).** DFS deduplication used to clone a
   syllable `Vec` for every lookup, then clone it again for a new span. A
   bounded inline key now owns only the lookup key; the span still owns its
   normal syllable vector. The index is cleared between start positions and
   its capacity and DFS scratch vectors are reused. Duplicate paths still
   retain the cheapest penalty and its syllable cuts.
2. **Beam decoding (`decoder.rs`).** Each span is visited once through
   `by_start`, so the span-word hash map retained data without serving a
   second lookup. Words now live only for that iteration. Grammar word IDs
   use fixed three-element head/tail buffers and one character pass instead
   of allocating three vectors and repeating character lookups. Backtracking
   borrows states instead of cloning each state's text before copying it
   into the result.
3. **Candidate pinyin hints (`tones.rs`).** Override searches borrow UTF-8
   slices using character byte boundaries rather than allocating every
   possible substring. Override readings are borrowed, and tone-stripped
   comparisons use iterators. Longest matching overrides, polyphonic
   readings, mixed Latin text and tone display retain their behavior.

The implementation was derived from this repository's loops and measured
workloads. No competitor source was inspected. No changes were made to
`weave-dict`, input schemas, handwriting recognition, C/JNI interfaces or
platform/UI code. Basic input features remain in the engine.

## Method and reproduction

`core/weave-engine/examples/input_perf.rs` measures:

- The first 100 original sentences in `data/eval/sentences.tsv`, typed one
  key at a time with a snapshot after each key, for full pinyin, Xiaohe and
  nine-key T9. Each schema runs with an empty in-memory user dictionary and
  after committing each workload's first candidate once. Context is reset
  between sentences; learned words and bigrams remain. Emoji is disabled,
  and default toned pinyin hints stay enabled.
- One warm-up pass, then three timed passes with allocation counting
  disabled, then a separate warmed allocation-counting pass. Counts include
  allocation and reallocation requests; requested bytes count the full new
  size of each request, **not peak memory, retained bytes or RSS**. Clear and
  context reset, once per sentence, and one scratch buffer per pass are
  included in allocation totals. Timed samples include only input + snapshot
  and disposal of that snapshot.
- A separate, untimed fingerprint pass over every keystroke's preedit,
  correction marks, commit, first-page text/annotations/comments/user badge,
  and each sentence's expanded candidate text/annotations up to 800 entries.
- Four fixed decoder workloads: a 41-letter chat sentence, `zgrm`
  abbreviation, `mingtinajian` with correction edges, and `946644867366`
  T9. Graph construction is outside these stage measurements. Each stage
  runs 60 timed calls and 20 separate allocation-counted calls. Candidate
  listing uses a cap of 40 here; the engine's normal first refresh uses 120
  candidates and snapshots expose up to 60.

No benchmark pass writes user dictionaries. Dictionaries and grammar are
loaded from this project's existing packed app resources. No external
data is fetched or regenerated. Run from the repository root:

```sh
PERF_DATA="$PWD/macos/build/preview/WeaveText.app/Contents/Resources/data"
PERF_TARGET=/tmp/weave-input-perf-target
cargo build --release -j 2 -p weave-engine --example input_perf --example eval \
  --manifest-path core/Cargo.toml --target-dir "$PERF_TARGET"
"$PERF_TARGET/release/examples/input_perf" --data "$PERF_DATA" \
  --eval "$PWD/data/eval/sentences.tsv" --limit 100 --rounds 3
WEAVE_TEST_DATA_DIR="$PERF_DATA" cargo test -j 2 -p weave-engine --lib \
  --test adaptive_learning --test cloud_badges --test hand_candidate_capacity \
  --test hand_context --test hotwords --test mixed_english --test prediction_policy \
  --manifest-path core/Cargo.toml --target-dir "$PERF_TARGET"
WEAVE_TEST_DATA_DIR="$PERF_DATA" cargo test --release -j 2 -p weave-engine \
  --test typing_latency --manifest-path core/Cargo.toml --target-dir "$PERF_TARGET" \
  -- --test-threads=1
"$PERF_TARGET/release/examples/eval" --data "$PERF_DATA" \
  --eval "$PWD/data/eval/sentences.tsv" --gram "$PERF_DATA/grammar.wvz" \
  --report /tmp/weave-input-sentences.tsv
"$PERF_TARGET/release/examples/eval" --data "$PERF_DATA" \
  --eval "$PWD/data/eval/correction-common.tsv" --gram "$PERF_DATA/grammar.wvz" \
  --report /tmp/weave-input-correction.tsv
```

Save the baseline executable before making changes, run the same commands
for each executable, compare fingerprint columns, and `cmp` the sentence
and correction TSV reports. For a fresh baseline checkout, copy the current
standalone `input_perf.rs` harness into the baseline engine before building.
The optional `WEAVE_TEST_DATA_DIR` lets existing mixed-English, prediction
and long-composition tests use these packed assets instead of skipping when
`data/build` is absent. An isolated target directory avoids other agents'
Cargo build locks. Do not run timed passes during a build or another test.

SHA-256 inputs:

| File | SHA-256 |
| --- | --- |
| `pinyin.wvz` | `61736ee472fb36b86d73cf020cebeb2e085aac1f971983101f4cd1cbe4bf799e` |
| `english.wvz` | `63bb2ac7e572e5a5754a395072458c9a4f62d0acfe7057dee1111c4bcaffac44` |
| `grammar.wvz` | `3f62fec4c0e7d1f6d2752d017a8a014396e0dd7e9b97f227e78c1c2cad127b49` |
| `sentences.tsv` | `cd1e9ad517d78639a0a2d97f413c73578b021ef48365fa4440ad3253e5b4b44d` |
| `correction-common.tsv` | `d1dc2c6da362b0922c3233a430f9a696ec3fe9b8b660e0a4064a8e8ef068f849` |

## Results

The full 100-sentence sweep made 6,078 timed pinyin/T9 keystrokes and 4,092
Xiaohe keystrokes per user-state run. All six before/after fingerprints
matched. Allocation requests per key fell **5.3–17.3%**; requested bytes per
key fell **1.1–3.8%**. The original and optimized sweeps were separated by
other work on the host, and the wall-clock results were mixed:

| Input / user state | Mean ms, before → after | p95 ms, before → after | Allocation requests/key, before → after | Requested MB/key, before → after |
| --- | ---: | ---: | ---: | ---: |
| Full pinyin / empty | 3.018 → 3.635 | 8.913 → 10.638 | 11,979 → 10,076 | 1.666 → 1.603 |
| Full pinyin / learned | 4.729 → 5.860 | 14.534 → 19.819 | 16,803 → 15,026 | 1.614 → 1.556 |
| Xiaohe / empty | 1.299 → 2.149 | 3.150 → 6.311 | 5,079 → 4,772 | 0.718 → 0.710 |
| Xiaohe / learned | 1.372 → 2.095 | 3.194 → 5.602 | 5,837 → 5,531 | 0.724 → 0.716 |
| T9 / empty | 5.748 → 4.970 | 15.340 → 12.268 | 11,454 → 9,478 | 2.377 → 2.308 |
| T9 / learned | 5.795 → 7.802 | 14.708 → 23.814 | 17,111 → 15,107 | 2.425 → 2.354 |

MB above is decimal (1,000,000 bytes). These timing increases are reported,
not treated as evidence of an across-the-board latency improvement.

To check the timing difference under closer conditions, a shorter follow-up
used the first 20 sentences, again with three timed passes, in **A/B/B/A**
order (A = saved baseline executable, B = optimized executable). Each
process warmed its own engine and used the same data. Other engine stress
work was suspended during benchmark passes. Both runs per executable are
shown so the substantial timing variance remains visible:

| Input / user state | A1 / A2 mean ms/key | B1 / B2 mean ms/key |
| --- | ---: | ---: |
| Full pinyin / empty | 0.776 / 0.548 | 0.508 / 0.517 |
| Full pinyin / learned | 1.290 / 0.565 | 0.522 / 0.517 |
| Xiaohe / empty | 0.956 / 0.376 | 0.385 / 0.384 |
| Xiaohe / learned | 0.904 / 0.419 | 0.364 / 0.382 |
| T9 / empty | 3.596 / 1.905 | 1.915 / 1.815 |
| T9 / learned | 2.862 / 3.271 | 1.812 / 1.794 |

All follow-up fingerprints matched. This smaller sample does not establish
the latency of the complete 100- or 1,000-sentence set on an idle machine.

The fixed decoder/hint workloads were identical across these processes.
Each executable had 120 timed stage calls across its two follow-up runs;
the table reports their combined mean. Stage allocation counts were exactly
reproducible across the original 100-sentence sweep and all four follow-up
processes:

| Stage | Mean µs, before → after | Allocation requests/call, before → after | Requested bytes/call, before → after |
| --- | ---: | ---: | ---: |
| Decode sentence | 897.8 → 540.7 | 2,420 → 1,585 | 398,598 → 370,392 |
| Decode abbreviation | 2,142.0 → 1,326.1 | 11,523 → 7,183 | 1,522,877 → 1,357,339 |
| Decode correction | 333.2 → 264.3 | 2,163 → 1,488 | 289,534 → 264,995 |
| Decode T9 | 727.8 → 562.9 | 2,418 → 1,547 | 362,592 → 336,216 |
| Hints sentence | 23.8 → 14.1 | 384 → 306 | 7,418 → 6,674 |
| Hints abbreviation | 51.7 → 28.8 | 626 → 367 | 13,584 → 11,152 |
| Hints correction | 19.2 → 13.0 | 317 → 290 | 5,988 → 5,748 |
| Hints T9 | 26.4 → 17.1 | 403 → 320 | 8,469 → 7,765 |

Decoder stage means fell **20.7–39.8%**, with **31.2–37.7% fewer allocation
requests**. Hint allocation requests fell **8.5–41.4%**. Candidate-list stage
allocation counts remained unchanged (523 / 317 / 321 / 572 for the four
workloads); no candidate enumeration optimization is claimed here. Stage
calls use context `今天`, LM weight 1.0 and baseline 12.0; full engine runs
retain the default LM weight 0.25.

## Correctness and changed paths

- **98 feature tests passed**, with one pre-existing ignored decoder dump;
  **all four real-dictionary latency tests passed in release mode**. The
  initial debug stress run was stopped while its two mashing checks were
  still executing; the clean feature suite and the complete release latency
  suite were then run using the commands above. No assertion failed.
- Before/after `eval --report` TSVs were byte-identical for all **1,000
  sentences**: top-1 75.8%, top-3 79.8%, character accuracy 95.86%. Both top
  three candidate text and preedit matched for every row.
- All **50 correction cases** also had byte-identical reports: top-1 98%,
  top-3 100%, character accuracy 99.07%.
- Existing tests cover full-pinyin/Xiaohe/T9 adaptive choices, private mode,
  undo and persistence, correction/embedded English, handwriting selection,
  context and candidate capacity, predictions, cloud badges and calculator
  enable/disable behavior. Three targeted regressions add cheapest-cut span
  deduplication with distinct starts, grammar ID runs around unknown
  characters, and mixed UTF-8 override boundaries.

Changed implementation paths are `core/weave-engine/src/decoder.rs` and
`core/weave-engine/src/tones.rs`. The new harness is
`core/weave-engine/examples/input_perf.rs`. Optional real-data path support
was added to `core/weave-engine/tests/mixed_english.rs`,
`core/weave-engine/tests/prediction_policy.rs` and
`core/weave-engine/tests/typing_latency.rs`. This document is the only added
documentation. `session.rs` still matches the initial working-tree file,
including the calculator flag. No native interface or platform build was
changed or invoked for this work.

## Measurement limits and remaining costs

The desktop was under substantial unrelated CPU/memory pressure. Warmed
wall-clock timings characterize this session, not mobile latency targets;
allocation counts and candidate fingerprints are the less noisy evidence.
The 100-sentence timing sample is the beginning of the daily-chat section,
not a random or balanced sample of all 1,000 sentences. Stage workloads and
the existing long-composition tests cover additional decoding pressure.
Startup, cold dictionary decompression, touch-device scheduling and the
handwriting neural model are outside this benchmark.

Candidate enumeration still materializes up to 600 words per span before
truncating the requested page, so reducing that work needs a separate,
ranking-preserving measurement. Learned bigram lookup still constructs
owned strings for its hash-map query. Refresh may legitimately decode
plain, corrected and literal readings separately; this change does not
cache across refreshes or change invalidation/learning behavior. A one-second
sample of the long debug stress run showed active DFS/trie reads and packed
block decompression; the latency checks are therefore run singly in the
shipping release profile. Random-mash checks remain expensive with real
resources: the composition cap bounds length, not worst-case wall latency.
This patch does not reduce DFS budgets or solve packed-cache churn. Further
work should profile these costs rather than change the architecture.
