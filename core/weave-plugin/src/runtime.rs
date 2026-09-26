//! 插件实例 = 一个 Lua 5.4 状态 + 一条专属线程（actor）。外部调用（start / 音频 / stop / cancel）
//! 与 ws / http / timer 的回调全部作为消息投递到这条线程，串行执行，Lua 状态从不跨线程。
//!
//! A plugin instance is one Lua 5.4 state plus a dedicated thread (actor). External calls and all
//! ws/http/timer callbacks are posted to that thread as messages and run serially; the Lua state
//! never leaves its thread.

use std::collections::HashMap;
use std::fs;
use std::io::{Read, Seek, SeekFrom};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use mlua::{
    Function, IntoLuaMulti, Lua, LuaOptions, MultiValue, RegistryKey, StdLib, Table, Value,
    Variadic,
};

use crate::config::ConfigStore;
use crate::manifest::Manifest;
use crate::net::{self, StreamEvent, WsConn, WsEvent};
use crate::timer::Timers;
use crate::{crypto, json, package, Logger, SpeechListener};

/// 宿主实现的插件 API 级别（`host.sdkVersion`）：0.8.0 起有 emitReplace / http.stream。
/// Plugin API level implemented (`host.sdkVersion`); emitReplace / http.stream exist since 0.8.0.
pub const SDK_VERSION: &str = "0.8.0";
/// 宿主版本（`host.hostVersion`），即本 crate 版本。Host version reported to plugins (crate version).
pub const HOST_VERSION: &str = env!("CARGO_PKG_VERSION");

/// stop() 之后等插件 emitEnd 的上限，超时由宿主收尾并调 cancel()。
/// How long to wait for emitEnd after stop() before the host finishes the session itself.
pub const STOP_TIMEOUT: Duration = Duration::from_secs(6);
const DEFAULT_HTTP_TIMEOUT_MS: u64 = 15_000;
const DEFAULT_AWAIT_MS: u64 = 5_000;

pub const LOG_DEBUG: u8 = 0;
pub const LOG_INFO: u8 = 1;
pub const LOG_WARN: u8 = 2;
pub const LOG_ERROR: u8 = 3;

pub(crate) enum Msg {
    Start {
        sid: u64,
        listener: Arc<dyn SpeechListener>,
    },
    Audio {
        sid: u64,
        pcm: Vec<u8>,
    },
    Stop {
        sid: u64,
    },
    Cancel {
        sid: u64,
    },
    Finish {
        sid: u64,
    },
    Ws {
        gen: u64,
        ev: WsEvent,
    },
    Stream {
        id: u64,
        ev: StreamEvent,
    },
    Timer {
        id: u64,
    },
    Call {
        method: String,
        args: Vec<serde_json::Value>,
        reply: Sender<Result<serde_json::Value, String>>,
    },
    IsConfigured {
        reply: Sender<bool>,
    },
    Shutdown,
}

pub(crate) struct InstanceSpec {
    pub id: String,
    pub dir: PathBuf,
    pub manifest: Arc<Manifest>,
    pub config: Arc<Mutex<ConfigStore>>,
    pub files_dir: PathBuf,
    pub logger: Option<Logger>,
    pub notify: Option<Arc<dyn Fn(&str) + Send + Sync>>,
}

// ================================================================ 对外句柄 / handles

pub(crate) struct Instance {
    tx: Sender<Msg>,
    next_sid: AtomicU64,
}

impl Instance {
    pub fn spawn(spec: InstanceSpec) -> Instance {
        let (tx, rx) = mpsc::channel();
        let tx2 = tx.clone();
        let name = format!("weave-plugin:{}", spec.id);
        std::thread::Builder::new()
            .name(name.chars().take(60).collect())
            .spawn(move || actor_main(spec, tx2, rx))
            .expect("spawn plugin thread");
        Instance {
            tx,
            next_sid: AtomicU64::new(1),
        }
    }

    pub fn start_speech(&self, listener: Arc<dyn SpeechListener>) -> SpeechSession {
        let sid = self.next_sid.fetch_add(1, Ordering::SeqCst);
        let _ = self.tx.send(Msg::Start { sid, listener });
        SpeechSession {
            sid,
            tx: self.tx.clone(),
        }
    }

    pub fn call(
        &self,
        method: &str,
        args: Vec<serde_json::Value>,
        timeout: Duration,
    ) -> Result<serde_json::Value, String> {
        let (reply, rx) = mpsc::channel();
        self.tx
            .send(Msg::Call {
                method: method.to_string(),
                args,
                reply,
            })
            .map_err(|_| "plugin thread is gone".to_string())?;
        rx.recv_timeout(timeout)
            .map_err(|_| format!("{method}: timed out"))?
    }

    /// 调 plugin.isConfigured()：没有该方法视为 true，出错或超时视为 false。
    /// Calls plugin.isConfigured(): true if undefined, false on error or timeout.
    pub fn is_configured(&self, timeout: Duration) -> bool {
        let (reply, rx) = mpsc::channel();
        if self.tx.send(Msg::IsConfigured { reply }).is_err() {
            return false;
        }
        rx.recv_timeout(timeout).unwrap_or(false)
    }
}

impl Drop for Instance {
    fn drop(&mut self) {
        let _ = self.tx.send(Msg::Shutdown);
    }
}

/// 一次语音会话。所有方法都只是投递消息，不阻塞；丢弃时自动 cancel。
/// One speech session. Methods only post messages and never block; dropping it cancels.
pub struct SpeechSession {
    sid: u64,
    tx: Sender<Msg>,
}

impl SpeechSession {
    /// 16 kHz / 16 bit / 单声道 / 小端 PCM。16 kHz mono s16le PCM.
    pub fn feed(&self, pcm16k_mono_le: &[u8]) {
        let _ = self.tx.send(Msg::Audio {
            sid: self.sid,
            pcm: pcm16k_mono_le.to_vec(),
        });
    }

    /// 用户松手：调 plugin.stop()，等 emitEnd（至多 STOP_TIMEOUT），然后宿主调 cancel()。
    /// User released: plugin.stop(), wait for emitEnd (≤ STOP_TIMEOUT), then the host calls cancel().
    pub fn stop(&self) {
        let _ = self.tx.send(Msg::Stop { sid: self.sid });
    }

    pub fn cancel(&self) {
        let _ = self.tx.send(Msg::Cancel { sid: self.sid });
    }

    pub fn id(&self) -> u64 {
        self.sid
    }
}

impl Drop for SpeechSession {
    fn drop(&mut self) {
        let _ = self.tx.send(Msg::Cancel { sid: self.sid });
    }
}

// ================================================================ 宿主状态 / host state

struct Session {
    sid: u64,
    listener: Arc<dyn SpeechListener>,
    stopping: bool,
    ended: bool,
    cancelled: bool,
    errored: bool,
    audio_error_logged: bool,
}

enum TimerKind {
    Lua { func: RegistryKey, repeat: bool },
    StopTimeout(u64),
}

