//! 拼音类解码器：音节图 × 词库前缀树 → 词图 → 整句 + 候选。
//! Pinyin-family decoder: syllable graph × lexicon trie → word lattice → sentence + candidates.
//!
//! 流程 / Pipeline:
//! 1. 从每个起点在音节图与（系统、用户）两棵前缀树上同步深搜，得到所有「词跨度」Span。
//!    DFS the graph and both tries (system + user) in lockstep from every start → spans.
//! 2. 在词图上做带束宽的动态规划（可叠加用户二元组奖励），得到最优整句。
//!    Beam DP over the lattice (user bigram bonus applied) → best sentence.
//! 3. 候选 = 整句 + 以 0 开头的词（覆盖越长越靠前，同长按 cost）。
//!    Candidates = sentence + words starting at 0, longer coverage first, then by cost.

use std::collections::HashMap;

use weave_dict::lexicon::{Lexicon, NodeId, ROOT};
use weave_dict::syllable::SyllableId;

use crate::graph::{EdgeKind, SyllableGraph};
use crate::userdict::{UNodeId, UserDict, UserEntry, UROOT};

/// 夹在拼音里的三个字母的英文词多半是打错的拼音碰巧拼出来的（ragdajia 的 rag），额外加价。
/// A three-letter English word inside pinyin is usually a mistyped syllable that happens to spell one (rag in ragdajia).
const SHORT_LATIN: u32 = 2500;
/// 一个词最多多少个音节。 Max syllables per word.
const MAX_WORD_SYLLABLES: usize = 10;
/// 单个起点的深搜预算（防简拼组合爆炸）。 DFS budget per start (guards abbreviation blow-up).
const DFS_BUDGET: usize = 40_000;
/// 一条路径上累计惩罚上限。 Max accumulated penalty along one word.
const MAX_WORD_PENALTY: u32 = 9_000;
/// 整句束宽。 Beam width.
const BEAM: usize = 6;
/// 完整拼写的同音词保留更多选择，供上下文评分；模糊、简拼等扩展分支仍用小池限制成本。
/// Keep more homophones for contextual scoring on exact spans; speculative branches stay small.
const SPAN_TOP: usize = 8;
const SPECULATIVE_TOP: usize = 4;
/// 每个词额外代价，偏好更少更长的词。 Per-word cost, prefers fewer longer words.
const WORD_COST: u32 = 600;
/// 候选列表单个 span 最多展开多少词。 Max entries expanded from one span for listing.
const SPAN_LIST_LIMIT: usize = 600;
/// 次优整句与最优相差多少以内才列出。 Max cost gap for listing the runner-up sentence.
const ALT_SENTENCE_MARGIN: u32 = 3500;
/// 候选总数上限。 Hard cap on listed candidates.
pub const MAX_CANDIDATES: usize = 800;
/// 系统词库 + 至多 15 个扩展词库（专业词库、热词）。 The system lexicon plus up to fifteen extra packs.
pub const MAX_LEX: usize = 16;

/// 每个词库在前缀树上的位置（下标 0 = 系统词库，其后为扩展词库）；紧凑存放，按值传递很便宜。
/// Position in each lexicon's trie (index 0 = system lexicon, then the extra packs); compact and cheap to copy.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Nodes([NodeId; MAX_LEX]);

impl Nodes {
    const NONE: NodeId = NodeId::MAX;
    pub const EMPTY: Nodes = Nodes([Self::NONE; MAX_LEX]);

    #[inline]
    pub fn get(&self, i: usize) -> Option<NodeId> {
        let v = self.0[i];
        (v != Self::NONE).then_some(v)
    }

    #[inline]
    pub fn set(&mut self, i: usize, n: Option<NodeId>) {
        self.0[i] = n.unwrap_or(Self::NONE);
    }

    #[inline]
    pub fn is_empty(&self) -> bool {
        self.0.iter().all(|&v| v == Self::NONE)
    }
}

/// 词图中的一条跨度。 One span of the word lattice.
#[derive(Clone, Debug)]
pub struct Span {
    pub start: usize,
    pub end: usize,
    /// 音节序列（已解析成完整音节）。 Full syllables of the word.
    pub key: Vec<SyllableId>,
    /// 每个音节在按键上的结束位置。 Key position where each syllable ends.
    pub cuts: Vec<usize>,
    pub penalty: u32,
    pub sys: Nodes,
    pub usr: Option<UNodeId>,
    /// 原样输出的按键。 Raw keys (no syllable).
    pub raw: bool,
    /// Protected Latin fragment participating in the same sentence lattice as Chinese words.
    pub literal: Option<(String,u32)>,
    /// 最后一个音节是句中简拼，且同一位置本可以读成更长的完整音节（如 lvse 里的 s）。
    /// 这种跨度只用于组句，不单独列为候选。
    /// Last syllable is a mid-input abbreviation although a longer full syllable starts at the
    /// same key (the `s` in `lvse`); such spans help sentences but are not listed.
    pub cut_short: bool,
}

/// 词条来源。 Where a word came from.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Origin {
    System,
    User,
    Raw,
}

/// 打分后的词。 A scored word of a span.
#[derive(Clone, Debug)]
pub struct Scored {
    pub text: String,
    pub cost: u32,
    pub origin: Origin,
}

/// 候选种类。 Candidate kind.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum CandKind {
    /// 整句；`words` 为组成它的词。 Whole sentence.
    Sentence,
    Word,
    Raw,
}

