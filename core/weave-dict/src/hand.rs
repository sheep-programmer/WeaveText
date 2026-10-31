//! 手写识别：按笔画轨迹匹配字形模板（联机手写，单字）。
//! Handwriting recognition: match pen trajectories against per-character stroke templates (online, single char).
//!
//! 模板来自每个字按笔顺排列的笔画中线，每笔按弧长重采样为 [`POINTS`] 个点、按字的外框归一化到 0..=255。
//! 识别时对用户笔迹做同样的归一化，逐笔求距离（允许反向书写，略加惩罚），再用最优指派配对笔画，
//! 因此笔顺不对也能认；笔顺错位与笔数差异另计小惩罚，最后用字频做轻微先验。
//!
//! Templates are the stroke medians of each character in stroke order, each resampled by arc length to
//! [`POINTS`] points and normalised to the character's bounding box (0..=255). Input is normalised the same
//! way; strokes are compared pairwise (reversed writing allowed with a small penalty) and paired by an optimal
//! assignment, so wrong stroke order still matches. Order inversions and stroke-count differences add small
//! penalties, and character frequency acts as a light prior.
//!
//! 数据布局（小端）/ Data layout (little endian):
//! ```text
//! Header 16 bytes: "WVHW" | version u32 | n_chars u32 | total_strokes u32
//! Index  [Entry; n_chars]  12 字节：codepoint u32 | first_stroke u32 | strokes u8 | prior u8 | pad u16
//! Points [[u8; 2]; total_strokes * POINTS]
//! ```

use std::io;

use crate::blob::{Blob, Source};

pub const MAGIC: &[u8; 4] = b"WVHW";
pub const VERSION: u32 = 1;
/// 每笔的采样点数。 Points per stroke.
pub const POINTS: usize = 8;
const HEADER: usize = 16;
const ENTRY: usize = 12;

/// 一笔：若干 (x, y) 点，坐标系任意（y 向下）。 One stroke: points in any frame, y pointing down.
pub type Stroke = Vec<(f32, f32)>;

pub(crate) fn next_model_id() -> u64 {
    static ID: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);
    ID.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
}

/// Keep inference work bounded; coordinates are the raw centreline, independent of display nib width/tails.
pub(crate) fn valid_ink(strokes: &[Stroke], max_strokes: usize) -> bool {
    strokes.len() <= max_strokes && strokes.iter().all(|s| {
        s.len() <= 4096 && s.iter().all(|&(x, y)| x.is_finite() && y.is_finite() && x.abs() <= 100_000.0 && y.abs() <= 100_000.0)
    })
}

/// 按弧长把一笔重采样为 [`POINTS`] 个点；只有一个点时复制。 Resample a stroke to [`POINTS`] points by arc length.
pub fn resample(s: &[(f32, f32)]) -> [(f32, f32); POINTS] {
    let mut out = [(0f32, 0f32); POINTS];
    if s.is_empty() {
        return out;
    }
    // Adjacent template joins have exactly 16 points: keep their cumulative lengths on the stack.
    let mut short = [0f32; 2 * POINTS];
    let mut heap;
    let cum: &mut [f32] = if s.len() <= short.len() { &mut short[..s.len()] }
        else { heap = vec![0f32; s.len()]; &mut heap };
    let mut total = 0f32;
    for (i, w) in s.windows(2).enumerate() {
        total += ((w[1].0 - w[0].0).powi(2) + (w[1].1 - w[0].1).powi(2)).sqrt();
        cum[i + 1] = total;
    }
    if total <= f32::EPSILON {
        return [s[0]; POINTS];
    }
    let mut j = 0;
    for (i, o) in out.iter_mut().enumerate() {
        let t = total * i as f32 / (POINTS - 1) as f32;
        while j + 1 < cum.len() - 1 && cum[j + 1] < t {
            j += 1;
        }
        let seg = (cum[j + 1] - cum[j]).max(f32::EPSILON);
        let f = ((t - cum[j]) / seg).clamp(0.0, 1.0);
        *o = (s[j].0 + (s[j + 1].0 - s[j].0) * f, s[j].1 + (s[j + 1].1 - s[j].1) * f);
    }
    out
}

/// 归一化时两轴比例最多放宽到的倍数。 How far the two axes may be scaled apart when normalising.
const ASPECT: f32 = 1.3;

/// 把一组笔画按整体外框缩放到 0..=255（居中）。 Normalise strokes to 0..=255, keeping aspect, centred.
pub fn normalise(strokes: &[[(f32, f32); POINTS]]) -> Vec<[(f32, f32); POINTS]> {
    let (mut x0, mut y0, mut x1, mut y1) = (f32::MAX, f32::MAX, f32::MIN, f32::MIN);
    for s in strokes {
        for &(x, y) in s {
            x0 = x0.min(x);
            y0 = y0.min(y);
            x1 = x1.max(x);
            y1 = y1.max(y);
        }
    }
    // 每个方向各自缩放，但两轴比例最多放宽到 [`ASPECT`]：写得偏高偏扁时仍能对上，一笔一横之类的细长字不被拉坏。
    // Scale each axis on its own, but keep the two within [`ASPECT`] of each other: tall or squat writing still
    // matches, while thin characters (一, 丨) aren't stretched out of shape.
    let (w, h) = ((x1 - x0).max(1e-3), (y1 - y0).max(1e-3));
    let size = w.max(h);
    let (mut kx, mut ky) = (255.0 / w, 255.0 / h);
    let base = 255.0 / size;
    kx = kx.min(base * ASPECT);
    ky = ky.min(base * ASPECT);
    let (ox, oy) = ((255.0 - w * kx) / 2.0, (255.0 - h * ky) / 2.0);
    strokes
        .iter()
        .map(|s| {
            let mut o = [(0f32, 0f32); POINTS];
            for (i, &(x, y)) in s.iter().enumerate() {
                o[i] = ((x - x0) * kx + ox, (y - y0) * ky + oy);
            }
            o
        })
        .collect()
}

// ------------------------------------------------------------------ builder

/// 模板构建器（构建工具用）。 Template builder, for build tools.
#[derive(Default)]
pub struct Builder {
    chars: Vec<(char, u8, Vec<[(f32, f32); POINTS]>)>,
}

