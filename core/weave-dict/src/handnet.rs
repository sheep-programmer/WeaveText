//! 手写识别的卷积网络：把整个字的笔迹画成灰度小图，由小型卷积网络分类（与笔顺、笔数、连笔无关）。
//! Handwriting CNN: the whole character's ink is drawn into a small grey image and classified by a compact
//! convolutional network, so stroke order, stroke count and joined strokes do not matter.
//!
//! 预处理必须与训练时完全一致：所有点的外框按长边缩放进 `size - 2 * margin` 并居中；每笔画成折线，
//! 像素值 = clamp(radius + 0.5 - 像素中心到折线的距离, 0, 1)，多笔取最大值。
//! Preprocessing must match training exactly: the bounding box of all points is scaled by its longer side into
//! `size - 2 * margin` and centred; each stroke is a polyline and a pixel's value is
//! clamp(radius + 0.5 - distance from the pixel centre to the polyline, 0, 1), the maximum over strokes.
//!
//! 数据布局（小端）/ Data layout (little endian):
//! ```text
//! "WVHN" | version u32 | size u32 | margin u32 | radius f32 | n_classes u32 | n_layers u32
//! classes [u32 codepoint; n_classes]
//! layers: kind u8, then
//!   1 conv3x3 + ReLU: in u32 | out u32 | scale [f32; out] | weights [i8; out*in*9] | bias [f32; out]
//!   2 maxpool 2x2
//!   3 global average pool
//!   4 fully connected: in u32 | out u32 | scale [f32; out] | weights [i8; out*in] | bias [f32; out]
//! ```
//! 权重按输出通道对称量化为 i8。卷积在载入时还原为 f32；全连接层很大，保留 i8 逐行计算。
//! Weights are symmetric per-output-channel i8. Convolutions are expanded to f32 on load; the large fully
//! connected layer stays i8 and is computed row by row.

use std::io;

use crate::blob::{Blob, Source};
use crate::hand::Stroke;

pub const MAGIC: &[u8; 4] = b"WVHN";
pub const VERSION: u32 = 1;

enum Layer {
    Conv { cin: usize, cout: usize, w: Vec<f32>, b: Vec<f32> },
    Pool,
    Gap,
    Fc { cin: usize, cout: usize, scale: Vec<f32>, w: Vec<i8>, b: Vec<f32> },
}

pub struct HandNet {
    size: usize,
    margin: f32,
    radius: f32,
    classes: Vec<char>,
    layers: Vec<Layer>,
}

struct Reader<'a> {
    b: &'a [u8],
    at: usize,
}

impl Reader<'_> {
    fn take(&mut self, n: usize) -> io::Result<&[u8]> {
        if self.at + n > self.b.len() {
            return Err(bad("truncated handwriting network"));
        }
        let s = &self.b[self.at..self.at + n];
        self.at += n;
        Ok(s)
    }
    fn u8(&mut self) -> io::Result<u8> {
        Ok(self.take(1)?[0])
    }
    fn u32(&mut self) -> io::Result<u32> {
        let s = self.take(4)?;
        Ok(u32::from_le_bytes([s[0], s[1], s[2], s[3]]))
    }
    fn f32s(&mut self, n: usize) -> io::Result<Vec<f32>> {
        Ok(self.take(n * 4)?.chunks_exact(4).map(|c| f32::from_le_bytes([c[0], c[1], c[2], c[3]])).collect())
    }
    fn i8s(&mut self, n: usize) -> io::Result<Vec<i8>> {
        Ok(self.take(n)?.iter().map(|&v| v as i8).collect())
    }
}

fn bad(m: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, m.to_string())
}

impl HandNet {
    pub fn open(src: &Source) -> io::Result<HandNet> {
        Self::from_bytes(&Blob::open(src)?.to_vec())
    }

