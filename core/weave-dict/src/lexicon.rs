//! 编译后的词库：按「符号序列」索引的前缀树，可直接 mmap，也可分块压缩后按需解压（见 [`crate::blob`]）。
//! Compiled lexicon: a trie over u16 symbol sequences; mmap friendly, or block-compressed and decoded
//! on demand (see [`crate::blob`]).
//!
//! 同一种格式同时服务于拼音（符号 = 音节 ID）、五笔与英文（符号 = 字母 1..=26）。
//! One format for pinyin (symbol = syllable id), wubi and English (symbol = letter 1..=26).
//!
//! 布局（全部小端，按列存放：同类数值挨在一起，压缩更好，查找也只碰需要的列）
//! Layout (little endian, columnar: like values sit together, which compresses better and lets each
//! lookup touch only the column it needs):
//! ```text
//! Header   96 bytes
//! Sym      [u16; node_count]       节点符号；BFS 顺序，同一父节点的子节点连续且按 sym 升序
//! Best     [u16; node_count]       子树中最小 cost
//! Kids     [u16; node_count]       子节点数
//! Count    [u16; node_count]       词条数
//! Checks   [(u32, u32); ⌈n/16⌉]    每 16 个节点一个检查点：(首个子节点下标, 首个词条下标)，其余由前缀和推出
//! Cost     [u16; entry_count]      每个节点的词条连续、按 cost 升序
//! Len      [u8; entry_count]       文本编码后的字节数
//! TextSamp [u32; ⌈entry_count/16⌉] 每 16 个词条一个文本起点，其余由长度前缀和推出
//! TextData 按词条顺序紧挨存放——同一节点的候选落在同一个压缩块里
//! CharTab  拼音词库：每个音节的常用字表 [u32; n_syms + 1] 偏移 + [u32] 字符（按出现次数降序）
//! ```
//!
//! 拼音词库的文本按**音节内序号**编码：第 i 个字记为它在第 i 个音节常用字表中的名次（<224 占 1 字节，
//! 否则 2 字节），几乎都是很小的数，压缩后只有 UTF-8 的三分之一；字数与音节数不符的词条以 0xFE 开头
//! 原样存 UTF-8。解码因此需要词条所在节点的音节序列。
//! Pinyin texts are coded as **per-syllable ranks**: the i-th character is stored as its rank in the
//! i-th syllable's character table (1 byte below 224, else 2) — nearly all tiny numbers, compressing to
//! a third of UTF-8. Entries whose length differs from the key start with 0xFE and keep raw UTF-8.
//! Decoding therefore needs the entry's key.
//!
//! cost = round(-ln(p) * COST_SCALE)，越小越常用。 Smaller cost = more frequent.

use std::collections::HashMap;
use std::fs::File;
use std::io::{self, Write};
use std::path::Path;
use std::sync::Arc;

use crate::blob::{Blob, Source};

pub const MAGIC: &[u8; 4] = b"WVLX";
pub const VERSION: u32 = 4;
pub const HEADER_LEN: usize = 96;
/// 单个词条文本编码后的最大字节数。 Longest encoded text in bytes.
pub const MAX_TEXT: usize = u8::MAX as usize;
/// 文本起点采样间隔（词条数）。 Entries per text-offset sample.
pub const TEXT_SAMPLE: usize = 16;
/// 检查点间隔（节点数）。 Nodes per checkpoint.
pub const CHECK_EVERY: usize = 16;
/// cost 的量化倍数。 Quantisation scale for costs.
pub const COST_SCALE: f64 = 1000.0;
pub const MAX_COST: u16 = u16::MAX;

/// 文本编码。 Text codec.
const CODEC_UTF8: u32 = 0;
const CODEC_RANK: u32 = 1;
/// 名次编码：< RANK_ONE 用 1 字节，否则 2 字节（高位 RANK_ONE..=0xFD）。 Rank coding boundaries.
const RANK_ONE: u32 = 224;
const RAW_MARK: u8 = 0xFE;
const MAX_RANK: u32 = (RAW_MARK as u32 - RANK_ONE) << 8;

/// 词库类型，决定符号的含义。 Lexicon kind: what symbols mean.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u32)]
pub enum Kind {
    Pinyin = 1,
    Letters = 2,
}