impl Builder {
    /// 加入一个字：笔画按笔顺，坐标 y 向下；prior 0..=255，越大越常用。 Add a char; y points down; larger prior = more common.
    pub fn push(&mut self, c: char, prior: u8, strokes: &[Stroke]) {
        let r: Vec<_> = strokes.iter().filter(|s| !s.is_empty()).map(|s| resample(s)).collect();
        if r.is_empty() || r.len() > u8::MAX as usize {
            return;
        }
        self.chars.push((c, prior, normalise(&r)));
    }

    pub fn len(&self) -> usize {
        self.chars.len()
    }

    pub fn is_empty(&self) -> bool {
        self.chars.is_empty()
    }

    pub fn build(self) -> Vec<u8> {
        let total: usize = self.chars.iter().map(|c| c.2.len()).sum();
        let mut out = Vec::with_capacity(HEADER + self.chars.len() * ENTRY + total * POINTS * 2);
        out.extend_from_slice(MAGIC);
        for v in [VERSION, self.chars.len() as u32, total as u32] {
            out.extend_from_slice(&v.to_le_bytes());
        }
        let mut first = 0u32;
        for (c, prior, s) in &self.chars {
            out.extend_from_slice(&(*c as u32).to_le_bytes());
            out.extend_from_slice(&first.to_le_bytes());
            out.push(s.len() as u8);
            out.push(*prior);
            out.extend_from_slice(&[0, 0]);
            first += s.len() as u32;
        }
        for (_, _, s) in &self.chars {
            for stroke in s {
                for &(x, y) in stroke {
                    out.push(x.round().clamp(0.0, 255.0) as u8);
                    out.push(y.round().clamp(0.0, 255.0) as u8);
                }
            }
        }
        out
    }
}

// ------------------------------------------------------------------ recognizer

/// 扫描中的一个候选：字、得分、模板序号（同分时按序号排）。 A scan candidate: char, score, template index (ties by index).
#[derive(Clone, Copy)]
struct Hit {
    ch: char,
    score: f32,
    at: usize,
}

/// 下界 `v` 是否已经比门槛 `(阈值, 阈值模板序号)` 差：同分时序号靠后的算差。
/// Whether a lower bound `v` is already worse than the threshold `(value, template index)`; ties go to the later index.
fn worse_than(v: f32, bound: (f32, usize), at: usize) -> bool {
    v > bound.0 || (v == bound.0 && at > bound.1)
}

/// 按 (得分, 序号) 插入，最多保留 `top` 个。 Insert by (score, index), keeping at most `top`.
fn insert_hit(best: &mut Vec<Hit>, hit: Hit, top: usize) {
    if best.len() == top && top > 0 {
        let last = best[top - 1];
        if (hit.score, hit.at) >= (last.score, last.at) {
            return;
        }
    }
    let pos = best.partition_point(|b| (b.score, b.at) < (hit.score, hit.at));
    best.insert(pos, hit);
    best.truncate(top);
}

struct Template {
    ch: char,
    prior: f32,
    strokes: std::ops::Range<usize>,
}

/// 手写识别器；压缩前模板约 2 MB，运行时展开为浮点点阵与特征。
/// Recognizer: ~2 MB of packed templates, expanded into floating-point trajectories and features at runtime.
pub struct Recognizer {
    pub(crate) model_id: u64,
    templates: Vec<Template>,
    points: Vec<[(f32, f32); POINTS]>,
    /// 与 points 一一对应的笔画特征（载入时算好）。 Per-stroke features, parallel to `points`, computed at load.
    feats: Vec<Feat>,
    /// Cached adjacent-join headings: 24 bytes per stored stroke, no joined trajectory copies.
    joined_feats: Vec<Feat>,
    /// Adjacent-join trajectories, parallel to `joined_feats`.
    joined_points: Vec<Samples>,
    /// 字 → 字频先验（查找用）。 Char → frequency prior, for lookups.
    priors: std::collections::HashMap<char, f32>,
}

/// 笔画特征：重心，以及各段走向（量化成 0..=255 的角度，[`NO_DIR`] 表示该段太短）。
/// Stroke features: centroid and segment headings quantised to 0..=255 ([`NO_DIR`] for a degenerate segment).
#[derive(Clone, Copy)]
struct Feat {
    c: (f32, f32),
    ang: [u16; POINTS - 1],
}

const NO_DIR: u16 = u16::MAX;

type Samples = [(f32, f32); POINTS];

#[derive(Clone, Copy)]
struct StrokeView<'a> {
    points: &'a [Samples],
    feats: &'a [Feat],
    joined_feats: &'a [Feat],
    joined_points: Option<&'a [Samples]>,
}

impl StrokeView<'_> {
    fn adjacent(&self, i: usize) -> (Samples, Feat) {
        let points = self.joined_points.map_or_else(|| join(&self.points[i], &self.points[i + 1]), |p| p[i]);
        (points, self.joined_feats[i])
    }
}

struct PreparedInk {
    points: Vec<Samples>,
    feats: Vec<Feat>,
    joined_points: Vec<Samples>,
    joined_feats: Vec<Feat>,
}

impl PreparedInk {
    fn new(strokes: &[Stroke]) -> Option<Self> {
        if !valid_ink(strokes, 64) { return None; }
        let input: Vec<_> = strokes.iter().filter(|s| !s.is_empty()).map(|s| resample(s)).collect();
        if input.is_empty() { return None; }
        let points = normalise(&input);
        let feats = points.iter().map(Feat::of).collect();
        let joined_points: Vec<_> = points.windows(2).map(|p| join(&p[0], &p[1])).collect();
        let joined_feats = joined_points.iter().map(Feat::of).collect();
        Some(Self { points, feats, joined_points, joined_feats })
    }

    fn view(&self) -> StrokeView<'_> {
        StrokeView { points: &self.points, feats: &self.feats, joined_points: Some(&self.joined_points), joined_feats: &self.joined_feats }
    }
}