    pub fn from_bytes(b: &[u8]) -> io::Result<HandNet> {
        if b.len() < 28 || &b[0..4] != MAGIC {
            return Err(bad("bad handwriting network"));
        }
        let mut r = Reader { b, at: 4 };
        if r.u32()? != VERSION {
            return Err(bad("unsupported handwriting network version"));
        }
        let size = r.u32()? as usize;
        let margin = r.u32()? as f32;
        let radius = r.f32s(1)?[0];
        let n = r.u32()? as usize;
        let nl = r.u32()? as usize;
        if !(8..=256).contains(&size) || n == 0 || nl > 64 {
            return Err(bad("bad handwriting network header"));
        }
        let mut classes = Vec::with_capacity(n);
        for _ in 0..n {
            classes.push(char::from_u32(r.u32()?).unwrap_or('\u{fffd}'));
        }
        let mut layers = Vec::with_capacity(nl);
        // 逐层核对通道数与尺寸，坏文件在载入时就报错而不是识别时越界。
        // Channel counts and sizes are checked layer by layer, so a bad file fails on load, not mid-recognition.
        let (mut ch, mut side, mut flat) = (1usize, size, false);
        for _ in 0..nl {
            match r.u8()? {
                1 => {
                    let (cin, cout) = (r.u32()? as usize, r.u32()? as usize);
                    if flat || cin != ch || cout == 0 || cout > 4096 {
                        return Err(bad("bad convolution layer"));
                    }
                    let scale = r.f32s(cout)?;
                    let q = r.i8s(cout * cin * 9)?;
                    let bias = r.f32s(cout)?;
                    let k = cin * 9;
                    let w = q.iter().enumerate().map(|(i, &v)| v as f32 * scale[i / k]).collect();
                    layers.push(Layer::Conv { cin, cout, w, b: bias });
                    ch = cout;
                }
                2 => {
                    if flat || side < 2 {
                        return Err(bad("bad pooling layer"));
                    }
                    side /= 2;
                    layers.push(Layer::Pool);
                }
                3 => {
                    flat = true;
                    layers.push(Layer::Gap);
                }
                4 => {
                    let (cin, cout) = (r.u32()? as usize, r.u32()? as usize);
                    if !flat || cin != ch {
                        return Err(bad("bad fully connected layer"));
                    }
                    let scale = r.f32s(cout)?;
                    let w = r.i8s(cout * cin)?;
                    let bias = r.f32s(cout)?;
                    layers.push(Layer::Fc { cin, cout, scale, w, b: bias });
                    ch = cout;
                }
                _ => return Err(bad("unknown layer")),
            }
        }
        if !flat || ch != n {
            return Err(bad("network output does not match the classes"));
        }
        Ok(HandNet { size, margin, radius, classes, layers })
    }

    pub fn len(&self) -> usize {
        self.classes.len()
    }

    pub fn is_empty(&self) -> bool {
        self.classes.is_empty()
    }

    /// 能认出的字符。 The characters it can recognise.
    pub fn classes(&self) -> &[char] {
        &self.classes
    }

    /// 识别一个字：概率最高的 `top` 个候选（概率 0..=1，从高到低）。
    /// Recognise one char: the `top` most likely candidates with probabilities 0..=1, best first.
    pub fn recognize(&self, strokes: &[Stroke], top: usize) -> Vec<(char, f32)> {
        let logits = match self.logits(strokes) {
            Some(l) => l,
            None => return Vec::new(),
        };
        let max = logits.iter().copied().fold(f32::MIN, f32::max);
        let sum: f32 = logits.iter().map(|&v| (v - max).exp()).sum();
        let mut idx: Vec<usize> = (0..logits.len()).collect();
        let k = top.min(idx.len());
        if k == 0 {
            return Vec::new();
        }
        idx.select_nth_unstable_by(k - 1, |&a, &b| logits[b].partial_cmp(&logits[a]).unwrap_or(std::cmp::Ordering::Equal));
        idx.truncate(k);
        idx.sort_by(|&a, &b| logits[b].partial_cmp(&logits[a]).unwrap_or(std::cmp::Ordering::Equal));
        idx.into_iter().map(|i| (self.classes[i], (logits[i] - max).exp() / sum)).collect()
    }

