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
    model_id: u64,
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
        Ok(HandNet { model_id: crate::hand::next_model_id(), size, margin, radius, classes, layers })
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
        if top == 0 || !crate::hand::valid_ink(strokes, 64) { return Vec::new(); }
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
        if !crate::hand::valid_ink(strokes, 64) { return None; }
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

/// 既有的模板融合权重；本轮不使用来源或许可未核实的笔迹重新调参。
/// Existing template fusion weight; do not retune on ink whose provenance or permission is unverified.
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
    corrections: std::sync::Mutex<Corrections>,
    personal_enabled: std::sync::atomic::AtomicBool,
    line_mode: std::sync::atomic::AtomicBool,
    cache: std::sync::Mutex<std::collections::VecDeque<CachedInk>>,
}

/// Exact raw-ink keys: no quantization/hash aliasing. Eight entries, at most 16384 points each (1 MiB of points).
/// Cache only immutable model scores and threshold answers; personal corrections are applied on every call.
struct CachedInk {
    ink: Vec<Stroke>,
    models: (u64, u64),
    scores: Option<Vec<(char, f32)>>,
    strong: Option<bool>,
}

#[derive(Default)]
struct Corrections {
    examples: Vec<(char, Vec<Stroke>)>,
    recognizer: Option<crate::hand::Recognizer>,
    undo: Option<Vec<(char, Vec<Stroke>)>>,
}

impl HandModels {
    pub fn new(templates: Option<crate::hand::Recognizer>, net: Option<HandNet>) -> Self {
        Self { templates, net, corrections: std::sync::Mutex::new(Corrections::default()), personal_enabled: std::sync::atomic::AtomicBool::new(true), line_mode: std::sync::atomic::AtomicBool::new(false), cache: std::sync::Mutex::new(std::collections::VecDeque::new()) }
    }

    fn model_ids(&self) -> (u64, u64) {
        (self.templates.as_ref().map_or(0, |m| m.model_id), self.net.as_ref().map_or(0, |m| m.model_id))
    }

    fn cached(&self, ink: &[Stroke]) -> (Option<Vec<(char, f32)>>, Option<bool>) {
        let ids = self.model_ids();
        let mut cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        let Some(i) = cache.iter().position(|e| e.models == ids && e.ink == ink) else { return (None, None); };
        let entry = cache.remove(i).unwrap();
        let result = (entry.scores.clone(), entry.strong);
        cache.push_back(entry);
        result
    }

    fn cache_result(&self, ink: &[Stroke], scores: Option<Vec<(char, f32)>>, strong: Option<bool>) {
        if ink.iter().map(Vec::len).sum::<usize>() > 16384 { return; }
        let ids = self.model_ids();
        let mut cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        let mut entry = if let Some(i) = cache.iter().position(|e| e.models == ids && e.ink == ink) {
            cache.remove(i).unwrap()
        } else { CachedInk { ink: ink.to_vec(), models: ids, scores: None, strong: None } };
        if scores.is_some() { entry.scores = scores; }
        if strong.is_some() { entry.strong = strong; }
        cache.push_back(entry);
        while cache.len() > 8 { cache.pop_front(); }
    }

    pub fn set_personal_enabled(&self, enabled: bool) {
        self.personal_enabled.store(enabled, std::sync::atomic::Ordering::Release);
        self.cache.lock().unwrap_or_else(|e| e.into_inner()).clear();
    }

