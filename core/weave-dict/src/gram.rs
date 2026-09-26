//! 字符级搭配模型（n-gram）：给「上文末尾几个字 + 下一个词开头几个字」打分。
//! Character collocation model: scores "last chars of the context + first chars of the next word".
//!
//! 布局（小端）/ Layout (little endian):
//! ```text
//! Header 32 bytes: "WVGM" | version u32 | n_chars u32 | [count_len2, count_len3, count_len4] u32×3 | pad
//! Chars   [u32; n_chars]                 按码位升序，下标即字符 id（u16）
//! 对每个长度 L ∈ {2,3,4}：
//!   Groups [u32; n_chars + 1]            以首字 id 分组的起始下标
//!   Keys   [[u16; L-1]; count_L]          组内按剩余字 id 升序
//!   Values [u8; count_L]                  值 = round(score × 2)，score 单位 nat
//! ```
//! Values are nats × 2, keys grouped by the id of their first character.

use std::collections::HashMap;
use std::fs::File;
use std::io::{self, Write};
use std::path::Path;
use std::sync::Arc;

use crate::blob::{Blob, Source};

pub const MAGIC: &[u8; 4] = b"WVGM";
pub const VERSION: u32 = 1;
const HEADER: usize = 32;
pub const MIN_LEN: usize = 2;
pub const MAX_LEN: usize = 4;

/// 构建器：收集 (字符串, 分值) 后序列化。 Builder: collect (key, score) then serialize.
#[derive(Default)]
pub struct GramBuilder {
    entries: Vec<(Vec<char>, f32)>,
}

impl GramBuilder {
    pub fn push(&mut self, key: &str, score: f32) {
        let k: Vec<char> = key.chars().collect();
        if (MIN_LEN..=MAX_LEN).contains(&k.len()) {
            self.entries.push((k, score));
        }
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    pub fn build(self) -> Vec<u8> {
        let mut chars: Vec<char> = self
            .entries
            .iter()
            .flat_map(|(k, _)| k.iter().copied())
            .collect();
        chars.sort_unstable();
        chars.dedup();
        assert!(chars.len() < u16::MAX as usize, "too many distinct chars");
        let id: HashMap<char, u16> = chars
            .iter()
            .enumerate()
            .map(|(i, &c)| (c, i as u16))
            .collect();
        let mut tables: Vec<Vec<(Vec<u16>, u8)>> = vec![Vec::new(); MAX_LEN + 1];
        for (k, s) in &self.entries {
            let ids: Vec<u16> = k.iter().map(|c| id[c]).collect();
            let v = (s * 2.0).round().clamp(0.0, 255.0) as u8;
            tables[k.len()].push((ids, v));
        }
        for t in tables.iter_mut() {
            t.sort_unstable_by(|a, b| a.0.cmp(&b.0));
            t.dedup_by(|a, b| a.0 == b.0);
        }
        let mut out = Vec::new();
        out.extend_from_slice(MAGIC);
        out.extend_from_slice(&VERSION.to_le_bytes());
        out.extend_from_slice(&(chars.len() as u32).to_le_bytes());
        for t in &tables[MIN_LEN..=MAX_LEN] {
            out.extend_from_slice(&(t.len() as u32).to_le_bytes());
        }
        out.resize(HEADER, 0);
        for c in &chars {
            out.extend_from_slice(&(*c as u32).to_le_bytes());
        }
        for t in &tables[MIN_LEN..=MAX_LEN] {
            let mut groups = vec![0u32; chars.len() + 1];
            for (k, _) in t {
                groups[k[0] as usize + 1] += 1;
            }
            for i in 1..groups.len() {
                groups[i] += groups[i - 1];
            }
            for g in &groups {
                out.extend_from_slice(&g.to_le_bytes());
            }
            for (k, _) in t {
                for s in &k[1..] {
                    out.extend_from_slice(&s.to_le_bytes());
                }
            }
            out.extend(t.iter().map(|(_, v)| *v));
        }
        out
    }

    pub fn write_to(self, path: &Path) -> io::Result<()> {
        let bytes = self.build();
        let tmp = path.with_extension("tmp");
        File::create(&tmp)?.write_all(&bytes)?;
        std::fs::rename(tmp, path)
    }
}

/// 只读模型。 Read-only model.
#[derive(Clone)]
pub struct Gram {
    blob: Blob,
    /// BMP 字符 → id + 1 的直查表（0 表示不在表中）。 Direct BMP lookup: id + 1, 0 = unknown.
    bmp: Arc<Vec<u16>>,
    n_chars: usize,
    chars_off: usize,
    /// 每个长度的 (groups_off, keys_off, values_off, count)。
    tables: [(usize, usize, usize, usize); MAX_LEN + 1],
}

#[inline]
fn rd32(b: &[u8], o: usize) -> u32 {
    u32::from_le_bytes([b[o], b[o + 1], b[o + 2], b[o + 3]])
}

impl Gram {
    /// 打开文件（原始文件 mmap，分块压缩文件按需解压）。 Open a raw (mmapped) or packed file.
    pub fn open(path: &Path) -> io::Result<Self> {
        Self::open_source(&Source::file(path))
    }

