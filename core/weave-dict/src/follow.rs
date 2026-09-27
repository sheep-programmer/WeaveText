//! 接续表：联想的主要来源。 The follow table, the main source of next-word prediction.

use std::io;

use crate::blob::{Blob, Source};

const HEADER: usize = 16;

/// 接续表：词库里 3–6 字的长词，按「前 1–4 字」查「剩下的部分」（如 今天 → 天气、晚上）。联想的主来源。
/// Follow table: 3–6 char phrases of the lexicon, looked up by their first 1–4 chars to get the rest
/// (今天 → 天气, 晚上). The main source of predictions.
///
/// 布局 / Layout: `"WVFL" | version u32 = 1 | n u32 | pad`，`Index [offset u32; n + 1]`，
/// 记录 / record: `key_len u8 | key | count u8 | (len u8 | text | cost u16) × count`，按 key 字节序升序。
#[derive(Default)]
pub struct FollowBuilder {
    rows: Vec<(String, String, u16)>,
}

impl FollowBuilder {
    /// 把一个长词拆成 (前缀, 剩余) 并记录。 Record every (prefix, rest) split of a phrase.
    pub fn push_phrase(&mut self, phrase: &str, cost: u16, min_key: usize, max_key: usize) {
        let chars: Vec<char> = phrase.chars().collect();
        for k in min_key.max(1)..chars.len().min(max_key + 1) {
            let key: String = chars[..k].iter().collect();
            let rest: String = chars[k..].iter().collect();
            if !rest.is_empty() && rest.chars().count() <= 4 {
                self.rows.push((key, rest, cost));
            }
        }
    }

    pub fn build(mut self, per_key: usize) -> Vec<u8> {
        self.rows.sort_by(|a, b| a.0.as_bytes().cmp(b.0.as_bytes()).then(a.2.cmp(&b.2)).then(a.1.cmp(&b.1)));
        self.rows.dedup_by(|a, b| a.0 == b.0 && a.1 == b.1);
        let mut offsets: Vec<u32> = Vec::new();
        let mut data: Vec<u8> = Vec::new();
        let mut i = 0;
        while i < self.rows.len() {
            let key = self.rows[i].0.clone();
            let mut j = i;
            while j < self.rows.len() && self.rows[j].0 == key {
                j += 1;
            }
            let take = (j - i).min(per_key);
            offsets.push(data.len() as u32);
            data.push(key.len() as u8);
            data.extend_from_slice(key.as_bytes());
            data.push(take as u8);
            for (_, rest, cost) in &self.rows[i..i + take] {
                data.push(rest.len() as u8);
                data.extend_from_slice(rest.as_bytes());
                data.extend_from_slice(&cost.to_le_bytes());
            }
            i = j;
        }
        let n = offsets.len();
        let base = (HEADER + (n + 1) * 4) as u32;
        let mut out = Vec::with_capacity(base as usize + data.len());
        out.extend_from_slice(b"WVFL");
        out.extend_from_slice(&1u32.to_le_bytes());
        out.extend_from_slice(&(n as u32).to_le_bytes());
        out.extend_from_slice(&0u32.to_le_bytes());
        for o in &offsets {
            out.extend_from_slice(&(base + o).to_le_bytes());
        }
        out.extend_from_slice(&(base + data.len() as u32).to_le_bytes());
        out.extend_from_slice(&data);
        out
    }
}

/// 只读接续表。 Read-only follow table.
pub struct Follow {
    blob: Blob,
    n: usize,
}

impl Follow {
    pub fn open_source(src: &Source) -> io::Result<Follow> {
        Self::parse(Blob::open(src)?)
    }

    pub fn from_bytes(b: Vec<u8>) -> io::Result<Follow> {
        Self::parse(Blob::from_bytes(b))
    }

    fn parse(blob: Blob) -> io::Result<Follow> {
        let bad = || io::Error::new(io::ErrorKind::InvalidData, "bad follow table");
        if blob.len() < HEADER || !blob.with(0, 4, |h| h == b"WVFL") || blob.u32(4) != 1 {
            return Err(bad());
        }
        let n = blob.u32(8) as usize;
        if blob.len() < HEADER + (n + 1) * 4 {
            return Err(bad());
        }
        Ok(Follow { blob, n })
    }

    fn key_at(&self, i: usize, buf: &mut Vec<u8>) -> usize {
        let off = self.blob.u32(HEADER + i * 4) as usize;
        let l = self.blob.u8(off) as usize;
        buf.clear();
        self.blob.with(off + 1, l, |b| buf.extend_from_slice(b));
        off + 1 + l
    }

    /// `key` 之后的接续（按 cost 升序）。 Continuations after `key`, cheapest first.
    pub fn after(&self, key: &str, out: &mut Vec<(String, u16)>) {
        out.clear();
        let target = key.as_bytes();
        let mut buf = Vec::with_capacity(16);
        let (mut lo, mut hi) = (0usize, self.n);
        while lo < hi {
            let mid = (lo + hi) / 2;
            self.key_at(mid, &mut buf);
            if buf.as_slice() < target {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        if lo >= self.n {
            return;
        }
        let at = self.key_at(lo, &mut buf);
        if buf.as_slice() != target {
            return;
        }
        let end = self.blob.u32(HEADER + (lo + 1) * 4) as usize;
        if end > self.blob.len() || at >= end {
            return;
        }
        self.blob.with(at, end - at, |b| {
            let count = b[0] as usize;
            let mut i = 1;
            for _ in 0..count {
                if i >= b.len() {
                    break;
                }
                let l = b[i] as usize;
                if i + 1 + l + 2 > b.len() {
                    break;
                }
                if let Ok(w) = std::str::from_utf8(&b[i + 1..i + 1 + l]) {
                    out.push((w.to_string(), u16::from_le_bytes([b[i + 1 + l], b[i + 2 + l]])));
                }
                i += 1 + l + 2;
            }
        });
    }
}

#[cfg(test)]
mod follow_tests {
    use super::*;

    #[test]
    fn follow_round_trip() {
        let mut b = FollowBuilder::default();
        b.push_phrase("今天天气", 9000, 1, 4);
        b.push_phrase("今天晚上", 8000, 1, 4);
        b.push_phrase("今晚", 7000, 1, 4);
        let f = Follow::from_bytes(b.build(8)).unwrap();
        let mut out = Vec::new();
        f.after("今天", &mut out);
        assert_eq!(out, vec![("晚上".into(), 8000), ("天气".into(), 9000)]);
        f.after("今", &mut out);
        assert_eq!(out.len(), 3);
        f.after("明天", &mut out);
        assert!(out.is_empty());
    }
}