struct HostState {
    id: String,
    dir: PathBuf,
    files_dir: PathBuf,
    manifest: Arc<Manifest>,
    config: Arc<Mutex<ConfigStore>>,
    tx: Sender<Msg>,
    logger: Option<Logger>,
    notify: Option<Arc<dyn Fn(&str) + Send + Sync>>,
    agent: ureq::Agent,

    session: Option<Session>,
    last_listener: Option<Arc<dyn SpeechListener>>,

    ws: Option<WsConn>,
    ws_gen: u64,
    ws_callbacks: Option<RegistryKey>,
    ws_last_error: Arc<Mutex<String>>,

    http_last_error: String,
    streams: HashMap<u64, RegistryKey>,
    next_stream: u64,

    timers: Timers,
    timer_kinds: HashMap<u64, TimerKind>,
    next_timer: u64,
}

fn st(lua: &Lua) -> mlua::AppDataRefMut<'_, HostState> {
    lua.app_data_mut::<HostState>()
        .expect("host state installed")
}

fn log(lua: &Lua, level: u8, msg: &str) {
    let (listener, logger, id) = {
        let s = st(lua);
        let l = s
            .session
            .as_ref()
            .map(|x| x.listener.clone())
            .or_else(|| s.last_listener.clone());
        (l, s.logger.clone(), s.id.clone())
    };
    if let Some(l) = &listener {
        l.on_log(level, msg);
    }
    if let Some(g) = &logger {
        g(&id, level, msg);
    }
    if listener.is_none() && logger.is_none() {
        eprintln!("[{id}] {msg}");
    }
}

fn active_listener(lua: &Lua) -> Option<Arc<dyn SpeechListener>> {
    let s = st(lua);
    s.session
        .as_ref()
        .filter(|x| !x.ended)
        .map(|x| x.listener.clone())
}

/// 调插件方法（不带 self，插件都用 `function plugin.x()` 定义）。方法不存在返回 Ok(None)。
/// Call a plugin method (no self). Ok(None) if the plugin doesn't define it.
fn call_plugin(
    lua: &Lua,
    name: &str,
    args: impl IntoLuaMulti,
) -> Result<Option<MultiValue>, String> {
    let plugin: Table = lua
        .named_registry_value("weave.plugin")
        .map_err(|e| e.to_string())?;
    match plugin.get::<Value>(name).map_err(|e| e.to_string())? {
        Value::Function(f) => f
            .call::<MultiValue>(args)
            .map(Some)
            .map_err(|e| format!("{name}(): {e}")),
        _ => Ok(None),
    }
}

fn call_logged(lua: &Lua, what: &str, f: &Function, args: impl IntoLuaMulti) {
    if let Err(e) = f.call::<()>(args) {
        log(lua, LOG_ERROR, &format!("{what}: {e}"));
    }
}

// ================================================================ 资源配额 / quotas

/// 每 N 条 Lua 指令检查一次预算。 Instruction hook granularity.
const HOOK_EVERY: u32 = 1_000_000;
/// 每处理一条宿主消息（一次回调）最多执行多少条 Lua 指令；纯计算死循环会在这里被打断，
/// 而等待网络等阻塞调用不计入。 Max Lua instructions per host message; blocking host calls don't count.
pub const INSTRUCTION_BUDGET: u64 = 300_000_000;
/// 每个插件实例的 Lua 内存上限。 Lua memory ceiling per plugin instance.
pub const MEMORY_LIMIT: usize = 64 * 1024 * 1024;

thread_local! {
    /// 本线程（插件 actor）当前消息已用掉的指令数（以 HOOK_EVERY 为单位）。
    static USED: std::cell::Cell<u64> = const { std::cell::Cell::new(0) };
}

/// 新消息开始：重置指令预算。 Reset the instruction budget for a new message.
fn reset_budget() {
    USED.with(|u| u.set(0));
}

fn install_quotas(lua: &Lua) -> mlua::Result<()> {
    lua.set_memory_limit(MEMORY_LIMIT)?;
    lua.set_hook(
        mlua::HookTriggers::new().every_nth_instruction(HOOK_EVERY),
        |_lua, _debug| {
            let used = USED.with(|u| {
                let v = u.get() + 1;
                u.set(v);
                v
            });
            if used * HOOK_EVERY as u64 > INSTRUCTION_BUDGET {
                Err(mlua::Error::runtime(
                    "script exceeded its instruction budget",
                ))
            } else {
                Ok(mlua::VmState::Continue)
            }
        },
    );
    Ok(())
}

// ================================================================ actor

fn actor_main(spec: InstanceSpec, tx: Sender<Msg>, rx: Receiver<Msg>) {
    let id = spec.id.clone();
    let lua = match build_lua(spec, tx) {
        Ok(l) => l,
        Err(e) => {
            // Lua 状态都建不起来：之后所有请求直接失败
            let err = format!("[{id}] lua init failed: {e}");
            for msg in rx.iter() {
                match msg {
                    Msg::Start { listener, .. } => {
                        listener.on_error(&err);
                        listener.on_end();
                    }
                    Msg::Call { reply, .. } => {
                        let _ = reply.send(Err(err.clone()));
                    }
                    Msg::IsConfigured { reply } => {
                        let _ = reply.send(false);
                    }
                    Msg::Shutdown => return,
                    _ => {}
                }
            }
            return;
        }
    };
    reset_budget();
    let loaded = load_plugin(&lua);
    match &loaded {
        Ok(()) => {
            for m in ["initialize", "onLoad"] {
                match call_plugin(&lua, m, ()) {
                    Ok(Some(r)) if matches!(r.front(), Some(Value::Boolean(false))) => {
                        log(&lua, LOG_WARN, &format!("{m}() returned false"))
                    }
                    Err(e) => log(&lua, LOG_ERROR, &e),
                    _ => {}
                }
            }
        }
        Err(e) => log(&lua, LOG_ERROR, e),
    }

    for msg in rx.iter() {
        reset_budget();
        match msg {
            Msg::Shutdown => {
                if loaded.is_ok() {
                    if let Some(sid) = st(&lua).session.as_ref().map(|s| s.sid) {
                        end_session(&lua, sid);
                    }
                    if let Err(e) = call_plugin(&lua, "onUnload", ()) {
                        log(&lua, LOG_ERROR, &e);
                    }
                }
                let mut s = st(&lua);
                s.ws = None;
                s.ws_gen += 1;
                break;
            }
            Msg::Start { sid, listener } => on_start(&lua, &loaded, sid, listener),
            Msg::Audio { sid, pcm } => on_audio(&lua, sid, pcm),
            Msg::Stop { sid } => on_stop(&lua, sid),
            Msg::Cancel { sid } | Msg::Finish { sid } => end_session(&lua, sid),
            Msg::Ws { gen, ev } => on_ws(&lua, gen, ev),
            Msg::Stream { id, ev } => on_stream(&lua, id, ev),
            Msg::Timer { id } => on_timer(&lua, id),
            Msg::Call {
                method,
                args,
                reply,
            } => {
                let r = match &loaded {
                    Ok(()) => call_json(&lua, &method, &args),
                    Err(e) => Err(e.clone()),
                };
                let _ = reply.send(r);
            }
            Msg::IsConfigured { reply } => {
                let r = loaded.is_ok()
                    && match call_plugin(&lua, "isConfigured", ()) {
                        Ok(None) => true,
                        Ok(Some(r)) => !matches!(
                            r.front(),
                            None | Some(Value::Nil) | Some(Value::Boolean(false))
                        ),
                        Err(e) => {
                            log(&lua, LOG_ERROR, &e);
                            false
                        }
                    };
                let _ = reply.send(r);
            }
        }
    }
}