    pub fn open_source(src: &Source) -> io::Result<Self> {
        Self::parse(Blob::open(src)?)
    }

    pub fn from_bytes(b: Vec<u8>) -> io::Result<Self> {
        Self::parse(Blob::from_bytes(b))
    }

    /// 底层数据（用于缓存统计）。 Underlying bytes, e.g. for cache statistics.
    pub fn blob(&self) -> &Blob {
        &self.blob
    }

    fn parse(blob: Blob) -> io::Result<Self> {
        let bad = |m: &str| io::Error::new(io::ErrorKind::InvalidData, m.to_string());
        if blob.len() < HEADER {
            return Err(bad("bad gram header"));
        }
        let h = blob.with(0, HEADER, |b| b.to_vec());
        if &h[0..4] != MAGIC || rd32(&h, 4) != VERSION {
            return Err(bad("bad gram header"));
        }
        let n_chars = rd32(&h, 8) as usize;
        let chars_off = HEADER;
        let mut off = chars_off + n_chars * 4;
        let mut tables = [(0, 0, 0, 0); MAX_LEN + 1];
        for (i, l) in (MIN_LEN..=MAX_LEN).enumerate() {
            let count = rd32(&h, 12 + i * 4) as usize;
            let groups = off;
            let keys = groups + (n_chars + 1) * 4;
            let values = keys + count * (l - 1) * 2;
            off = values + count;
            tables[l] = (groups, keys, values, count);
        }
        if off > blob.len() {
            return Err(bad("truncated gram"));
        }
        let mut bmp = vec![0u16; 0x10000];
        blob.with(chars_off, n_chars * 4, |b| {
            for i in 0..n_chars {
                let c = rd32(b, i * 4);
                if c < 0x10000 && i < u16::MAX as usize {
                    bmp[c as usize] = i as u16 + 1;
                }
            }
        });
        Ok(Gram {
            blob,
            bmp: Arc::new(bmp),
            n_chars,
            chars_off,
            tables,
        })
    }