    pub fn samples(&self)->Vec<(char,Vec<Stroke>)> {
        self.corrections.lock().unwrap_or_else(|e|e.into_inner()).examples.clone()
    }
    pub fn set_line_mode(&self,on:bool){self.line_mode.store(on,std::sync::atomic::Ordering::Release);}
    /// Separate up to four characters by horizontal whitespace. Keep original pen order within a group.
    /// A smaller gap is accepted only between two character-sized shapes; narrow radicals stay together.
    /// Touching ink or a stroke bridging two characters has no reliable whitespace boundary.
    pub fn line_groups(strokes:&[Stroke])->Vec<Vec<Stroke>> {
        if strokes.len() > 4 * 64 { return Vec::new(); }
        // Validate and collect all four bounds in one pass, rather than rescanning long trajectories for
        // validity, left/right, and top/bottom separately. Keep the same finite-coordinate and size limits.
        let mut bounds = Vec::with_capacity(strokes.len());
        let (mut top, mut bottom) = (f32::INFINITY, f32::NEG_INFINITY);
        for (i, stroke) in strokes.iter().enumerate() {
            if stroke.len() > 4096 { return Vec::new(); }
            let (mut left, mut right) = (f32::INFINITY, f32::NEG_INFINITY);
            for &(x, y) in stroke {
                if !x.is_finite() || !y.is_finite() || x.abs() > 100_000.0 || y.abs() > 100_000.0 { return Vec::new(); }
                left = left.min(x); right = right.max(x); top = top.min(y); bottom = bottom.max(y);
            }
            if !stroke.is_empty() { bounds.push((left, right, i)); }
        }
        bounds.sort_by(|a,b|a.0.total_cmp(&b.0));
        let height=(bottom-top).max(0.01);
        // First collect ink components using a conservative small whitespace threshold. Work is O(points +
        // strokes log strokes); no alternative segmentations or extra recognizer calls are generated.
        let mut components:Vec<(f32,f32,Vec<usize>)>=Vec::new();
        for (left,right,i) in bounds {
            if components.last().is_none_or(|g| left-g.1>height*0.08) {
                components.push((left,right,vec![i]));
            } else {
                let g=components.last_mut().unwrap();g.1=g.1.max(right);g.2.push(i);
            }
        }
        let mut groups:Vec<(f32,f32,Vec<usize>)>=Vec::new();
        for (i,component) in components.iter().enumerate() {
            let split=groups.last().is_none_or(|g| {
                let gap=component.0-g.1;
                let left_width=g.1-g.0;
                let right_width=component.1-component.0;
                // Large whitespace retains the old behavior unless both sides are thin parts of a single
                // roughly square character (e.g. separated left/right radicals).
                let radicals=left_width<height*0.55 && right_width<height*0.55 && component.1-g.0<=height*1.15;
                // Include nearby components of the right-hand glyph when assessing its width.
                let mut right_edge=component.1;
                for next in components.iter().skip(i+1).take(14) {
                    if next.0-right_edge>height*0.23 || next.1-component.0>height*1.15 { break; }
                    right_edge=next.1;
                }
                (gap>height*0.23 && !radicals) ||
                    (gap>height*0.08 && left_width>=height*0.55 && right_edge-component.0>=height*0.55)
            });
            if split {groups.push(component.clone());}
            else {let g=groups.last_mut().unwrap();g.1=g.1.max(component.1);g.2.extend(&component.2);}
        }
        if groups.len()>4 {return vec![strokes.to_vec()];}
        groups.into_iter().map(|(_,_,mut indices)| {
            indices.sort_unstable();indices.into_iter().map(|i|strokes[i].clone()).collect()
        }).collect()
    }
    /// Whitespace alone cannot distinguish two close characters from a wide character with detached radicals.
    /// Check only suspicious boundaries: close gaps, narrow/short pieces, few-stroke pieces, or a compact pair.
    /// Clear full-sized groups skip the whole-character query. Threshold queries/cache never run a whole CNN.
    fn input_groups(&self, strokes: &[Stroke]) -> Vec<Vec<Stroke>> {
        let groups = Self::line_groups(strokes);
        if groups.len() < 2 || strokes.len() > 64 { return groups; }
        if self.corrected_char(strokes).is_some() { return vec![strokes.to_vec()]; }
        if !Self::suspicious_groups(&groups) { return groups; }
        let strong = self.cached(strokes).1.unwrap_or_else(|| {
            let strong = self.templates.as_ref().is_some_and(|r| r.has_strong_match(strokes, 0.08));
            self.cache_result(strokes, None, Some(strong));
            strong
        });
        if strong { vec![strokes.to_vec()] } else { groups }
    }