impl Feat {
    fn of(s: &[(f32, f32); POINTS]) -> Feat {
        let (x, y) = s.iter().fold((0.0, 0.0), |a, p| (a.0 + p.0, a.1 + p.1));
        let mut ang = [NO_DIR; POINTS - 1];
        for (k, a) in ang.iter_mut().enumerate() {
            let (dx, dy) = (s[k + 1].0 - s[k].0, s[k + 1].1 - s[k].1);
            if dx * dx + dy * dy > 0.25 {
                let t = dy.atan2(dx).rem_euclid(std::f32::consts::TAU);
                *a = ((t / std::f32::consts::TAU * 256.0).round() as u16) & 255;
            }
        }
        Feat { c: (x / POINTS as f32, y / POINTS as f32), ang }
    }
}

/// (1 − cos Δ) / 2，Δ 为量化角度差。 (1 − cos Δ) / 2 for a quantised angle difference.
fn dir_cost_table() -> &'static [f32; 256] {
    static T: std::sync::OnceLock<[f32; 256]> = std::sync::OnceLock::new();
    T.get_or_init(|| {
        let mut t = [0f32; 256];
        for (i, v) in t.iter_mut().enumerate() {
            *v = (1.0 - (i as f32 / 256.0 * std::f32::consts::TAU).cos()) / 2.0;
        }
        t
    })
}

/// 各项代价的权重（`examples/handbench.rs` 的合成变形评测上调出）。 Cost weights, tuned on the synthetic benchmark.
const REVERSE: f32 = 1.35;
const MISSING: f32 = 0.30;
const EXTRA: f32 = 0.15;
const INVERSION: f32 = 0.10;
const PRIOR: f32 = 0.04;
/// 笔画距离三部分的权重：位置、形状、方向。 Weights of the three parts of the stroke distance.
const W_POS: f32 = 0.5;
const W_SHAPE: f32 = 0.8;
const W_DIR: f32 = 0.6;

impl Recognizer {
    pub fn open(src: &Source) -> io::Result<Recognizer> {
        Self::from_bytes(&Blob::open(src)?.to_vec())
    }

    pub fn from_bytes(b: &[u8]) -> io::Result<Recognizer> {
        let bad = |m: &str| io::Error::new(io::ErrorKind::InvalidData, m.to_string());
        if b.len() < HEADER || &b[0..4] != MAGIC {
            return Err(bad("bad handwriting data"));
        }
        let rd = |o: usize| u32::from_le_bytes([b[o], b[o + 1], b[o + 2], b[o + 3]]);
        if rd(4) != VERSION {
            return Err(bad("unsupported handwriting data version"));
        }
        let (n, total) = (rd(8) as usize, rd(12) as usize);
        let pts_off = HEADER + n * ENTRY;
        if pts_off + total * POINTS * 2 > b.len() {
            return Err(bad("truncated handwriting data"));
        }
        let mut templates = Vec::with_capacity(n);
        for i in 0..n {
            let o = HEADER + i * ENTRY;
            let (Some(ch), first, count) = (char::from_u32(rd(o)), rd(o + 4) as usize, b[o + 8] as usize) else {
                continue;
            };
            if first + count > total {
                return Err(bad("bad handwriting index"));
            }
            templates.push(Template { ch, prior: b[o + 9] as f32 / 255.0, strokes: first..first + count });
        }
        let mut points = Vec::with_capacity(total);
        for s in 0..total {
            let mut p = [(0f32, 0f32); POINTS];
            for (k, q) in p.iter_mut().enumerate() {
                let o = pts_off + (s * POINTS + k) * 2;
                *q = (b[o] as f32, b[o + 1] as f32);
            }
            points.push(p);
        }
        // 模板按同样的规则重新归一化（数据里存的是保持比例的版本）。 Re-normalise templates the same way as input.
        for t in &templates {
            let r = t.strokes.clone();
            let renorm = normalise(&points[r.clone()]);
            points[r].copy_from_slice(&renorm);
        }
        let feats = points.iter().map(Feat::of).collect();
        let mut joined_feats = vec![Feat { c: (0.0, 0.0), ang: [NO_DIR; POINTS - 1] }; points.len()];
        let mut joined_points = vec![[(0f32, 0f32); POINTS]; points.len()];
        for t in &templates {
            for i in t.strokes.clone().take(t.strokes.len().saturating_sub(1)) {
                joined_points[i] = join(&points[i], &points[i + 1]);
                joined_feats[i] = Feat::of(&joined_points[i]);
            }
        }
        let priors = templates.iter().map(|t| (t.ch, t.prior)).collect();
        Ok(Recognizer { model_id: next_model_id(), templates, points, feats, joined_feats, joined_points, priors })
    }

    pub fn len(&self) -> usize {
        self.templates.len()
    }

    /// 某字的字频先验（0..=1，没有该字为 0）。 Frequency prior of a char (0..=1; 0 when absent).
    pub fn prior_of(&self, c: char) -> f32 {
        self.priors.get(&c).copied().unwrap_or(0.0)
    }

    /// 有没有这个字的模板。 Whether there is a template for this char.
    pub fn contains(&self, c: char) -> bool {
        self.priors.contains_key(&c)
    }

    pub fn is_empty(&self) -> bool {
        self.templates.is_empty()
    }

    /// 识别一个字，返回代价最小的 `top` 个候选（代价越小越像）。模板分成几段在多个线程上并行比对。
    /// Recognise one char; the `top` lowest-cost candidates. Templates are scanned in parallel chunks.
    pub fn recognize(&self, strokes: &[Stroke], top: usize) -> Vec<(char, f32)> {
        if top == 0 { return Vec::new(); }
        let top = top.min(self.templates.len());
        if top == 0 { return Vec::new(); }
        let Some(prepared) = PreparedInk::new(strokes) else { return Vec::new(); };
        let input = prepared.view();
        let threads = std::thread::available_parallelism().map_or(1, |n| n.get()).min(self.templates.len() / 1000).clamp(1, 4);
        let chunk = self.templates.len().div_ceil(threads);
        let mut all: Vec<(char, f32)> = if threads == 1 {
            self.scan(&self.templates, input, top, None, None, false)
        } else {
            std::thread::scope(|sc| {
                let handles: Vec<_> = self
                    .templates
                    .chunks(chunk.max(1))
                    .map(|part| {
                        sc.spawn(move || self.scan(part, input, top, None, None, false))
                    })
                    .collect();
                handles.into_iter().flat_map(|h| h.join().unwrap_or_default()).collect()
            })
        };
        all.sort_by(|a, b| a.1.partial_cmp(&b.1).unwrap_or(std::cmp::Ordering::Equal));
        all.truncate(top);
        all
    }