#[derive(Clone, Debug)]
pub struct Candidate {
    pub text: String,
    pub comment: String,
    pub end: usize,
    pub key: Vec<SyllableId>,
    pub kind: CandKind,
    pub origin: Origin,
    /// 整句由哪些 (音节, 词) 组成，用于学习。 Words making up a sentence, for learning.
    pub words: Vec<(Vec<SyllableId>, String)>,
    pub cost: u32,
}

/// 用户学习对排序的影响。 How learning changes ranking.
pub mod learn_cost {
    use super::*;

    fn recency(tick: u64, e: &UserEntry) -> f64 {
        let age = tick.saturating_sub(e.last) as f64;
        700.0 * (-age / 400.0).exp()
    }

    fn count(tick: u64, e: &UserEntry) -> f64 {
        crate::userdict::decayed(e.count, tick.saturating_sub(e.last))
    }

    /// 系统词被用户用过：在本跨度的最优 cost 附近提升（次数随时间减半，久不用的词慢慢回到原位）。
    /// A system word the user has chosen: promote relative to the span's best. Counts halve over time, so words
    /// not used for long drift back.
    pub fn promoted(sys_cost: u32, top: u32, tick: u64, e: &UserEntry) -> u32 {
        let v = top as f64 + 350.0 - 450.0 * (1.0 + count(tick, e)).ln() - recency(tick, e);
        (v.max(0.0) as u32).min(sys_cost)
    }

    /// 只存在于用户词库的词（造词结果）。 A word that only exists in the user dictionary.
    pub fn user_only(syllables: usize, tick: u64, e: &UserEntry) -> u32 {
        let v = 5200.0 + 1200.0 * syllables as f64 - 450.0 * (1.0 + count(tick, e)).ln() - recency(tick, e);
        v.max(0.0) as u32
    }

    /// 用户二元组奖励（同样随时间衰减）。 User bigram bonus, decaying over time as well.
    pub fn bigram_bonus(count: u32, age: u64) -> u32 {
        (1600.0 * (1.0 + crate::userdict::decayed(count, age)).ln()).min(4500.0) as u32
    }
}

/// 解码上下文。 Decoding context.
pub struct Decoder<'a> {
    pub lex: Option<&'a Lexicon>,
    /// 扩展词库（专业词库、热词）；与系统词库同步深搜，词按文字合并取最低 cost。
    /// Extra lexicons (domain packs, hot words), walked in lockstep with the system one; merged by text, lowest cost.
    pub packs: &'a [Lexicon],
    pub user: &'a UserDict,
    pub graph: &'a SyllableGraph,
    /// 光标前的上一个词（用于二元组）。 Previous word before the cursor.
    pub context: Option<&'a str>,
    /// 字符搭配模型与其权重。 Character collocation model and its weighting.
    pub lm: Option<LmParams<'a>>,
}

/// 搭配模型参数：每个词边界加 `weight × (−分值)` nat，未命中时分值为 `−baseline`。
/// Collocation model: each word boundary adds `weight × (−score)` nats; misses score `−baseline`.
#[derive(Clone, Copy)]
pub struct LmParams<'a> {
    pub gram: &'a weave_dict::gram::Gram,
    pub weight: f32,
    pub baseline: f32,
}

/// 至多 3 个字 id 打包进 u64：低 2 位存个数（0..=3），其余每 16 位一个 id。
/// Up to three char ids packed into a u64: low 2 bits hold the count, then 16 bits per id.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
struct Ids(u64);

impl Ids {
    fn from_slice(ids: &[u16]) -> Ids {
        let ids = &ids[ids.len().saturating_sub(3)..];
        let mut v = ids.len() as u64;
        for (i, &id) in ids.iter().enumerate() {
            v |= (id as u64) << (2 + 16 * i);
        }
        Ids(v)
    }
    fn len(self) -> usize {
        (self.0 & 3) as usize
    }
    fn get(self, i: usize) -> u16 {
        (self.0 >> (2 + 16 * i)) as u16
    }
    fn to_array(self) -> ([u16; 3], usize) {
        let n = self.len();
        let mut a = [0u16; 3];
        for (i, slot) in a.iter_mut().enumerate().take(n) {
            *slot = self.get(i);
        }
        (a, n)
    }
    /// 接上另一段后保留末尾 3 个。 Append and keep the last three.
    fn append(self, other: Ids) -> Ids {
        let (a, n) = self.to_array();
        let (b, m) = other.to_array();
        let mut all = [0u16; 6];
        all[..n].copy_from_slice(&a[..n]);
        all[n..n + m].copy_from_slice(&b[..m]);
        Ids::from_slice(&all[..n + m])
    }
}

/// 简单快速的哈希（键本身已是均匀的整数）。 Cheap hasher; keys are already integers.
#[derive(Default)]
struct FastHasher(u64);

impl std::hash::Hasher for FastHasher {
    fn finish(&self) -> u64 {
        self.0
    }
    fn write(&mut self, bytes: &[u8]) {
        for &b in bytes {
            self.0 = (self.0.rotate_left(5) ^ b as u64).wrapping_mul(0x51_7c_c1_b7_27_22_0a_95);
        }
    }
    fn write_u64(&mut self, v: u64) {
        self.0 = (self.0.rotate_left(5) ^ v).wrapping_mul(0x51_7c_c1_b7_27_22_0a_95);
    }
}

type FastMap<K, V> = HashMap<K, V, std::hash::BuildHasherDefault<FastHasher>>;

/// 词的搭配信息：开头 3 字、末尾 3 字、是否全部可识别。 Word ids for the collocation model.
#[derive(Clone, Copy, Default)]
struct WordIds {
    head: Ids,
    tail: Ids,
    /// 所有字都在模型字表里（否则上下文在此中断）。 All chars known (else context breaks).
    complete: bool,
}