    /// 字符 → id。 Character to id.
    pub fn char_id(&self, c: char) -> Option<u16> {
        if (c as u32) < 0x10000 {
            let v = self.bmp[c as usize];
            return (v != 0).then(|| v - 1);
        }
        let (mut lo, mut hi) = (0usize, self.n_chars);
        let target = c as u32;
        while lo < hi {
            let mid = (lo + hi) / 2;
            let v = self.blob.u32(self.chars_off + mid * 4);
            if v == target {
                return Some(mid as u16);
            } else if v < target {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        None
    }

    /// 查一个完整键（字符 id 序列，长度 2..=4），返回分值（nat）。 Look up a key of char ids.
    pub fn get(&self, key: &[u16]) -> Option<f32> {
        let l = key.len();
        if !(MIN_LEN..=MAX_LEN).contains(&l) {
            return None;
        }
        let (groups, keys, values, _) = self.tables[l];
        let first = key[0] as usize;
        let (mut lo, mut hi) = self
            .blob
            .with(groups + first * 4, 8, |b| (rd32(b, 0) as usize, rd32(b, 4) as usize));
        let rest = &key[1..];
        let stride = (l - 1) * 2;
        while lo < hi {
            let mid = (lo + hi) / 2;
            let ord = self.blob.with(keys + mid * stride, stride, |b| {
                let mut ord = std::cmp::Ordering::Equal;
                for (j, &r) in rest.iter().enumerate() {
                    ord = u16::from_le_bytes([b[j * 2], b[j * 2 + 1]]).cmp(&r);
                    if ord != std::cmp::Ordering::Equal {
                        break;
                    }
                }
                ord
            });
            match ord {
                std::cmp::Ordering::Equal => return Some(self.blob.u8(values + mid) as f32 / 2.0),
                std::cmp::Ordering::Less => lo = mid + 1,
                std::cmp::Ordering::Greater => hi = mid,
            }
        }
        None
    }

    pub fn count(&self, len: usize) -> usize {
        self.tables.get(len).map(|t| t.3).unwrap_or(0)
    }

    /// 搭配分：上文 `context` 与下一个词 `word` 之间的最佳搭配（nat，未命中为 None）。
    /// 与 octagram 同口径：长度 ≥ 3 或恰为整个查询的搭配为强搭配（−12），否则为弱搭配（−24）；
    /// 调用方对未命中通常取 −12。
    /// Collocation score between `context` and `word` (nats; None when nothing matches). Strong
    /// matches (≥ 3 chars or the whole query) get −12, weak ones −24; callers usually use −12
    /// for a miss.
    pub fn collocation(&self, context: &str, word: &str) -> Option<f32> {
        let c: Vec<char> = context.chars().collect();
        let ctx = self.tail_ids(&c[c.len().saturating_sub(MAX_LEN - 1)..]);
        let w = self.head_ids(word);
        self.collocation_ids(&ctx, &w)
    }

    /// 末尾连续可识别字的 id（最多 3 个）。 Ids of the trailing run of known chars (≤ 3).
    pub fn tail_ids(&self, chars: &[char]) -> Vec<u16> {
        let mut ids = Vec::with_capacity(MAX_LEN - 1);
        for &ch in chars.iter().rev().take(MAX_LEN - 1) {
            match self.char_id(ch) {
                Some(i) => ids.push(i),
                None => break,
            }
        }
        ids.reverse();
        ids
    }

    /// 开头连续可识别字的 id（最多 3 个）。 Ids of the leading run of known chars (≤ 3).
    pub fn head_ids(&self, word: &str) -> Vec<u16> {
        word.chars()
            .take(MAX_LEN - 1)
            .map_while(|ch| self.char_id(ch))
            .collect()
    }

    /// 同 [`Gram::collocation`]，参数为预先查好的字 id。 Same, over precomputed char ids.
    pub fn collocation_ids(&self, ctx: &[u16], w: &[u16]) -> Option<f32> {
        const STRONG: f32 = -12.0;
        const WEAK: f32 = -24.0;
        if ctx.is_empty() || w.is_empty() {
            return None;
        }
        let mut best: Option<f32> = None;
        let mut key = [0u16; MAX_LEN];
        for s in 0..ctx.len() {
            let suffix = &ctx[s..];
            for p in 1..=w.len() {
                let len = suffix.len() + p;
                if len > MAX_LEN {
                    break;
                }
                key[..suffix.len()].copy_from_slice(suffix);
                key[suffix.len()..len].copy_from_slice(&w[..p]);
                if let Some(v) = self.get(&key[..len]) {
                    let whole = s == 0 && p == w.len();
                    let score = v + if len >= 3 || whole { STRONG } else { WEAK };
                    best = Some(best.map_or(score, |b: f32| b.max(score)));
                }
            }
        }
        best
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn build_and_query() {
        let mut b = GramBuilder::default();
        b.push("天气", 10.0);
        b.push("今天天", 13.0);
        b.push("天天气", 13.5);
        b.push("我们一起", 16.5);
        let g = Gram::from_bytes(b.build()).unwrap();
        assert_eq!(g.count(2), 1);
        assert_eq!(g.count(3), 2);
        let id = |c| g.char_id(c).unwrap();
        assert_eq!(g.get(&[id('天'), id('气')]), Some(10.0));
        assert_eq!(g.collocation("今天", "天气"), Some(1.5));
        assert_eq!(g.collocation("我们", "一起"), Some(4.5));
        assert_eq!(g.collocation("你", "好"), None);
        // 两字弱搭配（非整个查询）。 Weak 2-char collocation.
        assert_eq!(g.collocation("今天", "气温"), Some(-14.0));
    }
}