    /// 个人模板只比较实际笔数相同的样本，避免未完成字借用另一模板的护栏。
    /// Personal adaptation compares only equal-count templates, so another sample cannot admit partial ink.
    pub(crate) fn recognize_same_stroke_count(&self, strokes: &[Stroke], top: usize) -> Vec<(char, f32)> {
        let top = top.min(self.templates.len());
        if top == 0 { return Vec::new(); }
        let Some(prepared) = PreparedInk::new(strokes) else { return Vec::new(); };
        let input = prepared.view();
        self.scan(&self.templates, input, top, None, None, true)
    }

    /// Boolean threshold query for ambiguous segmentation. It does not need a top-30 list or the best match:
    /// use the strict ceiling from the first template, and stop at the first match below it.
    pub(crate) fn has_strong_match(&self, strokes: &[Stroke], ceiling: f32) -> bool {
        let Some(input) = PreparedInk::new(strokes) else { return false; };
        let threads = std::thread::available_parallelism().map_or(1, |n| n.get()).min(self.templates.len() / 1000).clamp(1, 4);
        if threads == 1 { return !self.scan(&self.templates, input.view(), 1, Some(ceiling), None, false).is_empty(); }
        let found = std::sync::atomic::AtomicBool::new(false);
        std::thread::scope(|sc| {
            for part in self.templates.chunks(self.templates.len().div_ceil(threads)) {
                let (input, found) = (input.view(), &found);
                sc.spawn(move || { self.scan(part, input, 1, Some(ceiling), Some(found), false); });
            }
        });
        found.load(std::sync::atomic::Ordering::Relaxed)
    }

    /// 在一段模板里找最好的 `top` 个，按 (代价, 模板序号) 排序；结果与逐个顺序扫描完全一致。
    /// The best `top` within a slice of templates, ordered by (cost, template index): the same result as an in-order scan.
    /// 先精算位置下界最小的一批模板，让门槛尽早变紧，再扫其余模板；被剪掉的模板不可能进前 `top`。
    /// Seeds: the templates with the smallest position bounds are scored first so the threshold tightens early;
    /// anything pruned later could not enter the top `top`.
    fn scan(&self, templates: &[Template], ink: StrokeView<'_>, top: usize, ceiling: Option<f32>, stop: Option<&std::sync::atomic::AtomicBool>, same_count: bool) -> Vec<(char, f32)> {
        let n = ink.points.len();
        let mut best: Vec<Hit> = Vec::with_capacity(top + 1);
        let mut cost = vec![0f32; n * 64];
        let mut seeded = vec![false; templates.len()];
        if ceiling.is_none() && top > 0 && n > 0 {
            let mut keys: Vec<(f32, usize)> = templates
                .iter()
                .enumerate()
                .filter(|(_, t)| t.strokes.len() == n)
                .map(|(at, t)| (self.position_bound(ink, t), at))
                .collect();
            let seeds = (2 * top).min(keys.len());
            if seeds > 0 && seeds < keys.len() {
                keys.select_nth_unstable_by(seeds - 1, |a, b| a.0.total_cmp(&b.0));
            }
            for &(_, at) in keys.iter().take(seeds) {
                seeded[at] = true;
                if let Some(score) = self.template_score(ink, &templates[at], at, &mut cost, None) {
                    insert_hit(&mut best, Hit { ch: templates[at].ch, score, at }, top);
                }
            }
        }
        for (at, t) in templates.iter().enumerate() {
            if stop.is_some_and(|s| s.load(std::sync::atomic::Ordering::Relaxed)) { break; }
            // 连笔会让笔数变少，漏写、多写也常见：笔数相差太多的直接跳过。
            // Joined strokes reduce the count; skip templates whose stroke count is far off.
            let m = t.strokes.len();
            if seeded[at] || m == 0 || n > m + 2 || m > n + 4 + n / 2 || (same_count && m != n) {
                continue;
            }
            let bound = if best.len() == top && top > 0 {
                let last = best[top - 1];
                Some((last.score, last.at))
            } else {
                ceiling.map(|c| (c, 0))
            };
            let Some(score) = self.template_score(ink, t, at, &mut cost, bound) else { continue };
            if let Some(ceiling) = ceiling {
                if score < ceiling {
                    if let Some(stop) = stop { stop.store(true, std::sync::atomic::Ordering::Relaxed); }
                    return vec![(t.ch, score)];
                }
                continue;
            }
            insert_hit(&mut best, Hit { ch: t.ch, score, at }, top);
        }
        best.into_iter().map(|h| (h.ch, h.score)).collect()
    }

    /// 同字数时每个模板的位置下界（只用位置项，约等于 `template_score` 的下界）。
    /// Position-only lower bound on a template's score, valid when the stroke counts are equal.
    fn position_bound(&self, ink: StrokeView<'_>, t: &Template) -> f32 {
        let tf = &self.feats[t.strokes.clone()];
        let n = ink.feats.len();
        let rows: f32 = ink.feats.iter().map(|fu| {
            tf.iter().map(|fv| W_POS * (oct((fu.c.0 - fv.c.0).abs(), (fu.c.1 - fv.c.1).abs()) / 255.0)).fold(f32::MAX, f32::min)
        }).sum();
        rows / n as f32 - PRIOR * t.prior
    }