    fn suspicious_groups(groups: &[Vec<Stroke>]) -> bool {
        let bounds: Vec<_> = groups.iter().map(|g| g.iter().flatten().fold(
            (f32::INFINITY, f32::INFINITY, f32::NEG_INFINITY, f32::NEG_INFINITY),
            |b, &(x, y)| (b.0.min(x), b.1.min(y), b.2.max(x), b.3.max(y)),
        )).collect();
        let height = (bounds.iter().map(|b| b.3).fold(f32::NEG_INFINITY, f32::max) -
            bounds.iter().map(|b| b.1).fold(f32::INFINITY, f32::min)).max(0.01);
        groups.iter().zip(&bounds).any(|(g, b)| g.len() < 3 || b.2-b.0 < height*0.55 || b.3-b.1 < height*0.55) ||
            bounds.windows(2).any(|b| b[1].0-b[0].2 <= height*0.23) ||
            (groups.len() == 2 && bounds[1].2-bounds[0].0 <= height*1.8)
    }
    pub fn recognize_input(&self,strokes:&[Stroke],top:usize)->Vec<char> {
        if top == 0 || !crate::hand::valid_ink(strokes, 4 * 64) { return Vec::new(); }
        if !self.line_mode.load(std::sync::atomic::Ordering::Acquire){return self.recognize(strokes,top);}
        let groups=self.input_groups(strokes);
        if groups.len()<2{return self.recognize(strokes,top);}
        let choices:Vec<_>=groups.iter().map(|g|self.recognize(g,3)).collect();
        if choices.iter().any(Vec::is_empty){return Vec::new();}
        let first:String=choices.iter().map(|c|c[0]).collect();let mut words=vec![first.clone()];
        for (i,list) in choices.iter().enumerate(){for &ch in list.iter().skip(1){
            let mut word:Vec<_>=first.chars().collect();word[i]=ch;words.push(word.into_iter().collect());
        }}
        let mut result=Vec::new();for word in words.into_iter().take(top){result.extend(word.chars());result.push('\0');}result
    }
    pub fn correct_line(&self,text:&str,strokes:&[Stroke])->bool {
        let groups=self.input_groups(strokes);let chars:Vec<_>=text.chars().collect();
        if chars.len()!=groups.len() || groups.len()<2{return false;}
        let before=self.samples();let mut changed=false;
        for (ch,ink) in chars.into_iter().zip(&groups){
            if self.recognize(ink,1).first().copied()!=Some(ch){self.correct(ch,ink);changed=true;}
        }
        if changed {self.corrections.lock().unwrap_or_else(|e|e.into_inner()).undo=Some(before);}
        changed
    }
    pub fn restore_samples(&self,samples:Vec<(char,Vec<Stroke>)>)->bool {
        if samples.len()>96 || samples.iter().any(|(_,ink)|ink.is_empty() || ink.len()>64 || ink.iter().any(|s|s.len()>64 || s.iter().any(|&(x,y)|!x.is_finite() || !y.is_finite() || x.abs()>100_000.0 || y.abs()>100_000.0))){return false;}
        let mut c=self.corrections.lock().unwrap_or_else(|e|e.into_inner());
        let mut builder=crate::hand::Builder::default();
        for (ch,ink) in &samples{builder.push(*ch,0,ink);}
        c.recognizer=crate::hand::Recognizer::from_bytes(&builder.build()).ok();c.examples=samples;c.undo=None;true
    }

    /// Only explicit candidate corrections teach personal shapes; bounded to this engine process.
    pub fn correct(&self, ch: char, strokes: &[Stroke]) {
        if !self.personal_enabled.load(std::sync::atomic::Ordering::Acquire) { return; }
        if strokes.is_empty() || strokes.len() > 64 || strokes.iter().any(|s| s.len() > 4096 || s.iter().any(|&(x,y)| !x.is_finite() || !y.is_finite())) { return; }
        let sampled: Vec<Stroke> = strokes.iter().filter(|s|!s.is_empty()).map(|s| crate::hand::resample(s).to_vec()).collect();
        if sampled.is_empty() { return; }
        let mut learned = self.corrections.lock().unwrap_or_else(|e|e.into_inner());
        learned.undo = Some(learned.examples.clone());
        if learned.examples.iter().filter(|(c,_)| *c == ch).count() >= 4 {
            if let Some(i) = learned.examples.iter().position(|(c,_)| *c == ch) { learned.examples.remove(i); }
        }
        learned.examples.push((ch, sampled));
        if learned.examples.len() > 96 { learned.examples.remove(0); }
        let mut builder = crate::hand::Builder::default();
        for (c, ink) in &learned.examples { builder.push(*c, 0, ink); }
        learned.recognizer = crate::hand::Recognizer::from_bytes(&builder.build()).ok();
    }

