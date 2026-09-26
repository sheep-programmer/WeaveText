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

/// 按弧长把一笔重采样为 [`POINTS`] 个点；只有一个点时复制。 Resample a stroke to [`POINTS`] points by arc length.
pub fn resample(s: &[(f32, f32)]) -> [(f32, f32); POINTS] {
    let mut out = [(0f32, 0f32); POINTS];
    if s.is_empty() {
        return out;
    }
    let mut cum = Vec::with_capacity(s.len());
    let mut total = 0f32;
    cum.push(0f32);
    for w in s.windows(2) {
        total += ((w[1].0 - w[0].0).powi(2) + (w[1].1 - w[0].1).powi(2)).sqrt();
        cum.push(total);
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

/// 把一组笔画按整体外框缩放到 0..=255（保持比例、居中）。 Normalise strokes to 0..=255, keeping aspect, centred.
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
    let size = (x1 - x0).max(y1 - y0).max(1e-3);
    let (ox, oy) = ((size - (x1 - x0)) / 2.0, (size - (y1 - y0)) / 2.0);
    let k = 255.0 / size;
    strokes
        .iter()
        .map(|s| {
            let mut o = [(0f32, 0f32); POINTS];
            for (i, &(x, y)) in s.iter().enumerate() {
                o[i] = ((x - x0 + ox) * k, (y - y0 + oy) * k);
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

struct Template {
    ch: char,
    prior: f32,
    strokes: std::ops::Range<usize>,
}

/// 手写识别器（模板整体读入内存，约 2 MB）。 Recognizer; templates are loaded into memory (~2 MB).
pub struct Recognizer {
    templates: Vec<Template>,
    points: Vec<[(f32, f32); POINTS]>,
}

/// 各项代价的权重。 Cost weights.
const REVERSE: f32 = 1.35;
const MISSING: f32 = 0.20;
const EXTRA: f32 = 0.30;
const INVERSION: f32 = 0.06;
const PRIOR: f32 = 0.025;

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
        Ok(Recognizer { templates, points })
    }

    pub fn len(&self) -> usize {
        self.templates.len()
    }

    /// 某字的字频先验（0..=1，没有该字为 0）。 Frequency prior of a char (0..=1; 0 when absent).
    pub fn prior_of(&self, c: char) -> f32 {
        self.templates.iter().find(|t| t.ch == c).map_or(0.0, |t| t.prior)
    }

    pub fn is_empty(&self) -> bool {
        self.templates.is_empty()
    }

    /// 识别一个字，返回代价最小的 `top` 个候选（代价越小越像）。 Recognise one char; the `top` lowest-cost candidates.
    pub fn recognize(&self, strokes: &[Stroke], top: usize) -> Vec<(char, f32)> {
        let input: Vec<_> = strokes.iter().filter(|s| !s.is_empty()).map(|s| resample(s)).collect();
        if input.is_empty() {
            return Vec::new();
        }
        let input = normalise(&input);
        let n = input.len();
        let mut best: Vec<(char, f32)> = Vec::with_capacity(top + 1);
        let mut cost = vec![0f32; n * 64];
        for t in &self.templates {
            let m = t.strokes.len();
            // 连笔会让笔数变少，漏写、多写也常见：笔数相差太多的直接跳过。
            // Joined strokes reduce the count; skip templates whose stroke count is far off.
            if n > m + 2 || m > n + 4 + n / 2 {
                continue;
            }
            let tmpl = &self.points[t.strokes.clone()];
            if cost.len() < n * m {
                cost.resize(n * m, 0.0);
            }
            for (i, u) in input.iter().enumerate() {
                for (j, v) in tmpl.iter().enumerate() {
                    cost[i * m + j] = stroke_distance(u, v);
                }
            }
            let unmatched_tmpl = m.saturating_sub(n) as f32;
            let unmatched_input = n.saturating_sub(m) as f32;
            let fixed = MISSING * unmatched_tmpl / m as f32 + EXTRA * unmatched_input / n as f32 - PRIOR * t.prior;
            // 剪枝：每行（或每列）各取最小值之和是指派代价的下界；下界已比当前第 top 名差就不必精算。
            // Pruning: the sum of per-row (or per-column) minima bounds the assignment from below.
            if best.len() == top {
                let k = n.min(m);
                let lower = if n <= m {
                    (0..n).map(|i| cost[i * m..i * m + m].iter().copied().fold(f32::MAX, f32::min)).sum::<f32>()
                } else {
                    (0..m).map(|j| (0..n).map(|i| cost[i * m + j]).fold(f32::MAX, f32::min)).sum::<f32>()
                };
                if lower / k.max(1) as f32 + fixed >= best[top - 1].1 {
                    continue;
                }
            }
            let (assigned, pairs) = assignment(&cost[..n * m], n, m);
            let inv = inversions(&pairs);
            let score = assigned / n.min(m).max(1) as f32 + INVERSION * inv + fixed;
            if best.len() < top || score < best.last().map_or(f32::MAX, |b| b.1) {
                let pos = best.partition_point(|b| b.1 <= score);
                best.insert(pos, (t.ch, score));
                best.truncate(top);
            }
        }
        best
    }
}

/// 两笔之间的距离（0..≈1）：逐点平均距离；反向书写乘以 [`REVERSE`]。 Mean point distance, reversed × REVERSE.
fn stroke_distance(u: &[(f32, f32); POINTS], v: &[(f32, f32); POINTS]) -> f32 {
    let (mut fwd, mut rev) = (0f32, 0f32);
    for k in 0..POINTS {
        let (a, b, c) = (u[k], v[k], v[POINTS - 1 - k]);
        fwd += ((a.0 - b.0).powi(2) + (a.1 - b.1).powi(2)).sqrt();
        rev += ((a.0 - c.0).powi(2) + (a.1 - c.1).powi(2)).sqrt();
    }
    (fwd.min(rev * REVERSE)) / (POINTS as f32 * 255.0)
}

/// 最小代价指派（行 n、列 m，配对 min(n, m) 对），返回总代价与 (行, 列) 对。
/// Minimum-cost assignment of min(n, m) pairs (Hungarian algorithm on the padded square matrix).
fn assignment(cost: &[f32], n: usize, m: usize) -> (f32, Vec<(usize, usize)>) {
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