impl LmParams<'_> {
    /// 边界代价（cost 单位，可为负）。 Boundary cost in cost units (may be negative).
    fn boundary(&self, ctx: Ids, word: Ids) -> i64 {
        let (c, n) = ctx.to_array();
        let (w, m) = word.to_array();
        let score = self
            .gram
            .collocation_ids(&c[..n], &w[..m])
            .map_or(-self.baseline, |s| s.max(-self.baseline));
        (-score * self.weight * 1000.0) as i64
    }

    fn word_ids(&self, word: &str) -> WordIds {
        let chars: Vec<char> = word.chars().collect();
        let tail = self.gram.tail_ids(&chars);
        WordIds {
            head: Ids::from_slice(&self.gram.head_ids(word)),
            complete: tail.len() == chars.len().min(3)
                && chars.iter().all(|&c| self.gram.char_id(c).is_some()),
            tail: Ids::from_slice(&tail),
        }
    }

    /// 接上一个词后的上下文尾。 Context tail after appending a word.
    fn next_tail(&self, prev: Ids, w: &WordIds) -> Ids {
        if !w.complete || w.tail.len() >= 3 {
            w.tail
        } else {
            prev.append(w.tail)
        }
    }
}

/// 解码结果。 Decoding result.
pub struct Lattice {
    pub spans: Vec<Span>,
    pub by_start: Vec<Vec<usize>>,
    /// 最优整句：(span 下标, 词)。 Best sentence as (span index, text).
    pub best: Vec<(usize, String)>,
    pub best_cost: u32,
    /// 次优整句（文字与最优不同）。 Runner-up sentence with different text.
    pub alt: Vec<(usize, String)>,
    pub alt_cost: u32,
}

