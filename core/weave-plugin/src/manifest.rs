//! manifest.yaml 解析（YAML → serde_json::Value → 结构体）。
//! manifest.yaml parsing (YAML → serde_json::Value → struct).

use serde_json::{Map, Value};
use yaml_rust2::{Yaml, YamlLoader};

#[derive(Clone, Debug, Default)]
pub struct Manifest {
    pub id: String,
    pub name: String,
    pub description: String,
    pub version: String,
    /// `type` 字段，如 `speech`。The `type` field, e.g. `speech`.
    pub kind: String,
    pub entry: String,
    /// 图标：资源文件名（`icon.png`）或直接是一个字/emoji。
    /// Icon: a resource file name, or literally a glyph/emoji.
    pub icon: Option<String>,
    pub min_host_version: Option<String>,
    pub sdk_version: Option<String>,
    /// configSchema 数组原样转成 JSON。configSchema array as JSON.
    pub config_schema: Value,
    pub network_hosts: Vec<String>,
    pub allow_custom_hosts: bool,
    pub permissions: Vec<String>,
    /// 整份 manifest 的 JSON 形式。The whole manifest as JSON.
    pub raw: Value,
}

fn yaml_to_json(y: &Yaml) -> Value {
    match y {
        Yaml::Real(s) => s
            .parse::<f64>()
            .ok()
            .and_then(serde_json::Number::from_f64)
            .map(Value::Number)
            .unwrap_or_else(|| Value::String(s.clone())),
        Yaml::Integer(i) => Value::from(*i),
        Yaml::String(s) => Value::String(s.clone()),
        Yaml::Boolean(b) => Value::Bool(*b),
        Yaml::Array(a) => Value::Array(a.iter().map(yaml_to_json).collect()),
        Yaml::Hash(h) => {
            let mut m = Map::new();
            for (k, v) in h {
                let key = match k {
                    Yaml::String(s) => s.clone(),
                    Yaml::Integer(i) => i.to_string(),
                    Yaml::Real(s) => s.clone(),
                    Yaml::Boolean(b) => b.to_string(),
                    _ => continue,
                };
                m.insert(key, yaml_to_json(v));
            }
            Value::Object(m)
        }
        Yaml::Alias(_) | Yaml::Null | Yaml::BadValue => Value::Null,
    }
}

/// 标量统一成字符串（`version: 1.0` 会被 YAML 当成浮点数，`defaultValue: true` 当成布尔）。
/// Scalars as strings (YAML may type `1.0` as float or `true` as bool).
pub fn scalar_string(v: &Value) -> Option<String> {
    match v {
        Value::String(s) => Some(s.clone()),
        Value::Number(n) => Some(n.to_string()),
        Value::Bool(b) => Some(b.to_string()),
        _ => None,
    }
}

fn string_list(v: Option<&Value>) -> Vec<String> {
    v.and_then(Value::as_array)
        .map(|a| a.iter().filter_map(scalar_string).collect())
        .unwrap_or_default()
}

impl Manifest {
    pub fn parse(text: &str) -> Result<Manifest, String> {
        let docs = YamlLoader::load_from_str(text).map_err(|e| format!("manifest.yaml: {e}"))?;
        let raw = docs.first().map(yaml_to_json).unwrap_or(Value::Null);
        if !raw.is_object() {
            return Err("manifest.yaml: top level must be a mapping".into());
        }
        let s = |k: &str| raw.get(k).and_then(scalar_string);
        let id = s("id")
            .filter(|v| !v.is_empty())
            .ok_or("manifest.yaml: missing id")?;
        if id.contains(['/', '\\']) || id.starts_with('.') {
            return Err(format!("manifest.yaml: invalid id {id}"));
        }
        let mut config_schema = raw
            .get("configSchema")
            .cloned()
            .unwrap_or(Value::Array(vec![]));
        // defaultValue 统一成字符串，与 host.config.get 的返回类型一致
        if let Some(items) = config_schema.as_array_mut() {
            for it in items.iter_mut() {
                if let Some(obj) = it.as_object_mut() {
                    if let Some(dv) = obj.get("defaultValue").and_then(scalar_string) {
                        obj.insert("defaultValue".into(), Value::String(dv));
                    }
                }
            }
        } else {
            config_schema = Value::Array(vec![]);
        }
        let network = raw.get("network");
        Ok(Manifest {
            name: s("name").unwrap_or_else(|| id.clone()),
            description: s("description").unwrap_or_default(),
            version: s("version").unwrap_or_default(),
            kind: s("type").unwrap_or_default(),
            entry: s("entry").unwrap_or_else(|| "main.lua".into()),
            icon: s("icon").filter(|v| !v.is_empty()),
            min_host_version: s("minHostVersion"),
            sdk_version: s("sdkVersion"),
            network_hosts: string_list(network.and_then(|n| n.get("hosts"))),
            allow_custom_hosts: network
                .and_then(|n| n.get("allowCustomHosts"))
                .and_then(Value::as_bool)
                .unwrap_or(false),
            permissions: string_list(raw.get("permissions")),
            config_schema,
            id,
            raw,
        })
    }