fn on_start(lua: &Lua, loaded: &Result<(), String>, sid: u64, listener: Arc<dyn SpeechListener>) {
    if let Some(old) = st(lua).session.as_ref().map(|s| s.sid) {
        end_session(lua, old);
    }
    if let Err(e) = loaded {
        listener.on_error(e);
        listener.on_end();
        return;
    }
    {
        let mut s = st(lua);
        s.last_listener = Some(listener.clone());
        s.session = Some(Session {
            sid,
            listener,
            stopping: false,
            ended: false,
            cancelled: false,
            errored: false,
            audio_error_logged: false,
        });
    }
    let failure = match call_plugin(lua, "start", ()) {
        Ok(Some(r)) if matches!(r.front(), Some(Value::Boolean(false))) => {
            Some("start() returned false".to_string())
        }
        Ok(Some(_)) => None,
        Ok(None) => Some("plugin has no start()".to_string()),
        Err(e) => Some(e),
    };
    if let Some(msg) = failure {
        log(lua, LOG_WARN, &msg);
        let tell = {
            let s = st(lua);
            s.session
                .as_ref()
                .filter(|x| x.sid == sid && !x.ended && !x.errored)
                .map(|x| x.listener.clone())
        };
        if let Some(l) = tell {
            l.on_error(&msg);
        }
        end_session(lua, sid);
    }
}

fn on_audio(lua: &Lua, sid: u64, pcm: Vec<u8>) {
    let ok = {
        let s = st(lua);
        matches!(&s.session, Some(x) if x.sid == sid && !x.ended && !x.stopping)
    };
    if !ok {
        return;
    }
    let data = match lua.create_string(&pcm) {
        Ok(d) => d,
        Err(_) => return,
    };
    if let Err(e) = call_plugin(lua, "processAudioChunk", data) {
        let first = {
            let mut s = st(lua);
            match s.session.as_mut() {
                Some(x) if !x.audio_error_logged => {
                    x.audio_error_logged = true;
                    true
                }
                _ => false,
            }
        };
        if first {
            log(lua, LOG_ERROR, &e);
        }
    }
}

fn on_stop(lua: &Lua, sid: u64) {
    {
        let mut s = st(lua);
        match s.session.as_mut() {
            Some(x) if x.sid == sid && !x.ended && !x.stopping => x.stopping = true,
            _ => return,
        }
        let tid = s.next_timer;
        s.next_timer += 1;
        s.timer_kinds.insert(tid, TimerKind::StopTimeout(sid));
        s.timers.add(tid, STOP_TIMEOUT, None);
    }
    if let Err(e) = call_plugin(lua, "stop", ()) {
        log(lua, LOG_ERROR, &e);
        end_session(lua, sid);
    }
}

/// 收尾：通知 on_end（仅一次），再调一次 plugin.cancel()（仅一次），然后丢弃会话。
/// Finish: on_end once, plugin.cancel() once, then drop the session.
fn end_session(lua: &Lua, sid: u64) {
    let (listener, need_end, need_cancel) = {
        let mut s = st(lua);
        match s.session.as_mut() {
            Some(x) if x.sid == sid => {
                let ne = !x.ended;
                let nc = !x.cancelled;
                x.ended = true;
                x.cancelled = true;
                (Some(x.listener.clone()), ne, nc)
            }
            _ => (None, false, false),
        }
    };
    let Some(listener) = listener else { return };
    if need_end {
        listener.on_end();
    }
    if need_cancel {
        if let Err(e) = call_plugin(lua, "cancel", ()) {
            log(lua, LOG_ERROR, &e);
        }
    }
    let mut s = st(lua);
    if s.session.as_ref().map(|x| x.sid) == Some(sid) {
        s.session = None;
    }
    let stale: Vec<u64> = s
        .timer_kinds
        .iter()
        .filter(|(_, k)| matches!(k, TimerKind::StopTimeout(x) if *x == sid))
        .map(|(id, _)| *id)
        .collect();
    for id in stale {
        s.timer_kinds.remove(&id);
        s.timers.cancel(id);
    }
}

fn on_ws(lua: &Lua, gen: u64, ev: WsEvent) {
    let cbs: Option<Table> = {
        let s = st(lua);
        if s.ws_gen != gen {
            return;
        }
        s.ws_callbacks
            .as_ref()
            .and_then(|k| lua.registry_value::<Table>(k).ok())
    };
    let Some(cbs) = cbs else { return };
    let get = |name: &str| cbs.get::<Option<Function>>(name).ok().flatten();
    match ev {
        WsEvent::Open => {
            if let Some(f) = get("onOpen") {
                call_logged(lua, "ws.onOpen", &f, ());
            }
        }
        WsEvent::Text(t) => {
            if let (Some(f), Ok(s)) = (get("onMessage"), lua.create_string(&t)) {
                call_logged(lua, "ws.onMessage", &f, s);
            }
        }
        WsEvent::Binary(b) => {
            // 没有 onBinary 回调的插件退回 onMessage。Plugins without onBinary get binary frames via onMessage.
            if let (Some(f), Ok(s)) = (
                get("onBinary").or_else(|| get("onMessage")),
                lua.create_string(&b),
            ) {
                call_logged(lua, "ws.onBinary", &f, s);
            }
        }
        WsEvent::Error(msg) => {
            if let Some(f) = get("onError") {
                call_logged(lua, "ws.onError", &f, msg);
            }
        }
        WsEvent::Close(code, reason) => {
            if let Some(f) = get("onClose") {
                call_logged(lua, "ws.onClose", &f, (code, reason));
            }
        }
    }
}

fn on_stream(lua: &Lua, id: u64, ev: StreamEvent) {
    let done = !matches!(ev, StreamEvent::Data(_));
    let cbs: Option<Table> = {
        let mut s = st(lua);
        if done {
            s.streams.remove(&id).and_then(|k| {
                let t = lua.registry_value::<Table>(&k).ok();
                let _ = lua.remove_registry_value(k);
                t
            })
        } else {
            s.streams
                .get(&id)
                .and_then(|k| lua.registry_value::<Table>(k).ok())
        }
    };
    let Some(cbs) = cbs else { return };
    let get = |name: &str| cbs.get::<Option<Function>>(name).ok().flatten();
    match ev {
        StreamEvent::Data(d) => {
            if let (Some(f), Ok(s)) = (get("onData"), lua.create_string(&d)) {
                call_logged(lua, "http.stream.onData", &f, s);
            }
        }
        StreamEvent::Done(all) => {
            if let (Some(f), Ok(s)) = (get("onDone"), lua.create_string(&all)) {
                call_logged(lua, "http.stream.onDone", &f, s);
            }
        }
        StreamEvent::Error(msg) => {
            if let Some(f) = get("onError") {
                call_logged(lua, "http.stream.onError", &f, msg);
            }
        }
    }
}

