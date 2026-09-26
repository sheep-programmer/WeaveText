//! 码表类查询（五笔、英文）：按字母编码查前缀树，精确匹配在前、补全在后。
//! Table lookups (Wubi, English): exact code matches first, then completions.

use std::cmp::Reverse;
use std::collections::{BinaryHeap, HashMap};

use weave_dict::lexicon::{letter_sym, sym_letter, Lexicon, NodeId, ROOT};

#[derive(Clone, Debug)]
pub struct TableCand {
    pub text: String,
    /// 剩余编码（补全提示）。 Remaining code, shown as a completion hint.
    pub comment: String,
    pub cost: u32,
    pub exact: bool,
}

pub fn code_key(code: &str) -> Option<Vec<u16>> {
    code.bytes()
        .map(|c| letter_sym(c.to_ascii_lowercase()))
        .collect()
}

/// 查询：`exact_limit` 个精确匹配 + 最多 `completion_limit` 个补全（按子树最优 cost 优先）。
/// Lookup: exact matches plus best-first completions.
pub fn lookup(lex: &Lexicon, code: &str, completion_limit: usize) -> Vec<TableCand> {
    let Some(key) = code_key(code) else {
        return Vec::new();
    };
    let Some(node) = lex.find(&key) else {
        return Vec::new();
    };
    let mut out: Vec<TableCand> = lex
        .entries(node)
        .map(|e| TableCand {
            text: lex.text(e.text_id, &[]),
            comment: String::new(),
            cost: e.cost as u32,
            exact: true,
        })
        .collect();
    if completion_limit == 0 {
        return out;
    }
    // 子树按 best 做最佳优先搜索。 Best-first over the subtree using `best`.
    let mut heap: BinaryHeap<Reverse<(u16, NodeId, String)>> = BinaryHeap::new();
    for c in lex.children(node) {
        heap.push(Reverse((
            lex.best(c),
            c,
            sym_letter(lex.sym(c)).to_string(),
        )));
    }
    let mut completions: Vec<TableCand> = Vec::new();
    while let Some(Reverse((_, n, suffix))) = heap.pop() {
        if completions.len() >= completion_limit {
            break;
        }
        for e in lex.entries(n) {
            completions.push(TableCand {
                text: lex.text(e.text_id, &[]),
                comment: suffix.clone(),
                cost: e.cost as u32,
                exact: false,
            });
        }
        for c in lex.children(n) {
            let mut s = suffix.clone();
            s.push(sym_letter(lex.sym(c)));
            heap.push(Reverse((lex.best(c), c, s)));
        }
    }
    completions.sort_by_key(|c| (c.comment.len(), c.cost));
    completions.truncate(completion_limit);
    out.extend(completions);
    out
}

/// 反查表：字/词 → 最长（完整）编码。 Reverse table: text → its longest code.
pub fn reverse_index(lex: &Lexicon) -> HashMap<String, String> {
    let mut map: HashMap<String, String> = HashMap::new();
    let mut stack: Vec<(NodeId, String)> = vec![(ROOT, String::new())];
    while let Some((n, code)) = stack.pop() {
        for e in lex.entries(n) {
            let t = lex.text(e.text_id, &[]);
            let slot = map.entry(t.to_string()).or_default();
            if code.len() > slot.len() {
                *slot = code.clone();
            }
        }
        for c in lex.children(n) {
            let mut s = code.clone();
            s.push(sym_letter(lex.sym(c)));
            stack.push((c, s));
        }
    }
    map
}