impl<'a> Decoder<'a> {
    /// 第 i 个词库（0 = 系统词库）。 The i-th lexicon (0 = system).
    fn lexicon(&self, i: usize) -> Option<&'a Lexicon> {
        if i == 0 {
            self.lex
        } else {
            self.packs.get(i - 1)
        }
    }

    fn lex_count(&self) -> usize {
        (1 + self.packs.len()).min(MAX_LEX)
    }

    /// 各词库的根。 Roots of all lexicons.
    fn roots(&self) -> Nodes {
        let mut n = Nodes::EMPTY;
        for i in 0..self.lex_count() {
            n.set(i, self.lexicon(i).map(|_| ROOT));
        }
        n
    }

    /// 建词图并求最优整句。 Build the lattice and the best sentence.
    pub fn decode(&self) -> Lattice {
        let n = self.graph.len;
        let mut spans: Vec<Span> = Vec::new();
        let mut index: HashMap<(usize, usize, Vec<SyllableId>), usize> = HashMap::new();
        for start in 0..n {
            let mut budget = DFS_BUDGET;
            let mut key = Vec::new();
            let mut cuts = Vec::new();
            let sys = self.roots();
            self.dfs(
                start,
                start,
                sys,
                Some(UROOT),
                &mut key,
                &mut cuts,
                0,
                &mut budget,
                &mut spans,
                &mut index,
            );
        }
        let mut by_start = vec![Vec::new(); n + 1];
        for (i, s) in spans.iter().enumerate() {
            by_start[s.start].push(i);
        }
        let ((best, best_cost), (alt, alt_cost)) = self.beam(&spans, &by_start);
        Lattice {
            spans,
            by_start,
            best,
            best_cost,
            alt,
            alt_cost,
        }
    }

    /// `correcting`：纠错时不把偶然拼出的短英文当成中英混打。
    /// `correcting`: do not let accidental short English fragments block pinyin correction.
    pub fn decode_with_latin(&self,english:Option<&Lexicon>,original:&[u8],raw_input:&str,english_user:Option<&UserDict>,correcting:bool)->Lattice {
        let mut lat=self.decode();
        if original.len()!=self.graph.len{return lat;}
        if !original.iter().any(u8::is_ascii_uppercase) && self.graph.spells_fully() {return lat;}
        let english_user=english_user.filter(|u|u.entry_count()>0);
        let before=lat.spans.len();
        let positions:Vec<_>=raw_input.bytes().enumerate().filter(|(_,b)|b.is_ascii_alphabetic()).map(|(i,_)|i).collect();
        for start in 0..original.len() {
            let mut node=Some(ROOT);
            // Latin homographs of complete Chinese readings must not steal ordinary Chinese input.
            let mut chinese=vec![false;original.len()+1];chinese[start]=true;
            for at in start..(start+32).min(original.len()) {
                if chinese[at] {for edge in &self.graph.out[at] {
                    if edge.kind==crate::graph::EdgeKind::Full {chinese[edge.end]=true;}
                }}
            }
            for end in start+1..=(start+32).min(original.len()) {
                let b=original[end-1];
                if !b.is_ascii_alphabetic(){break;}
                node=node.and_then(|n|english.and_then(|lex|lex.child(n,weave_dict::lexicon::letter_sym(b.to_ascii_lowercase())?)));
                let slice=&original[start..end];
                let capitals=slice.iter().all(u8::is_ascii_uppercase);
                if node.is_none() && english_user.is_none() && !capitals {break;}
                if !capitals && chinese[end] {continue;}
                let spelling=String::from_utf8_lossy(slice).to_ascii_lowercase();
                let known=node.and_then(|n|english.and_then(|lex|lex.entries(n).next())).filter(|e|end-start>=3 && e.cost<=13_000
                    && weave_dict::syllable::id_of(&spelling).is_none());
                let learned=english_user.is_some_and(|u|crate::table::code_key(&spelling).is_some_and(|key|u.get(&key,&spelling).is_some()));
                if !capitals && known.is_none() && !learned {continue;}
                let short_latin=end-start==3 && end-start<original.len() && !learned && !slice.iter().any(u8::is_ascii_uppercase);
                if correcting && short_latin {continue;}
                let text=if positions.len()==original.len(){raw_input[positions[start]..positions[end-1]+1].to_string()}else{String::from_utf8_lossy(slice).into_owned()};
                let si=lat.spans.len();
                lat.spans.push(Span{start,end,key:Vec::new(),cuts:vec![end],penalty:0,sys:Nodes::EMPTY,usr:None,raw:true,
                    literal:Some((text,if capitals{3500}else if short_latin {6200+SHORT_LATIN}else{6200})),cut_short:false});
                lat.by_start[start].push(si);
            }
        }
        if lat.spans.len()==before{return lat;}
        let ((best,cost),(alt,alt_cost))=self.beam(&lat.spans,&lat.by_start);
        lat.best=best;lat.best_cost=cost;lat.alt=alt;lat.alt_cost=alt_cost;lat
    }

    #[allow(clippy::too_many_arguments)]
    fn dfs(
        &self,
        start: usize,
        pos: usize,
        sys: Nodes,
        usr: Option<UNodeId>,
        key: &mut Vec<SyllableId>,
        cuts: &mut Vec<usize>,
        pen: u32,
        budget: &mut usize,
        spans: &mut Vec<Span>,
        index: &mut HashMap<(usize, usize, Vec<SyllableId>), usize>,
    ) {
        for edge in &self.graph.out[pos] {
            if edge.kind == EdgeKind::Raw {
                if pos == start {
                    let k = (start, edge.end, Vec::new());
                    if let std::collections::hash_map::Entry::Vacant(e) = index.entry(k) {
                        e.insert(spans.len());
                        spans.push(Span {
                            start,
                            end: edge.end,
                            key: Vec::new(),
                            cuts: vec![edge.end],
                            penalty: edge.penalty as u32,
                            sys: Nodes::EMPTY,
                            usr: None,
                            raw: true,
                            literal: None,
                            cut_short: false,
                        });
                    }
                }
                continue;
            }
            if key.len() >= MAX_WORD_SYLLABLES {
                continue;
            }
            let p = pen + edge.penalty as u32;
            if p > MAX_WORD_PENALTY {
                continue;
            }
            // 大集合（简拼）按子节点扫描位图，小集合逐个二分查找。
            // Large sets (abbreviations) scan the node's children against a bitset.
            let nlex = self.lex_count();
            let mut via_children: Vec<(SyllableId, Nodes)> = Vec::new();
            let scan = !edge.bits.is_empty();
            if scan {
                for i in 0..nlex {
                    let (Some(lex), Some(n)) = (self.lexicon(i), sys.get(i)) else { continue };
                    for c in lex.children(n) {
                        let sym = lex.sym(c);
                        if edge.contains(sym) {
                            // 系统词库先扫，子节点音节互不重复，直接追加；扩展词库才需要合并。
                            // The system lexicon goes first and its children are unique: push directly; only packs merge.
                            let found = if i == 0 { None } else { via_children.iter_mut().find(|(s, _)| *s == sym) };
                            match found {
                                Some((_, nodes)) => nodes.set(i, Some(c)),
                                None => {
                                    let mut nodes = Nodes::EMPTY;
                                    nodes.set(i, Some(c));
                                    via_children.push((sym, nodes));
                                }
                            }
                        }
                    }
                }
                if let Some(un) = usr {
                    for &(sym, _) in self.user.children(un) {
                        if edge.contains(sym) && !via_children.iter().any(|(s, _)| *s == sym) {
                            via_children.push((sym, Nodes::EMPTY));
                        }
                    }
                }
            }
            let direct: &[SyllableId] = if scan { &[] } else { &edge.syls };
            let items = via_children
                .iter()
                .map(|&(s, n)| (s, Some(n)))
                .chain(direct.iter().map(|&s| (s, None)));
            for (syl, known) in items {
                if *budget == 0 {
                    return;
                }
                *budget -= 1;
                let ns: Nodes = match known {
                    Some(n) => n,
                    None => {
                        let mut n = Nodes::EMPTY;
                        for i in 0..nlex {
                            if let (Some(lex), Some(at)) = (self.lexicon(i), sys.get(i)) {
                                n.set(i, lex.child(at, syl));
                            }
                        }
                        n
                    }
                };
                let nu = usr.and_then(|n| self.user.child(n, syl));
                if ns.is_empty() && nu.is_none() {
                    continue;
                }
                key.push(syl);
                cuts.push(edge.end);
                let mut with_entries = Nodes::EMPTY;
                for i in 0..nlex {
                    if let (Some(l), Some(n)) = (self.lexicon(i), ns.get(i)) {
                        if l.entry_count_of(n) > 0 {
                            with_entries.set(i, Some(n));
                        }
                    }
                }
                let has_sys = !with_entries.is_empty();
                let has_usr = nu.is_some_and(|n| !self.user.entries(n).is_empty());
                if has_sys || has_usr {
                    let cut_short = edge.kind == EdgeKind::Abbrev
                        && self.graph.out[pos]
                            .iter()
                            .any(|o| o.kind == EdgeKind::Full && o.end > edge.end);
                    let k = (start, edge.end, key.clone());
                    match index.get(&k) {
                        Some(&i) => {
                            if p < spans[i].penalty {
                                spans[i].penalty = p;
                                spans[i].cuts = cuts.clone();
                            }
                        }
                        None => {
                            index.insert(k, spans.len());
                            spans.push(Span {
                                start,
                                end: edge.end,
                                key: key.clone(),
                                cuts: cuts.clone(),
                                penalty: p,
                                sys: with_entries,
                                usr: nu.filter(|_| has_usr),
                                raw: false,
                                literal: None,
                                cut_short,
                            });
                        }
                    }
                }
                let more_sys = (0..nlex).any(|i| {
                    matches!((self.lexicon(i), ns.get(i)), (Some(l), Some(n)) if !l.children(n).is_empty())
                });
                let more_usr = nu.is_some_and(|n| !self.user.children(n).is_empty());
                if (more_sys || more_usr) && edge.end < self.graph.len {
                    self.dfs(start, edge.end, ns, nu, key, cuts, p, budget, spans, index);
                }
                key.pop();
                cuts.pop();
            }
        }
    }

    /// 某跨度的词（按 cost 升序，含跨度惩罚）。 Words of a span, ascending cost incl. penalty.
    pub fn span_words(
        &self,
        span: &Span,
        limit: usize,
        raw_text: &dyn Fn(usize, usize) -> String,
    ) -> Vec<Scored> {
        if let Some((text,cost))=&span.literal{return vec![Scored{text:text.clone(),cost:*cost,origin:Origin::Raw}];}
        if span.raw {
            return vec![Scored {
                text: raw_text(span.start, span.end),
                cost: span.penalty,
                origin: Origin::Raw,
            }];
        }
        let tick = self.user.tick();
        let user_entries: &[UserEntry] = span.usr.map(|n| self.user.entries(n)).unwrap_or(&[]);
        let mut out: Vec<Scored> = Vec::new();
        // 本跨度的最优 cost：取所有词库首条的最小值（学习提升以它为基准）。
        // The span's best cost across all lexicons; learning promotions are relative to it.
        let top: Option<u32> = (0..self.lex_count())
            .filter_map(|i| {
                let (lex, n) = (self.lexicon(i)?, span.sys.get(i)?);
                lex.entries(n).next().map(|e| e.cost as u32)
            })
            .min();
        if let Some(t) = top {
            for i in 0..self.lex_count() {
                let (Some(lex), Some(n)) = (self.lexicon(i), span.sys.get(i)) else { continue };
                for e in lex.entries(n).take(limit) {
                    let text = lex.text(e.text_id, &span.key);
                    let cost = e.cost as u32;
                    // 同一个词出现在多个词库：取最低 cost（系统词库内部不重复，不必查）。
                    // A word in several lexicons keeps its lowest cost (system entries are unique, no lookup).
                    if let Some(s) = if i == 0 { None } else { out.iter_mut().find(|s| s.text == text) } {
                        if s.origin == Origin::System && cost < s.cost {
                            s.cost = cost;
                        }
                        continue;
                    }
                    let (cost, origin) = match user_entries.iter().find(|u| u.text == text) {
                        Some(ue) => (learn_cost::promoted(cost, t, tick, ue), Origin::User),
                        None => (cost, Origin::System),
                    };
                    out.push(Scored { text, cost, origin });
                }
                // 用户用过、但排在 limit 之外的词：按存储字节直接查，不解码整个节点。
                // Words the user has used that rank beyond `limit`: looked up by bytes, no full decode.
                for ue in user_entries {
                    if out.iter().any(|s| s.text == ue.text) {
                        continue;
                    }
                    if let Some(e) = lex.find_entry(n, &span.key, &ue.text) {
                        out.push(Scored {
                            text: ue.text.clone(),
                            cost: learn_cost::promoted(e.cost as u32, t, tick, ue),
                            origin: Origin::User,
                        });
                    }
                }
            }
        }
        for ue in user_entries {
            if !out.iter().any(|s| s.text == ue.text) {
                out.push(Scored {
                    text: ue.text.clone(),
                    cost: learn_cost::user_only(span.key.len(), tick, ue),
                    origin: Origin::User,
                });
            }
        }
        // A repeated intentional choice can beat old accumulated counts for the same reading.
        // Keep correction penalties outside this promotion so a familiar typo cannot win by history alone.
        let best = out.iter().map(|w| w.cost).min().unwrap_or(0);
        if span.penalty == 0 {
            for w in &mut out {
                if self.user.preferred(&span.key, &w.text) { w.cost = best.saturating_sub(1200); }
            }
        }
        for s in &mut out {
            s.cost += span.penalty;
        }
        out.sort_by_key(|s| (s.cost, !self.user.preferred(&span.key, &s.text)));
        out.truncate(limit);
        out
    }

    #[allow(clippy::type_complexity)]
    fn beam(
        &self,
        spans: &[Span],
        by_start: &[Vec<usize>],
    ) -> ((Vec<(usize, String)>, u32), (Vec<(usize, String)>, u32)) {
        #[derive(Clone)]
        struct State {
            /// 可为负（搭配奖励可能超过词代价）。 May go negative with collocation bonuses.
            cost: i64,
            back: Option<(usize, usize)>,
            span: usize,
            text: String,
            /// text 的哈希，去重时先比它。 Hash of `text`, compared before the string.
            text_hash: u64,
            /// 已组句文字末尾 3 字的 id（含上文），供搭配模型用。 Ids of the last three chars.
            tail: Ids,
            /// 本状态最后一个词是否为干净的完整音节输入。 Last word typed with full syllables only.
            clean: bool,
        }
        let n = self.graph.len;
        let mut states: Vec<Vec<State>> = vec![Vec::new(); n + 1];
        states[0].push(State {
            cost: 0,
            back: None,
            span: usize::MAX,
            text: self.context.unwrap_or("").to_string(),
            text_hash: 0,
            tail: self
                .lm
                .map(|lm| lm.word_ids(self.context.unwrap_or("")).tail)
                .unwrap_or_default(),
            clean: true,
        });
        let no_raw = |_: usize, _: usize| String::new();
        let mut words_cache: HashMap<usize, Vec<(Scored, WordIds, u64)>> = HashMap::new();
        let hash_text = |t: &str| {
            use std::hash::Hasher;
            let mut h = FastHasher::default();
            h.write(t.as_bytes());
            h.finish()
        };
        // 同一次解码里 (上文尾, 词头) 组合大量重复：缓存边界代价。 Cache boundary costs per decode.
        let mut lm_cache: FastMap<(Ids, Ids), i64> = FastMap::default();
        for pos in 0..n {
            if states[pos].is_empty() {
                continue;
            }
            states[pos].sort_by_key(|s| s.cost);
            states[pos].truncate(BEAM);
            let cur = std::mem::take(&mut states[pos]);
            for &si in &by_start[pos] {
                let span = &spans[si];
                let words = words_cache.entry(si).or_insert_with(|| {
                    let limit = if span.penalty == 0 && span.start == 0
                        && self.context.is_some_and(|c| !c.is_empty())
                        && (self.lm.is_some() || self.user.has_bigrams()) {
                        SPAN_TOP
                    } else { SPECULATIVE_TOP };
                    let mut w = self.span_words(span, limit, &no_raw);
                    if span.raw && span.literal.is_none() {
                        w[0].text = String::new();
                    }
                    w.into_iter()
                        .map(|s| {
                            let ids = self.lm.map(|lm| lm.word_ids(&s.text)).unwrap_or_default();
                            let h = hash_text(&s.text);
                            (s, ids, h)
                        })
                        .collect()
                });
                // 只在两个干净跨度之间用搭配模型：否则它会奖励把完整音节拆成简拼。
                // Only between two clean spans; otherwise it rewards splitting syllables.
                let clean = span.penalty == 0 && !span.raw;
                let slot = &mut states[span.end];
                for (pi, prev) in cur.iter().enumerate() {
                    for (w, ids, text_hash) in words.iter() {
                        let text_hash = *text_hash;
                        let mut cost = prev.cost + (w.cost + WORD_COST) as i64;
                        if self.user.has_bigrams() && !prev.text.is_empty() {
                            if let Some(b) = self.user.bigram(&prev.text, &w.text) {
                                cost -= learn_cost::bigram_bonus(b.count, self.user.tick().saturating_sub(b.last)) as i64;
                            }
                        }
                        let tail = match &self.lm {
                            Some(lm) => {
                                if span.literal.is_some() || (prev.span!=usize::MAX && spans[prev.span].literal.is_some()) {
                                    // The Chinese LM has no useful probability for an English boundary.
                                } else if clean && prev.clean {
                                    cost += *lm_cache
                                        .entry((prev.tail, ids.head))
                                        .or_insert_with(|| lm.boundary(prev.tail, ids.head));
                                } else {
                                    cost += (lm.baseline * lm.weight * 1000.0) as i64;
                                }
                                lm.next_tail(prev.tail, ids)
                            }
                            None => Ids::default(),
                        };
                        if let Some(same) = slot.iter_mut().find(|s| {
                            s.text_hash == text_hash && s.tail == tail && s.text == w.text
                        }) {
                            if cost < same.cost {
                                same.cost = cost;
                                same.back = Some((pos, pi));
                                same.span = si;
                                same.clean = clean;
                            }
                        } else {
                            slot.push(State {
                                cost,
                                back: Some((pos, pi)),
                                span: si,
                                text: w.text.clone(),
                                text_hash,
                                tail,
                                clean,
                            });
                        }
                    }
                }
                if slot.len() > BEAM * 4 {
                    slot.sort_by_key(|s| s.cost);
                    slot.truncate(BEAM);
                }
            }
            states[pos] = cur;
        }
        let backtrack = |end: &State| {
            let mut path = Vec::new();
            let mut cur = end.clone();
            while let Some((p, i)) = cur.back {
                path.push((cur.span, cur.text.clone()));
                cur = states[p][i].clone();
            }
            path.reverse();
            path
        };
        let mut finals: Vec<&State> = states[n].iter().collect();
        finals.sort_by_key(|s| s.cost);
        let clamp = |c: i64| c.clamp(0, u32::MAX as i64) as u32;
        let Some(first) = finals.first() else {
            return ((Vec::new(), u32::MAX), (Vec::new(), u32::MAX));
        };
        let best = backtrack(first);
        let joined = |p: &[(usize, String)]| p.iter().map(|(_, t)| t.as_str()).collect::<String>();
        let best_text = joined(&best);
        let mut alt = (Vec::new(), u32::MAX);
        for f in finals.iter().skip(1).take(8) {
            let p = backtrack(f);
            if joined(&p) != best_text {
                alt = (p, clamp(f.cost));
                break;
            }
        }
        ((best, clamp(first.cost)), alt)
    }

    /// 生成候选列表。 Produce the candidate list.
    /// `cap` 为最多生成多少个；返回的列表长度达到 `cap` 表示可能还有更多。
    /// `cap` bounds the list; a list of length `cap` may have more behind it.
    pub fn candidates(
        &self,
        lat: &Lattice,
        raw_text: &dyn Fn(usize, usize) -> String,
        cap: usize,
    ) -> Vec<Candidate> {
        let cap = cap.min(MAX_CANDIDATES);
        let n = self.graph.len;
        let mut out: Vec<Candidate> = Vec::new();
        let mut seen: std::collections::HashSet<String> = Default::default();

        // 学过的完整选择可以排到最前；单个字的偏好不会把更长输入的整句候选挤走。选它时读法是不是原样
        // 拼出来的（简拼、补全、纠错）都算数——用户既然选过，下次就该在前面；但必须覆盖整个输入。
        // Complete learned choices may lead the list; a partial single character never displaces a longer
        // input's sentence. How the reading was reached (abbreviation, completion, correction) does not
        // matter — a word the user picked belongs in front next time — but it must cover the whole input.
        let mut preferred = Vec::new();
        for &si in &lat.by_start[0] {
            let span = &lat.spans[si];
            if span.end != n || span.raw { continue; }
            if let Some(choice) = self.user.choice(&span.key).filter(|c| self.user.preferred(&span.key, &c.text)) {
                preferred.push((choice, span));
            }
        }
        preferred.sort_by_key(|(c, span)| (std::cmp::Reverse(c.last), std::cmp::Reverse(c.repeats), span.key.len()));
        for (choice, span) in preferred {
            if out.len() >= cap { return out; }
            if !seen.insert(choice.text.clone()) { continue; }
            out.push(Candidate { text: choice.text.clone(), comment: String::new(), end: n, key: span.key.clone(),
                kind: CandKind::Word, origin: Origin::User, words: vec![(span.key.clone(), choice.text.clone())], cost: 0 });
        }

        // 1. 解码器选出的完整结果（及接近的次优结果）。也可能恰好是一个词；不能在这里
        // 丢掉上下文/用户搭配的打分，再以裸词频重排首选。
        // Keep the decoder's full result even when it is a single word: otherwise listing
        // by dictionary frequency silently discards context and learned bigram scores.
        let sentence = |path: &[(usize, String)], cost: u32| {
            let mut text = String::new();
            let mut key = Vec::new();
            let mut words = Vec::new();
            for (si, w) in path {
                let span = &lat.spans[*si];
                let t = if let Some((text,_))=&span.literal {text.clone()} else if span.raw {
                    raw_text(span.start, span.end)
                } else {
                    w.clone()
                };
                text.push_str(&t);
                key.extend_from_slice(&span.key);
                if !span.raw || span.literal.is_some() {
                    words.push((span.key.clone(), t));
                }
            }
            let single = (path.len() == 1).then(|| &lat.spans[path[0].0]);
            let kind = match single {
                Some(span) if span.raw => CandKind::Raw,
                Some(_) => CandKind::Word,
                None => CandKind::Sentence,
            };
            let origin = match single {
                Some(span) if span.raw => Origin::Raw,
                Some(span) if self.user.get(&span.key, &text).is_some() => Origin::User,
                _ => Origin::System,
            };
            Candidate {
                text,
                comment: String::new(),
                end: n,
                key,
                kind,
                origin,
                words,
                cost,
            }
        };
        if !lat.best.is_empty() {
            let c = sentence(&lat.best, lat.best_cost);
            if seen.insert(c.text.clone()) && out.len() < cap { out.push(c); }
        }
        // 次优整句总是放在第 2 位（最优是单个词时，它排在那个词之后）。
        // The runner-up always goes second, also when the best result is a single word.
        let mut alt_sentence = None;
        if lat.alt.len() >= 2 && lat.alt_cost <= lat.best_cost.saturating_add(ALT_SENTENCE_MARGIN) {
            let c = sentence(&lat.alt, lat.alt_cost);
            if seen.insert(c.text.clone()) {
                alt_sentence = Some(c);
            }
        }
        if let Some(c) = alt_sentence.take().filter(|_| !out.is_empty()) {
            if out.len() < cap { out.push(c); }
        }
        if out.len() >= cap { return out; }

        // 2. 以 0 开头的词，覆盖长的优先。 Words from 0, longest coverage first.
        let mut ends: Vec<usize> = lat.by_start[0].iter().map(|&i| lat.spans[i].end).collect();
        ends.sort_unstable_by(|a, b| b.cmp(a));
        ends.dedup();
        let mut pending_alt = alt_sentence;
        for end in ends {
            let mut group: Vec<(Scored, usize)> = Vec::new();
            for &si in &lat.by_start[0] {
                let span = &lat.spans[si];
                if span.end != end || (span.cut_short && end < n) {
                    continue;
                }
                for w in self.span_words(span, SPAN_LIST_LIMIT, raw_text) {
                    group.push((w, si));
                }
            }
            group.sort_by_key(|(w, si)| (w.cost, lat.spans[*si].key.len()));
            for (w, si) in group {
                if out.len() >= cap {
                    return out;
                }
                if !seen.insert(w.text.clone()) {
                    continue;
                }
                let span = &lat.spans[si];
                if out.len() == 1 {
                    if let Some(c) = pending_alt.take() {
                        out.push(c);
                        if out.len() >= cap { return out; }
                    }
                }
                out.push(Candidate {
                    kind: if span.raw {
                        CandKind::Raw
                    } else {
                        CandKind::Word
                    },
                    comment: String::new(),
                    end: span.end,
                    key: span.key.clone(),
                    words: vec![(span.key.clone(), w.text.clone())],
                    text: w.text,
                    origin: w.origin,
                    cost: w.cost,
                });
            }
        }
        out.extend(pending_alt);
        out
    }
}