fn on_timer(lua: &Lua, id: u64) {
    enum Act {
        Lua(Function),
        Stop(u64),
    }
    let act = {
        let mut s = st(lua);
        match s.timer_kinds.get(&id) {
            None => None,
            Some(TimerKind::StopTimeout(sid)) => {
                let sid = *sid;
                s.timer_kinds.remove(&id);
                Some(Act::Stop(sid))
            }
            Some(TimerKind::Lua { func, repeat }) => {
                let f = lua.registry_value::<Function>(func).ok();
                if !*repeat {
                    if let Some(TimerKind::Lua { func, .. }) = s.timer_kinds.remove(&id) {
                        let _ = lua.remove_registry_value(func);
                    }
                }
                f.map(Act::Lua)
            }
        }
    };
    match act {
        Some(Act::Lua(f)) => call_logged(lua, "timer", &f, ()),
        Some(Act::Stop(sid)) => {
            let waiting = matches!(&st(lua).session, Some(x) if x.sid == sid && !x.ended);
            if waiting {
                log(
                    lua,
                    LOG_WARN,
                    "no emitEnd after stop(); host finishes the session",
                );
                end_session(lua, sid);
            }
        }
        None => {}
    }
}

fn call_json(
    lua: &Lua,
    method: &str,
    args: &[serde_json::Value],
) -> Result<serde_json::Value, String> {
    let mut mv = MultiValue::new();
    for a in args {
        mv.push_back(json::json_to_lua(lua, a).map_err(|e| e.to_string())?);
    }
    match call_plugin(lua, method, mv)? {
        None => Err(format!("plugin has no {method}()")),
        Some(r) => json::lua_to_json(r.front().unwrap_or(&Value::Nil), 0),
    }
}

// ================================================================ Lua 环境 / environment

fn build_lua(spec: InstanceSpec, tx: Sender<Msg>) -> mlua::Result<Lua> {
    let libs = StdLib::TABLE
        | StdLib::STRING
        | StdLib::MATH
        | StdLib::UTF8
        | StdLib::COROUTINE
        | StdLib::OS;
    let lua = Lua::new_with(libs, LuaOptions::default())?;
    let timer_tx = tx.clone();
    let timers = Timers::spawn(move |id| {
        let _ = timer_tx.send(Msg::Timer { id });
    });
    lua.set_app_data(HostState {
        id: spec.id,
        dir: spec.dir,
        files_dir: spec.files_dir,
        manifest: spec.manifest,
        config: spec.config,
        tx,
        logger: spec.logger,
        notify: spec.notify,
        agent: net::agent(),
        session: None,
        last_listener: None,
        ws: None,
        ws_gen: 0,
        ws_callbacks: None,
        ws_last_error: Arc::new(Mutex::new(String::new())),
        http_last_error: String::new(),
        streams: HashMap::new(),
        next_stream: 1,
        timers,
        timer_kinds: HashMap::new(),
        next_timer: 1,
    });
    sandbox(&lua)?;
    install_quotas(&lua)?;
    install_host(&lua)?;
    install_require(&lua)?;
    Ok(lua)
}

/// 沙箱：不开 io / debug / package；os 只留时间函数；去掉 dofile / loadfile。
/// Sandbox: no io / debug / package; os keeps only time functions; no dofile / loadfile.
fn sandbox(lua: &Lua) -> mlua::Result<()> {
    let g = lua.globals();
    let os: Table = g.get("os")?;
    for name in [
        "execute",
        "exit",
        "getenv",
        "remove",
        "rename",
        "tmpname",
        "setlocale",
    ] {
        os.set(name, Value::Nil)?;
    }
    g.set("dofile", Value::Nil)?;
    g.set("loadfile", Value::Nil)?;
    g.set(
        "print",
        lua.create_function(|lua, args: Variadic<Value>| {
            log(lua, LOG_INFO, &join_values(&args));
            Ok(())
        })?,
    )?;
    Ok(())
}

fn join_values(args: &[Value]) -> String {
    args.iter()
        .map(|v| match v {
            Value::String(s) => String::from_utf8_lossy(&s.as_bytes()).into_owned(),
            other => other
                .to_string()
                .unwrap_or_else(|_| format!("<{}>", other.type_name())),
        })
        .collect::<Vec<_>>()
        .join(" ")
}

/// `require("x")` 从包内 `libs/x.lua`（其次包根 `x.lua`）加载，结果缓存。
/// `require("x")` loads `libs/x.lua` (then `x.lua` at the package root) and caches the result.
fn install_require(lua: &Lua) -> mlua::Result<()> {
    let loaded = lua.create_table()?;
    lua.set_named_registry_value("weave.loaded", &loaded)?;
    let package = lua.create_table()?;
    package.set("loaded", &loaded)?;
    lua.globals().set("package", package)?;
    let require = lua.create_function(|lua, name: String| {
        let loaded: Table = lua.named_registry_value("weave.loaded")?;
        match loaded.get::<Value>(name.as_str())? {
            Value::Nil => {}
            Value::LightUserData(_) => {
                return Err(mlua::Error::runtime(format!(
                    "require: loop while loading '{name}'"
                )));
            }
            v => return Ok(v),
        }
        let dir = st(lua).dir.clone();
        let rel = name.replace('.', "/");
        let candidates = [
            format!("libs/{rel}.lua"),
            format!("{rel}.lua"),
            format!("libs/{rel}/init.lua"),
        ];
        let found = candidates.iter().find_map(|c| {
            let p = package::safe_relative(c)?;
            fs::read(dir.join(&p)).ok().map(|src| (c.clone(), src))
        });
        let Some((chunk_name, src)) = found else {
            return Err(mlua::Error::runtime(format!(
                "module '{name}' not found (looked in libs/)"
            )));
        };
        loaded.set(
            name.as_str(),
            Value::LightUserData(mlua::LightUserData(std::ptr::null_mut())),
        )?;
        let result = lua
            .load(src)
            .set_name(format!("@{chunk_name}"))
            .call::<Value>(name.as_str());
        let v = match result {
            Ok(Value::Nil) => Value::Boolean(true),
            Ok(v) => v,
            Err(e) => {
                loaded.set(name.as_str(), Value::Nil)?;
                return Err(e);
            }
        };
        loaded.set(name.as_str(), &v)?;
        Ok(v)
    })?;
    lua.globals().set("require", require)?;
    Ok(())
}

fn load_plugin(lua: &Lua) -> Result<(), String> {
    let (dir, entry) = {
        let s = st(lua);
        (s.dir.clone(), s.manifest.entry.clone())
    };
    let rel = package::safe_relative(&entry).ok_or_else(|| format!("invalid entry {entry}"))?;
    let src = fs::read(dir.join(&rel)).map_err(|e| format!("{entry}: {e}"))?;
    let v: Value = lua
        .load(src)
        .set_name(format!("@{entry}"))
        .eval()
        .map_err(|e| format!("load {entry}: {e}"))?;
    match v {
        Value::Table(t) => lua
            .set_named_registry_value("weave.plugin", t)
            .map_err(|e| e.to_string()),
        other => Err(format!(
            "{entry} must return the plugin table, got {}",
            other.type_name()
        )),
    }
}