    /// configSchema 里各 key 的 defaultValue。Default values from configSchema.
    pub fn config_defaults(&self) -> Vec<(String, String)> {
        let mut out = Vec::new();
        if let Some(items) = self.config_schema.as_array() {
            for it in items {
                if let (Some(k), Some(v)) = (
                    it.get("key").and_then(scalar_string),
                    it.get("defaultValue").and_then(scalar_string),
                ) {
                    out.push((k, v));
                }
            }
        }
        out
    }

    /// 网络白名单：声明的域名（支持 `*.example.com`），或声明了 network_unrestricted /
    /// allowCustomHosts（这两者本应由用户授权，宿主 UI 负责把关）。
    /// Network allow-list: declared hosts (with `*.` wildcards), or unrestricted when the plugin
    /// declares `network_unrestricted` / `allowCustomHosts` (the UI is expected to gate those).
    pub fn host_allowed(&self, host: &str) -> bool {
        if self.allow_custom_hosts || self.permissions.iter().any(|p| p == "network_unrestricted") {
            return true;
        }
        let host = host.trim_end_matches('.').to_ascii_lowercase();
        self.network_hosts.iter().any(|pat| {
            let pat = pat.trim().to_ascii_lowercase();
            if let Some(suffix) = pat.strip_prefix("*.") {
                host.len() > suffix.len() + 1
                    && host.ends_with(suffix)
                    && host.as_bytes()[host.len() - suffix.len() - 1] == b'.'
            } else {
                host == pat
            }
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = r#"
# comment
id: com.example.asr
name: 示例
icon: "icon.png"
description: >-
  第一行
  第二行
version: 1.0
type: speech
network:
  hosts:
    - api.example.com
    - "*.cdn.example.com"
configSchema:
  - key: smart
    type: switch
    defaultValue: true
  - key: mode
    defaultValue: manualStop
    options: [a, b]
"#;

    #[test]
    fn parse_sample() {
        let m = Manifest::parse(SAMPLE).unwrap();
        assert_eq!(m.id, "com.example.asr");
        assert_eq!(m.name, "示例");
        assert_eq!(m.description, "第一行 第二行");
        assert_eq!(m.version, "1.0");
        assert_eq!(m.kind, "speech");
        assert_eq!(m.entry, "main.lua");
        assert_eq!(m.icon.as_deref(), Some("icon.png"));
        assert_eq!(
            m.config_defaults(),
            vec![
                ("smart".to_string(), "true".to_string()),
                ("mode".into(), "manualStop".into())
            ]
        );
        assert!(m.host_allowed("api.example.com"));
        assert!(m.host_allowed("a.cdn.example.com"));
        assert!(!m.host_allowed("cdn.example.com"));
        assert!(!m.host_allowed("evil.com"));
        assert!(!m.host_allowed("xcdn.example.com"));
    }

    #[test]
    fn missing_id_rejected() {
        assert!(Manifest::parse("name: x").is_err());
        assert!(Manifest::parse("id: ../x").is_err());
    }
}
