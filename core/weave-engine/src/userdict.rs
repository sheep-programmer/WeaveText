//! 用户词库：学习用户选过的词、造过的词和常用搭配（用户二元组）。
//! User dictionary: learns chosen words, user-made phrases, and word pairs (user bigrams).
//!
//! 持久化为**只追加日志**：每次学习追加一行，进程随时被杀都不会损坏已有数据；
//! 启动时重放日志，日志过长时自动压缩重写。
//! Persisted as an **append-only log**: each update appends one line, so being killed at any
//! moment never corrupts earlier data; the log is replayed on load and compacted when large.
//!
//! 行格式 / Line format (tab separated):
//! ```text
//! W  <sym.sym.sym>  <text>  <count>  <tick>     词条（绝对值，后写覆盖前写）
//! D  <sym.sym.sym>  <text>                      删除
//! B  <prev>  <next>  <count>  <tick>            二元组
//! P  <sym.sym.sym>  <text>  <repeats> <tick>    近期明确选词（次数 0 = 撤销）
//! ```

use std::collections::HashMap;
use std::fs::{File, OpenOptions};
use std::io::{BufRead, BufReader, BufWriter, Write};
use std::path::{Path, PathBuf};

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct UserEntry {
    pub text: String,
    pub count: u32,
    /// 最近一次使用时的逻辑时钟。 Logical clock of the last use.
    pub last: u64,
}

/// One recent intentional choice per reading. Lifetime frequency alone cannot express a changed habit.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RecentChoice {
    pub text: String,
    pub repeats: u32,
    pub last: u64,
}

const CHOICE_WINDOW: u64 = 2_000;

#[derive(Clone, Debug, Default)]
struct UNode {
    children: Vec<(u16, u32)>,
    entries: Vec<UserEntry>,
    choice: Option<RecentChoice>,
}

pub type UNodeId = u32;
pub const UROOT: UNodeId = 0;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct BigramStat {
    pub count: u32,
    pub last: u64,
}

/// 用户词库。 User dictionary.
pub struct UserDict {
    nodes: Vec<UNode>,
    bigrams: HashMap<(String, String), BigramStat>,
    tick: u64,
    path: Option<PathBuf>,
    log: Option<BufWriter<File>>,
    log_lines: usize,
    /// 学习开关（密码框等场景由上层关闭）。 Learning switch.
    pub learning: bool,
}

impl Default for UserDict {
    fn default() -> Self {
        Self::in_memory()
    }
}

/// 次数的半衰期（逻辑时钟：每学一个词或一对搭配走一步，约一周的高强度输入）。
/// Half-life of counts in logical ticks (one per learned word or pair; about a week of heavy typing).
pub const HALF_LIFE: f64 = 20_000.0;
/// 压缩时的容量上限。 Capacity limits applied when compacting.
pub const MAX_WORDS: usize = 20_000;
pub const MAX_BIGRAMS: usize = 30_000;

/// 随时间减半后的次数。 A count after halving over `age` ticks.
pub fn decayed(count: u32, age: u64) -> f64 {
    count as f64 * (-(age as f64) / HALF_LIFE).exp2()
}

fn key_str(key: &[u16]) -> String {
    key.iter()
        .map(|s| s.to_string())
        .collect::<Vec<_>>()
        .join(".")
}

fn parse_key(s: &str) -> Option<Vec<u16>> {
    s.split('.').map(|x| x.parse().ok()).collect()
}

fn clean(s: &str) -> bool {
    !s.is_empty() && !s.contains(['\t', '\n', '\r'])
}

impl UserDict {
    pub fn in_memory() -> Self {
        UserDict {
            nodes: vec![UNode::default()],
            bigrams: HashMap::new(),
            tick: 0,
            path: None,
            log: None,
            log_lines: 0,
            learning: true,
        }
    }