// ---------------------------------------------------------------- 参数工具 / arg helpers

/// Lua 字符串（数字按 Lua 规则转成字符串）→ 字节。Strings (numbers coerced) → bytes.
fn bytes_of(v: &Value) -> Option<Vec<u8>> {
    match v {
        Value::String(s) => Some(s.as_bytes().to_vec()),
        Value::Integer(i) => Some(i.to_string().into_bytes()),
        Value::Number(n) => Some(n.to_string().into_bytes()),
        _ => None,
    }
}

fn str_of(v: &Value) -> Option<String> {
    match v {
        Value::Boolean(b) => Some(b.to_string()),
        other => bytes_of(other).map(|b| String::from_utf8_lossy(&b).into_owned()),
    }
}

fn arg(args: &Variadic<Value>, i: usize) -> Value {
    args.get(i).cloned().unwrap_or(Value::Nil)
}

fn int_of(v: &Value) -> Option<i64> {
    match v {
        Value::Integer(i) => Some(*i),
        Value::Number(n) if n.is_finite() => Some(*n as i64),
        Value::String(s) => s.to_str().ok()?.trim().parse().ok(),
        _ => None,
    }
}

fn headers_of(v: &Value) -> Vec<(String, Vec<u8>)> {
    let mut out = Vec::new();
    if let Value::Table(t) = v {
        for (k, val) in t.clone().pairs::<Value, Value>().flatten() {
            if let (Some(k), Some(val)) = (
                str_of(&k),
                bytes_of(&val).or_else(|| str_of(&val).map(String::into_bytes)),
            ) {
                out.push((k, val));
            }
        }
    }
    out
}

fn lstr(lua: &Lua, b: impl AsRef<[u8]>) -> mlua::Result<Value> {
    Ok(Value::String(lua.create_string(b.as_ref())?))
}

fn opt_bytes(lua: &Lua, b: Option<Vec<u8>>) -> mlua::Result<Value> {
    match b {
        Some(b) => lstr(lua, b),
        None => Ok(Value::Nil),
    }
}

fn check_host(lua: &Lua, url: &str) -> Result<(), String> {
    let host = net::url_host(url).ok_or_else(|| format!("invalid url: {url}"))?;
    if st(lua).manifest.host_allowed(&host) {
        Ok(())
    } else {
        Err(format!(
            "host not declared in manifest.network.hosts: {host}"
        ))
    }
}

fn resource_path(lua: &Lua, name: &str) -> Option<PathBuf> {
    let rel = package::safe_relative(name)?;
    Some(st(lua).dir.join("resources").join(rel))
}

// ---------------------------------------------------------------- host.*

fn install_host(lua: &Lua) -> mlua::Result<()> {
    let host = lua.create_table()?;
    host.set("sdkVersion", SDK_VERSION)?;
    host.set("hostVersion", HOST_VERSION)?;
    host.set("pluginId", st(lua).id.clone())?;

    host.set(
        "log",
        lua.create_function(|lua, args: Variadic<Value>| {
            log(lua, LOG_INFO, &join_values(&args));
            Ok(())
        })?,
    )?;
    host.set(
        "logError",
        lua.create_function(|lua, args: Variadic<Value>| {
            log(lua, LOG_ERROR, &join_values(&args));
            Ok(())
        })?,
    )?;
    host.set("uuid", lua.create_function(|_, ()| Ok(crypto::uuid_v4()))?)?;

    host.set("json", json_api(lua)?)?;
    host.set("config", config_api(lua)?)?;
    host.set("crypto", crypto_api(lua)?)?;
    host.set("asr", asr_api(lua)?)?;
    host.set("ws", ws_api(lua)?)?;
    host.set("http", http_api(lua)?)?;
    host.set("resource", resource_api(lua)?)?;
    host.set("timer", timer_api(lua)?)?;
    host.set("fs", fs_api(lua)?)?;
    host.set("sync", sync_api(lua)?)?;
    lua.globals().set("host", host)?;
    Ok(())
}

fn json_api(lua: &Lua) -> mlua::Result<Table> {
    let t = lua.create_table()?;
    t.set(
        "encode",
        lua.create_function(|lua, v: Value| match json::encode(&v) {
            Ok(s) => (lstr(lua, s)?, Value::Nil).into_lua_multi(lua),
            Err(e) => (Value::Nil, e).into_lua_multi(lua),
        })?,
    )?;
    t.set(
        "decode",
        lua.create_function(|lua, v: Value| {
            let Some(b) = bytes_of(&v) else {
                return (Value::Nil, "json.decode: expected string").into_lua_multi(lua);
            };
            match json::decode(lua, &b) {
                Ok(v) => (v, Value::Nil).into_lua_multi(lua),
                Err(e) => (Value::Nil, e).into_lua_multi(lua),
            }
        })?,
    )?;
    Ok(t)
}

fn config_api(lua: &Lua) -> mlua::Result<Table> {
    let t = lua.create_table()?;
    t.set(
        "get",
        lua.create_function(|lua, key: Value| {
            let Some(key) = str_of(&key) else {
                return Ok(Value::Nil);
            };
            let cfg = st(lua).config.clone();
            let v = cfg.lock().unwrap().get(&key);
            opt_bytes(lua, v.map(String::into_bytes))
        })?,
    )?;
    t.set(
        "set",
        lua.create_function(|lua, (key, value): (Value, Value)| {
            let Some(key) = str_of(&key) else {
                return Ok(false);
            };
            let cfg = st(lua).config.clone();
            let mut c = cfg.lock().unwrap();
            match str_of(&value) {
                Some(v) => c.set(&key, &v),
                None => c.remove(&key),
            }
            Ok(true)
        })?,
    )?;
    t.set(
        "remove",
        lua.create_function(|lua, key: Value| {
            if let Some(key) = str_of(&key) {
                let cfg = st(lua).config.clone();
                cfg.lock().unwrap().remove(&key);
            }
            Ok(true)
        })?,
    )?;
    Ok(t)
}

