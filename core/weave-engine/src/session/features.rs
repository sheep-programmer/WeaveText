use super::*;
use serde_json::{json, Value};

impl Engine {
    fn policy_scope(&self, c: &Cand) -> String {
        match &c.action {
            Action::Pinyin(p) if p.text.chars().any(|c| c.is_ascii_alphabetic()) => {
                format!("mixed:{}", self.rest_raw().to_ascii_lowercase())
            }
            Action::Pinyin(p) => format!("pinyin:{}", spell_key(&p.key)),
            Action::Table { .. } if self.schema == Schema::Hand => {
                use std::hash::{Hash, Hasher};
                let mut hash = std::collections::hash_map::DefaultHasher::new();
                self.hand_strokes.len().hash(&mut hash);
                for stroke in &self.hand_strokes {
                    for (x, y) in weave_dict::hand::resample(stroke) {
                        ((x * 4.0).round() as i32, (y * 4.0).round() as i32).hash(&mut hash);
                    }
                }
                format!("hand:{:x}", hash.finish())
            }
            Action::Table { .. } if self.predicting => {
                format!("predict:{}", self.last_word.as_deref().unwrap_or(""))
            }
            _ => format!("{}:{}", self.schema.key(), self.raw.to_ascii_lowercase()),
        }
    }
    fn policy_key(&self, c: &Cand) -> String {
        let text = match &c.action {
            Action::Pinyin(p) => &p.text,
            Action::Table { text } => text,
        };
        format!("policy:{}", json!([self.policy_scope(c), text]))
    }
    fn policy_mode(&self, c: &Cand) -> &str {
        let text = match &c.action {
            Action::Pinyin(p) => &p.text,
            Action::Table { text } => text,
        };
        let pin = format!("pin:{}", self.policy_scope(c));
        if let Some(record) = self.personal.records.get(&pin) {
            if record.value == *text {
                return "pin";
            }
            return if self.personal.get(&self.policy_key(c)) == "down" {
                "down"
            } else {
                ""
            };
        }
        self.personal.get(&self.policy_key(c))
    }
    pub(super) fn apply_personal_policies(&mut self) {
        if !self.user_pinyin.learning {
            return;
        }
        let total = self.graph_len();
        let mut scored: Vec<_> = self
            .cands
            .iter()
            .cloned()
            .enumerate()
            .map(|(i, mut c)| {
                let mode = self.policy_mode(&c);
                let partial = matches!(&c.action,Action::Pinyin(p) if p.end<total);
                let raw = self.schema == Schema::English && i == 0;
                let score = if raw {
                    -1
                } else if mode == "pin" {
                    0
                } else if mode == "down" {
                    2
                } else {
                    1
                };
                if mode == "pin" {
                    c.view.comment = if c.view.comment.is_empty() {
                        "已固定".into()
                    } else {
                        format!("{} · 已固定", c.view.comment)
                    };
                }
                ((partial, score, i), c)
            })
            .collect();
        scored.sort_by_key(|(k, _)| *k);
        self.cands = scored.into_iter().map(|(_, c)| c).collect();
        if let Some(template) = self
            .personal
            .records
            .get(&format!("snippet:{}", self.rest_raw().to_ascii_lowercase()))
            .map(|r| r.value.clone())
            .filter(|s| !s.is_empty())
        {
            let text = expand(&template, self.options.utc_offset_min);
            let c = Cand {
                view: CandidateView { cloud: false,
                    text: text.clone(),
                    comment: "快捷短语".into(),
                    user: false,
                },
                action: Action::Table { text },
            };
            self.cands.insert(0, c);
        }
    }
    pub fn features(&mut self, cmd: &Value) -> Value {
        match cmd["op"].as_str().unwrap_or("") {
            "policy" => {
                let Some(c) = cmd["index"]
                    .as_u64()
                    .and_then(|i| self.cands.get(i as usize))
                    .cloned()
                else {
                    return json!({"ok":false});
                };
                if cmd["text"].as_str().is_some_and(|t| t != c.view.text) {
                    return json!({"ok":false,"error":"candidate changed"});
                }
                let key = self.policy_key(&c);
                if let Some(mode) = cmd["mode"].as_str() {
                    if !self.user_pinyin.learning || !["pin", "down", ""].contains(&mode) {
                        return json!({"ok":false});
                    }
                    if mode == "pin" {
                        let scope = self.policy_scope(&c);
                        let keys: Vec<_> = self
                            .cands
                            .iter()
                            .filter(|other| self.policy_scope(other) == scope)
                            .map(|other| self.policy_key(other))
                            .filter(|k| self.personal.get(k) == "pin")
                            .collect();
                        for key in keys {
                            self.personal.set(key, String::new());
                        }
                    }
                    if mode == "pin" {
                        if let Action::Pinyin(p) = &c.action {
                            if p.text.chars().all(is_cjk) {
                                self.user_pinyin.import(&p.key, &p.text, 1);
                                self.user_pinyin.flush();
                            }
                        }
                        if self.schema == Schema::Hand {
                            if let Action::Table { text } = &c.action {
                                if text.chars().count() == 1 {
                                    if let Some(m) = self.hand_models() {
                                        m.correct(text.chars().next().unwrap(), &self.hand_strokes);
                                    }
                                    self.save_hand_samples();
                                }
                            }
                        }
                    }
                    let scope = format!("pin:{}", self.policy_scope(&c));
                    let text = match &c.action {
                        Action::Pinyin(p) => p.text.clone(),
                        Action::Table { text } => text.clone(),
                    };
                    if mode == "pin" {
                        self.personal.set(scope, text);
                    } else if self.personal.get(&scope) == text {
                        self.personal.set(scope, String::new());
                    }
                    self.personal.set(
                        key,
                        if mode == "pin" {
                            String::new()
                        } else {
                            mode.into()
                        },
                    );
                    self.refresh();
                    json!({"ok":true})
                } else {
                    json!({"ok":true,"mode":self.policy_mode(&c)})
                }
            }
            "reconvert" => json!({"ok":cmd["text"].as_str().is_some_and(|s|self.reconvert(s))}),
            "snippets" => {
                json!({"ok":true,"items":self.personal.records.iter().filter_map(|(k,r)|k.strip_prefix("snippet:").filter(|_|!r.value.is_empty()).map(|code|json!({"code":code,"text":r.value}))).collect::<Vec<_>>()})
            }
            "setSnippet" => {
                let (code, text) = (
                    cmd["code"].as_str().unwrap_or(""),
                    cmd["text"].as_str().unwrap_or(""),
                );
                if code.is_empty()
                    || code.len() > 24
                    || !code.bytes().all(|b| b.is_ascii_alphabetic())
                    || text.len() > 16_000
                {
                    return json!({"ok":false});
                }
                self.personal.set(
                    format!("snippet:{}", code.to_ascii_lowercase()),
                    text.into(),
                );
                json!({"ok":true})
            }
            "exportPersonal" => {
                self.capture_personal_words();
                json!({"ok":true,"data":json!({"format":"weavetext-personal-1","personal":self.personal}).to_string()})
            }
            "importPersonal" => {
                if self.is_composing() {
                    return json!({"ok":false,"error":"请先完成当前输入，再合并个人资料"});
                }
                let data = cmd["data"].as_str().unwrap_or("");
                if data.len() > 8 * 1024 * 1024 {
                    return json!({"ok":false,"error":"profile too large"});
                }
                let Ok(bundle) = serde_json::from_str::<Value>(data) else {
                    return json!({"ok":false,"error":"invalid profile"});
                };
                if bundle["format"] != "weavetext-personal-1" {
                    return json!({"ok":false,"error":"unsupported profile"});
                }
                let Ok(other) =
                    serde_json::from_value::<crate::personal::Personal>(bundle["personal"].clone())
                else {
                    return json!({"ok":false});
                };
                if other.records.len() > 50_000 {
                    return json!({"ok":false});
                }
                for key in other.records.keys().filter_map(|k| k.strip_prefix("word:")) {
                    let Ok((lang, code, _)) =
                        serde_json::from_str::<(String, Vec<u16>, String)>(key)
                    else {
                        return json!({"ok":false,"error":"invalid word record"});
                    };
                    if !["pinyin", "english"].contains(&lang.as_str())
                        || code.is_empty()
                        || code.len() > 64
                        || code.iter().any(|id| {
                            *id == 0
                                || (lang == "pinyin" && *id as usize > syllable::count())
                                || (lang == "english" && *id > 26)
                        })
                    {
                        return json!({"ok":false,"error":"invalid reading code"});
                    }
                }
                for key in other
                    .records
                    .keys()
                    .filter_map(|k| k.strip_prefix("choice:"))
                {
                    let Ok((lang, code)) = serde_json::from_str::<(String, Vec<u16>)>(key) else {
                        return json!({"ok":false,"error":"invalid choice record"});
                    };
                    if !["pinyin", "english"].contains(&lang.as_str())
                        || code.is_empty()
                        || code.len() > 64
                        || code.iter().any(|id| {
                            *id == 0
                                || (lang == "pinyin" && *id as usize > syllable::count())
                                || (lang == "english" && *id > 26)
                        })
                    {
                        return json!({"ok":false,"error":"invalid reading code"});
                    }
                }
                self.capture_personal_words();
                let old_choices: std::collections::BTreeMap<_, _> = self
                    .personal
                    .records
                    .iter()
                    .filter(|(k, _)| k.starts_with("choice:"))
                    .map(|(k, r)| (k.clone(), (r.clock, r.device.clone())))
                    .collect();
                let changes = self.personal.merge(&other);
                for (key, r) in &self.personal.records {
                    let Some(raw) = key.strip_prefix("word:") else {
                        continue;
                    };
                    let Ok((language, code, text)) =
                        serde_json::from_str::<(String, Vec<u16>, String)>(raw)
                    else {
                        continue;
                    };
                    if code.is_empty()
                        || code.len() > 64
                        || text.len() > 512
                        || text.contains(['\t', '\n', '\r'])
                    {
                        continue;
                    }
                    let user = if language == "pinyin" {
                        &mut self.user_pinyin
                    } else if language == "english" {
                        &mut self.user_english
                    } else {
                        continue;
                    };
                    if r.value.is_empty() {
                        user.forget(&code, &text);
                        continue;
                    }
                    let Ok(value) = serde_json::from_str::<Value>(&r.value) else {
                        continue;
                    };
                    let count = value["count"].as_u64().unwrap_or(1).min(u32::MAX as u64) as u32;
                    user.import(&code, &text, count.max(1));
                }
                for (key, r) in &self.personal.records {
                    let Some(raw) = key.strip_prefix("choice:") else {
                        continue;
                    };
                    if old_choices.get(key) == Some(&(r.clock, r.device.clone())) {
                        continue;
                    }
                    let Ok((language, code)) = serde_json::from_str::<(String, Vec<u16>)>(raw)
                    else {
                        continue;
                    };
                    let user = if language == "pinyin" {
                        &mut self.user_pinyin
                    } else if language == "english" {
                        &mut self.user_english
                    } else {
                        continue;
                    };
                    let choice = serde_json::from_str::<Value>(&r.value)
                        .ok()
                        .and_then(|value| {
                            let text = value["text"].as_str()?.to_string();
                            let repeats = value["repeats"].as_u64()?.min(u32::MAX as u64) as u32;
                            user.get(&code, &text)?;
                            Some(crate::userdict::RecentChoice {
                                text,
                                repeats,
                                last: user.tick(),
                            })
                        });
                    user.restore_choice(&code, choice);
                }
                if let Some(m) = &self.hand {
                    let data = self.personal.get("hand-samples");
                    if data.is_empty() {
                        m.restore_samples(Vec::new());
                    } else if let Ok(samples) = serde_json::from_str(data) {
                        m.restore_samples(samples);
                    }
                }
                self.flush();
                self.refresh();
                json!({"ok":true,"changes":changes})
            }
            "voiceWords" => {
                let mut words = self
                    .user_pinyin
                    .all_entries()
                    .into_iter()
                    .chain(self.user_english.all_entries())
                    .map(|(_, e)| e)
                    .collect::<Vec<_>>();
                words.sort_by_key(|e| std::cmp::Reverse(e.count));
                let mut seen = std::collections::HashSet::new();
                json!({"ok":true,"words":words.into_iter().filter(|e| e.text.chars().count()>=2 && e.text.chars().count()<=24 && seen.insert(e.text.clone())).take(256).map(|e|e.text).collect::<Vec<_>>()})
            }
            "setHandLine" => {
                let value = cmd["on"].as_bool().unwrap_or(false).to_string();
                if self.personal.get("hand-line") != value {
                    self.personal.set("hand-line".into(), value.clone());
                    if let Some(m) = &self.hand {
                        m.set_line_mode(value == "true");
                    }
                    self.hand_words.clear();
                    self.hand_strokes.clear();
                    self.refresh();
                }
                json!({"ok":true})
            }
            "clearHand" => {
                self.personal.set("hand-samples".into(), String::new());
                let keys: Vec<_> = self
                    .personal
                    .records
                    .keys()
                    .filter(|k| k.starts_with("pin:hand:") || k.starts_with("policy:[\"hand:"))
                    .cloned()
                    .collect();
                for key in keys {
                    self.personal.set(key, String::new());
                }
                if let Some(m) = &self.hand {
                    m.restore_samples(Vec::new());
                }
                self.refresh();
                json!({"ok":true})
            }
            "handApply" => {
                let Ok(strokes) = serde_json::from_value(cmd["strokes"].clone()) else {
                    return json!({"ok":false});
                };
                let Ok(codes) = serde_json::from_value::<Vec<u32>>(cmd["codes"].clone()) else {
                    return json!({"ok":false});
                };
                let cands = codes.into_iter().filter_map(char::from_u32).collect();
                json!({"ok":self.hand_apply(strokes,cands)})
            }
            "handInput" => {
                let strokes = serde_json::from_value(cmd["strokes"].clone()).unwrap_or_default();
                self.set_schema(Schema::Hand);
                json!({"ok":self.hand_input(strokes)})
            }
            _ => json!({"ok":false,"error":"unknown operation"}),
        }
    }
    fn capture_personal_words(&mut self) {
        let mut values = std::collections::BTreeMap::new();
        for (language, user) in [
            ("pinyin", &self.user_pinyin),
            ("english", &self.user_english),
        ] {
            for (code, e) in user.all_entries() {
                values.insert(
                    format!("word:{}", json!([language, code, e.text])),
                    json!({"count":e.count}).to_string(),
                );
                if let Some(choice) = user.choice(&code) {
                    values.insert(
                        format!("choice:{}", json!([language, code])),
                        json!({"text":choice.text,"repeats":choice.repeats}).to_string(),
                    );
                }
            }
        }
        for key in self
            .personal
            .records
            .keys()
            .filter(|k| k.starts_with("word:") || k.starts_with("choice:"))
            .cloned()
            .collect::<Vec<_>>()
        {
            values.entry(key).or_default();
        }
        self.personal.update(values);
    }
    pub(super) fn save_hand_samples(&mut self) {
        if !self.user_pinyin.learning {
            return;
        }
        if let Some(models) = &self.hand {
            if let Ok(data) = serde_json::to_string(&models.samples()) {
                self.personal.set("hand-samples".into(), data);
            }
        }
    }
    pub fn reconvert(&mut self, text: &str) -> bool {
        if !self.user_pinyin.learning || text.is_empty() || text.chars().count() > 8 {
            return false;
        }
        if text.chars().all(|c| c.is_ascii_alphabetic() || c == '\'') {
            self.set_schema(Schema::English);
            self.clear();
            self.raw = text.into();
            self.refresh();
            return true;
        }
        if !text.chars().all(is_cjk) {
            return false;
        }
        let Some(lex) = &self.pinyin else {
            return false;
        };
        let readings = self
            .readings
            .get_or_insert_with(|| crate::predict::Readings::build(lex));
        let mut keys = readings.keys_for(text, 64);
        keys.sort_by_key(|key| {
            !lex.find(key)
                .is_some_and(|n| lex.find_entry(n, key, text).is_some())
        });
        let Some(key) = keys.into_iter().next() else {
            return false;
        };
        self.set_schema(Schema::Pinyin);
        self.clear();
        self.raw = spell_key(&key).replace(' ', "'");
        self.refresh();
        true
    }
}
fn expand(template: &str, offset: i32) -> String {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64;
    let options = crate::special::date_candidates("rq", now, offset);
    let date = options.first().map(|(text, _)| text.as_str()).unwrap_or("");
    let times = crate::special::date_candidates("sj", now, offset);
    let time = times.first().map(|(text, _)| text.as_str()).unwrap_or("");
    template.replace("{date}", date).replace("{time}", time)
}