    /// 打开（或新建）日志文件。 Open or create the log.
    pub fn open(path: &Path) -> std::io::Result<Self> {
        let mut d = Self::in_memory();
        if let Ok(f) = File::open(path) {
            // 按字节读：写到一半断电留下的坏行只跳过这一行，后面追加的学习照样读回来。
            // Read raw lines: a torn write only costs that line; learning appended after it still loads.
            for line in BufReader::new(f).split(b'\n') {
                let Ok(line) = line else { break };
                d.apply_line(String::from_utf8_lossy(&line).trim_end_matches('\r'));
                d.log_lines += 1;
            }
        }
        d.path = Some(path.to_path_buf());
        let live = d.entry_count() + d.bigrams.len();
        if d.log_lines > 2 * live + 2000 {
            d.compact()?;
        } else {
            d.open_log()?;
        }
        Ok(d)
    }

    fn open_log(&mut self) -> std::io::Result<()> {
        if let Some(p) = &self.path {
            if let Some(dir) = p.parent() {
                std::fs::create_dir_all(dir)?;
            }
            let f = OpenOptions::new().create(true).append(true).open(p)?;
            self.log = Some(BufWriter::new(f));
        }
        Ok(())
    }

    fn apply_line(&mut self, line: &str) {
        let cols: Vec<&str> = line.split('\t').collect();
        match cols.as_slice() {
            ["W", key, text, count, tick] => {
                if let (Some(k), Ok(c), Ok(t)) = (parse_key(key), count.parse(), tick.parse()) {
                    self.set_entry(&k, text, c, t);
                    self.tick = self.tick.max(t);
                }
            }
            ["D", key, text] => {
                if let Some(k) = parse_key(key) {
                    self.remove_entry(&k, text);
                }
            }
            ["P", key, text, repeats, tick] => {
                if let (Some(k), Ok(repeats), Ok(last)) = (parse_key(key), repeats.parse::<u32>(), tick.parse::<u64>()) {
                    let n = self.node_for(&k);
                    self.nodes[n as usize].choice = (repeats > 0 && clean(text)).then(|| RecentChoice { text: (*text).into(), repeats, last });
                    self.tick = self.tick.max(last);
                }
            }
            ["B", prev, next, count, tick] => {
                if let (Ok(c), Ok(t)) = (count.parse::<u32>(), tick.parse()) {
                    let k = (prev.to_string(), next.to_string());
                    // 次数 0 = 撤销后删除。 A zero count means removed by an undo.
                    if c == 0 {
                        self.bigrams.remove(&k);
                    } else {
                        self.bigrams.insert(k, BigramStat { count: c, last: t });
                    }
                    self.tick = self.tick.max(t);
                }
            }
            _ => {}
        }
    }

    fn write_line(&mut self, line: String) {
        if let Some(w) = &mut self.log {
            let _ = writeln!(w, "{line}");
            self.log_lines += 1;
        }
    }

    /// 把缓冲写到磁盘（上屏后、收起键盘时调用）。 Flush buffered writes.
    pub fn flush(&mut self) {
        if let Some(w) = &mut self.log {
            let _ = w.flush();
        }
    }

    /// 压缩：只保留当前有效状态。 Rewrite the log with live state only.
    pub fn compact(&mut self) -> std::io::Result<()> {
        let Some(path) = self.path.clone() else {
            return Ok(());
        };
        self.log = None;
        let tmp = path.with_extension("tmp");
        {
            let mut w = BufWriter::new(File::create(&tmp)?);
            let mut lines = 0;
            self.prune();
            let mut stack = vec![(UROOT, Vec::<u16>::new())];
            while let Some((n, key)) = stack.pop() {
                for e in &self.nodes[n as usize].entries {
                    writeln!(
                        w,
                        "W\t{}\t{}\t{}\t{}",
                        key_str(&key),
                        e.text,
                        e.count,
                        e.last
                    )?;
                    lines += 1;
                }
                if let Some(c) = &self.nodes[n as usize].choice {
                    writeln!(w, "P\t{}\t{}\t{}\t{}", key_str(&key), c.text, c.repeats, c.last)?;
                    lines += 1;
                }
                for &(sym, c) in &self.nodes[n as usize].children {
                    let mut k = key.clone();
                    k.push(sym);
                    stack.push((c, k));
                }
            }
            for ((p, n), s) in &self.bigrams {
                writeln!(w, "B\t{p}\t{n}\t{}\t{}", s.count, s.last)?;
                lines += 1;
            }
            w.flush()?;
            w.get_ref().sync_all()?;
            self.log_lines = lines;
        }
        std::fs::rename(&tmp, &path)?;
        self.open_log()
    }