fn crypto_api(lua: &Lua) -> mlua::Result<Table> {
    let t = lua.create_table()?;
    // 单参数：bytes → bytes/string；参数非字符串时返回 nil。
    macro_rules! unary {
        ($name:expr, $f:expr) => {
            t.set(
                $name,
                lua.create_function(|lua, v: Value| match bytes_of(&v) {
                    Some(b) => {
                        let r: Option<Vec<u8>> = $f(&b);
                        opt_bytes(lua, r)
                    }
                    None => Ok(Value::Nil),
                })?,
            )?;
        };
    }
    macro_rules! binary {
        ($name:expr, $f:expr) => {
            t.set(
                $name,
                lua.create_function(|lua, (k, d): (Value, Value)| {
                    match (bytes_of(&k), bytes_of(&d)) {
                        (Some(k), Some(d)) => lstr(lua, $f(&k, &d)),
                        _ => Ok(Value::Nil),
                    }
                })?,
            )?;
        };
    }
    unary!("md5", |b: &[u8]| Some(crypto::md5(b)));
    unary!("sha1", |b: &[u8]| Some(crypto::sha1(b)));
    unary!("sha256", |b: &[u8]| Some(crypto::sha256(b)));
    unary!("sha512", |b: &[u8]| Some(crypto::sha512(b)));
    binary!("hmacMd5", crypto::hmac_md5);
    binary!("hmacSha1", crypto::hmac_sha1);
    binary!("hmacSha256", crypto::hmac_sha256);
    binary!("hmacSha512", crypto::hmac_sha512);
    unary!("base64", |b: &[u8]| Some(
        crypto::base64_encode(b).into_bytes()
    ));
    unary!("base64Encode", |b: &[u8]| Some(
        crypto::base64_encode(b).into_bytes()
    ));
    unary!("base64Decode", crypto::base64_decode);
    unary!("base64Url", |b: &[u8]| Some(
        crypto::base64url_encode(b).into_bytes()
    ));
    unary!("base64UrlEncode", |b: &[u8]| Some(
        crypto::base64url_encode(b).into_bytes()
    ));
    unary!("base64UrlDecode", crypto::base64url_decode);
    unary!("hex", |b: &[u8]| Some(crypto::hex_encode(b).into_bytes()));
    unary!("hexEncode", |b: &[u8]| Some(
        crypto::hex_encode(b).into_bytes()
    ));
    unary!("hexDecode", crypto::hex_decode);
    unary!("urlEncode", |b: &[u8]| Some(
        crypto::url_encode(b).into_bytes()
    ));
    unary!("urlDecode", |b: &[u8]| Some(crypto::url_decode(b)));

    t.set(
        "randomBytes",
        lua.create_function(|lua, n: Value| {
            let n = int_of(&n).unwrap_or(16).clamp(0, 1 << 20) as usize;
            lstr(lua, crypto::random_bytes(n))
        })?,
    )?;
    t.set(
        "epochSeconds",
        lua.create_function(|_, ()| Ok(crypto::epoch_seconds()))?,
    )?;
    t.set(
        "epochMillis",
        lua.create_function(|_, ()| Ok(crypto::epoch_millis()))?,
    )?;

    fn sym(lua: &Lua, args: Variadic<Value>, enc: bool) -> mlua::Result<MultiValue> {
        let (Some(tr), Some(key), Some(data)) = (
            str_of(&arg(&args, 0)),
            bytes_of(&arg(&args, 1)),
            bytes_of(&arg(&args, 2)),
        ) else {
            return (Value::Nil, "symEncrypt/symDecrypt: bad arguments").into_lua_multi(lua);
        };
        let iv = bytes_of(&arg(&args, 3));
        let r = if enc {
            crypto::sym_encrypt(&tr, &key, &data, iv.as_deref())
        } else {
            crypto::sym_decrypt(&tr, &key, &data, iv.as_deref())
        };
        match r {
            Ok(b) => (lstr(lua, b)?, Value::Nil).into_lua_multi(lua),
            Err(e) => {
                log(lua, LOG_WARN, &format!("crypto: {e}"));
                (Value::Nil, e).into_lua_multi(lua)
            }
        }
    }
    t.set(
        "symEncrypt",
        lua.create_function(|lua, a: Variadic<Value>| sym(lua, a, true))?,
    )?;
    t.set(
        "symDecrypt",
        lua.create_function(|lua, a: Variadic<Value>| sym(lua, a, false))?,
    )?;
    t.set(
        "rsaEncrypt",
        lua.create_function(|lua, a: Variadic<Value>| {
            let (Some(der), Some(data)) = (bytes_of(&arg(&a, 0)), bytes_of(&arg(&a, 1))) else {
                return (Value::Nil, "rsaEncrypt: bad arguments").into_lua_multi(lua);
            };
            let padding = str_of(&arg(&a, 2)).unwrap_or_else(|| "PKCS1".into());
            match crypto::rsa_encrypt(&der, &data, &padding) {
                Ok(b) => (lstr(lua, b)?, Value::Nil).into_lua_multi(lua),
                Err(e) => {
                    log(lua, LOG_WARN, &format!("crypto: {e}"));
                    (Value::Nil, e).into_lua_multi(lua)
                }
            }
        })?,
    )?;
    t.set(
        "ecGenerateKeypair",
        lua.create_function(|lua, curve: Value| {
            let curve = str_of(&curve).unwrap_or_default().to_ascii_lowercase();
            if curve != "secp128r1" {
                return Ok(Value::Nil);
            }
            let (private, public) = crypto::secp128r1::generate();
            let t = lua.create_table()?;
            t.set("privateHex", crypto::hex_encode(&private))?;
            t.set("publicHex", crypto::hex_encode(&public))?;
            Ok(Value::Table(t))
        })?,
    )?;
    t.set(
        "ecdhSharedX",
        lua.create_function(|lua, (curve, private, public): (Value, Value, Value)| {
            let curve = str_of(&curve).unwrap_or_default().to_ascii_lowercase();
            if curve != "secp128r1" {
                return Ok(Value::Nil);
            }
            let (Some(pk), Some(pubk)) = (
                bytes_of(&private).and_then(|h| crypto::hex_decode(&h)),
                bytes_of(&public).and_then(|h| crypto::hex_decode(&h)),
            ) else {
                return Ok(Value::Nil);
            };
            match crypto::secp128r1::shared_x(&pk, &pubk) {
                Some(x) => lstr(lua, crypto::hex_encode(&x)),
                None => Ok(Value::Nil),
            }
        })?,
    )?;
    Ok(t)
}

fn asr_api(lua: &Lua) -> mlua::Result<Table> {
    let t = lua.create_table()?;
    t.set(
        "emitPartial",
        lua.create_function(|lua, v: Value| {
            if let (Some(l), Some(s)) = (active_listener(lua), str_of(&v)) {
                l.on_partial(&s);
            }
            Ok(())
        })?,
    )?;
    t.set(
        "emitFinal",
        lua.create_function(|lua, v: Value| {
            if let (Some(l), Some(s)) = (active_listener(lua), str_of(&v)) {
                l.on_final(&s);
            }
            Ok(())
        })?,
    )?;
    t.set(
        "emitError",
        lua.create_function(|lua, v: Value| {
            let msg = str_of(&v).unwrap_or_else(|| "unknown error".into());
            let l = {
                let mut s = st(lua);
                match s.session.as_mut() {
                    Some(x) if !x.ended => {
                        x.errored = true;
                        Some(x.listener.clone())
                    }
                    _ => None,
                }
            };
            match l {
                Some(l) => l.on_error(&msg),
                None => log(
                    lua,
                    LOG_WARN,
                    &format!("emitError outside a session: {msg}"),
                ),
            }
            Ok(())
        })?,
    )?;
    t.set(
        "emitEnd",
        lua.create_function(|lua, ()| {
            let r = {
                let mut s = st(lua);
                let r = match s.session.as_mut() {
                    Some(x) if !x.ended => {
                        x.ended = true;
                        Some((x.listener.clone(), x.sid))
                    }
                    _ => None,
                };
                if let Some((_, sid)) = &r {
                    let _ = s.tx.send(Msg::Finish { sid: *sid });
                }
                r
            };
            if let Some((l, _)) = r {
                l.on_end();
            }
            Ok(())
        })?,
    )?;
    // emitReplace 常在会话结束之后才到（后台整理），发给最近一次会话的监听者，
    // 由宿主 UI 判断旧文本是否仍在光标前。
    // emitReplace usually arrives after the session ended; route it to the latest listener.
    t.set(
        "emitReplace",
        lua.create_function(|lua, (old, new): (Value, Value)| {
            let l = {
                let s = st(lua);
                s.session
                    .as_ref()
                    .map(|x| x.listener.clone())
                    .or_else(|| s.last_listener.clone())
            };
            if let (Some(l), Some(o), Some(n)) = (l, str_of(&old), str_of(&new)) {
                l.on_replace(&o, &n);
            }
            Ok(())
        })?,
    )?;
    Ok(t)
}

