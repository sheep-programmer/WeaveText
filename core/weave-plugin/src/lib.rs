//! 织文输入法插件宿主：加载织文 Lua 插件格式的插件（`manifest.yaml` + `main.lua` + `resources/`
//! + 可选 `libs/`，打包为 `.xipk` zip）。架构与 host API 见 `docs/plugin-host.md`。
//!
//! WeaveText plugin host for the WeaveText Lua 5.4 plugin format. See
//! `docs/plugin-host.md` for the architecture and the host API reference.

mod config;
mod crypto;
mod json;
mod manifest;
mod net;
mod package;
mod runtime;
mod timer;

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::Duration;

pub use manifest::Manifest;

/// 只读取 `.xipk` 的 manifest，不安装（用于导入前确认）。 Read a package manifest without installing.
pub fn inspect_package(xipk: &std::path::Path) -> Result<Manifest, String> {
    package::read_xipk_manifest(xipk)
}
pub use runtime::{
    SpeechSession, HOST_VERSION, LOG_DEBUG, LOG_ERROR, LOG_INFO, LOG_WARN, SDK_VERSION,
    STOP_TIMEOUT,
};

use config::ConfigStore;
use runtime::{Instance, InstanceSpec};

/// 宿主级日志回调：(插件 id, 级别, 消息)。Host-level log sink: (plugin id, level, message).
pub type Logger = Arc<dyn Fn(&str, u8, &str) + Send + Sync>;

#[derive(Clone, Debug)]
pub struct PluginInfo {
    pub id: String,
    pub name: String,
    pub description: String,
    pub version: String,
    /// manifest 的 `type`，如 `speech`。The manifest `type`, e.g. `speech`.
    pub kind: String,
    /// `resources/<icon>` 的 PNG 字节（icon 是文件名时）。PNG bytes when `icon` names a file.
    pub icon_png: Option<Vec<u8>>,
    /// icon 不是文件而是一个字/emoji 时。When `icon` is a glyph instead of a file.
    pub icon_text: Option<String>,
    /// manifest.configSchema 的 JSON 数组（defaultValue 已统一为字符串）。
    pub config_schema_json: String,
    pub path: PathBuf,
}

/// 语音结果回调。全部在插件线程上调用，实现方应尽快返回（例如转投 UI 线程）。
/// Speech callbacks, all invoked on the plugin thread; return quickly (e.g. post to the UI thread).
pub trait SpeechListener: Send + Sync {
    fn on_partial(&self, text: &str);
    fn on_final(&self, text: &str);
    /// 旧文本仍原样停在光标前时才替换，否则丢弃（可能在 on_end 之后到达）。
    /// Replace only if `old` is still right before the cursor; may arrive after on_end.
    fn on_replace(&self, old: &str, new: &str);
    fn on_error(&self, msg: &str);
    /// 每个会话恰好一次。Exactly once per session.
    fn on_end(&self);
    /// level: 0 debug / 1 info / 2 warn / 3 error
    fn on_log(&self, level: u8, msg: &str);
}

struct Entry {
    info: PluginInfo,
    manifest: Arc<Manifest>,
    config: Arc<Mutex<ConfigStore>>,
    instance: Option<Instance>,
    /// 经 add_path 登记、不在 plugins_dir 里的插件；scan 不会移除它。
    external: bool,
}

pub struct PluginManager {
    plugins_dir: PathBuf,
    config_dir: PathBuf,
    plugins: BTreeMap<String, Entry>,
    logger: Option<Logger>,
    notify: Option<Arc<dyn Fn(&str) + Send + Sync>>,
}

impl PluginManager {
    pub fn new(plugins_dir: impl Into<PathBuf>, config_dir: impl Into<PathBuf>) -> PluginManager {
        PluginManager {
            plugins_dir: plugins_dir.into(),
            config_dir: config_dir.into(),
            plugins: BTreeMap::new(),
            logger: None,
            notify: None,
        }
    }

    /// 没有活动会话时插件日志的去处（之后新建的实例生效）。
    /// Where plugin logs go besides the session listener (applies to instances created later).
    pub fn set_logger(&mut self, logger: Logger) {
        self.logger = Some(logger);
    }

    /// `host.sync.notifyChanged()` 的处理函数，参数为插件 id。Handler for host.sync.notifyChanged().
    pub fn set_sync_notifier(&mut self, f: Arc<dyn Fn(&str) + Send + Sync>) {
        self.notify = Some(f);
    }