/// 字母 → 符号（a=1 … z=26）。 Letter → symbol.
pub fn letter_sym(c: u8) -> Option<u16> {
    match c {
        b'a'..=b'z' => Some((c - b'a' + 1) as u16),
        _ => None,
    }
}

/// 符号 → 字母。 Symbol → letter.
pub fn sym_letter(s: u16) -> char {
    (b'a' + (s as u8) - 1) as char
}

pub fn prob_to_cost(p: f64) -> u16 {
    let c = (-p.ln() * COST_SCALE).round();
    c.clamp(0.0, (MAX_COST - 1) as f64) as u16
}

pub fn cost_to_logp(c: u16) -> f64 {
    -(c as f64) / COST_SCALE
}

// ------------------------------------------------------------------ builder

struct BuildNode {
    sym: u16,
    parent: u32,
    children: Vec<u32>,
    entries: Vec<(u32, u16)>,
}

/// 词库构建器。 Lexicon builder.
pub struct Builder {
    kind: Kind,
    nodes: Vec<BuildNode>,
    texts: Vec<String>,
    text_ids: HashMap<String, u32>,
    child_map: HashMap<(u32, u16), u32>,
}

/// 按音节的常用字表（构建期）。 Per-syllable character tables, build side.
struct RankTable {
    lists: Vec<Vec<char>>,
    rank: HashMap<(u16, char), u32>,
}

impl RankTable {
    fn encode(&self, key: &[u16], text: &str, out: &mut Vec<u8>) {
        let start = out.len();
        let n = text.chars().count();
        if n == key.len() {
            for (&s, c) in key.iter().zip(text.chars()) {
                match self.rank.get(&(s, c)) {
                    Some(&r) if r < RANK_ONE => out.push(r as u8),
                    Some(&r) if r < MAX_RANK => {
                        out.push((RANK_ONE + (r >> 8)) as u8);
                        out.push(r as u8);
                    }
                    _ => {
                        out.truncate(start);
                        break;
                    }
                }
            }
            if out.len() > start && out.len() - start <= MAX_TEXT {
                return;
            }
            out.truncate(start);
        }
        out.push(RAW_MARK);
        out.extend_from_slice(text.as_bytes());
    }
}

impl Builder {
    pub fn new(kind: Kind) -> Self {
        Builder {
            kind,
            nodes: vec![BuildNode {
                sym: 0,
                parent: 0,
                children: Vec::new(),
                entries: Vec::new(),
            }],
            texts: Vec::new(),
            text_ids: Default::default(),
            child_map: Default::default(),
        }
    }

    pub fn text_id(&mut self, text: &str) -> u32 {
        if let Some(&id) = self.text_ids.get(text) {
            return id;
        }
        let id = self.texts.len() as u32;
        self.texts.push(text.to_owned());
        self.text_ids.insert(text.to_owned(), id);
        id
    }

    /// 插入一个词条；同 key 同 text 重复插入时保留更小的 cost。过长的文本被忽略，返回 false。
    /// Insert an entry; duplicates of (key, text) keep the smaller cost. Over-long texts are ignored
    /// and return false.
    pub fn insert(&mut self, key: &[u16], text: &str, cost: u16) -> bool {
        assert!(!key.is_empty(), "empty key");
        if text.is_empty() || text.len() + 1 > MAX_TEXT {
            return false;
        }
        let tid = self.text_id(text);
        let mut cur = 0u32;
        for &sym in key {
            cur = match self.child_map.get(&(cur, sym)) {
                Some(&c) => c,
                None => {
                    let id = self.nodes.len() as u32;
                    self.nodes.push(BuildNode {
                        sym,
                        parent: cur,
                        children: Vec::new(),
                        entries: Vec::new(),
                    });
                    self.nodes[cur as usize].children.push(id);
                    self.child_map.insert((cur, sym), id);
                    id
                }
            };
        }
        let entries = &mut self.nodes[cur as usize].entries;
        if let Some(e) = entries.iter_mut().find(|e| e.0 == tid) {
            e.1 = e.1.min(cost);
        } else {
            entries.push((tid, cost));
        }
        true
    }