fn ws_api(lua: &Lua) -> mlua::Result<Table> {
    let t = lua.create_table()?;
    t.set(
        "connect",
        lua.create_function(|lua, (url, headers, callbacks): (Value, Value, Value)| {
            let Some(url) = str_of(&url) else {
                return Ok(false);
            };
            let last_error = st(lua).ws_last_error.clone();
            if let Err(e) = check_host(lua, &url) {
                *last_error.lock().unwrap() = e.clone();
                log(lua, LOG_ERROR, &e);
                return Ok(false);
            }
            let cb_key = match callbacks {
                Value::Table(t) => Some(lua.create_registry_value(t)?),
                _ => None,
            };
            let mut s = st(lua);
            // 已有活连接：直接复用，不重连、不补发 onOpen，只换回调表（插件需要新连接时应先 close()）。
            // Live connection: reused without reconnecting or re-sending onOpen; only callbacks swap
            // (plugins that need a fresh connection call close() first).
            if let Some(c) = &s.ws {
                if matches!(c.state(), net::WS_CONNECTING | net::WS_OPEN) {
                    if let Some(old) = std::mem::replace(&mut s.ws_callbacks, cb_key) {
                        let _ = lua.remove_registry_value(old);
                    }
                    return Ok(true);
                }
            }
            s.ws = None;
            s.ws_gen += 1;
            let gen = s.ws_gen;
            last_error.lock().unwrap().clear();
            let tx = s.tx.clone();
            match WsConn::connect(&url, headers_of(&headers), last_error.clone(), move |ev| {
                let _ = tx.send(Msg::Ws { gen, ev });
            }) {
                Ok(conn) => {
                    s.ws = Some(conn);
                    if let Some(old) = std::mem::replace(&mut s.ws_callbacks, cb_key) {
                        let _ = lua.remove_registry_value(old);
                    }
                    Ok(true)
                }
                Err(e) => {
                    *last_error.lock().unwrap() = e.clone();
                    drop(s);
                    log(lua, LOG_ERROR, &format!("ws.connect: {e}"));
                    Ok(false)
                }
            }
        })?,
    )?;
    t.set(
        "sendText",
        lua.create_function(|lua, v: Value| {
            let Some(b) = bytes_of(&v) else {
                return Ok(false);
            };
            let Ok(text) = String::from_utf8(b) else {
                return Ok(false);
            };
            Ok(st(lua).ws.as_ref().is_some_and(|c| c.send_text(text)))
        })?,
    )?;
    t.set(
        "sendBinary",
        lua.create_function(|lua, v: Value| {
            let Some(b) = bytes_of(&v) else {
                return Ok(false);
            };
            Ok(st(lua).ws.as_ref().is_some_and(|c| c.send_binary(b)))
        })?,
    )?;
    // close() 之后这条连接不再有任何回调（包括 onClose）。No callbacks at all after close().
    t.set(
        "close",
        lua.create_function(|lua, ()| {
            let mut s = st(lua);
            if s.ws.take().is_some() {
                s.ws_gen += 1;
            }
            if let Some(old) = s.ws_callbacks.take() {
                let _ = lua.remove_registry_value(old);
            }
            Ok(true)
        })?,
    )?;
    t.set(
        "getState",
        lua.create_function(|lua, ()| {
            Ok(st(lua).ws.as_ref().map_or(net::WS_CLOSED, |c| c.state()))
        })?,
    )?;
    t.set(
        "lastError",
        lua.create_function(|lua, ()| {
            let e = st(lua).ws_last_error.lock().unwrap().clone();
            opt_bytes(lua, (!e.is_empty()).then(|| e.into_bytes()))
        })?,
    )?;
    t.set(
        "awaitBinary",
        lua.create_function(|lua, timeout: Value| {
            let ms = int_of(&timeout)
                .unwrap_or(DEFAULT_AWAIT_MS as i64)
                .clamp(0, 600_000) as u64;
            let shared = st(lua).ws.as_ref().map(|c| c.shared.clone());
            let Some(shared) = shared else {
                return Ok(Value::Nil);
            };
            opt_bytes(lua, shared.await_binary(Duration::from_millis(ms)))
        })?,
    )?;
    Ok(t)
}

fn http_api(lua: &Lua) -> mlua::Result<Table> {
    let t = lua.create_table()?;
    t.set(
        "request",
        lua.create_function(|lua, a: Variadic<Value>| {
            let method = str_of(&arg(&a, 0)).unwrap_or_else(|| "GET".into());
            let Some(url) = str_of(&arg(&a, 1)) else {
                return Ok(Value::Nil);
            };
            let headers = headers_of(&arg(&a, 2));
            let body = bytes_of(&arg(&a, 3));
            let timeout = int_of(&arg(&a, 4))
                .filter(|v| *v > 0)
                .map(|v| v as u64)
                .unwrap_or(DEFAULT_HTTP_TIMEOUT_MS);
            if let Err(e) = check_host(lua, &url) {
                log(lua, LOG_ERROR, &e);
                st(lua).http_last_error = e;
                return Ok(Value::Nil);
            }
            let agent = st(lua).agent.clone();
            let manifest = st(lua).manifest.clone();
            let allow = |h: &str| manifest.host_allowed(h);
            match net::request(
                &agent,
                &method,
                &url,
                &headers,
                body,
                Duration::from_millis(timeout),
                &allow,
            ) {
                Ok(r) => {
                    st(lua).http_last_error.clear();
                    let t = lua.create_table()?;
                    t.set("status", r.status)?;
                    t.set("code", r.status)?;
                    t.set("ok", (200..300).contains(&r.status))?;
                    let body = lua.create_string(&r.body)?;
                    t.set("body", &body)?;
                    t.set("text", body)?;
                    let h = lua.create_table()?;
                    for (k, v) in r.headers {
                        h.set(k, v)?;
                    }
                    t.set("headers", h)?;
                    Ok(Value::Table(t))
                }
                Err(e) => {
                    log(lua, LOG_WARN, &format!("http.request {method} {url}: {e}"));
                    st(lua).http_last_error = e;
                    Ok(Value::Nil)
                }
            }
        })?,
    )?;
    t.set(
        "lastError",
        lua.create_function(|lua, ()| {
            let e = st(lua).http_last_error.clone();
            opt_bytes(lua, (!e.is_empty()).then(|| e.into_bytes()))
        })?,
    )?;
    t.set(
        "stream",
        lua.create_function(|lua, a: Variadic<Value>| {
            let Some(url) = str_of(&arg(&a, 0)) else {
                return Ok(Value::Boolean(false));
            };
            let headers = headers_of(&arg(&a, 1));
            let Value::Table(cbs) = arg(&a, 2) else {
                return Ok(Value::Boolean(false));
            };
            let timeout = int_of(&arg(&a, 3))
                .filter(|v| *v > 0)
                .map(|v| v as u64)
                .unwrap_or(DEFAULT_HTTP_TIMEOUT_MS);
            let body = bytes_of(&arg(&a, 5));
            let method = str_of(&arg(&a, 4)).unwrap_or_else(|| {
                if body.is_some() {
                    "POST".into()
                } else {
                    "GET".into()
                }
            });
            if let Err(e) = check_host(lua, &url) {
                log(lua, LOG_ERROR, &e);
                st(lua).http_last_error = e;
                return Ok(Value::Boolean(false));
            }
            let key = lua.create_registry_value(cbs)?;
            let mut s = st(lua);
            let id = s.next_stream;
            s.next_stream += 1;
            s.streams.insert(id, key);
            let tx = s.tx.clone();
            let agent = s.agent.clone();
            let manifest = s.manifest.clone();
            drop(s);
            std::thread::Builder::new()
                .name("weave-sse".into())
                .spawn(move || {
                    let allow = |h: &str| manifest.host_allowed(h);
                    net::stream(
                        &agent,
                        &method,
                        &url,
                        &headers,
                        body,
                        Duration::from_millis(timeout),
                        &allow,
                        &|ev| {
                            let _ = tx.send(Msg::Stream { id, ev });
                        },
                    )
                })
                .map_err(mlua::Error::external)?;
            Ok(Value::Integer(id as i64))
        })?,
    )?;
    Ok(t)
}