    pub fn tick(&self) -> u64 {
        self.tick
    }

    /// 清掉早已衰减殆尽的记录，并按剩余次数截到容量上限（压缩时调用）。
    /// Drop records that have decayed away and cap by remaining count (called while compacting).
    fn prune(&mut self) {
        let tick = self.tick;
        let stale = |c: u32, last: u64| decayed(c, tick.saturating_sub(last)) < 0.25;
        for n in &mut self.nodes {
            n.entries.retain(|e| !stale(e.count, e.last));
            if n.choice.as_ref().is_some_and(|c| tick.saturating_sub(c.last) > CHOICE_WINDOW || !n.entries.iter().any(|e| e.text == c.text)) {
                n.choice = None;
            }
        }
        self.bigrams.retain(|_, s| !stale(s.count, s.last));
        let words = self.entry_count();
        if words > MAX_WORDS {
            let mut all: Vec<f64> = self.nodes.iter().flat_map(|n| n.entries.iter().map(|e| decayed(e.count, tick.saturating_sub(e.last)))).collect();
            all.sort_by(|a, b| b.partial_cmp(a).unwrap_or(std::cmp::Ordering::Equal));
            let cut = all[MAX_WORDS - 1];
            for n in &mut self.nodes {
                n.entries.retain(|e| decayed(e.count, tick.saturating_sub(e.last)) >= cut);
                if n.choice.as_ref().is_some_and(|c| !n.entries.iter().any(|e| e.text == c.text)) { n.choice = None; }
            }
        }
        if self.bigrams.len() > MAX_BIGRAMS {
            let mut all: Vec<f64> = self.bigrams.values().map(|s| decayed(s.count, tick.saturating_sub(s.last))).collect();
            all.sort_by(|a, b| b.partial_cmp(a).unwrap_or(std::cmp::Ordering::Equal));
            let cut = all[MAX_BIGRAMS - 1];
            self.bigrams.retain(|_, s| decayed(s.count, tick.saturating_sub(s.last)) >= cut);
        }
    }

    pub fn entry_count(&self) -> usize {
        self.nodes.iter().map(|n| n.entries.len()).sum()
    }

    fn node_for(&mut self, key: &[u16]) -> UNodeId {
        let mut cur = UROOT;
        for &sym in key {
            let children = &self.nodes[cur as usize].children;
            cur = match children.binary_search_by_key(&sym, |c| c.0) {
                Ok(i) => children[i].1,
                Err(i) => {
                    let id = self.nodes.len() as u32;
                    self.nodes.push(UNode::default());
                    self.nodes[cur as usize].children.insert(i, (sym, id));
                    id
                }
            };
        }
        cur
    }

    fn set_entry(&mut self, key: &[u16], text: &str, count: u32, last: u64) {
        let n = self.node_for(key);
        let entries = &mut self.nodes[n as usize].entries;
        match entries.iter_mut().find(|e| e.text == text) {
            Some(e) => {
                e.count = count;
                e.last = last;
            }
            None => entries.push(UserEntry {
                text: text.to_string(),
                count,
                last,
            }),
        }
    }

    fn remove_entry(&mut self, key: &[u16], text: &str) -> bool {
        if let Some(n) = self.find(key) {
            let node = &mut self.nodes[n as usize];
            if node.choice.as_ref().is_some_and(|c| c.text == text) { node.choice = None; }
            let entries = &mut node.entries;
            let before = entries.len();
            entries.retain(|e| e.text != text);
            return entries.len() != before;
        }
        false
    }