#[cfg(test)]
mod debug_tests {
    use super::*;
    use crate::graph::{build_full_pinyin, FuzzyOptions, Letters};
    use weave_dict::{gram::GramBuilder, lexicon::{Builder, Kind}, syllable};

    fn homophones() -> Lexicon {
        let mut b = Builder::new(Kind::Pinyin);
        let key = syllable::parse_seq("shi jie").unwrap();
        b.insert(&key, "世界", 14000);
        b.insert(&key, "视界", 15000);
        Lexicon::from_bytes(b.build()).unwrap()
    }

    #[test]
    fn single_word_candidates_keep_the_decoders_contextual_winner() {
        let lex = homophones();
        let mut user = UserDict::in_memory();
        for _ in 0..5 { user.learn_bigram("虚拟", "视界"); }
        let graph = build_full_pinyin(&Letters::parse("shijie"), &FuzzyOptions::default());
        let decoder = Decoder { lex: Some(&lex), packs: &[], user: &user,
            graph: &graph, context: Some("虚拟"), lm: None };
        let lat = decoder.decode();
        assert_eq!(lat.best[0].1, "视界");
        let candidates = decoder.candidates(&lat, &|_, _| String::new(), 20);
        assert_eq!(candidates[0].text, "视界");
        assert_eq!(candidates[0].kind, CandKind::Word);
    }

