//! 编译后的词库：按「符号序列」索引的前缀树，可直接 mmap。
//! Compiled lexicon: a trie over u16 symbol sequences, mmap friendly.
//!
//! 同一种格式同时服务于拼音（符号 = 音节 ID）、五笔与英文（符号 = 字母 1..=26）。
//! One format for pinyin (symbol = syllable id), wubi and English (symbol = letter 1..=26).
//!
//! 布局（全部小端）/ Layout (little endian):
//! ```text
//! Header   64 bytes
//! Nodes    [Node; node_count]      8 字节：sym u16 | best u16 | child_count u16 | entry_count u16
//!                                  BFS 顺序，同一父节点的子节点连续且按 sym 升序
//! Checks   [(u32, u32); ⌈n/16⌉]    每 16 个节点一个检查点：(首个子节点下标, 首个词条下标)
//!                                  其余节点由检查点加前缀和推出，省去每个节点 8 字节
//! Entries  [Entry; entry_count]    6 字节：text_id u32 | cost u16；每个节点的词条连续、按 cost 升序
//! TextOffs [u32; text_count + 1]
//! TextData utf-8
//! ```
//! cost = round(-ln(p) * COST_SCALE)，越小越常用。 Smaller cost = more frequent.

use std::fs::File;
use std::io::{self, Write};
use std::path::Path;
use std::sync::Arc;

pub const MAGIC: &[u8; 4] = b"WVLX";
pub const VERSION: u32 = 3;
pub const HEADER_LEN: usize = 64;
pub const NODE_LEN: usize = 8;
pub const ENTRY_LEN: usize = 6;
/// 检查点间隔（节点数）。 Nodes per checkpoint.
pub const CHECK_EVERY: usize = 16;
/// cost 的量化倍数。 Quantisation scale for costs.
pub const COST_SCALE: f64 = 1000.0;
pub const MAX_COST: u16 = u16::MAX;

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
    children: Vec<u32>,
    entries: Vec<(u32, u16)>,
}

/// 词库构建器。 Lexicon builder.
pub struct Builder {
    kind: Kind,
    nodes: Vec<BuildNode>,
    texts: Vec<String>,
    text_ids: std::collections::HashMap<String, u32>,
    child_map: std::collections::HashMap<(u32, u16), u32>,
}