    /// 犹豫时同时看略宽、略高的写法，缓解手机上写扁/写长的比例偏差；明确的结果只推理一次。
    /// For uncertain ink, average slightly wider/taller views; confident ink needs only one inference.
    pub fn recognize_robust(&self, strokes: &[Stroke], top: usize) -> Vec<(char, f32)> {
        let original = self.recognize(strokes, top);
        if original.is_empty() || original[0].1 >= 0.6 {
            return original;
        }
        let mut probabilities = std::collections::HashMap::new();
        for &(ch, p) in &original { probabilities.insert(ch, p * 0.75); }
        for (sx, sy) in [(1.15, 1.0), (1.0, 1.15)] {
            let view: Vec<Stroke> = strokes.iter().map(|s| s.iter().map(|&(x,y)| (x * sx, y * sy)).collect()).collect();
            for (ch, p) in self.recognize(&view, top) {
                *probabilities.entry(ch).or_insert(0.0) += p * 0.125;
            }
        }
        let mut probabilities: Vec<_> = probabilities.into_iter().collect();
        probabilities.sort_by(|a,b| b.1.total_cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
        probabilities.truncate(top);
        probabilities
    }

    /// 网络输出（未归一化的对数概率）；没有笔迹时为 `None`。 Raw logits; `None` without ink.
    pub fn logits(&self, strokes: &[Stroke]) -> Option<Vec<f32>> {
        let img = raster(strokes, self.size, self.margin, self.radius)?;
        let threads = std::thread::available_parallelism().map_or(1, |n| n.get()).clamp(1, 4);
        let (mut x, mut ch, mut side) = (img, 1usize, self.size);
        for l in &self.layers {
            x = match l {
                Layer::Conv { cin, cout, w, b } => {
                    debug_assert_eq!(*cin, ch);
                    ch = *cout;
                    conv3x3(&x, *cin, side, w, b, *cout, threads)
                }
                Layer::Pool => {
                    side /= 2;
                    pool2(&x, ch, side * 2)
                }
                Layer::Gap => {
                    let hw = side * side;
                    let v = x.chunks_exact(hw).map(|c| c.iter().sum::<f32>() / hw as f32).collect();
                    side = 1;
                    v
                }
                Layer::Fc { cin, cout, scale, w, b } => {
                    ch = *cout;
                    (0..*cout)
                        .map(|o| {
                            let row = &w[o * cin..(o + 1) * cin];
                            let dot: f32 = row.iter().zip(&x).map(|(&q, &v)| q as f32 * v).sum();
                            dot * scale[o] + b[o]
                        })
                        .collect()
                }
            };
        }
        Some(x)
    }
}

/// 融合时模板代价的权重（真人笔迹评测上调出）。 Weight of the template cost in the fusion (tuned on real ink).
const FUSE_TEMPLATE: f32 = 12.0;
/// 融合时字频先验的权重。 Weight of the frequency prior in the fusion.
const FUSE_PRIOR: f32 = 2.0;
/// 两个识别器各取多少个候选参与融合。 Candidates taken from each recogniser for the fusion.
const FUSE_POOL: usize = 30;

/// 手写识别的全部模型：模板匹配器与卷积网络，两者都可缺省。线程间共享（只读）。
/// All handwriting models: the template matcher and the CNN, either may be missing. Shared read-only across threads.
pub struct HandModels {
    pub templates: Option<crate::hand::Recognizer>,
    pub net: Option<HandNet>,
}

impl HandModels {
    pub fn is_empty(&self) -> bool {
        self.templates.is_none() && self.net.is_none()
    }