    pub fn undo_correction(&self) {
        let mut learned = self.corrections.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(before) = learned.undo.take() {
            learned.examples = before;
            let mut builder = crate::hand::Builder::default();
            for (ch, ink) in &learned.examples { builder.push(*ch, 0, ink); }
            learned.recognizer = crate::hand::Recognizer::from_bytes(&builder.build()).ok();
        }
    }

    fn corrected_char(&self, strokes: &[Stroke]) -> Option<char> {
        if !self.personal_enabled.load(std::sync::atomic::Ordering::Acquire) { return None; }
        let learned = self.corrections.lock().unwrap_or_else(|e| e.into_inner());
        let found = learned.recognizer.as_ref()?.recognize(strokes, 8);
        let first = found.first()?;
        // A partial character must not be promoted from a learned complete character.
        if !learned.examples.iter().any(|(ch, ink)| *ch == first.0 && ink.len() == strokes.iter().filter(|s|!s.is_empty()).count()) { return None; }
        let next = found.iter().find(|(ch,_)| *ch != first.0).map_or(1.0, |x| x.1);
        (first.1 < 0.16 && next - first.1 > 0.05).then_some(first.0)
    }

    pub fn is_empty(&self) -> bool {
        self.templates.is_none() && self.net.is_none()
    }

    /// 识别一个字，返回最像的 `top` 个字（从好到差）。有网络时与模板匹配融合：
    /// 分数 = ln P(网络) − 12·模板代价 + 2·字频先验。网络擅长连笔、潦草，模板擅长工整、笔顺标准的书写，
    /// 本轮准确率只能由带来源与许可说明的评测集支持；合成变形不能代表真人表现。
    /// Recognise one char: the `top` best, best first. With a network it is fused with the templates:
    /// score = ln P(net) − 12·template cost + 2·frequency prior. The network handles joined and sloppy writing,
    /// the templates neat writing in standard stroke order. Synthetic deformation scores do not establish
    /// real-writer accuracy; that requires an evaluation set with verified provenance and permission.
    pub fn recognize(&self, strokes: &[Stroke], top: usize) -> Vec<char> {
        self.recognize_scored(strokes, top).into_iter().map(|x| x.0).collect()
    }

    /// 同 [`HandModels::recognize`]，并带上分数。个人纠正过的字排在最前，分数比第一名再高一点。
    /// Same as [`HandModels::recognize`] with scores; a personally corrected char goes first, scored just above the best.
    pub fn recognize_scored(&self, strokes: &[Stroke], top: usize) -> Vec<(char, f32)> {
        if top == 0 || !crate::hand::valid_ink(strokes, 64) { return Vec::new(); }
        let pool = FUSE_POOL.max(top);
        let cached = if top <= FUSE_POOL { self.cached(strokes).0 } else { None };
        let mut result = cached.unwrap_or_else(|| {
        let tmpl = self.templates.as_ref().map(|t| t.recognize(strokes, pool)).unwrap_or_default();
        let strong = tmpl.first().is_some_and(|&(_, cost)| cost < 0.08);
        let result = if let Some(net) = &self.net {
            let probs = net.recognize_robust(strokes, pool);
            let t = self.templates.as_ref();
            fuse_scored(&probs, &tmpl, |c| t.map_or(0.0, |t| t.prior_of(c)), |c| t.is_some_and(|t| t.contains(c)), pool)
        } else {
            // 只有模板：代价越低越好，换成同方向的分数。 Templates only: lower cost is better; flip the sign.
            tmpl.into_iter().take(pool).map(|c| (c.0, -c.1 * 12.0)).collect()
        };
        if top <= FUSE_POOL { self.cache_result(strokes, Some(result.clone()), Some(strong)); }
        result
        });
        result.truncate(top);
        if let Some(ch) = self.corrected_char(strokes) {
            let best = result.iter().map(|x| x.1).fold(f32::NEG_INFINITY, f32::max);
            result.retain(|&(c, _)| c != ch);
            // 个人纠正是用户明确教的：加得足够多，上文重排也翻不过它。 Taught explicitly; no context re-ranking may overturn it.
            result.insert(0, (ch, if best.is_finite() { best + 50.0 } else { 0.0 }));
            result.truncate(top);
        }
        result
    }