    pub fn node_count(&self) -> usize {
        self.nodes.len()
    }

    fn key_of(&self, mut n: u32, buf: &mut Vec<u16>) {
        buf.clear();
        while n != 0 {
            buf.push(self.nodes[n as usize].sym);
            n = self.nodes[n as usize].parent;
        }
        buf.reverse();
    }

    /// 统计每个音节位置上出现的字，按次数排名。 Rank characters per syllable by occurrence count.
    fn rank_table(&self) -> RankTable {
        let mut counts: HashMap<(u16, char), u32> = HashMap::new();
        let mut key = Vec::new();
        for (i, n) in self.nodes.iter().enumerate() {
            if n.entries.is_empty() {
                continue;
            }
            self.key_of(i as u32, &mut key);
            for &(tid, _) in &n.entries {
                let t = &self.texts[tid as usize];
                if t.chars().count() == key.len() {
                    for (&s, c) in key.iter().zip(t.chars()) {
                        *counts.entry((s, c)).or_default() += 1;
                    }
                }
            }
        }
        let n_syms = counts.keys().map(|k| k.0 as usize + 1).max().unwrap_or(0);
        let mut lists: Vec<Vec<(u32, char)>> = vec![Vec::new(); n_syms];
        for (&(s, c), &n) in &counts {
            lists[s as usize].push((n, c));
        }
        let mut rank = HashMap::with_capacity(counts.len());
        let lists = lists
            .into_iter()
            .enumerate()
            .map(|(s, mut l)| {
                l.sort_unstable_by(|a, b| b.0.cmp(&a.0).then(a.1.cmp(&b.1)));
                for (r, &(_, c)) in l.iter().enumerate() {
                    rank.insert((s as u16, c), r as u32);
                }
                l.into_iter().map(|(_, c)| c).collect()
            })
            .collect();
        RankTable { lists, rank }
    }