    /// 学习一个词：次数 +1、刷新时间。 Learn one word.
    pub fn learn(&mut self, key: &[u16], text: &str) {
        if !self.learning || key.is_empty() || !clean(text) {
            return;
        }
        self.tick += 1;
        let tick = self.tick;
        // 先按时间减半再加一：很久以前的次数不再全额累积。 Decay first, then add one.
        let count = self
            .get(key, text)
            .map(|e| decayed(e.count, tick.saturating_sub(e.last)).round() as u32)
            .unwrap_or(0)
            .saturating_add(1);
        self.set_entry(key, text, count, tick);
        self.write_line(format!(
            "W\t{}\t{}\t{}\t{}",
            key_str(key),
            text,
            count,
            tick
        ));
    }

    pub fn choice(&self, key: &[u16]) -> Option<&RecentChoice> {
        self.find(key).and_then(|n| self.nodes[n as usize].choice.as_ref())
    }

    pub fn preferred(&self, key: &[u16], text: &str) -> bool {
        self.learning && self.choice(key).is_some_and(|c| c.text == text && c.repeats >= 1 && self.tick.saturating_sub(c.last) <= CHOICE_WINDOW)
    }

    /// Called only for a chosen candidate; generated sentence components just receive normal counts.
    pub fn select(&mut self, key: &[u16], text: &str) {
        if !self.learning || key.is_empty() || self.get(key, text).is_none() { return; }
        let repeats = self.choice(key).filter(|c| c.text == text && self.tick.saturating_sub(c.last) <= CHOICE_WINDOW)
            .map_or(1, |c| c.repeats.saturating_add(1));
        self.restore_choice(key, Some(RecentChoice { text: text.into(), repeats, last: self.tick }));
    }

    pub fn restore_choice(&mut self, key: &[u16], choice: Option<RecentChoice>) {
        let n = self.node_for(key);
        let line = match &choice {
            Some(c) => format!("P\t{}\t{}\t{}\t{}", key_str(key), c.text, c.repeats, c.last),
            None => format!("P\t{}\t\t0\t{}", key_str(key), self.tick),
        };
        self.nodes[n as usize].choice = choice;
        self.write_line(line);
    }

    /// Restore the complete pre-commit state, including recency, rather than decrementing a fresh timestamp.
    pub fn restore_entry(&mut self, key: &[u16], text: &str, previous: Option<UserEntry>) {
        match previous {
            Some(e) => {
                self.set_entry(key, text, e.count, e.last);
                self.write_line(format!("W\t{}\t{}\t{}\t{}", key_str(key), text, e.count, e.last));
            }
            None => self.forget(key, text),
        }
    }

    pub fn restore_bigram(&mut self, prev: &str, next: &str, previous: Option<BigramStat>) {
        let key = (prev.to_string(), next.to_string());
        let stat = previous.unwrap_or_default();
        if previous.is_some() { self.bigrams.insert(key, stat); } else { self.bigrams.remove(&key); }
        self.write_line(format!("B\t{prev}\t{next}\t{}\t{}", stat.count, stat.last));
    }

    /// 学习相邻两个词的搭配。 Learn that `next` followed `prev`.
    pub fn learn_bigram(&mut self, prev: &str, next: &str) {
        if !self.learning || !clean(prev) || !clean(next) {
            return;
        }
        self.tick += 1;
        let tick = self.tick;
        let s = self
            .bigrams
            .entry((prev.to_string(), next.to_string()))
            .or_default();
        s.count = (decayed(s.count, tick.saturating_sub(s.last)).round() as u32).saturating_add(1);
        s.last = tick;
        let line = format!("B\t{prev}\t{next}\t{}\t{}", s.count, s.last);
        self.write_line(line);
    }