impl Builder {
    pub fn new(kind: Kind) -> Self {
        Builder {
            kind,
            nodes: vec![BuildNode {
                sym: 0,
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

    /// 插入一个词条；同 key 同 text 重复插入时保留更小的 cost。
    /// Insert an entry; duplicates of (key, text) keep the smaller cost.
    pub fn insert(&mut self, key: &[u16], text: &str, cost: u16) {
        assert!(!key.is_empty(), "empty key");
        let tid = self.text_id(text);
        let mut cur = 0u32;
        for &sym in key {
            cur = match self.child_map.get(&(cur, sym)) {
                Some(&c) => c,
                None => {
                    let id = self.nodes.len() as u32;
                    self.nodes.push(BuildNode {
                        sym,
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
    }

    pub fn node_count(&self) -> usize {
        self.nodes.len()
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
        // BFS 编号：order 本身就是 BFS 序，且同一父节点的子节点连续。
        // BFS order already keeps siblings contiguous.
        let mut new_index = vec![0u32; self.nodes.len()];
        for (i, &n) in order.iter().enumerate() {
            new_index[n as usize] = i as u32;
        }

        let node_count = order.len();
        let entry_count: usize = self.nodes.iter().map(|n| n.entries.len()).sum();
        let text_bytes: usize = self.texts.iter().map(|t| t.len()).sum();
        let nodes_off = HEADER_LEN;
        let checks_off = nodes_off + node_count * NODE_LEN;
        let checks = node_count.div_ceil(CHECK_EVERY);
        let entries_off = checks_off + checks * 8;
        let toffs_off = entries_off + entry_count * ENTRY_LEN;
        let tdata_off = toffs_off + (self.texts.len() + 1) * 4;
        let total = tdata_off + text_bytes;

        let mut out = Vec::with_capacity(total);
        out.extend_from_slice(MAGIC);
        for v in [
            VERSION,
            self.kind as u32,
            crate::syllable::table_fingerprint(),
            node_count as u32,
            entry_count as u32,
            self.texts.len() as u32,
            text_bytes as u32,
            nodes_off as u32,
            entries_off as u32,
            toffs_off as u32,
            tdata_off as u32,
            checks_off as u32,
        ] {
            out.extend_from_slice(&v.to_le_bytes());
        }
        out.resize(HEADER_LEN, 0);

        // BFS 序下：节点 i 的首个子节点 = 1 + 前面所有节点的子节点数；首个词条同理。
        // In BFS order the first child of node i is 1 + Σ child counts before it; same for entries.
        let mut checks_buf = Vec::with_capacity(checks * 8);
        let (mut child_cursor, mut entry_cursor) = (1u32, 0u32);
        for (i, &n) in order.iter().enumerate() {
            let node = &self.nodes[n as usize];
            if i % CHECK_EVERY == 0 {
                checks_buf.extend_from_slice(&child_cursor.to_le_bytes());
                checks_buf.extend_from_slice(&entry_cursor.to_le_bytes());
            }
            debug_assert!(node
                .children
                .first()
                .is_none_or(|&c| new_index[c as usize] == child_cursor));
            assert!(
                node.children.len() <= u16::MAX as usize && node.entries.len() <= u16::MAX as usize
            );
            out.extend_from_slice(&node.sym.to_le_bytes());
            out.extend_from_slice(&best[n as usize].to_le_bytes());
            out.extend_from_slice(&(node.children.len() as u16).to_le_bytes());
            out.extend_from_slice(&(node.entries.len() as u16).to_le_bytes());
            child_cursor += node.children.len() as u32;
            entry_cursor += node.entries.len() as u32;
        }
        out.extend_from_slice(&checks_buf);
        for &n in &order {
            for &(tid, cost) in &self.nodes[n as usize].entries {
                out.extend_from_slice(&tid.to_le_bytes());
                out.extend_from_slice(&cost.to_le_bytes());
            }
        }
        let mut off = 0u32;
        for t in &self.texts {
            out.extend_from_slice(&off.to_le_bytes());
            off += t.len() as u32;
        }
        out.extend_from_slice(&off.to_le_bytes());
        for t in &self.texts {
            out.extend_from_slice(t.as_bytes());
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

/// 词库字节的来源。 Where the lexicon bytes live.
#[derive(Clone)]
enum Backing {
    Owned(Arc<Vec<u8>>),
    Mapped(Arc<memmap2::Mmap>),
}

impl Backing {
    fn bytes(&self) -> &[u8] {
        match self {
            Backing::Owned(v) => v,
            Backing::Mapped(m) => m,
        }
    }
}

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
    backing: Backing,
    kind: Kind,
    node_count: u32,
    entry_count: u32,
    text_count: u32,
    nodes_off: usize,
    checks_off: usize,
    entries_off: usize,
    toffs_off: usize,
    tdata_off: usize,
}

/// 节点句柄（节点下标）。 Node handle (index).
pub type NodeId = u32;
pub const ROOT: NodeId = 0;

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
        Self::parse(Backing::Owned(Arc::new(bytes)))
    }

    /// mmap 打开文件。 Open with mmap.
    pub fn open(path: &Path) -> Result<Self, LoadError> {
        let file = File::open(path)?;
        // SAFETY: 词库文件由本程序生成、只读打开；外部篡改只会导致解析失败或错误结果，
        // 读取时所有下标都做了边界检查（切片索引会 panic 而不是越界读）。
        // The file is read-only; every access is bounds-checked by slice indexing.
        let map = unsafe { memmap2::Mmap::map(&file)? };
        Self::parse(Backing::Mapped(Arc::new(map)))
    }

    fn parse(backing: Backing) -> Result<Self, LoadError> {
        let b = backing.bytes();
        if b.len() < HEADER_LEN || &b[0..4] != MAGIC {
            return Err(LoadError::Format("bad magic"));
        }
        if rd32(b, 4) != VERSION {
            return Err(LoadError::Format("unsupported version"));
        }
        let kind = match rd32(b, 8) {
            1 => Kind::Pinyin,
            2 => Kind::Letters,
            _ => return Err(LoadError::Format("unknown kind")),
        };
        if kind == Kind::Pinyin && rd32(b, 12) != crate::syllable::table_fingerprint() {
            return Err(LoadError::Format("syllable table mismatch"));
        }
        let lex = Lexicon {
            kind,
            node_count: rd32(b, 16),
            entry_count: rd32(b, 20),
            text_count: rd32(b, 24),
            nodes_off: rd32(b, 32) as usize,
            entries_off: rd32(b, 36) as usize,
            toffs_off: rd32(b, 40) as usize,
            tdata_off: rd32(b, 44) as usize,
            checks_off: rd32(b, 48) as usize,
            backing: backing.clone(),
        };
        let text_bytes = rd32(b, 28) as usize;
        if lex.nodes_off + lex.node_count as usize * NODE_LEN > lex.checks_off
            || lex.checks_off + (lex.node_count as usize).div_ceil(CHECK_EVERY) * 8
                > lex.entries_off
            || lex.entries_off + lex.entry_count as usize * ENTRY_LEN > lex.toffs_off
            || lex.toffs_off + (lex.text_count as usize + 1) * 4 > lex.tdata_off
            || lex.tdata_off + text_bytes > b.len()
            || lex.node_count == 0
        {
            return Err(LoadError::Format("truncated"));
        }
        Ok(lex)
    }

    #[inline]
    fn b(&self) -> &[u8] {
        self.backing.bytes()
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

    pub fn text_count(&self) -> u32 {
        self.text_count
    }

    #[inline]
    fn node_off(&self, n: NodeId) -> usize {
        debug_assert!(n < self.node_count);
        self.nodes_off + n as usize * NODE_LEN
    }

    /// 节点自身的符号。 Symbol on the edge into this node.
    #[inline]
    pub fn sym(&self, n: NodeId) -> u16 {
        rd16(self.b(), self.node_off(n))
    }

    /// 子树中最小 cost。 Smallest cost in the subtree.
    #[inline]
    pub fn best(&self, n: NodeId) -> u16 {
        rd16(self.b(), self.node_off(n) + 2)
    }

    /// (首个子节点, 首个词条)：检查点加上组内前缀和。 (first child, first entry) via checkpoint + prefix sum.
    #[inline]
    fn starts(&self, n: NodeId) -> (u32, u32) {
        let b = self.b();
        let k = n as usize / CHECK_EVERY;
        let co = self.checks_off + k * 8;
        let (mut child, mut entry) = (rd32(b, co), rd32(b, co + 4));
        let base = self.nodes_off + k * CHECK_EVERY * NODE_LEN;
        for j in 0..(n as usize % CHECK_EVERY) {
            let o = base + j * NODE_LEN;
            child += rd16(b, o + 4) as u32;
            entry += rd16(b, o + 6) as u32;
        }
        (child, entry)
    }

    /// 子节点下标范围。 Range of child node ids.
    #[inline]
    pub fn children(&self, n: NodeId) -> std::ops::Range<NodeId> {
        let count = rd16(self.b(), self.node_off(n) + 4) as u32;
        if count == 0 {
            return 0..0;
        }
        let first = self.starts(n).0;
        first..first + count
    }

    /// 二分查找子节点。 Binary search a child by symbol.
    pub fn child(&self, n: NodeId, sym: u16) -> Option<NodeId> {
        let r = self.children(n);
        let (mut lo, mut hi) = (r.start, r.end);
        while lo < hi {
            let mid = lo + (hi - lo) / 2;
            let s = self.sym(mid);
            if s == sym {
                return Some(mid);
            } else if s < sym {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        None
    }

    /// 沿 key 走到节点。 Walk a full key.
    pub fn find(&self, key: &[u16]) -> Option<NodeId> {
        key.iter().try_fold(ROOT, |n, &s| self.child(n, s))
    }

    #[inline]
    pub fn entry_count_of(&self, n: NodeId) -> u32 {
        rd16(self.b(), self.node_off(n) + 6) as u32
    }

    /// 节点上的第 i 个词条（按 cost 升序）。 i-th entry of a node, ascending cost.
    #[inline]
    pub fn entry(&self, n: NodeId, i: u32) -> Entry {
        self.entry_at(self.starts(n).1 + i)
    }

    #[inline]
    fn entry_at(&self, idx: u32) -> Entry {
        let eo = self.entries_off + idx as usize * ENTRY_LEN;
        Entry {
            text_id: rd32(self.b(), eo),
            cost: rd16(self.b(), eo + 4),
        }
    }

    pub fn entries(&self, n: NodeId) -> impl Iterator<Item = Entry> + '_ {
        let count = self.entry_count_of(n);
        let start = if count == 0 { 0 } else { self.starts(n).1 };
        (start..start + count).map(move |i| self.entry_at(i))
    }

    /// 文本。 Text of a text id.
    pub fn text(&self, id: u32) -> &str {
        let b = self.b();
        let s = rd32(b, self.toffs_off + id as usize * 4) as usize;
        let e = rd32(b, self.toffs_off + id as usize * 4 + 4) as usize;
        // 构建时写入的都是合法 UTF-8；损坏文件退化为空串而非 UB。
        std::str::from_utf8(&b[self.tdata_off + s..self.tdata_off + e]).unwrap_or("")
    }

    /// 查 text 的 id（线性扫描，仅用于工具与测试）。 Linear lookup, tools/tests only.
    pub fn text_id_slow(&self, text: &str) -> Option<u32> {
        (0..self.text_count).find(|&i| self.text(i) == text)
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
            .map(|e| (lex.text(e.text_id).to_string(), e.cost))
            .collect();
        assert_eq!(e, vec![("王".to_string(), 3), ("一".to_string(), 8)]);
        assert_eq!(lex.best(ROOT), 3);
        assert_eq!(lex.best(lex.find(&k("a")).unwrap()), 7);
        assert!(lex.find(&k("gx")).is_none());
        let g = lex.find(&k("g")).unwrap();
        assert_eq!(lex.best(g), 3);
        assert_eq!(lex.entries(g).next().unwrap().cost, 5);
    }
}