    #[test]
    fn single_word_candidates_keep_the_language_models_winner() {
        let lex = homophones();
        let user = UserDict::in_memory();
        let mut builder = GramBuilder::default();
        builder.push("拟视界", 20.0);
        builder.push("世界", 1.0);
        let gram = weave_dict::gram::Gram::from_bytes(builder.build()).unwrap();
        let graph = build_full_pinyin(&Letters::parse("shijie"), &FuzzyOptions::default());
        let decoder = Decoder { lex: Some(&lex), packs: &[], user: &user,
            graph: &graph, context: Some("虚拟"),
            lm: Some(LmParams { gram: &gram, weight: 0.25, baseline: 12.0 }) };
        let lat = decoder.decode();
        assert_eq!(lat.best[0].1, "视界");
        assert_eq!(decoder.candidates(&lat, &|_, _| String::new(), 20)[0].text, "视界");
    }

    #[test]
    fn contextual_scoring_can_reach_a_homophone_beyond_the_first_four() {
        let mut b = Builder::new(Kind::Pinyin);
        let key = syllable::parse_seq("mai").unwrap();
        for (i, word) in ["卖", "脉", "麦", "埋", "买"].iter().enumerate() {
            b.insert(&key, word, 14000 + i as u16 * 100);
        }
        let lex = Lexicon::from_bytes(b.build()).unwrap();
        let user = UserDict::in_memory();
        let mut builder = GramBuilder::default();
        builder.push("我买", 20.0);
        let gram = weave_dict::gram::Gram::from_bytes(builder.build()).unwrap();
        let graph = build_full_pinyin(&Letters::parse("mai"), &FuzzyOptions::default());
        let decoder = Decoder { lex: Some(&lex), packs: &[], user: &user,
            graph: &graph, context: Some("我"),
            lm: Some(LmParams { gram: &gram, weight: 0.25, baseline: 12.0 }) };
        let lat = decoder.decode();
        assert_eq!(decoder.candidates(&lat, &|_, _| String::new(), 20)[0].text, "买");
    }