    /// 序列化为字节。 Serialize to bytes.
    pub fn build(mut self) -> Vec<u8> {
        // 子节点按 sym 排序、词条按 cost 排序。 Sort children and entries.
        let syms: Vec<u16> = self.nodes.iter().map(|n| n.sym).collect();
        for n in &mut self.nodes {
            n.children.sort_by_key(|&c| syms[c as usize]);
            n.entries.sort_by_key(|e| (e.1, e.0));
        }
        // 子树最小 cost（后序）。 Subtree best cost, computed children-first.
        let mut best = vec![MAX_COST; self.nodes.len()];
        let mut order = Vec::with_capacity(self.nodes.len());
        let mut queue = std::collections::VecDeque::from([0u32]);
        while let Some(n) = queue.pop_front() {
            order.push(n);
            queue.extend(self.nodes[n as usize].children.iter().copied());
        }
        for &n in order.iter().rev() {
            let node = &self.nodes[n as usize];
            let mut b = node.entries.first().map(|e| e.1).unwrap_or(MAX_COST);
            for &c in &node.children {
                b = b.min(best[c as usize]);
            }
            best[n as usize] = b;
        }

        // 文本编码（按 BFS 词条顺序）。 Encode texts in BFS entry order.
        let ranks = (self.kind == Kind::Pinyin).then(|| self.rank_table());
        let mut tdata = Vec::new();
        let mut lens = Vec::new();
        let mut key = Vec::new();
        for &n in &order {
            let node = &self.nodes[n as usize];
            if node.entries.is_empty() {
                continue;
            }
            if ranks.is_some() {
                self.key_of(n, &mut key);
            }
            for &(tid, _) in &node.entries {
                let t = &self.texts[tid as usize];
                let before = tdata.len();
                match &ranks {
                    Some(r) => r.encode(&key, t, &mut tdata),
                    None => tdata.extend_from_slice(t.as_bytes()),
                }
                lens.push((tdata.len() - before) as u8);
            }
        }

        let node_count = order.len();
        let entry_count = lens.len();
        let checks = node_count.div_ceil(CHECK_EVERY);
        let n_syms = ranks.as_ref().map_or(0, |r| r.lists.len());
        let tab_chars: usize = ranks.as_ref().map_or(0, |r| r.lists.iter().map(Vec::len).sum());
        let sym_off = HEADER_LEN;
        let best_off = sym_off + node_count * 2;
        let kids_off = best_off + node_count * 2;
        let count_off = kids_off + node_count * 2;
        let checks_off = count_off + node_count * 2;
        let cost_off = checks_off + checks * 8;
        let len_off = cost_off + entry_count * 2;
        let tsamp_off = len_off + entry_count;
        let tdata_off = tsamp_off + entry_count.div_ceil(TEXT_SAMPLE) * 4;
        let tab_off = tdata_off + tdata.len();
        let total = tab_off + if n_syms > 0 { (n_syms + 1 + tab_chars) * 4 } else { 0 };

        let mut out = Vec::with_capacity(total);
        out.extend_from_slice(MAGIC);
        for v in [
            VERSION,
            self.kind as u32,
            crate::syllable::table_fingerprint(),
            node_count as u32,
            entry_count as u32,
            tdata.len() as u32,
            n_syms as u32,
            sym_off as u32,
            best_off as u32,
            kids_off as u32,
            count_off as u32,
            checks_off as u32,
            cost_off as u32,
            len_off as u32,
            tsamp_off as u32,
            tdata_off as u32,
            tab_off as u32,
            if ranks.is_some() { CODEC_RANK } else { CODEC_UTF8 },
        ] {
            out.extend_from_slice(&v.to_le_bytes());
        }
        out.resize(HEADER_LEN, 0);
        let column = |out: &mut Vec<u8>, f: &dyn Fn(u32) -> u16| {
            for &n in &order {
                out.extend_from_slice(&f(n).to_le_bytes());
            }
        };
        column(&mut out, &|n| self.nodes[n as usize].sym);
        column(&mut out, &|n| best[n as usize]);
        column(&mut out, &|n| {
            let c = self.nodes[n as usize].children.len();
            assert!(c <= u16::MAX as usize);
            c as u16
        });
        column(&mut out, &|n| {
            let c = self.nodes[n as usize].entries.len();
            assert!(c <= u16::MAX as usize);
            c as u16
        });
        // BFS 序下：节点 i 的首个子节点 = 1 + 前面所有节点的子节点数；首个词条同理。
        // In BFS order the first child of node i is 1 + Σ child counts before it; same for entries.
        let (mut child_cursor, mut entry_cursor) = (1u32, 0u32);
        for (i, &n) in order.iter().enumerate() {
            let node = &self.nodes[n as usize];
            if i % CHECK_EVERY == 0 {
                out.extend_from_slice(&child_cursor.to_le_bytes());
                out.extend_from_slice(&entry_cursor.to_le_bytes());
            }
            child_cursor += node.children.len() as u32;
            entry_cursor += node.entries.len() as u32;
        }
        for &n in &order {
            for &(_, cost) in &self.nodes[n as usize].entries {
                out.extend_from_slice(&cost.to_le_bytes());
            }
        }
        out.extend_from_slice(&lens);
        let mut off = 0u32;
        for (i, &l) in lens.iter().enumerate() {
            if i % TEXT_SAMPLE == 0 {
                out.extend_from_slice(&off.to_le_bytes());
            }
            off += l as u32;
        }
        out.extend_from_slice(&tdata);
        if let Some(r) = &ranks {
            let mut o = 0u32;
            for l in &r.lists {
                out.extend_from_slice(&o.to_le_bytes());
                o += l.len() as u32;
            }
            out.extend_from_slice(&o.to_le_bytes());
            for l in &r.lists {
                for &c in l {
                    out.extend_from_slice(&(c as u32).to_le_bytes());
                }
            }
        }
        debug_assert_eq!(out.len(), total);
        out
    }

    pub fn write_to(self, path: &Path) -> io::Result<()> {
        let bytes = self.build();
        let tmp = path.with_extension("tmp");
        File::create(&tmp)?.write_all(&bytes)?;
        std::fs::rename(tmp, path)
    }
}

// ------------------------------------------------------------------ reader

#[derive(Debug)]
pub enum LoadError {
    Io(io::Error),
    Format(&'static str),
}

impl std::fmt::Display for LoadError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            LoadError::Io(e) => write!(f, "io: {e}"),
            LoadError::Format(m) => write!(f, "format: {m}"),
        }
    }
}