    /// 撤销一次学习（选错后马上退格）：次数减一，减到 0 就删掉。
    /// Undo one learning step (backspace right after a wrong pick): count minus one, removed at zero.
    pub fn unlearn(&mut self, key: &[u16], text: &str) {
        let Some(e) = self.get(key, text).cloned() else { return };
        if e.count <= 1 {
            self.forget(key, text);
        } else {
            self.set_entry(key, text, e.count - 1, e.last);
            self.write_line(format!("W\t{}\t{}\t{}\t{}", key_str(key), text, e.count - 1, e.last));
        }
    }

    pub fn unlearn_bigram(&mut self, prev: &str, next: &str) {
        let k = (prev.to_string(), next.to_string());
        let Some(s) = self.bigrams.get(&k).copied() else { return };
        let c = s.count.saturating_sub(1);
        if c == 0 {
            self.bigrams.remove(&k);
        } else {
            self.bigrams.insert(k, BigramStat { count: c, last: s.last });
        }
        self.write_line(format!("B\t{prev}\t{next}\t{c}\t{}", s.last));
    }

    /// 删除一个用户词。 Forget a user word.
    pub fn forget(&mut self, key: &[u16], text: &str) {
        if self.remove_entry(key, text) {
            self.write_line(format!("D\t{}\t{}", key_str(key), text));
        }
    }

    /// 删除一条用户二元组（长按删除联想词）。 Forget a user bigram (deleting a predicted word).
    pub fn forget_bigram(&mut self, prev: &str, next: &str) {
        let k = (prev.to_string(), next.to_string());
        if let Some(s) = self.bigrams.remove(&k) {
            self.write_line(format!("B\t{prev}\t{next}\t0\t{}", s.last));
        }
    }

    /// 以 `prev` 开头的全部用户二元组（联想用）。 All user bigrams starting with `prev`, for prediction.
    pub fn bigrams_after(&self, prev: &str) -> Vec<(String, BigramStat)> {
        self.bigrams.iter().filter(|((p, _), _)| p == prev).map(|((_, n), s)| (n.clone(), *s)).collect()
    }

    pub fn bigram(&self, prev: &str, next: &str) -> Option<BigramStat> {
        // 避免为查询分配：常见路径下 bigrams 很小，但仍用 owned key 查 HashMap。
        self.bigrams
            .get(&(prev.to_string(), next.to_string()))
            .copied()
    }

    pub fn has_bigrams(&self) -> bool {
        !self.bigrams.is_empty()
    }

    // ------------------------------------------------------------ trie view

    pub fn child(&self, n: UNodeId, sym: u16) -> Option<UNodeId> {
        let c = &self.nodes[n as usize].children;
        c.binary_search_by_key(&sym, |x| x.0).ok().map(|i| c[i].1)
    }

    pub fn children(&self, n: UNodeId) -> &[(u16, u32)] {
        &self.nodes[n as usize].children
    }

    pub fn entries(&self, n: UNodeId) -> &[UserEntry] {
        &self.nodes[n as usize].entries
    }

    /// Prefix completions stay on the trie path; no full user-dictionary scan per keystroke.
    pub fn prefix_entries(&self, key: &[u16], limit: usize) -> Vec<&UserEntry> {
        let Some(start) = self.find(key) else { return Vec::new() };
        let mut out = Vec::new();
        let mut pending = vec![start];
        let mut budget = 2048;
        while let Some(n) = pending.pop() {
            if budget == 0 { break; }
            budget -= 1;
            out.extend(self.entries(n));
            pending.extend(self.children(n).iter().map(|(_, child)| *child));
        }
        out.sort_by_key(|e| std::cmp::Reverse(e.last));
        out.truncate(limit);
        out
    }

    pub fn find(&self, key: &[u16]) -> Option<UNodeId> {
        key.iter().try_fold(UROOT, |n, &s| self.child(n, s))
    }