    #[test]
    fn a_single_word_and_sentence_runner_up_respect_a_one_candidate_limit() {
        let mut builder = Builder::new(Kind::Pinyin);
        for (py, text, cost) in [("xiu ba", "秀吧", 10), ("xiu", "修", 100), ("ba", "吧", 100)] {
            builder.insert(&syllable::parse_seq(py).unwrap(), text, cost);
        }
        let lex = Lexicon::from_bytes(builder.build()).unwrap();
        let user = UserDict::in_memory();
        let graph = build_full_pinyin(&Letters::parse("xiuba"), &FuzzyOptions::default());
        let decoder = Decoder { lex: Some(&lex), packs: &[], user: &user, graph: &graph, context: None, lm: None };
        let lat = decoder.decode();
        assert_eq!(lat.best.len(), 1);
        assert_eq!(lat.alt.len(), 2);
        assert_eq!(decoder.candidates(&lat, &|_, _| String::new(), 0).len(), 0);
        let first = decoder.candidates(&lat, &|_, _| String::new(), 1);
        assert_eq!(first.len(), 1);
        assert_eq!(first[0].text, "秀吧");
        assert_eq!(decoder.candidates(&lat, &|_, _| String::new(), 2).len(), 2);
    }

    #[test]
    #[ignore]
    fn dump() {
        let input = std::env::var("PY").unwrap_or("lvse".into());
        let lex = Lexicon::open(std::path::Path::new("../../data/build/pinyin.wvl")).unwrap();
        let user = UserDict::in_memory();
        let l = Letters::parse(&input);
        let g = build_full_pinyin(&l, &FuzzyOptions::default());
        for (i, es) in g.out.iter().enumerate() {
            for e in es {
                println!(
                    "edge {i}->{} {:?} pen={} n={} first={:?}",
                    e.end,
                    e.kind,
                    e.penalty,
                    e.syls.len(),
                    e.syls
                        .iter()
                        .take(5)
                        .map(|&s| weave_dict::syllable::spelling(s))
                        .collect::<Vec<_>>()
                );
            }
        }
        let d = Decoder {
            lex: Some(&lex),
            packs: &[],
            user: &user,
            graph: &g,
            context: None,
            lm: None,
        };
        let lat = d.decode();
        let raw = |_: usize, _: usize| String::new();
        for c in d.candidates(&lat, &raw, MAX_CANDIDATES).iter().take(8) {
            println!(
                "{} cost={} key={:?}",
                c.text,
                c.cost,
                c.key
                    .iter()
                    .map(|&s| weave_dict::syllable::spelling(s))
                    .collect::<Vec<_>>()
            );
        }
    }
}