    /// 扫描 plugins_dir：已解包的目录直接登记；`.xipk` 若尚未安装或版本不同则解包到 `<id>/`。
    /// Scan plugins_dir: unpacked dirs are registered; `.xipk` files are unpacked when new or changed.
    pub fn scan(&mut self) -> Vec<PluginInfo> {
        let mut seen = Vec::new();
        let mut xipks = Vec::new();
        if let Ok(rd) = fs::read_dir(&self.plugins_dir) {
            let mut paths: Vec<PathBuf> = rd.flatten().map(|e| e.path()).collect();
            paths.sort();
            for p in paths {
                let hidden = p
                    .file_name()
                    .and_then(|n| n.to_str())
                    .is_some_and(|n| n.starts_with('.'));
                if hidden {
                    continue;
                }
                if p.is_dir() {
                    match package::read_dir_manifest(&p) {
                        Ok(m) => seen.push(self.register(m, p, false).id),
                        Err(e) => self.log_host(&format!("skip {}: {e}", p.display())),
                    }
                } else if p
                    .extension()
                    .is_some_and(|x| x.eq_ignore_ascii_case("xipk"))
                {
                    xipks.push(p);
                }
            }
        }
        for x in xipks {
            let m = match package::read_xipk_manifest(&x) {
                Ok(m) => m,
                Err(e) => {
                    self.log_host(&format!("skip {}: {e}", x.display()));
                    continue;
                }
            };
            let current = self
                .plugins
                .get(&m.id)
                .is_some_and(|e| e.info.version == m.version && !e.external);
            if current {
                continue;
            }
            match self.install(&x) {
                Ok(info) => seen.push(info.id),
                Err(e) => self.log_host(&format!("install {}: {e}", x.display())),
            }
        }
        self.plugins.retain(|id, e| e.external || seen.contains(id));
        self.list()
    }

    /// 解包 `.xipk` 到 `plugins_dir/<id>/` 并登记（同 id 的旧实例会被卸下）。
    /// Unpack a `.xipk` into `plugins_dir/<id>/` and register it (an old instance is shut down).
    pub fn install(&mut self, xipk: &Path) -> Result<PluginInfo, String> {
        let m = package::read_xipk_manifest(xipk)?;
        if let Some(e) = self.plugins.get_mut(&m.id) {
            e.instance = None;
        }
        let (m, dir) = package::install_xipk(xipk, &self.plugins_dir)?;
        Ok(self.register(m, dir, false))
    }

    /// 登记任意位置的插件：目录原地登记，`.xipk` 则安装到 plugins_dir。
    /// Register a plugin from anywhere: directories in place, `.xipk` files get installed.
    pub fn add_path(&mut self, path: &Path) -> Result<PluginInfo, String> {
        if path.is_dir() {
            let m = package::read_dir_manifest(path)?;
            let external = !path.starts_with(&self.plugins_dir);
            Ok(self.register(m, path.to_path_buf(), external))
        } else {
            self.install(path)
        }
    }

    /// 卸载：停掉实例，删除解包目录（仅限 plugins_dir 内）与配置文件。
    /// Uninstall: shut the instance down, delete its unpacked dir (inside plugins_dir only) and config.
    pub fn uninstall(&mut self, id: &str) -> Result<(), String> {
        let e = self
            .plugins
            .remove(id)
            .ok_or_else(|| format!("no such plugin: {id}"))?;
        drop(e.instance);
        if !e.external && e.info.path.starts_with(&self.plugins_dir) {
            fs::remove_dir_all(&e.info.path)
                .map_err(|err| format!("{}: {err}", e.info.path.display()))?;
        }
        let _ = fs::remove_file(self.config_dir.join(format!("{id}.json")));
        let _ = fs::remove_dir_all(self.config_dir.join(format!("{id}.files")));
        Ok(())
    }

    pub fn list(&self) -> Vec<PluginInfo> {
        self.plugins.values().map(|e| e.info.clone()).collect()
    }

    pub fn manifest(&self, id: &str) -> Option<Arc<Manifest>> {
        self.plugins.get(id).map(|e| e.manifest.clone())
    }

    pub fn get_config(&self, id: &str, key: &str) -> Option<String> {
        self.plugins.get(id)?.config.lock().unwrap().get(key)
    }

    pub fn set_config(&mut self, id: &str, key: &str, value: &str) {
        if let Some(e) = self.plugins.get(id) {
            e.config.lock().unwrap().set(key, value);
        }
    }

    pub fn remove_config(&mut self, id: &str, key: &str) {
        if let Some(e) = self.plugins.get(id) {
            e.config.lock().unwrap().remove(key);
        }
    }