fn resource_api(lua: &Lua) -> mlua::Result<Table> {
    let t = lua.create_table()?;
    t.set(
        "read",
        lua.create_function(|lua, name: Value| {
            let p = str_of(&name).and_then(|n| resource_path(lua, &n));
            opt_bytes(lua, p.and_then(|p| fs::read(p).ok()))
        })?,
    )?;
    t.set(
        "readAt",
        lua.create_function(|lua, (name, offset, len): (Value, Value, Value)| {
            let (Some(p), Some(off), Some(len)) = (
                str_of(&name).and_then(|n| resource_path(lua, &n)),
                int_of(&offset),
                int_of(&len),
            ) else {
                return Ok(Value::Nil);
            };
            if off < 0 || len < 0 {
                return Ok(Value::Nil);
            }
            let read = (|| -> std::io::Result<Vec<u8>> {
                let mut f = fs::File::open(p)?;
                f.seek(SeekFrom::Start(off as u64))?;
                let mut buf = Vec::with_capacity((len as usize).min(16 << 20));
                f.take(len as u64).read_to_end(&mut buf)?;
                Ok(buf)
            })();
            opt_bytes(lua, read.ok())
        })?,
    )?;
    t.set(
        "exists",
        lua.create_function(|lua, name: Value| {
            Ok(str_of(&name)
                .and_then(|n| resource_path(lua, &n))
                .is_some_and(|p| p.is_file()))
        })?,
    )?;
    Ok(t)
}

fn timer_api(lua: &Lua) -> mlua::Result<Table> {
    let t = lua.create_table()?;
    fn add(lua: &Lua, ms: Value, f: Function, repeat: bool) -> mlua::Result<i64> {
        let ms = int_of(&ms).unwrap_or(0).max(if repeat { 10 } else { 0 }) as u64;
        let key = lua.create_registry_value(f)?;
        let mut s = st(lua);
        let id = s.next_timer;
        s.next_timer += 1;
        s.timer_kinds
            .insert(id, TimerKind::Lua { func: key, repeat });
        let d = Duration::from_millis(ms);
        s.timers.add(id, d, repeat.then_some(d));
        Ok(id as i64)
    }
    t.set(
        "setInterval",
        lua.create_function(|lua, (ms, f): (Value, Function)| add(lua, ms, f, true))?,
    )?;
    t.set(
        "setTimeout",
        lua.create_function(|lua, (ms, f): (Value, Function)| add(lua, ms, f, false))?,
    )?;
    t.set(
        "cancel",
        lua.create_function(|lua, id: Value| {
            let Some(id) = int_of(&id) else {
                return Ok(false);
            };
            let id = id as u64;
            let mut s = st(lua);
            match s.timer_kinds.get(&id) {
                Some(TimerKind::Lua { .. }) => {
                    if let Some(TimerKind::Lua { func, .. }) = s.timer_kinds.remove(&id) {
                        let _ = lua.remove_registry_value(func);
                    }
                    s.timers.cancel(id);
                    Ok(true)
                }
                _ => Ok(false),
            }
        })?,
    )?;
    Ok(t)
}

/// 最小 host.fs：限定在 `config_dir/<id>.files/` 下的私有文件。
/// Minimal host.fs: private files confined to `config_dir/<id>.files/`.
fn fs_api(lua: &Lua) -> mlua::Result<Table> {
    fn path(lua: &Lua, name: &Value) -> Option<PathBuf> {
        let rel = package::safe_relative(&str_of(name)?)?;
        Some(st(lua).files_dir.join(rel))
    }
    let t = lua.create_table()?;
    t.set(
        "read",
        lua.create_function(|lua, n: Value| {
            opt_bytes(lua, path(lua, &n).and_then(|p| fs::read(p).ok()))
        })?,
    )?;
    t.set(
        "write",
        lua.create_function(|lua, (n, data): (Value, Value)| {
            let (Some(p), Some(d)) = (path(lua, &n), bytes_of(&data)) else {
                return Ok(false);
            };
            if let Some(parent) = p.parent() {
                let _ = fs::create_dir_all(parent);
            }
            Ok(fs::write(p, d).is_ok())
        })?,
    )?;
    t.set(
        "exists",
        lua.create_function(|lua, n: Value| Ok(path(lua, &n).is_some_and(|p| p.exists())))?,
    )?;
    t.set(
        "remove",
        lua.create_function(|lua, n: Value| {
            Ok(path(lua, &n).is_some_and(|p| fs::remove_file(p).is_ok()))
        })?,
    )?;
    Ok(t)
}

fn sync_api(lua: &Lua) -> mlua::Result<Table> {
    let t = lua.create_table()?;
    t.set(
        "notifyChanged",
        lua.create_function(|lua, ()| {
            let (notify, id) = {
                let s = st(lua);
                (s.notify.clone(), s.id.clone())
            };
            match notify {
                Some(n) => n(&id),
                None => log(lua, LOG_DEBUG, "sync.notifyChanged (no handler)"),
            }
            Ok(())
        })?,
    )?;
    Ok(t)
}

#[allow(dead_code)]
fn _assert_send() {
    fn is_send<T: Send>() {}
    is_send::<Msg>();
    is_send::<SpeechSession>();
}