impl std::error::Error for LoadError {}

impl From<io::Error> for LoadError {
    fn from(e: io::Error) -> Self {
        LoadError::Io(e)
    }
}

/// 只读词库。克隆很便宜（共享底层字节）。 Read-only lexicon; clones share bytes.
#[derive(Clone)]
pub struct Lexicon {
    blob: Blob,
    kind: Kind,
    node_count: u32,
    entry_count: u32,
    sym_off: usize,
    best_off: usize,
    kids_off: usize,
    count_off: usize,
    checks_off: usize,
    cost_off: usize,
    len_off: usize,
    tsamp_off: usize,
    tdata_off: usize,
    /// 音节常用字表：(每个音节的起点, 字符)；UTF-8 词库为空。 Per-syllable tables; empty for UTF-8.
    chartab: Arc<(Vec<u32>, Vec<char>)>,
}

/// 节点句柄（节点下标）。 Node handle (index).
pub type NodeId = u32;
pub const ROOT: NodeId = 0;

/// 词条；`text_id` 即词条下标，用 [`Lexicon::text`] 取文本。
/// An entry; `text_id` is the entry index, resolved with [`Lexicon::text`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Entry {
    pub text_id: u32,
    pub cost: u16,
}

#[inline]
fn rd16(b: &[u8], o: usize) -> u16 {
    u16::from_le_bytes([b[o], b[o + 1]])
}

#[inline]
fn rd32(b: &[u8], o: usize) -> u32 {
    u32::from_le_bytes([b[o], b[o + 1], b[o + 2], b[o + 3]])
}

impl Lexicon {
    pub fn from_bytes(bytes: Vec<u8>) -> Result<Self, LoadError> {
        Self::parse(Blob::from_bytes(bytes))
    }

    /// 打开文件（原始文件 mmap，分块压缩文件按需解压）。 Open a raw (mmapped) or packed file.
    pub fn open(path: &Path) -> Result<Self, LoadError> {
        Self::open_source(&Source::file(path))
    }

    pub fn open_source(src: &Source) -> Result<Self, LoadError> {
        Self::parse(Blob::open(src)?)
    }

    fn parse(blob: Blob) -> Result<Self, LoadError> {
        if blob.len() < HEADER_LEN {
            return Err(LoadError::Format("bad magic"));
        }
        let h = blob.with(0, HEADER_LEN, |b| b.to_vec());
        if &h[0..4] != MAGIC {
            return Err(LoadError::Format("bad magic"));
        }
        if rd32(&h, 4) != VERSION {
            return Err(LoadError::Format("unsupported version"));
        }
        let kind = match rd32(&h, 8) {
            1 => Kind::Pinyin,
            2 => Kind::Letters,
            _ => return Err(LoadError::Format("unknown kind")),
        };
        if kind == Kind::Pinyin && rd32(&h, 12) != crate::syllable::table_fingerprint() {
            return Err(LoadError::Format("syllable table mismatch"));
        }
        let at = |o: usize| rd32(&h, o) as usize;
        let (nodes, entries, text_bytes, n_syms) = (at(16), at(20), at(24), at(28));
        let offs = [at(32), at(36), at(40), at(44), at(48), at(52), at(56), at(60), at(64), at(68)];
        let [sym_off, best_off, kids_off, count_off, checks_off, cost_off, len_off, tsamp_off, tdata_off, tab_off] = offs;
        let codec = rd32(&h, 72);
        let tab_end = if codec == CODEC_RANK { tab_off + (n_syms + 1) * 4 } else { tab_off };
        let ok = nodes > 0
            && sym_off >= HEADER_LEN
            && sym_off + nodes * 2 <= best_off
            && best_off + nodes * 2 <= kids_off
            && kids_off + nodes * 2 <= count_off
            && count_off + nodes * 2 <= checks_off
            && checks_off + nodes.div_ceil(CHECK_EVERY) * 8 <= cost_off
            && cost_off + entries * 2 <= len_off
            && len_off + entries <= tsamp_off
            && tsamp_off + entries.div_ceil(TEXT_SAMPLE) * 4 <= tdata_off
            && tdata_off + text_bytes <= tab_off
            && tab_end <= blob.len()
            && (codec == CODEC_UTF8 || codec == CODEC_RANK);
        if !ok {
            return Err(LoadError::Format("truncated"));
        }
        let chartab = if codec == CODEC_RANK {
            let starts: Vec<u32> = blob.with(tab_off, (n_syms + 1) * 4, |b| {
                b.chunks_exact(4).map(|c| rd32(c, 0)).collect()
            });
            let n_chars = *starts.last().unwrap() as usize;
            if starts.windows(2).any(|w| w[0] > w[1]) || tab_end + n_chars * 4 > blob.len() {
                return Err(LoadError::Format("truncated"));
            }
            let chars = blob.with(tab_end, n_chars * 4, |b| {
                b.chunks_exact(4)
                    .map(|c| char::from_u32(rd32(c, 0)).unwrap_or('\u{FFFD}'))
                    .collect()
            });
            (starts, chars)
        } else {
            (Vec::new(), Vec::new())
        };
        Ok(Lexicon {
            blob,
            kind,
            node_count: nodes as u32,
            entry_count: entries as u32,
            sym_off,
            best_off,
            kids_off,
            count_off,
            checks_off,
            cost_off,
            len_off,
            tsamp_off,
            tdata_off,
            chartab: Arc::new(chartab),
        })
    }