    /// 一次笔迹的识别结果，按「字组」分：单字模式只有一组；连写模式按字间留白分成 2–4 组、每组前 5 个。
    /// Recognition of one ink: grouped per character. Single-char mode gives one group; spaced mode splits at the
    /// whitespace into 2–4 groups of five candidates each.
    pub fn recognize_groups(&self, strokes: &[Stroke], top: usize) -> Vec<Vec<(char, f32)>> {
        if top == 0 || !crate::hand::valid_ink(strokes, 4 * 64) { return Vec::new(); }
        if self.line_mode.load(std::sync::atomic::Ordering::Acquire) {
            let groups = self.input_groups(strokes);
            if groups.len() >= 2 {
                return groups.iter().map(|g| self.recognize_scored(g, LINE_PER_GROUP)).collect();
            }
        }
        vec![self.recognize_scored(strokes, top)]
    }

    /// [`HandModels::recognize_groups`] 编成传输格式（见 [`encode_wire`]）。 Encoded for transport.
    pub fn recognize_wire(&self, strokes: &[Stroke], top: usize) -> Vec<u32> {
        encode_wire(&self.recognize_groups(strokes, top))
    }
}

/// 连写模式每个字组保留的候选数。 Candidates kept per group in spaced mode.
pub const LINE_PER_GROUP: usize = 5;
/// 传输格式的开头标记（"WHV1"）。 Leading marker of the wire format ("WHV1").
pub const WIRE_MAGIC: u32 = 0x5748_5631;

/// 识别结果的传输格式：`[MAGIC, 组数, (个数, (字码位, 分数×1000)…)…]`。Android 当作 `IntArray`、Mac 当作 JSON 数组原样转交，
/// 分数按补码存进 u32。没有开头标记的数组仍按旧格式（只有字码位）解读。
/// Wire format of a recognition: `[MAGIC, groups, (count, (code point, score×1000)…)…]`. Android hands it over as an
/// `IntArray` and the Mac as a JSON array, untouched; scores are stored as two's complement in u32. An array without the
/// marker is read in the old format (code points only).
pub fn encode_wire(groups: &[Vec<(char, f32)>]) -> Vec<u32> {
    let mut out = vec![WIRE_MAGIC, groups.len() as u32];
    for g in groups {
        out.push(g.len() as u32);
        for &(c, s) in g {
            out.push(c as u32);
            out.push(((s * 1000.0).round().clamp(-2.0e9, 2.0e9) as i32) as u32);
        }
    }
    out
}

/// 读回 [`encode_wire`]；格式不对（或是旧格式）为 `None`。 Reads [`encode_wire`] back; `None` if malformed or old format.
pub fn decode_wire(wire: &[u32]) -> Option<Vec<Vec<(char, f32)>>> {
    let mut it = wire.iter().copied();
    if it.next()? != WIRE_MAGIC { return None; }
    let groups = it.next()? as usize;
    if groups == 0 || groups > 8 { return None; }
    let mut out = Vec::with_capacity(groups);
    for _ in 0..groups {
        let n = it.next()? as usize;
        if n > 64 { return None; }
        let mut g = Vec::with_capacity(n);
        for _ in 0..n {
            let c = char::from_u32(it.next()?)?;
            let s = it.next()? as i32 as f32 / 1000.0;
            g.push((c, s));
        }
        out.push(g);
    }
    it.next().is_none().then_some(out)
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
    fuse_scored(net, tmpl, prior, has_template, top).into_iter().map(|x| x.0).collect()
}