    /// 识别一个字，返回最像的 `top` 个字（从好到差）。有网络时与模板匹配融合：
    /// 分数 = ln P(网络) − 12·模板代价 + 2·字频先验。网络擅长连笔、潦草，模板擅长工整、笔顺标准的书写，
    /// 两者合起来在真人笔迹上的首选准确率比单用网络高约 3 个百分点。
    /// Recognise one char: the `top` best, best first. With a network it is fused with the templates:
    /// score = ln P(net) − 12·template cost + 2·frequency prior. The network handles joined and sloppy writing,
    /// the templates neat writing in standard stroke order; together they beat the network alone by about three
    /// points of top-1 on real ink.
    pub fn recognize(&self, strokes: &[Stroke], top: usize) -> Vec<char> {
        let tmpl = self.templates.as_ref().map(|t| t.recognize(strokes, FUSE_POOL.max(top))).unwrap_or_default();
        let Some(net) = &self.net else {
            return tmpl.into_iter().take(top).map(|c| c.0).collect();
        };
        let probs = net.recognize_robust(strokes, FUSE_POOL.max(top));
        if probs.is_empty() {
            return Vec::new();
        }
        let t = self.templates.as_ref();
        fuse(&probs, &tmpl, |c| t.map_or(0.0, |t| t.prior_of(c)), |c| t.is_some_and(|t| t.contains(c)), top)
    }
}

/// 合并两个识别器的候选（见 [`HandModels::recognize`]）。只在一边出现的字，另一边按「刚好排不上」计分；
/// 模板里根本没有的字（数字、字母、标点）模板无从评判，按模板的最好代价计，只看网络。
/// Merge the two candidate lists (see [`HandModels::recognize`]). A char found by only one side scores on the
/// other side as if it just missed that list. A char with no template at all (digits, letters, punctuation) can't
/// be judged by the templates, so it gets their best cost and only the network decides.
pub fn fuse(
    net: &[(char, f32)],
    tmpl: &[(char, f32)],
    prior: impl Fn(char) -> f32,
    has_template: impl Fn(char) -> bool,
    top: usize,
) -> Vec<char> {
    let worst = tmpl.last().map_or(1.0, |x| x.1) + 0.05;
    let best = tmpl.first().map_or(0.0, |x| x.1);
    let pmin = (net.last().map_or(1e-6, |x| x.1) * 0.5).max(1e-6);
    let mut cand: Vec<char> = net.iter().chain(tmpl).map(|x| x.0).collect();
    cand.sort_unstable();
    cand.dedup();
    let mut scored: Vec<(char, f32)> = cand
        .into_iter()
        .map(|c| {
            let p = net.iter().find(|x| x.0 == c).map_or(pmin, |x| x.1.max(1e-9));
            let w = if tmpl.is_empty() { 0.0 } else { FUSE_TEMPLATE };
            let cost = tmpl.iter().find(|x| x.0 == c).map_or(if has_template(c) { worst } else { best }, |x| x.1);
            (c, p.ln() - w * cost + FUSE_PRIOR * prior(c))
        })
        .collect();
    scored.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
    scored.into_iter().take(top).map(|x| x.0).collect()
}

/// 笔迹归一化并画成 `n × n` 灰度图（与训练脚本逐像素一致）；没有点时为 `None`。
/// Normalise the ink and draw an `n × n` grey image (pixel-identical to the training script); `None` without points.
pub fn raster(strokes: &[Stroke], n: usize, margin: f32, r: f32) -> Option<Vec<f32>> {
    let pts = strokes.iter().flatten();
    let (mut x0, mut y0, mut x1, mut y1) = (f32::MAX, f32::MAX, f32::MIN, f32::MIN);
    let mut any = false;
    for &(x, y) in pts {
        any = true;
        x0 = x0.min(x);
        y0 = y0.min(y);
        x1 = x1.max(x);
        y1 = y1.max(y);
    }
    if !any {
        return None;
    }
    let nf = n as f32;
    let (w, h) = (x1 - x0, y1 - y0);
    let scale = (nf - 2.0 * margin) / w.max(h).max(1e-3);
    let (ox, oy) = ((nf - w * scale) / 2.0, (nf - h * scale) / 2.0);
    let map = |&(x, y): &(f32, f32)| ((x - x0) * scale + ox, (y - y0) * scale + oy);
    let mut img = vec![0f32; n * n];
    for s in strokes {
        if s.is_empty() {
            continue;
        }
        let p: Vec<(f32, f32)> = s.iter().map(map).collect();
        let segs: Vec<((f32, f32), (f32, f32))> =
            if p.len() == 1 { vec![(p[0], p[0])] } else { p.windows(2).map(|w| (w[0], w[1])).collect() };
        for (a, b) in segs {
            let reach = r + 1.0;
            let lx = ((a.0.min(b.0) - reach).floor().max(0.0)) as usize;
            let hx = ((a.0.max(b.0) + reach).ceil().min(nf)) as usize;
            let ly = ((a.1.min(b.1) - reach).floor().max(0.0)) as usize;
            let hy = ((a.1.max(b.1) + reach).ceil().min(nf)) as usize;
            let (dx, dy) = (b.0 - a.0, b.1 - a.1);
            let len2 = (dx * dx + dy * dy).max(1e-6);
            for py in ly..hy {
                let cy = py as f32 + 0.5;
                for px in lx..hx {
                    let cx = px as f32 + 0.5;
                    let t = (((cx - a.0) * dx + (cy - a.1) * dy) / len2).clamp(0.0, 1.0);
                    let (qx, qy) = (a.0 + t * dx - cx, a.1 + t * dy - cy);
                    let v = (r + 0.5 - (qx * qx + qy * qy).sqrt()).clamp(0.0, 1.0);
                    let cell = &mut img[py * n + px];
                    if v > *cell {
                        *cell = v;
                    }
                }
            }
        }
    }
    Some(img)
}

/// 输出列按这么宽分块计算，块内累加器留在寄存器 / L1 里。 Output columns are processed in tiles this wide.
const TILE: usize = 64;

/// 3×3 卷积（补零保持尺寸）+ ReLU。先展开成 (cin·9) × (side²) 的矩阵，再按输出通道分给几个线程。
/// 3×3 convolution with zero padding and ReLU: im2col into a (cin·9) × side² matrix, output channels split across threads.
fn conv3x3(x: &[f32], cin: usize, side: usize, w: &[f32], b: &[f32], cout: usize, threads: usize) -> Vec<f32> {
    let hw = side * side;
    let k = cin * 9;
    let mut col = vec![0f32; k * hw];
    for c in 0..cin {
        let plane = &x[c * hw..(c + 1) * hw];
        for ky in 0..3 {
            for kx in 0..3 {
                let row = &mut col[((c * 9) + ky * 3 + kx) * hw..][..hw];
                for y in 0..side {
                    let sy = y as isize + ky as isize - 1;
                    if sy < 0 || sy >= side as isize {
                        continue;
                    }
                    for xx in 0..side {
                        let sx = xx as isize + kx as isize - 1;
                        if sx >= 0 && sx < side as isize {
                            row[y * side + xx] = plane[sy as usize * side + sx as usize];
                        }
                    }
                }
            }
        }
    }
    let mut out = vec![0f32; cout * hw];
    let per = cout.div_ceil(threads.max(1));
    let work = |o0: usize, part: &mut [f32]| {
        for (oi, dst) in part.chunks_exact_mut(hw).enumerate() {
            let o = o0 + oi;
            let wr = &w[o * k..(o + 1) * k];
            let mut t0 = 0;
            while t0 < hw {
                let tl = TILE.min(hw - t0);
                let mut acc = [0f32; TILE];
                let acc = &mut acc[..tl];
                acc.fill(b[o]);
                for (kk, &wv) in wr.iter().enumerate() {
                    if wv == 0.0 {
                        continue;
                    }
                    let src = &col[kk * hw + t0..kk * hw + t0 + tl];
                    for (a, &s) in acc.iter_mut().zip(src) {
                        *a += wv * s;
                    }
                }
                for (d, &a) in dst[t0..t0 + tl].iter_mut().zip(acc.iter()) {
                    *d = a.max(0.0);
                }
                t0 += tl;
            }
        }
    };
    if threads <= 1 || cout < 8 {
        work(0, &mut out);
    } else {
        std::thread::scope(|sc| {
            for (i, part) in out.chunks_mut(per * hw).enumerate() {
                let work = &work;
                sc.spawn(move || work(i * per, part));
            }
        });
    }
    out
}

/// 2×2 最大池化。 2×2 max pooling.
fn pool2(x: &[f32], ch: usize, side: usize) -> Vec<f32> {
    let h = side / 2;
    let mut out = vec![0f32; ch * h * h];
    for c in 0..ch {
        let p = &x[c * side * side..];
        for y in 0..h {
            for xx in 0..h {
                let i = 2 * y * side + 2 * xx;
                out[c * h * h + y * h + xx] = p[i].max(p[i + 1]).max(p[i + side]).max(p[i + side + 1]);
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 手工拼一个小网络文件：一层卷积、池化、全局平均、全连接。 Assemble a tiny network file by hand.
    fn tiny(fc_w: &[i8]) -> Vec<u8> {
        let mut b = Vec::new();
        b.extend_from_slice(MAGIC);
        for v in [VERSION, 8, 1] {
            b.extend_from_slice(&v.to_le_bytes());
        }
        b.extend_from_slice(&1.0f32.to_le_bytes());
        b.extend_from_slice(&2u32.to_le_bytes()); // classes
        b.extend_from_slice(&4u32.to_le_bytes()); // layers
        for c in ['一', '丨'] {
            b.extend_from_slice(&(c as u32).to_le_bytes());
        }
        // 卷积 1→2：通道 0 对水平线敏感，通道 1 对竖线敏感。 Conv 1→2: channel 0 likes horizontal, 1 vertical.
        b.push(1);
        b.extend_from_slice(&1u32.to_le_bytes());
        b.extend_from_slice(&2u32.to_le_bytes());
        for _ in 0..2 {
            b.extend_from_slice(&(1.0f32 / 127.0).to_le_bytes());
        }
        let h: [i8; 9] = [-64, -64, -64, 127, 127, 127, -64, -64, -64];
        let v: [i8; 9] = [-64, 127, -64, -64, 127, -64, -64, 127, -64];
        b.extend(h.iter().chain(v.iter()).map(|&q| q as u8));
        for _ in 0..2 {
            b.extend_from_slice(&0f32.to_le_bytes());
        }
        b.push(2);
        b.push(3);
        b.push(4);
        b.extend_from_slice(&2u32.to_le_bytes());
        b.extend_from_slice(&2u32.to_le_bytes());
        for _ in 0..2 {
            b.extend_from_slice(&(1.0f32 / 127.0).to_le_bytes());
        }
        b.extend(fc_w.iter().map(|&q| q as u8));
        for _ in 0..2 {
            b.extend_from_slice(&0f32.to_le_bytes());
        }
        b
    }

    #[test]
    fn classifies_with_a_hand_built_network() {
        let net = HandNet::from_bytes(&tiny(&[127, -127, -127, 127])).unwrap();
        assert_eq!(net.len(), 2);
        // 横线外框很扁，缩放后仍是一条居中的水平线。 A flat bounding box still draws a centred horizontal line.
        let h = vec![vec![(0.0, 5.0), (100.0, 5.0)]];
        let v = vec![vec![(5.0, 0.0), (5.0, 100.0)]];
        assert_eq!(net.recognize(&h, 2)[0].0, '一');
        assert_eq!(net.recognize(&v, 2)[0].0, '丨');
        let p = net.recognize(&h, 2);
        assert!((p[0].1 + p[1].1 - 1.0).abs() < 1e-4);
        assert!(net.recognize(&[], 2).is_empty());
    }

    #[test]
    fn rejects_mismatched_files() {
        let mut b = tiny(&[127, -127, -127, 127]);
        assert!(HandNet::from_bytes(&b[..b.len() - 3]).is_err());
        b[4] = 9;
        assert!(HandNet::from_bytes(&b).is_err());
    }

    #[test]
    fn raster_covers_the_line_and_centres_it() {
        let img = raster(&[vec![(0.0, 0.0), (10.0, 0.0)]], 16, 2.0, 1.0).unwrap();
        // 水平线落在第 8 行附近，两端在边距处。 The line sits around row 8, ends at the margins.
        let row: f32 = (0..16).map(|x| img[8 * 16 + x]).sum();
        assert!(row > 10.0, "{row}");
        assert_eq!(img[0], 0.0);
        let dot = raster(&[vec![(3.0, 3.0)]], 16, 2.0, 1.0).unwrap();
        // 单点画在正中，落在四个像素的交角上，中心到像素中心距离 √2/2，覆盖率上限约为 1.5 − √2/2 ≈ 0.79。
        // A single point sits at the centre, on the corner of four pixels: distance √2/2 to each centre, so the
        // maximum coverage is 1.5 − √2/2 ≈ 0.79. Assert it inks the middle, not the whole canvas.
        assert!(dot.iter().any(|&v| v > 0.7), "{}", dot.iter().cloned().fold(0f32, f32::max));
        // 四个交角像素都被点到，画布四角为空。 The four corner pixels are all inked; the canvas corners are not.
        for (px, py) in [(7, 7), (7, 8), (8, 7), (8, 8)] {
            assert!(dot[py * 16 + px] > 0.7, "{px},{py} = {}", dot[py * 16 + px]);
        }
        assert_eq!(dot[0], 0.0);
    }

    #[test]
    fn fusion_lets_a_clear_template_match_win_and_keeps_net_only_chars() {
        // 网络略偏向「干」，模板明确是「于」：融合后「于」在前；只有网络认得的「千」仍保留。
        // The net slightly prefers 干, the templates clearly say 于: 于 wins; 千, only known to the net, stays.
        let net = [('干', 0.40), ('于', 0.35), ('千', 0.10)];
        let tmpl = [('于', 0.05), ('干', 0.12)];
        let got = fuse(&net, &tmpl, |_| 0.0, |_| true, 3);
        assert_eq!(got, vec!['于', '干', '千']);
        // 没有模板时就是网络的次序。 Without templates it is the network's order.
        assert_eq!(fuse(&net, &[], |_| 0.0, |_| true, 2), vec!['干', '于']);
        // 模板里没有的数字不因模板吃亏：网络更看好「2」就排在前。 A digit with no template isn't penalised by them.
        let net = [('2', 0.45), ('乙', 0.30)];
        let tmpl = [('乙', 0.10), ('之', 0.20)];
        assert_eq!(fuse(&net, &tmpl, |_| 0.0, |c| c != '2', 2), vec!['2', '乙']);
    }

    #[test]
    fn convolution_matches_a_naive_version() {
        let (cin, cout, side) = (3, 5, 6);
        let x: Vec<f32> = (0..cin * side * side).map(|i| ((i * 37 % 11) as f32 - 5.0) / 7.0).collect();
        let w: Vec<f32> = (0..cout * cin * 9).map(|i| ((i * 13 % 17) as f32 - 8.0) / 9.0).collect();
        let b: Vec<f32> = (0..cout).map(|i| i as f32 * 0.1 - 0.2).collect();
        for threads in [1, 3] {
            let got = conv3x3(&x, cin, side, &w, &b, cout, threads);
            for o in 0..cout {
                for y in 0..side {
                    for xx in 0..side {
                        let mut s = b[o];
                        for c in 0..cin {
                            for ky in 0..3 {
                                for kx in 0..3 {
                                    let (sy, sx) = (y as isize + ky - 1, xx as isize + kx - 1);
                                    if sy >= 0 && sx >= 0 && (sy as usize) < side && (sx as usize) < side {
                                        s += w[((o * cin + c) * 9) + (ky * 3 + kx) as usize]
                                            * x[c * side * side + sy as usize * side + sx as usize];
                                    }
                                }
                            }
                        }
                        let g = got[o * side * side + y * side + xx];
                        assert!((g - s.max(0.0)).abs() < 1e-4, "{g} vs {s}");
                    }
                }
            }
        }
    }
}