    pub fn kind(&self) -> Kind {
        self.kind
    }

    pub fn node_count(&self) -> u32 {
        self.node_count
    }

    pub fn entry_count(&self) -> u32 {
        self.entry_count
    }

    /// 底层数据（用于缓存统计）。 Underlying bytes, e.g. for cache statistics.
    pub fn blob(&self) -> &Blob {
        &self.blob
    }

    /// 节点自身的符号。 Symbol on the edge into this node.
    #[inline]
    pub fn sym(&self, n: NodeId) -> u16 {
        self.blob.u16(self.sym_off + n as usize * 2)
    }

    /// 子树中最小 cost。 Smallest cost in the subtree.
    #[inline]
    pub fn best(&self, n: NodeId) -> u16 {
        self.blob.u16(self.best_off + n as usize * 2)
    }

    /// (首个子节点, 首个词条)：检查点加上组内前缀和。 (first child, first entry) via checkpoint + prefix sum.
    #[inline]
    fn starts(&self, n: NodeId) -> (u32, u32) {
        let k = n as usize / CHECK_EVERY;
        let (mut child, mut entry) =
            self.blob.with(self.checks_off + k * 8, 8, |b| (rd32(b, 0), rd32(b, 4)));
        let j = n as usize % CHECK_EVERY;
        if j > 0 {
            let first = k * CHECK_EVERY * 2;
            let sum = |b: &[u8]| (0..j).map(|i| rd16(b, i * 2) as u32).sum::<u32>();
            child += self.blob.with(self.kids_off + first, j * 2, sum);
            entry += self.blob.with(self.count_off + first, j * 2, sum);
        }
        (child, entry)
    }

    /// 子节点下标范围。 Range of child node ids.
    #[inline]
    pub fn children(&self, n: NodeId) -> std::ops::Range<NodeId> {
        let count = self.blob.u16(self.kids_off + n as usize * 2) as u32;
        if count == 0 {
            return 0..0;
        }
        let first = self.starts(n).0;
        first..first + count
    }

