//! 按插件 id 持久化的键值配置：`config_dir/<id>.json`，值一律为字符串。
//! Per-plugin string key/value config persisted at `config_dir/<id>.json`.

use std::collections::BTreeMap;
use std::fs;
use std::path::PathBuf;

pub struct ConfigStore {
    path: PathBuf,
    values: BTreeMap<String, String>,
    defaults: BTreeMap<String, String>,
}

impl ConfigStore {
    pub fn open(
        config_dir: &std::path::Path,
        id: &str,
        defaults: Vec<(String, String)>,
    ) -> ConfigStore {
        let path = config_dir.join(format!("{id}.json"));
        let values = fs::read(&path)
            .ok()
            .and_then(|b| serde_json::from_slice::<serde_json::Value>(&b).ok())
            .and_then(|v| v.as_object().cloned())
            .map(|m| {
                m.into_iter()
                    .filter_map(|(k, v)| crate::manifest::scalar_string(&v).map(|s| (k, s)))
                    .collect()
            })
            .unwrap_or_default();
        ConfigStore {
            path,
            values,
            defaults: defaults.into_iter().collect(),
        }
    }

    /// 已设置的值，否则 manifest 的 defaultValue，否则 None。
    /// Stored value, else the manifest defaultValue, else None.
    pub fn get(&self, key: &str) -> Option<String> {
        self.values
            .get(key)
            .or_else(|| self.defaults.get(key))
            .cloned()
    }

    pub fn set(&mut self, key: &str, value: &str) {
        self.values.insert(key.to_string(), value.to_string());
        self.save();
    }

    pub fn remove(&mut self, key: &str) {
        if self.values.remove(key).is_some() {
            self.save();
        }
    }

    /// 写临时文件再改名，避免进程被杀时留下半截 JSON。
    /// Write-then-rename so a killed process never leaves a truncated file.
    fn save(&self) {
        if let Some(dir) = self.path.parent() {
            let _ = fs::create_dir_all(dir);
        }
        let map: serde_json::Map<String, serde_json::Value> = self
            .values
            .iter()
            .map(|(k, v)| (k.clone(), serde_json::Value::String(v.clone())))
            .collect();
        let text = serde_json::to_vec_pretty(&serde_json::Value::Object(map)).unwrap_or_default();
        let tmp = self.path.with_extension("json.tmp");
        if fs::write(&tmp, text).is_ok() {
            let _ = fs::rename(&tmp, &self.path);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn persist_and_defaults() {
        let dir = crate::package::tests::temp_dir("cfg");
        let mut c = ConfigStore::open(&dir, "p", vec![("mode".into(), "manualStop".into())]);
        assert_eq!(c.get("mode").as_deref(), Some("manualStop"));
        assert_eq!(c.get("x"), None);
        c.set("mode", "autoVAD");
        c.set("k", "中文");
        let c2 = ConfigStore::open(&dir, "p", vec![("mode".into(), "manualStop".into())]);
        assert_eq!(c2.get("mode").as_deref(), Some("autoVAD"));
        assert_eq!(c2.get("k").as_deref(), Some("中文"));
        let mut c3 = c2;
        c3.remove("mode");
        assert_eq!(c3.get("mode").as_deref(), Some("manualStop"));
        fs::remove_dir_all(&dir).unwrap();
    }
}