    /// 一个模板的得分（越小越像）。`bound` 是 (门槛, 门槛所在模板序号)：下界已确定比门槛差时返回 `None`。
    /// Score of one template (lower is better). `bound` is (threshold, its template index); returns `None` when the
    /// lower bound is already worse than the threshold.
    fn template_score(&self, ink: StrokeView<'_>, t: &Template, at: usize, cost: &mut Vec<f32>, bound: Option<(f32, usize)>) -> Option<f32> {
        let (input, input_feats) = (ink.points, ink.feats);
        let n = input.len();
        let m = t.strokes.len();
        let tmpl = &self.points[t.strokes.clone()];
        let tf = &self.feats[t.strokes.clone()];
        let tv = StrokeView { points: tmpl, feats: tf, joined_feats: &self.joined_feats[t.strokes.clone()], joined_points: Some(&self.joined_points[t.strokes.clone()]) };
        if cost.len() < n * m {
            cost.resize(n * m, 0.0);
        }
        let unmatched_tmpl = m.saturating_sub(n) as f32;
        let unmatched_input = n.saturating_sub(m) as f32;
        let penalty = MISSING * unmatched_tmpl / m as f32 + EXTRA * unmatched_input / n as f32;
        let fixed = penalty - PRIOR * t.prior;
        let k = n.min(m).max(1) as f32;
        // 剪枝：每行（或每列）各取最小值之和是指派代价的下界；下界已比门槛差就不必精算。
        // Pruning: the sum of per-row (or per-column) minima bounds the assignment from below.
        match bound {
            // 同字数：逐行填矩阵，行最小值之和只增不减，已经比门槛差就立即停止（结果与填完再判断相同）。
            // Equal counts: fill row by row. The running sum of row minima only grows, so stop as soon as it is already worse.
            Some(bound) if n == m => {
                let mut lower = 0f32;
                for (i, u) in input.iter().enumerate() {
                    let row = &mut cost[i * m..i * m + m];
                    for (j, (v, f)) in tmpl.iter().zip(tf).enumerate() {
                        row[j] = stroke_distance(u, &input_feats[i], v, f);
                    }
                    lower += row.iter().copied().fold(f32::MAX, f32::min);
                    if worse_than(lower / k + fixed - penalty, bound, at) {
                        return None;
                    }
                }
            }
            _ => {
                for (i, u) in input.iter().enumerate() {
                    for (j, v) in tmpl.iter().enumerate() {
                        cost[i * m + j] = stroke_distance(u, &input_feats[i], v, &tf[j]);
                    }
                }
                if let Some(bound) = bound {
                    let lower = if n <= m {
                        (0..n).map(|i| cost[i * m..i * m + m].iter().copied().fold(f32::MAX, f32::min)).sum::<f32>()
                    } else {
                        (0..m).map(|j| (0..n).map(|i| cost[i * m + j]).fold(f32::MAX, f32::min)).sum::<f32>()
                    };
                    // A join can reduce the matched distance as well as the count penalty. The single-stroke lower
                    // bound is only sufficient when counts agree; otherwise also bound the adjacent joined shapes.
                    if worse_than(lower / k + fixed - penalty, bound, at) {
                        let cutoff = (bound.0 - fixed + penalty) * k;
                        if worse_than(joined_lower(&cost[..n * m], n, m, ink, tv, cutoff) / k + fixed - penalty, bound, at) {
                            return None;
                        }
                    }
                }
            }
        }
        let (mut assigned, pairs) = assignment(&cost[..n * m], n, m);
        let mut fixed = fixed;
        if n != m {
            let (gain, freed) = if n < m {
                joins(cost, &pairs, n, m, ink, tv, false)
            } else {
                joins(cost, &pairs, n, m, ink, tv, true)
            };
            assigned -= gain;
            fixed -= freed as f32 * if n < m { MISSING / m as f32 } else { EXTRA / n as f32 };
        }
        // 笔数少时一次错位就占很大比例（先竖后横写「十」很常见）：按配对数打折。
        // With few strokes one swap is a large fraction (writing 十 vertical-first is common): scale it down.
        let k = pairs.len() as f32;
        let inv = inversions(&pairs) * (k - 1.0).max(0.0) / (k + 1.0);
        Some(assigned / n.min(m).max(1) as f32 + INVERSION * inv + fixed)
    }
}

/// 两笔之间的距离（0..≈1），分三部分：位置（两笔重心之差）、形状（去掉位置后的逐点差）、方向（各段走向之差）。
/// 部件整体错位只影响位置项，笔画略长略短、略弯时方向项仍然稳定。反向书写乘以 [`REVERSE`]。
/// Distance between two strokes (0..≈1) in three parts: position (centroid offset), shape (point offsets with the
/// position removed) and direction (segment headings). A shifted component only costs position; a slightly longer,
/// shorter or bowed stroke keeps its direction. Reversed writing is multiplied by [`REVERSE`].
fn stroke_distance(u: &[(f32, f32); POINTS], fu: &Feat, v: &[(f32, f32); POINTS], fv: &Feat) -> f32 {
    let (wp, ws, wd) = (W_POS, W_SHAPE, W_DIR);
    let (cu, cv) = (fu.c, fv.c);
    let pos = oct((cu.0 - cv.0).abs(), (cu.1 - cv.1).abs()) / 255.0;
    let (mut sf, mut sr) = (0f32, 0f32);
    for k in 0..POINTS {
        let (ax, ay) = (u[k].0 - cu.0, u[k].1 - cu.1);
        let (b, c) = (v[k], v[POINTS - 1 - k]);
        // 八边形近似欧氏距离（max + 0.41·min），省掉开方。 Octagonal approximation of the Euclidean norm, no sqrt.
        sf += oct((ax - (b.0 - cv.0)).abs(), (ay - (b.1 - cv.1)).abs());
        sr += oct((ax - (c.0 - cv.0)).abs(), (ay - (c.1 - cv.1)).abs());
    }
    let t = dir_cost_table();
    let (mut df, mut dr, mut nf, mut nr) = (0f32, 0f32, 0f32, 0f32);
    // 无走向的段不计入（用 0/1 掩码代替分支，结果与跳过相同：加上的是 0）。
    // Degenerate segments are masked out instead of branched on; the masked terms add exactly zero.
    for k in 0..POINTS - 1 {
        let a = fu.ang[k];
        let ma = f32::from(u8::from(a != NO_DIR));
        let b = fv.ang[k];
        let mb = ma * f32::from(u8::from(b != NO_DIR));
        df += mb * t[(a.wrapping_sub(b) & 255) as usize];
        nf += mb;
        // 反向：模板倒着走，每段走向转 180°。 Reversed: walk the template backwards, each heading turned 180°.
        let c = fv.ang[POINTS - 2 - k];
        let mc = ma * f32::from(u8::from(c != NO_DIR));
        dr += mc * t[(a.wrapping_sub(c.wrapping_add(128) & 255) & 255) as usize];
        nr += mc;
    }
    let norm = POINTS as f32 * 255.0;
    let fwd = ws * sf / norm + wd * if nf > 0.0 { df / nf } else { 0.0 };
    let rev = ws * sr / norm + wd * if nr > 0.0 { dr / nr } else { 0.0 };
    wp * pos + fwd.min(rev * REVERSE)
}