    /// 提前建好实例（加载 Lua、调 initialize()），让第一次 start 不用等。
    /// Create the instance ahead of time (load Lua, run initialize()) so the first start is instant.
    pub fn preload(&mut self, id: &str) -> Result<(), String> {
        self.instance(id).map(|_| ())
    }

    /// 停掉实例（下次使用时重新加载）。Shut an instance down (it reloads on next use).
    pub fn unload(&mut self, id: &str) {
        if let Some(e) = self.plugins.get_mut(id) {
            e.instance = None;
        }
    }

    /// 开始一次语音会话。同一实例可反复 start/stop；新会话会先结束仍在进行的旧会话。
    /// Start a speech session. Instances are reused; a new session ends any running one first.
    pub fn start_speech(
        &mut self,
        id: &str,
        listener: Arc<dyn SpeechListener>,
    ) -> Result<SpeechSession, String> {
        let kind = self
            .plugins
            .get(id)
            .map(|e| e.info.kind.clone())
            .ok_or_else(|| format!("no such plugin: {id}"))?;
        if kind != "speech" {
            return Err(format!("{id} is a {kind} plugin, not speech"));
        }
        Ok(self.instance(id)?.start_speech(listener))
    }

    /// 通用调用：`plugin.<method>(args...)`，参数与返回值走 JSON（给非语音类插件用）。
    /// 阻塞直到插件线程返回或超时。
    ///
    /// Generic call `plugin.<method>(args...)` with JSON-encoded args/result (non-speech plugins).
    /// Blocks until the plugin thread answers or the timeout expires.
    pub fn call(
        &mut self,
        id: &str,
        method: &str,
        args_json: &str,
        timeout: Duration,
    ) -> Result<String, String> {
        let args = match serde_json::from_str::<serde_json::Value>(args_json)
            .map_err(|e| e.to_string())?
        {
            serde_json::Value::Array(a) => a,
            serde_json::Value::Null => vec![],
            other => vec![other],
        };
        self.instance(id)?
            .call(method, args, timeout)
            .map(|v| v.to_string())
    }

    /// 插件是否已配置好（调插件的 `isConfigured()`；未定义视为 true）。会按需加载实例。
    /// Whether the plugin is ready to use (its `isConfigured()`; true if undefined). Loads the instance.
    pub fn is_configured(&mut self, id: &str) -> bool {
        match self.instance(id) {
            Ok(inst) => inst.is_configured(Duration::from_secs(5)),
            Err(_) => false,
        }
    }

    fn instance(&mut self, id: &str) -> Result<&Instance, String> {
        let files_dir = self.config_dir.join(format!("{id}.files"));
        let logger = self.logger.clone();
        let notify = self.notify.clone();
        let e = self
            .plugins
            .get_mut(id)
            .ok_or_else(|| format!("no such plugin: {id}"))?;
        if e.instance.is_none() {
            e.instance = Some(Instance::spawn(InstanceSpec {
                id: id.to_string(),
                dir: e.info.path.clone(),
                manifest: e.manifest.clone(),
                config: e.config.clone(),
                files_dir,
                logger,
                notify,
            }));
        }
        Ok(e.instance.as_ref().unwrap())
    }

    fn register(&mut self, m: Manifest, dir: PathBuf, external: bool) -> PluginInfo {
        let (icon_png, icon_text) = match &m.icon {
            Some(icon) => match package::safe_relative(icon).map(|r| dir.join("resources").join(r))
            {
                Some(p) if p.is_file() => (fs::read(p).ok(), None),
                _ if icon.chars().count() <= 4 && !icon.contains('.') => (None, Some(icon.clone())),
                _ => (None, None),
            },
            None => (None, None),
        };
        let info = PluginInfo {
            id: m.id.clone(),
            name: m.name.clone(),
            description: m.description.clone(),
            version: m.version.clone(),
            kind: m.kind.clone(),
            icon_png,
            icon_text,
            config_schema_json: m.config_schema.to_string(),
            path: dir,
        };
        if let Some(e) = self.plugins.get_mut(&m.id) {
            if e.info.path == info.path && e.info.version == info.version {
                e.info = info.clone();
                return info;
            }
        }
        let config = Arc::new(Mutex::new(ConfigStore::open(
            &self.config_dir,
            &m.id,
            m.config_defaults(),
        )));
        self.plugins.insert(
            m.id.clone(),
            Entry {
                info: info.clone(),
                manifest: Arc::new(m),
                config,
                instance: None,
                external,
            },
        );
        info
    }

    fn log_host(&self, msg: &str) {
        match &self.logger {
            Some(l) => l("host", LOG_WARN, msg),
            None => eprintln!("[weave-plugin] {msg}"),
        }
    }
}
