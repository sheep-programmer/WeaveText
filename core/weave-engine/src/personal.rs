//! Device-local candidate decisions and short phrases. Explicit records merge by Lamport version;
//! clear records are retained so an older device cannot resurrect a deleted decision.
use serde::{Deserialize, Serialize};
use std::{
    collections::BTreeMap,
    path::{Path, PathBuf},
};

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Record {
    pub value: String,
    pub clock: u64,
    pub device: String,
}
#[derive(Default, Serialize, Deserialize)]
pub struct Personal {
    pub device: String,
    pub clock: u64,
    pub records: BTreeMap<String, Record>,
    #[serde(skip)]
    path: Option<PathBuf>,
}
impl Personal {
    pub fn open(dir: Option<&Path>) -> Self {
        let path = dir.map(|d| d.join("personal.json"));
        let mut p: Self = path
            .as_ref()
            .and_then(|p| std::fs::read(p).ok())
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or_default();
        if p.device.is_empty() {
            let now = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_nanos();
            p.device = format!("{now:x}-{:x}", std::process::id());
        }
        p.path = path;
        p
    }
    pub fn set(&mut self, key: String, value: String) {
        self.clock = self.clock.saturating_add(1);
        self.records.insert(
            key,
            Record {
                value,
                clock: self.clock,
                device: self.device.clone(),
            },
        );
        self.save();
    }
    pub fn update(&mut self, values: BTreeMap<String, String>) {
        for (key, value) in values {
            if self.records.get(&key).is_none_or(|r| r.value != value) {
                self.clock = self.clock.saturating_add(1);
                self.records.insert(
                    key,
                    Record {
                        value,
                        clock: self.clock,
                        device: self.device.clone(),
                    },
                );
            }
        }
        self.save();
    }
    pub fn get(&self, key: &str) -> &str {
        self.records
            .get(key)
            .map(|r| r.value.as_str())
            .unwrap_or("")
    }
    pub fn merge(&mut self, other: &Personal) -> usize {
        let mut changes = 0;
        for (key, r) in &other.records {
            if key.len() > 1024
                || r.value.len()
                    > if key == "hand-samples" {
                        6 * 1024 * 1024
                    } else {
                        32_000
                    }
                || r.device.len() > 96
            {
                continue;
            }
            self.clock = self.clock.max(r.clock);
            if self
                .records
                .get(key)
                .is_none_or(|old| (r.clock, &r.device) > (old.clock, &old.device))
            {
                self.records.insert(key.clone(), r.clone());
                changes += 1;
            }
        }
        self.save();
        changes
    }
    fn save(&self) {
        let Some(path) = &self.path else { return };
        let result = (|| -> std::io::Result<()> {
            if let Some(parent) = path.parent() {
                std::fs::create_dir_all(parent)?;
            }
            let bytes = serde_json::to_vec(self)?;
            let tmp = path.with_extension("json.tmp");
            std::fs::write(&tmp, bytes)?;
            std::fs::rename(tmp, path)
        })();
        if result.is_err() {
            eprintln!("WeaveText: cannot save personal preferences");
        }
    }
}