    /// 二分查找子节点。 Binary search a child by symbol.
    pub fn child(&self, n: NodeId, sym: u16) -> Option<NodeId> {
        let r = self.children(n);
        if r.is_empty() {
            return None;
        }
        let len = (r.end - r.start) as usize;
        self.blob.with(self.sym_off + r.start as usize * 2, len * 2, |b| {
            let (mut lo, mut hi) = (0usize, len);
            while lo < hi {
                let mid = lo + (hi - lo) / 2;
                let s = rd16(b, mid * 2);
                if s == sym {
                    return Some(r.start + mid as u32);
                } else if s < sym {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            None
        })
    }

    /// 沿 key 走到节点。 Walk a full key.
    pub fn find(&self, key: &[u16]) -> Option<NodeId> {
        key.iter().try_fold(ROOT, |n, &s| self.child(n, s))
    }

    #[inline]
    pub fn entry_count_of(&self, n: NodeId) -> u32 {
        self.blob.u16(self.count_off + n as usize * 2) as u32
    }

    /// 节点上的第 i 个词条（按 cost 升序）。 i-th entry of a node, ascending cost.
    #[inline]
    pub fn entry(&self, n: NodeId, i: u32) -> Entry {
        self.entry_at(self.starts(n).1 + i)
    }

    #[inline]
    fn entry_at(&self, idx: u32) -> Entry {
        Entry {
            text_id: idx,
            cost: self.blob.u16(self.cost_off + idx as usize * 2),
        }
    }

    pub fn entries(&self, n: NodeId) -> impl Iterator<Item = Entry> + '_ {
        let count = self.entry_count_of(n);
        let start = if count == 0 { 0 } else { self.starts(n).1 };
        (start..start + count).map(move |i| self.entry_at(i))
    }

    /// 在节点 `n` 的词条里找文本 `text`，不解码其他词条（按存储编码直接比较字节）。
    /// Find `text` among node `n`'s entries by comparing encoded bytes, without decoding the others.
    pub fn find_entry(&self, n: NodeId, key: &[u16], text: &str) -> Option<Entry> {
        let count = self.entry_count_of(n) as usize;
        if count == 0 {
            return None;
        }
        let (starts, chars) = &*self.chartab;
        let mut coded = Vec::new();
        let mut raw = Vec::with_capacity(text.len() + 1);
        if starts.is_empty() {
            raw.extend_from_slice(text.as_bytes());
        } else {
            raw.push(RAW_MARK);
            raw.extend_from_slice(text.as_bytes());
            if text.chars().count() == key.len() {
                for (&s, c) in key.iter().zip(text.chars()) {
                    let (Some(&lo), Some(&hi)) = (starts.get(s as usize), starts.get(s as usize + 1)) else {
                        coded.clear();
                        break;
                    };
                    match chars[lo as usize..hi as usize].iter().position(|&x| x == c) {
                        Some(r) if (r as u32) < RANK_ONE => coded.push(r as u8),
                        Some(r) if (r as u32) < MAX_RANK => {
                            coded.push((RANK_ONE + (r as u32 >> 8)) as u8);
                            coded.push(r as u8);
                        }
                        _ => {
                            coded.clear();
                            break;
                        }
                    }
                }
            }
        }
        let first = self.starts(n).1 as usize;
        let k = first / TEXT_SAMPLE;
        let mut off = self.blob.u32(self.tsamp_off + k * 4) as usize;
        let head = first - k * TEXT_SAMPLE;
        let lens = self.blob.with(self.len_off + k * TEXT_SAMPLE, head + count, |b| b.to_vec());
        off += lens[..head].iter().map(|&l| l as usize).sum::<usize>();
        let lens = &lens[head..];
        let total: usize = lens.iter().map(|&l| l as usize).sum();
        let hit = self.blob.with(self.tdata_off + off, total, |b| {
            let mut pos = 0;
            for (i, &l) in lens.iter().enumerate() {
                let t = &b[pos..pos + l as usize];
                if t == raw.as_slice() || (!coded.is_empty() && t == coded.as_slice()) {
                    return Some(i);
                }
                pos += l as usize;
            }
            None
        })?;
        Some(self.entry_at((first + hit) as u32))
    }

    /// 词条文本。`key` 是词条所在节点的符号序列（拼音词库解码需要；字母词库忽略）。
    /// Text of an entry. `key` is the entry's node key (needed for pinyin; ignored for letters).
    pub fn text(&self, id: u32, key: &[u16]) -> String {
        if id >= self.entry_count {
            return String::new();
        }
        let id = id as usize;
        let k = id / TEXT_SAMPLE;
        let first = k * TEXT_SAMPLE;
        let mut off = self.blob.u32(self.tsamp_off + k * 4) as usize;
        let n = id - first + 1;
        let len = self.blob.with(self.len_off + first, n, |b| {
            off += b[..n - 1].iter().map(|&l| l as usize).sum::<usize>();
            b[n - 1] as usize
        });
        let (starts, chars) = &*self.chartab;
        self.blob.with(self.tdata_off + off, len, |b| {
            if starts.is_empty() || b.first() == Some(&RAW_MARK) {
                let raw = if starts.is_empty() { b } else { &b[1..] };
                // 构建时写入的都是合法 UTF-8；损坏文件退化为空串而非 UB。
                return std::str::from_utf8(raw).unwrap_or("").to_owned();
            }
            let mut out = String::with_capacity(key.len() * 3);
            let mut i = 0;
            for &s in key {
                let Some(&c0) = b.get(i) else { return String::new() };
                let r = if (c0 as u32) < RANK_ONE {
                    i += 1;
                    c0 as u32
                } else {
                    let Some(&c1) = b.get(i + 1) else { return String::new() };
                    i += 2;
                    ((c0 as u32 - RANK_ONE) << 8) | c1 as u32
                };
                let (Some(&lo), Some(&hi)) = (starts.get(s as usize), starts.get(s as usize + 1)) else {
                    return String::new();
                };
                match chars.get((lo + r) as usize) {
                    Some(&c) if lo + r < hi => out.push(c),
                    _ => return String::new(),
                }
            }
            if i == b.len() {
                out
            } else {
                String::new()
            }
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn build_and_read() {
        let mut b = Builder::new(Kind::Letters);
        let k = |s: &str| {
            s.bytes()
                .map(|c| letter_sym(c).unwrap())
                .collect::<Vec<_>>()
        };
        b.insert(&k("gg"), "一", 10);
        b.insert(&k("g"), "一", 5);
        b.insert(&k("gg"), "王", 3);
        b.insert(&k("ab"), "工", 7);
        b.insert(&k("gg"), "一", 8);
        let lex = Lexicon::from_bytes(b.build()).unwrap();
        let n = lex.find(&k("gg")).unwrap();
        let e: Vec<_> = lex
            .entries(n)
            .map(|e| (lex.text(e.text_id, &[]), e.cost))
            .collect();
        assert_eq!(e, vec![("王".to_string(), 3), ("一".to_string(), 8)]);
        assert_eq!(lex.best(ROOT), 3);
        assert_eq!(lex.best(lex.find(&k("a")).unwrap()), 7);
        assert!(lex.find(&k("gx")).is_none());
        let g = lex.find(&k("g")).unwrap();
        assert_eq!(lex.best(g), 3);
        assert_eq!(lex.entries(g).next().unwrap().cost, 5);
    }

    #[test]
    fn pinyin_rank_codec() {
        let mut b = Builder::new(Kind::Pinyin);
        b.insert(&[5, 9], "中国", 10);
        b.insert(&[5, 9], "种过", 20);
        b.insert(&[5], "中", 3);
        b.insert(&[5], "钟", 4);
        b.insert(&[9, 5], "卡拉OK", 30); // 字数与音节数不符：原样存放 / length mismatch: raw
        let lex = Lexicon::from_bytes(b.build()).unwrap();
        let texts = |key: &[u16]| {
            let n = lex.find(key).unwrap();
            lex.entries(n).map(|e| lex.text(e.text_id, key)).collect::<Vec<_>>()
        };
        assert_eq!(texts(&[5, 9]), vec!["中国", "种过"]);
        assert_eq!(texts(&[5]), vec!["中", "钟"]);
        assert_eq!(texts(&[9, 5]), vec!["卡拉OK"]);
        let zg = lex.find(&[5, 9]).unwrap();
        assert_eq!(lex.find_entry(zg, &[5, 9], "种过").map(|e| e.cost), Some(20));
        assert!(lex.find_entry(zg, &[5, 9], "中果").is_none());
        let m = lex.find(&[9, 5]).unwrap();
        assert_eq!(lex.find_entry(m, &[9, 5], "卡拉OK").map(|e| e.cost), Some(30));
        // 错误的 key 得到空串而不是乱码或崩溃。 A wrong key yields "" rather than garbage or a panic.
        let n = lex.find(&[5, 9]).unwrap();
        let e = lex.entries(n).next().unwrap();
        assert_eq!(lex.text(e.text_id, &[5]), "");
    }
}