#[inline]
fn oct(a: f32, b: f32) -> f32 {
    a.max(b) + 0.41 * a.min(b)
}

/// 把两笔按书写顺序接成一笔并重采样。 Join two strokes in order and resample.
fn join(a: &[(f32, f32); POINTS], b: &[(f32, f32); POINTS]) -> [(f32, f32); POINTS] {
    let mut all = [(0f32, 0f32); 2 * POINTS];
    all[..POINTS].copy_from_slice(a);
    all[POINTS..].copy_from_slice(b);
    resample(&all)
}

/// Relax the assignment to independent row/column minima, allowing every adjacent two-stroke join.
/// Every distance used by `joins` appears here, so this remains a lower bound even if a join frees a stroke.
/// Only evaluated when the cheaper single-stroke bound would otherwise prune a candidate.
fn joined_lower(
    cost: &[f32], n: usize, m: usize,
    input: StrokeView<'_>, tmpl: StrokeView<'_>, cutoff: f32,
) -> f32 {
    let (small, large) = if n < m { (input, tmpl) } else { (tmpl, input) };
    let (sp, sf) = (small.points, small.feats);
    let mut minima: Vec<f32> = (0..sp.len()).map(|i| {
        if n < m { cost[i * m..(i + 1) * m].iter().copied().fold(f32::MAX, f32::min) }
        else { (0..n).map(|j| cost[j * m + i]).fold(f32::MAX, f32::min) }
    }).collect();
    let original = minima.clone();
    let base: f32 = original.iter().sum();
    // `joins` can replace at most one matched distance per unmatched stroke. Allowing all rows to join
    // made the old relaxation needlessly loose and sent many losing templates into Hungarian assignment.
    let max_joins = n.abs_diff(m).min(sp.len());
    let limited = |minima: &[f32]| {
        let mut gain = [0f32; 64];
        for (i, (&a, &b)) in original.iter().zip(minima).enumerate() { gain[i] = (a - b).max(0.0); }
        let saved = if max_joins <= 2 {
            let (mut first, mut second) = (0f32, 0f32);
            for &g in &gain[..sp.len()] {
                if g > first { second = first; first = g; } else { second = second.max(g); }
            }
            first + if max_joins == 2 { second } else { 0.0 }
        } else {
            gain[..sp.len()].select_nth_unstable_by(max_joins - 1, |a, b| b.total_cmp(a));
            gain[..max_joins].iter().sum()
        };
        (base - saved - 1e-5 * sp.len() as f32).max(0.0)
    };
    for j in 0..large.points.len() - 1 {
        let (joined, jf) = large.adjacent(j);
        for (i, minimum) in minima.iter_mut().enumerate() {
            // Position is a nonnegative term in every joined distance. Skip shape/direction work when even
            // this cheaper bound cannot improve the minimum; reuse the already computed single-stroke features.
            if W_POS * oct((sf[i].c.0 - jf.c.0).abs(), (sf[i].c.1 - jf.c.1).abs()) / 255.0 >= *minimum { continue; }
            // Reversal penalties make stroke_distance asymmetric; preserve the input/template direction.
            let d = if n < m { stroke_distance(&sp[i], &sf[i], &joined, &jf) }
                else { stroke_distance(&joined, &jf, &sp[i], &sf[i]) };
            *minimum = minimum.min(d);
        }
        // Once the lower bound drops below the pruning cutoff, more joins cannot restore it. A zero lower
        // bound safely disables pruning and avoids the rest of this scan.
        if minima.iter().sum::<f32>() < cutoff && limited(&minima) < cutoff { return 0.0; }
    }
    limited(&minima)
}

/// 连笔 / 断笔修正：指派之后，看没配上的笔能否与相邻、已配上的笔合成一笔来配。
/// `split = false`：写的笔少（连笔），没配上的是模板笔，与它相邻的模板笔接起来和写的那笔比；
/// `split = true`：写的笔多（断笔），没配上的是写的笔，与相邻的写的笔接起来和模板那笔比。
/// 返回 (指派代价减少量, 因此不再算缺失 / 多余的笔数)。
/// Join / split pass after the assignment: an unmatched stroke may merge with an adjacent matched one. With fewer
/// written strokes (joined writing) the unmatched ones are template strokes; with more (a broken stroke) they are
/// written strokes. Returns (reduction of the assignment cost, strokes no longer counted as missing / extra).
#[allow(clippy::too_many_arguments)]
fn joins(
    cost: &[f32],
    pairs: &[(usize, usize)],
    n: usize,
    m: usize,
    input: StrokeView<'_>,
    tmpl: StrokeView<'_>,
    split: bool,
) -> (f32, usize) {
    let (total, share) = if split { (n, EXTRA / n as f32) } else { (m, MISSING / m as f32) };
    let mut used = vec![false; total];
    for &(i, j) in pairs {
        used[if split { i } else { j }] = true;
    }
    let (mut gain, mut freed) = (0f32, 0usize);
    let k = n.min(m).max(1) as f32;
    for &(i, j) in pairs {
        let own = if split { i } else { j };
        let mut best: Option<(f32, usize, f32)> = None;
        for q in [own.wrapping_sub(1), own + 1] {
            if q >= total || used[q] {
                continue;
            }
            let lo = own.min(q);
            let d = if split {
                let (joined, jf) = input.adjacent(lo);
                stroke_distance(&joined, &jf, &tmpl.points[j], &tmpl.feats[j])
            } else {
                let (joined, jf) = tmpl.adjacent(lo);
                stroke_distance(&input.points[i], &input.feats[i], &joined, &jf)
            };
            // 值得换：指派代价的变化（按配对数平均）加上省下的缺失 / 多余惩罚。 Worth it when it beats the penalty.
            let delta = (cost[i * m + j] - d) / k + share;
            if delta > 0.0 && best.is_none_or(|b| delta > b.0) {
                best = Some((delta, q, d));
            }
        }
        if let Some((_, q, d)) = best {
            used[q] = true;
            gain += cost[i * m + j] - d;
            freed += 1;
        }
    }
    (gain, freed)
}