    pub fn get(&self, key: &[u16], text: &str) -> Option<&UserEntry> {
        let n = self.find(key)?;
        self.nodes[n as usize]
            .entries
            .iter()
            .find(|e| e.text == text)
    }

    /// 清空全部用户数据。 Wipe all user data.
    pub fn clear(&mut self) -> std::io::Result<()> {
        self.nodes = vec![UNode::default()];
        self.bigrams.clear();
        self.compact()
    }

    /// 导入一个词条（count 取较大值）。 Import an entry, keeping the larger count.
    pub fn import(&mut self, key: &[u16], text: &str, count: u32) {
        if key.is_empty() || !clean(text) {
            return;
        }
        let old = self.get(key, text).map(|e| e.count).unwrap_or(0);
        if count <= old {
            return;
        }
        self.tick += 1;
        let tick = self.tick;
        self.set_entry(key, text, count, tick);
        self.write_line(format!(
            "W\t{}\t{}\t{}\t{}",
            key_str(key),
            text,
            count,
            tick
        ));
    }

    /// 全部词条（导出 / 管理界面用）。 All entries, for export and management UI.
    pub fn all_entries(&self) -> Vec<(Vec<u16>, UserEntry)> {
        let mut out = Vec::new();
        let mut stack = vec![(UROOT, Vec::<u16>::new())];
        while let Some((n, key)) = stack.pop() {
            for e in &self.nodes[n as usize].entries {
                out.push((key.clone(), e.clone()));
            }
            for &(sym, c) in &self.nodes[n as usize].children {
                let mut k = key.clone();
                k.push(sym);
                stack.push((c, k));
            }
        }
        out.sort_by_key(|x| std::cmp::Reverse(x.1.last));
        out
    }
}

impl Drop for UserDict {
    fn drop(&mut self) {
        self.flush();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn persist_and_replay() {
        let dir = std::env::temp_dir().join(format!("weave-ud-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let path = dir.join("user.log");
        {
            let mut d = UserDict::open(&path).unwrap();
            d.learn(&[1, 2], "织文");
            d.learn(&[1, 2], "织文");
            d.learn(&[3], "输");
            d.learn_bigram("织文", "输入法");
            d.learn_bigram("织文", "键盘");
            d.forget(&[3], "输");
            d.forget_bigram("织文", "键盘");
        }
        let d = UserDict::open(&path).unwrap();
        assert_eq!(d.get(&[1, 2], "织文").unwrap().count, 2);
        assert!(d.get(&[3], "输").is_none());
        assert_eq!(d.bigram("织文", "输入法").unwrap().count, 1);
        // 删掉的二元组重放后不再出现。 A forgotten bigram stays gone after a replay.
        assert!(d.bigram("织文", "键盘").is_none());
        assert_eq!(d.bigrams_after("织文").len(), 1);
        assert_eq!(d.tick(), 5);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn preferences_survive_compaction_and_expire_without_changing_frequency() {
        let dir = std::env::temp_dir().join(format!("weave-ud-choice-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let path = dir.join("user.log");
        let mut d = UserDict::open(&path).unwrap();
        for _ in 0..5 { d.learn(&[1], "嗜"); d.select(&[1], "嗜"); }
        assert!(d.preferred(&[1], "嗜"));
        d.compact().unwrap();
        let mut reloaded = UserDict::open(&path).unwrap();
        assert!(reloaded.preferred(&[1], "嗜"));
        assert_eq!(reloaded.get(&[1], "嗜").unwrap().count, 5);
        reloaded.learning = false;
        assert!(!reloaded.preferred(&[1], "嗜"));
        reloaded.learning = true;
        reloaded.tick += CHOICE_WINDOW + 1;
        assert!(!reloaded.preferred(&[1], "嗜"));
        reloaded.forget(&[1], "嗜"); reloaded.flush();
        assert!(UserDict::open(&path).unwrap().choice(&[1]).is_none());
        drop(reloaded); drop(d); let _ = std::fs::remove_dir_all(dir);
    }
}