/// 同 [`fuse`]，并带上融合分数（近似对数概率，越大越像）。 Same as [`fuse`], with the fused score (roughly a log
/// probability, larger is better) so a caller can combine it with other evidence such as the context.
pub fn fuse_scored(
    net: &[(char, f32)],
    tmpl: &[(char, f32)],
    prior: impl Fn(char) -> f32,
    has_template: impl Fn(char) -> bool,
    top: usize,
) -> Vec<(char, f32)> {
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
    scored.truncate(top);
    scored
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
    #[test]
    fn cached_scores_do_not_cache_corrections_or_alias_changed_points_or_models() {
        let cross = vec![vec![(0.0,50.0),(100.0,50.0)],vec![(50.0,0.0),(50.0,100.0)]];
        let mut builder = crate::hand::Builder::default();
        builder.push('十', 200, &cross);
        builder.push('一', 255, &cross[..1]);
        let bytes = builder.build();
        let mut models = HandModels::new(Some(crate::hand::Recognizer::from_bytes(&bytes).unwrap()), None);
        let original = models.recognize_scored(&cross, 5);
        assert_eq!(models.recognize_scored(&cross, 1), original[..1]);
        models.correct('土', &cross);
        assert_eq!(models.recognize(&cross, 5)[0], '土');
        models.undo_correction();
        assert_eq!(models.recognize_scored(&cross, 5), original);
        let changed = vec![vec![(0.0,50.0),(50.0,0.0),(100.0,50.0)],cross[1].clone()];
        let fresh = HandModels::new(Some(crate::hand::Recognizer::from_bytes(&bytes).unwrap()), None);
        assert_eq!(models.recognize_scored(&changed, 5), fresh.recognize_scored(&changed, 5));
        let mut builder = crate::hand::Builder::default();
        builder.push('干', 255, &cross);
        models.templates = Some(crate::hand::Recognizer::from_bytes(&builder.build()).unwrap());
        assert_eq!(models.recognize(&cross, 1), vec!['干']);
    }

    #[test]
    fn threshold_queries_skip_clear_layouts_and_exact_ink_cache_is_bounded() {
        let square = vec![vec![(0.0,0.0),(0.0,100.0)],vec![(0.0,0.0),(80.0,0.0),(80.0,100.0)],vec![(0.0,100.0),(80.0,100.0)]];
        let right: Vec<Stroke> = square.iter().map(|s|s.iter().map(|&(x,y)|(x+140.0,y)).collect()).collect();
        assert!(!HandModels::suspicious_groups(&[square.clone(),right]));
        let close: Vec<Stroke> = square.iter().map(|s|s.iter().map(|&(x,y)|(x+90.0,y)).collect()).collect();
        assert!(HandModels::suspicious_groups(&[square.clone(),close]));
        let mut builder = crate::hand::Builder::default(); builder.push('口', 255, &square);
        let models = HandModels::new(Some(crate::hand::Recognizer::from_bytes(&builder.build()).unwrap()), None);
        for i in 0..20 {
            let ink: Vec<Stroke> = square.iter().map(|s|s.iter().map(|&(x,y)|(x+i as f32,y)).collect()).collect();
            assert_eq!(models.recognize(&ink, 1), vec!['口']);
        }
        assert!(models.cache.lock().unwrap().len() <= 8);
        let large = vec![vec![(0.0,0.0);4096],vec![(1.0,1.0);4096],vec![(2.0,2.0);4096],vec![(3.0,3.0);4096],vec![(4.0,4.0)]];
        models.recognize(&large, 1);
        assert!(models.cached(&large).0.is_none());
    }

    #[test]
    fn touching_cross_character_ink_and_overlong_rows_stay_unsplit() {
        // A single pen-down across two shapes cannot be separated safely by whitespace alone.
        let joined = vec![vec![(0.0, 0.0), (80.0, 100.0), (90.0, 0.0), (170.0, 100.0)]];
        assert_eq!(HandModels::line_groups(&joined), vec![joined.clone()]);
        let five: Vec<Stroke> = (0..5).map(|i| vec![(i as f32 * 160.0, 0.0), (i as f32 * 160.0 + 80.0, 100.0)]).collect();
        assert_eq!(HandModels::line_groups(&five), vec![five.clone()]);
    }

    #[test]
    fn a_complete_wide_character_is_not_split_into_its_radicals() {
        let ink = vec![vec![(0.0, 0.0), (60.0, 100.0)], vec![(72.0, 0.0), (132.0, 100.0)]];
        assert_eq!(HandModels::line_groups(&ink).len(), 2); // Geometry alone is ambiguous.
        let mut builder = crate::hand::Builder::default();
        builder.push('从', 255, &ink);
        let models = HandModels::new(Some(crate::hand::Recognizer::from_bytes(&builder.build()).unwrap()), None);
        models.set_line_mode(true);
        let result = models.recognize_groups(&ink, 5);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0][0].0, '从');
        // Additional strokes of the same character keep using single-character recognition.
        assert_eq!(models.recognize_groups(&ink[..1], 5).len(), 1);
        assert!(!models.correct_line("人人", &ink));
    }

    #[test]
    fn inference_rejects_invalid_or_excessive_ink_and_zero_candidates() {
        let net = HandNet::from_bytes(&tiny(&[127, -127, -127, 127])).unwrap();
        let models = HandModels::new(None, Some(net));
        for ink in [vec![vec![(f32::NAN, 0.0)]], vec![vec![(0.0, f32::INFINITY)]], vec![vec![(0.0, 0.0)]; 65], vec![vec![(0.0, 0.0); 4097]]] {
            assert!(models.recognize(&ink, 5).is_empty());
            assert!(models.net.as_ref().unwrap().logits(&ink).is_none());
        }
        models.set_line_mode(true);
        assert!(models.recognize_groups(&[vec![(0.0, 0.0)]], 0).is_empty());
        assert!(HandModels::line_groups(&vec![vec![(0.0, 0.0)]; 257]).is_empty());
    }

    #[test]
    fn line_groups_preserve_pen_order_inside_each_character() {
        let left = vec![vec![(40.0, 0.0), (40.0, 100.0)], vec![(0.0, 50.0), (80.0, 50.0)]];
        let right: Vec<Stroke> = left.iter().map(|s| s.iter().map(|&(x, y)| (x + 130.0, y)).collect()).collect();
        // Spatial sorting may find the character order, but must never rewrite the order used by template joins.
        let all: Vec<Stroke> = left.iter().chain(&right).cloned().collect();
        assert_eq!(HandModels::line_groups(&all), vec![left, right]);
    }

    #[test]
    fn line_groups_separate_close_full_size_chars_and_keep_narrow_radicals() {
        let square = vec![vec![(0.0, 0.0), (0.0, 100.0)], vec![(0.0, 0.0), (80.0, 0.0), (80.0, 100.0)], vec![(0.0, 100.0), (80.0, 100.0)]];
        let shifted: Vec<Stroke> = square.iter().map(|s| s.iter().map(|&(x, y)| (x + 90.0, y)).collect()).collect();
        let all: Vec<Stroke> = square.iter().chain(&shifted).cloned().collect();
        assert_eq!(HandModels::line_groups(&all), vec![square.clone(), shifted]);
        let narrow: Vec<Stroke> = square.iter().map(|s| s.iter().map(|&(x, y)| (x * 0.4, y)).collect()).collect();
        let radical: Vec<Stroke> = narrow.iter().map(|s| s.iter().map(|&(x, y)| (x + 62.0, y)).collect()).collect();
        let one: Vec<Stroke> = narrow.iter().chain(&radical).cloned().collect();
        assert_eq!(HandModels::line_groups(&one), vec![one]);
    }

    #[test]
    fn personal_corrections_generalize_to_scaled_ink_and_do_not_affect_private_fields() {
        let cross = vec![vec![(0.0,50.0),(100.0,50.0)],vec![(50.0,0.0),(50.0,100.0)]];
        let mut builder = crate::hand::Builder::default();
        builder.push('十', 200, &cross);
        builder.push('一', 255, &cross[..1]);
        let models = HandModels::new(Some(crate::hand::Recognizer::from_bytes(&builder.build()).unwrap()), None);
        assert_eq!(models.recognize(&cross, 5)[0], '十');
        models.correct('土', &cross);
        let shifted: Vec<Stroke> = cross.iter().map(|s|s.iter().map(|&(x,y)|(x*1.05+17.0,y*0.97+9.0)).collect()).collect();
        assert_eq!(models.recognize(&shifted, 5)[0], '土');
        assert_eq!(models.recognize(&cross[..1], 5)[0], '一');
        models.set_personal_enabled(false);
        assert_eq!(models.recognize(&shifted, 5)[0], '十');
        models.correct('干', &cross);
        models.set_personal_enabled(true);
        assert_eq!(models.recognize(&shifted, 5)[0], '土');
    }
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