/// 最小代价指派（行 n、列 m，配对 min(n, m) 对），返回总代价与 (行, 列) 对。
/// Minimum-cost assignment of min(n, m) pairs (Hungarian algorithm on the padded square matrix).
fn assignment(cost: &[f32], n: usize, m: usize) -> (f32, Vec<(usize, usize)>) {
    let size = n.max(m);
    // 补零成方阵（与原先越界取 0 的语义相同），内层循环直接索引，不再逐元素判断边界。
    // Zero-padded square matrix (same as the old out-of-range zeros); the inner loops index it directly.
    let mut c = vec![0f32; size * size];
    for i in 0..n {
        c[i * size..i * size + m].copy_from_slice(&cost[i * m..i * m + m]);
    }
    // 经典 O(size³) 匈牙利算法（势函数版）。 Classic O(size³) Hungarian with potentials.
    // 运算顺序与原版完全一致，结果逐位相同；缓冲区在行之间复用。
    // The operation order matches the original exactly, so results are bit-identical; buffers are reused across rows.
    let inf = f32::MAX / 4.0;
    let mut u = vec![0f32; size + 1];
    let mut v = vec![0f32; size + 1];
    let mut p = vec![0usize; size + 1];
    let mut way = vec![0usize; size + 1];
    let mut minv = vec![inf; size + 1];
    let mut used = vec![false; size + 1];
    for i in 1..=size {
        p[0] = i;
        let mut j0 = 0;
        minv.fill(inf);
        used.fill(false);
        loop {
            used[j0] = true;
            let i0 = p[j0];
            let row = &c[(i0 - 1) * size..i0 * size];
            let ui0 = u[i0];
            let mut delta = inf;
            let mut j1 = 0;
            for j in 1..=size {
                if !used[j] {
                    let cur = row[j - 1] - ui0 - v[j];
                    if cur < minv[j] {
                        minv[j] = cur;
                        way[j] = j0;
                    }
                    if minv[j] < delta {
                        delta = minv[j];
                        j1 = j;
                    }
                }
            }
            for j in 0..=size {
                if used[j] {
                    u[p[j]] += delta;
                    v[j] -= delta;
                } else {
                    minv[j] -= delta;
                }
            }
            j0 = j1;
            if p[j0] == 0 {
                break;
            }
        }
        loop {
            let j1 = way[j0];
            p[j0] = p[j1];
            j0 = j1;
            if j0 == 0 {
                break;
            }
        }
    }
    let mut pairs = Vec::with_capacity(n.min(m));
    let mut total = 0f32;
    for j in 1..=size {
        let i = p[j];
        if i >= 1 && i <= n && j <= m {
            total += cost[(i - 1) * m + (j - 1)];
            pairs.push((i - 1, j - 1));
        }
    }
    pairs.sort_unstable();
    (total, pairs)
}

/// 笔顺错位比例：按书写顺序配对后，模板笔序逆序对所占比例。 Fraction of inverted pairs in the matched order.
fn inversions(pairs: &[(usize, usize)]) -> f32 {
    let k = pairs.len();
    if k < 2 {
        return 0.0;
    }
    let mut inv = 0usize;
    for a in 0..k {
        for b in a + 1..k {
            if pairs[a].1 > pairs[b].1 {
                inv += 1;
            }
        }
    }
    inv as f32 / (k * (k - 1) / 2) as f32
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strict_threshold_and_cached_join_features_agree_with_full_ranking() {
        let r = tiny();
        assert_eq!(std::mem::size_of::<Feat>(), 24);
        for shift in 0..32 {
            let f = shift as f32;
            for ink in [
                vec![vec![(20.0+f,20.0),(80.0,20.0),(0.0,80.0),(100.0,80.0)]],
                vec![line(0.0,50.0,40.0+f,50.0),line(40.0+f,50.0,100.0,50.0),line(50.0,0.0,50.0,100.0)],
            ] {
                let full = r.recognize(&ink, r.len());
                assert_eq!(r.recognize(&ink, 1), full[..1]);
                for ceiling in [-0.05,0.0,0.08,0.25,0.5,1.0] {
                    assert_eq!(r.has_strong_match(&ink, ceiling), full.iter().any(|&(_, cost)| cost < ceiling));
                }
            }
        }
    }

    fn line(x0: f32, y0: f32, x1: f32, y1: f32) -> Stroke {
        vec![(x0, y0), ((x0 + x1) / 2.0, (y0 + y1) / 2.0), (x1, y1)]
    }

    fn tiny() -> Recognizer {
        let mut b = Builder::default();
        b.push('一', 255, &[line(0.0, 50.0, 100.0, 50.0)]);
        b.push('二', 200, &[line(20.0, 20.0, 80.0, 20.0), line(0.0, 80.0, 100.0, 80.0)]);
        b.push('十', 200, &[line(0.0, 50.0, 100.0, 50.0), line(50.0, 0.0, 50.0, 100.0)]);
        b.push('丁', 100, &[line(0.0, 10.0, 100.0, 10.0), line(50.0, 10.0, 50.0, 100.0)]);
        Recognizer::from_bytes(&b.build()).unwrap()
    }

    #[test]
    fn recognizes_simple_shapes_and_tolerates_order_and_direction() {
        let r = tiny();
        assert_eq!(r.len(), 4);
        // 十：先竖后横（笔顺错）、横从右往左写（反向）也认得。 Wrong order and reversed stroke.
        let got = r.recognize(&[line(52.0, 2.0, 49.0, 97.0), line(98.0, 51.0, 3.0, 48.0)], 3);
        assert_eq!(got[0].0, '十', "{got:?}");
        let got = r.recognize(&[line(25.0, 22.0, 75.0, 18.0), line(2.0, 78.0, 99.0, 82.0)], 3);
        assert_eq!(got[0].0, '二', "{got:?}");
        let got = r.recognize(&[line(0.0, 12.0, 100.0, 8.0), line(51.0, 11.0, 48.0, 99.0)], 3);
        assert_eq!(got[0].0, '丁', "{got:?}");
        assert!(r.recognize(&[], 3).is_empty());
    }

    #[test]
    fn tolerates_shifted_parts_and_joined_strokes() {
        let r = tiny();
        // 十：横写得偏上、偏短，竖偏右——位置不准但走向对。 十 with the bar high and short, the post off-centre.
        let got = r.recognize(&[line(15.0, 25.0, 80.0, 28.0), line(62.0, 0.0, 60.0, 100.0)], 3);
        assert_eq!(got[0].0, '十', "{got:?}");
        // 二：两横一笔连写（中间带一段回笔）。 二 written in one stroke, the two bars joined by a return.
        let joined: Stroke = vec![(20.0, 20.0), (50.0, 20.0), (80.0, 20.0), (40.0, 50.0), (0.0, 80.0), (50.0, 80.0), (100.0, 80.0)];
        let got = r.recognize(&[joined], 3);
        assert_eq!(got[0].0, '二', "{got:?}");
    }

    #[test]
    fn joined_candidate_is_not_pruned_before_join_correction() {
        let r = tiny();
        let ink = vec![vec![(20.0, 20.0), (80.0, 20.0), (0.0, 80.0), (100.0, 80.0)]];
        let full = r.recognize(&ink, r.len());
        assert_eq!(full[0].0, '二', "{full:?}");
        assert_eq!(r.recognize(&ink, 1), full[..1]);
    }

    #[test]
    fn joined_and_broken_ink_has_the_same_ranking_at_every_candidate_limit() {
        let r = tiny();
        let cases = [
            vec![vec![(20.0, 20.0), (80.0, 20.0), (0.0, 80.0), (100.0, 80.0)]],
            vec![line(0.0, 50.0, 45.0, 50.0), line(45.0, 50.0, 100.0, 50.0), line(50.0, 0.0, 50.0, 100.0)],
            vec![line(20.0, 20.0, 80.0, 20.0), line(0.0, 80.0, 35.0, 80.0), line(35.0, 80.0, 100.0, 80.0)],
        ];
        for ink in cases {
            let exhaustive = r.recognize(&ink, r.len());
            for top in 1..r.len() { assert_eq!(r.recognize(&ink, top), exhaustive[..top]); }
        }
        assert!(r.recognize(&[vec![(f32::NAN, 0.0)]], 1).is_empty());
        assert!(r.recognize(&vec![vec![(0.0, 0.0)]; 65], 1).is_empty());
    }

    /// 原版指派（参照）。 The original assignment, kept as the reference.
    fn assignment_reference(cost: &[f32], n: usize, m: usize) -> (f32, Vec<(usize, usize)>) {
        let size = n.max(m);
        let at = |i: usize, j: usize| if i < n && j < m { cost[i * m + j] } else { 0.0 };
        // 经典 O(size³) 匈牙利算法（势函数版）。 Classic O(size³) Hungarian with potentials.
        let inf = f32::MAX / 4.0;
        let mut u = vec![0f32; size + 1];
        let mut v = vec![0f32; size + 1];
        let mut p = vec![0usize; size + 1];
        let mut way = vec![0usize; size + 1];
        for i in 1..=size {
            p[0] = i;
            let mut j0 = 0;
            let mut minv = vec![inf; size + 1];
            let mut used = vec![false; size + 1];
            loop {
                used[j0] = true;
                let i0 = p[j0];
                let mut delta = inf;
                let mut j1 = 0;
                for j in 1..=size {
                    if !used[j] {
                        let cur = at(i0 - 1, j - 1) - u[i0] - v[j];
                        if cur < minv[j] {
                            minv[j] = cur;
                            way[j] = j0;
                        }
                        if minv[j] < delta {
                            delta = minv[j];
                            j1 = j;
                        }
                    }
                }
                for j in 0..=size {
                    if used[j] {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        minv[j] -= delta;
                    }
                }
                j0 = j1;
                if p[j0] == 0 {
                    break;
                }
            }
            loop {
                let j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
                if j0 == 0 {
                    break;
                }
            }
        }
        let mut pairs = Vec::with_capacity(n.min(m));
        let mut total = 0f32;
        for j in 1..=size {
            let i = p[j];
            if i >= 1 && i <= n && j <= m {
                total += at(i - 1, j - 1);
                pairs.push((i - 1, j - 1));
            }
        }
        pairs.sort_unstable();
        (total, pairs)
    }

    /// 新旧实现在随机矩阵（方形与非方形）上逐位一致。 Old and new agree bit for bit on random matrices, square and not.
    #[test]
    fn assignment_matches_the_reference_bit_for_bit() {
        let mut seed = 0x2545_f491_4f6c_dd1du64;
        let mut next = move || {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            (seed >> 40) as f32 / (1u64 << 24) as f32
        };
        for n in 1..=12 {
            for m in 1..=12 {
                for _ in 0..8 {
                    let cost: Vec<f32> = (0..n * m).map(|_| next() * 2.0).collect();
                    let (a, pa) = assignment(&cost, n, m);
                    let (b, pb) = assignment_reference(&cost, n, m);
                    assert_eq!(a.to_bits(), b.to_bits(), "total differs for {n}x{m}");
                    assert_eq!(pa, pb, "pairs differ for {n}x{m}");
                }
            }
        }
    }

    #[test]
    fn assignment_is_optimal() {
        // 2×2：交叉配对更便宜。 Crossed pairing is cheaper.
        let (c, pairs) = assignment(&[5.0, 1.0, 1.0, 5.0], 2, 2);
        assert_eq!(c, 2.0);
        assert_eq!(pairs, vec![(0, 1), (1, 0)]);
        // 行多于列：多出的行不配对。 More rows than columns.
        let (c, pairs) = assignment(&[3.0, 1.0, 2.0], 3, 1);
        assert_eq!(c, 1.0);
        assert_eq!(pairs, vec![(1, 0)]);
    }
}
