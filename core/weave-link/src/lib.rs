//! 织文互联（WeaveLink）：手机与电脑在同一局域网内配对，互传文字、剪贴板、图片与文件，全程端到端加密。
//! WeaveLink: devices on the same LAN pair once, then exchange text, clipboard, images and files end-to-end
//! encrypted. No server, no account; nothing leaves the local network.
//!
//! 宿主（Android / macOS）通过两条接口驱动：[Link::call] 发 JSON 命令，[Link::poll] 取 JSON 事件。
//! Hosts drive it with JSON commands via [Link::call] and read JSON events via [Link::poll].
//!
//! 命令 / commands (`op`):
//! `info` · `openPairing` → `{code, uri, expiresIn}` · `closePairing` · `pair {addrs, code}` · `peers` ·
//! `forget {id}` · `connect {id}` · `rename {name}` · `sendText {to?, text, clip}` ·
//! `sendFile {to?, path | fd, name?, mime?, clip}` → `{id}` · `cancel {id}` · `stop`
//!
//! 事件 / events (`type`):
//! `peerFound` · `peerLost` · `connected` · `disconnected` · `paired` · `pairFailed` · `pairAttempt` · `text` ·
//! `fileStart` · `fileProgress` · `fileDone` · `fileFailed` · `error`
//!
//! 发送文件时，若连报价都没发出去，只会收到 `fileFailed`（没有 `fileStart`）。每次 `sendFile` 各开一个线程，
//! 同一连接上的多个文件会交错传输；需要逐个发送时由宿主排队。
//! A send that fails before the offer goes out yields only `fileFailed` (no `fileStart`). Each `sendFile` runs on
//! its own thread, so files on one connection interleave; hosts queue them when they want one at a time.

pub mod discovery;
pub mod secure;
pub mod store;
pub mod wire;

use std::collections::{HashMap, HashSet};
use std::fs::{self, File};
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{channel, Receiver, RecvTimeoutError, Sender};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use serde_json::{json, Value};
use sha2::{Digest, Sha256};

use discovery::{Discovery, Found, Seen};
use secure::Channel;
use store::{device_id, err, fingerprint, Identity, Peer, PeerStore};
use wire::{hex, Kind, Message, CHUNK};

/// 固定的首选端口（记住的地址在重启后仍然有效）；被占用时改用随机端口。
/// Preferred fixed port, so remembered addresses survive restarts; falls back to a random one.
pub const DEFAULT_PORT: u16 = 47811;
const PAIRING_WINDOW: Duration = Duration::from_secs(120);
const PAIRING_ATTEMPTS: u32 = 5;
const HEARTBEAT: Duration = Duration::from_secs(20);
const PROGRESS_EVERY: Duration = Duration::from_millis(200);
/// 剪贴板图片只留最近几张。 Only the latest few clipboard images are kept.
const CLIP_KEEP: usize = 4;

#[derive(Clone, Debug)]
pub struct Config {
    pub name: String,
    /// "android" / "mac"。
    pub platform: String,
    /// 身份密钥与已配对设备。 Identity key and trusted peers.
    pub state_dir: PathBuf,
    /// 收到的文件存放处。 Where received files go.
    pub inbox_dir: PathBuf,
    /// 0 = [DEFAULT_PORT]，被占用则随机。 0 = [DEFAULT_PORT], random if taken.
    pub port: u16,
    pub mdns: bool,
}

impl Config {
    pub fn from_json(v: &Value) -> Option<Config> {
        Some(Config {
            name: v["name"].as_str()?.to_string(),
            platform: v["platform"].as_str().unwrap_or("unknown").to_string(),
            state_dir: PathBuf::from(v["stateDir"].as_str()?),
            inbox_dir: PathBuf::from(v["inboxDir"].as_str()?),
            port: v["port"].as_u64().unwrap_or(0) as u16,
            mdns: v["mdns"].as_bool().unwrap_or(true),
        })
    }
}

struct Pairing {
    code: String,
    expires: Instant,
    attempts: u32,
}

struct Incoming {
    file: File,
    part: PathBuf,
    name: String,
    mime: String,
    size: u64,
    got: u64,
    clip: bool,
    hash: Sha256,
    reported: Instant,
}

struct Conn {
    peer: Peer,
    ch: Channel,
    /// 是否由本机发起（重复连接时据此取舍）。 Whether we initiated it (decides duplicates).
    outbound: bool,
    incoming: Mutex<HashMap<String, Incoming>>,
}

struct Inner {
    me: Identity,
    id: String,
    name: Mutex<String>,
    platform: String,
    state_dir: PathBuf,
    inbox: Mutex<PathBuf>,
    port: u16,
    peers: Mutex<PeerStore>,
    nearby: Mutex<HashMap<String, Found>>,
    conns: Mutex<HashMap<String, Arc<Conn>>>,
    dialing: Mutex<HashSet<String>>,
    pairing: Mutex<Option<Pairing>>,
    cancels: Mutex<HashMap<String, Arc<AtomicBool>>>,
    tx: Mutex<Sender<Value>>,
    running: AtomicBool,
    discovery: Mutex<Option<Discovery>>,
}

pub struct Link {
    inner: Arc<Inner>,
    rx: Mutex<Receiver<Value>>,
    wake_addr: SocketAddr,
    accept_thread: Mutex<Option<std::thread::JoinHandle<()>>>,
}

impl Link {
    pub fn start(cfg: Config) -> Result<Link, String> {
        fs::create_dir_all(&cfg.state_dir).map_err(err)?;
        let me = Identity::load_or_create(&cfg.state_dir)?;
        let listener = bind(cfg.port)?;
        let listen_addr = listener.local_addr().map_err(err)?;
        let port = listen_addr.port();
        let wake_addr = if listen_addr.is_ipv6() {
            SocketAddr::from(([0u16, 0, 0, 0, 0, 0, 0, 1], port))
        } else {
            SocketAddr::from(([127, 0, 0, 1], port))
        };
        let (tx, rx) = channel();
        let inner = Arc::new(Inner {
            id: me.id(),
            me,
            name: Mutex::new(cfg.name.clone()),
            platform: cfg.platform.clone(),
            peers: Mutex::new(PeerStore::load(&cfg.state_dir)),
            state_dir: cfg.state_dir.clone(),
            inbox: Mutex::new(cfg.inbox_dir.clone()),
            port,
            nearby: Mutex::new(HashMap::new()),
            conns: Mutex::new(HashMap::new()),
            dialing: Mutex::new(HashSet::new()),
            pairing: Mutex::new(None),
            cancels: Mutex::new(HashMap::new()),
            tx: Mutex::new(tx),
            running: AtomicBool::new(true),
            discovery: Mutex::new(None),
        });
        let accept_thread = {
            let inner = inner.clone();
            std::thread::Builder::new()
                .name("weavelink-accept".into())
                .spawn(move || accept_loop(inner, listener))
                .map_err(err)?
        };
        let link = Link { inner: inner.clone(), rx: Mutex::new(rx), wake_addr, accept_thread: Mutex::new(Some(accept_thread)) };
        if cfg.mdns {
            let weak = Arc::downgrade(&inner);
            match Discovery::start(&inner.id, &cfg.name, &cfg.platform, port, move |seen| {
                if let Some(inner) = weak.upgrade() {
                    on_seen(&inner, seen);
                }
            }) {
                Ok(d) => *inner.discovery.lock().unwrap() = Some(d),
                Err(e) => inner.emit(json!({"type": "error", "message": format!("discovery: {e}")})),
            }
        }
        {
            let inner = inner.clone();
            std::thread::Builder::new().name("weavelink-timer".into()).spawn(move || timer_loop(inner)).map_err(err)?;
        }
        Ok(link)
    }

    /// 取下一个事件（最多等 timeout）；停止后返回 None。 Next event, waiting up to `timeout`; None once stopped.
    pub fn poll(&self, timeout: Duration) -> Option<Value> {
        let rx = self.rx.lock().ok()?;
        match rx.recv_timeout(timeout) {
            Ok(v) => Some(v),
            Err(RecvTimeoutError::Timeout) => self.inner.running.load(Ordering::SeqCst).then(|| json!({"type": "idle"})),
            Err(RecvTimeoutError::Disconnected) => None,
        }
    }

    pub fn call(&self, cmd: &Value) -> Value {
        let inner = &self.inner;
        match cmd["op"].as_str().unwrap_or("") {
            "info" => inner.info(),
            "openPairing" => inner.open_pairing(cmd),
            "closePairing" => {
                *inner.pairing.lock().unwrap() = None;
                json!({"ok": true})
            }
            "pair" => {
                let addrs: Vec<SocketAddr> =
                    cmd["addrs"].as_array().into_iter().flatten().filter_map(|a| a.as_str()?.parse().ok()).collect();
                let Some(code) = cmd["code"].as_str().map(|c| c.trim().to_string()).filter(|c| c.len() == 6 && c.bytes().all(|b| b.is_ascii_digit())) else {
                    return json!({"ok": false, "error": "code"});
                };
                if addrs.is_empty() {
                    return json!({"ok": false, "error": "addrs"});
                }
                let inner = inner.clone();
                std::thread::spawn(move || pair_with(&inner, &addrs, &code));
                json!({"ok": true})
            }
            "peers" => inner.peers_json(),
            "forget" => {
                let id = cmd["id"].as_str().unwrap_or("");
                if let Some(c) = inner.conns.lock().unwrap().remove(id) {
                    c.ch.shutdown();
                }
                json!({"ok": inner.peers.lock().unwrap().remove(id)})
            }
            "connect" => {
                let id = cmd["id"].as_str().unwrap_or("").to_string();
                if inner.peers.lock().unwrap().get(&id).is_none() { return json!({"ok": false, "error": "unknown peer"}); }
                let explicit: Vec<SocketAddr> = cmd["addrs"].as_array().into_iter().flatten().filter_map(|v| v.as_str()?.parse().ok()).collect();
                if cmd["addrs"].as_array().is_some_and(|a| !a.is_empty() && explicit.len() != a.len()) { return json!({"ok":false,"error":"invalid address"}); }
                for addr in cmd["addrs"].as_array().into_iter().flatten().filter_map(|v| v.as_str()).filter(|s| s.parse::<SocketAddr>().is_ok()) {
                    inner.peers.lock().unwrap().remember_addr(&id, addr);
                }
                dial_to(inner, &id, explicit);
                json!({"ok": true})
            }
            "setInbox" => {
                let Some(path) = cmd["path"].as_str().map(PathBuf::from).filter(|p| p.is_absolute()) else { return json!({"ok":false,"error":"invalid directory"}); };
                if let Err(e) = fs::create_dir_all(&path) { return json!({"ok":false,"error":e.to_string()}); }
                *inner.inbox.lock().unwrap() = path;
                json!({"ok":true})
            }
            "discovered" => {
                let addrs: Vec<SocketAddr> = cmd["addrs"].as_array().into_iter().flatten().filter_map(|v| v.as_str()?.parse().ok()).take(16).collect();
                let id = cmd["id"].as_str().unwrap_or("");
                if id.len() != 16 || !id.bytes().all(|c| c.is_ascii_hexdigit()) || id == inner.id || addrs.is_empty() { return json!({"ok":false}); }
                on_seen(inner, Seen::Found(Found { id:id.into(), name:cmd["name"].as_str().unwrap_or("").chars().take(40).collect(), platform:cmd["platform"].as_str().unwrap_or("unknown").into(), addrs }));
                json!({"ok":true})
            }
            "discoveryLost" => { on_seen(inner, Seen::Lost(format!("{}.{}", cmd["id"].as_str().unwrap_or(""), discovery::SERVICE))); json!({"ok":true}) }
            "rename" => {
                if let Some(n) = cmd["name"].as_str().filter(|n| !n.trim().is_empty()) {
                    *inner.name.lock().unwrap() = n.trim().to_string();
                }
                json!({"ok": true})
            }
            "sendText" => {
                let text = cmd["text"].as_str().unwrap_or("");
                let m = Message::new(Kind::Text, json!({"text": text, "clip": cmd["clip"].as_bool().unwrap_or(false)}));
                let targets = inner.targets(cmd["to"].as_str());
                let mut sent = 0;
                for c in &targets {
                    if c.ch.send(&m).is_ok() {
                        sent += 1;
                    }
                }
                json!({"ok": sent > 0, "sent": sent})
            }
            "sendFile" => inner.send_file(cmd),
            "cancel" => {
                let id = cmd["id"].as_str().unwrap_or("");
                if let Some(f) = inner.cancels.lock().unwrap().get(id) {
                    f.store(true, Ordering::SeqCst);
                }
                json!({"ok": true})
            }
            "stop" => {
                self.stop();
                json!({"ok": true})
            }
            op => json!({"ok": false, "error": format!("unknown op {op}")}),
        }
    }

    pub fn stop(&self) {
        let inner = &self.inner;
        if inner.running.swap(false, Ordering::SeqCst) {
            if let Some(d) = inner.discovery.lock().unwrap().take() {
                d.stop();
            }
            for (_, c) in inner.conns.lock().unwrap().drain() {
                c.ch.shutdown();
            }
            // 按监听器的地址族唤醒 accept，避免 IPv4 请求被同端口的另一监听器接走。
            // Wake accept through its own address family, not a shadow listener on the same port.
            let _ = TcpStream::connect_timeout(&self.wake_addr, Duration::from_millis(300));
            // 断开事件通道：poll 返回 None。 Close the event channel so poll returns None.
            let (dead, _) = channel();
            *inner.tx.lock().unwrap() = dead;
        }
        // 等监听线程退出并释放端口，重启才不会回退到随机端口、让记住的地址失效。
        // Wait for the listener to release its port before a restart can reuse the remembered address.
        if let Some(thread) = self.accept_thread.lock().unwrap().take() {
            let _ = thread.join();
        }
    }
}

impl Drop for Link {
    fn drop(&mut self) {
        self.stop();
    }
}

fn bind(port: u16) -> Result<TcpListener, String> {
    let want = if port == 0 { DEFAULT_PORT } else { port };
    // Darwin can allow a reusable IPv6 listener beside an existing IPv4 listener;
    // IPv4 clients would then reach the other process. Check IPv4 ownership first.
    let available = socket2::Socket::new(socket2::Domain::IPV4, socket2::Type::STREAM, Some(socket2::Protocol::TCP))
        .and_then(|probe| {
            probe.set_reuse_address(true)?;
            probe.bind(&SocketAddr::from(([0, 0, 0, 0], want)).into())?;
            probe.listen(1)
        }).is_ok();
    let want = if available { want } else { 0 };
    for port in [want, 0] {
        if let Ok(socket) = socket2::Socket::new(socket2::Domain::IPV6, socket2::Type::STREAM, Some(socket2::Protocol::TCP)) {
            let _ = socket.set_reuse_address(true);
            if socket.set_only_v6(false).is_ok() && socket.bind(&SocketAddr::from(([0u16;8],port)).into()).is_ok() && socket.listen(128).is_ok() {
                return Ok(socket.into());
            }
        }
    }
    TcpListener::bind(("0.0.0.0", want)).or_else(|_| TcpListener::bind(("0.0.0.0", 0))).map_err(err)
}

impl Inner {
    fn emit(&self, v: Value) {
        if let Ok(tx) = self.tx.lock() {
            let _ = tx.send(v);
        }
    }

    fn name(&self) -> String {
        self.name.lock().unwrap().clone()
    }

    fn info(&self) -> Value {
        let addrs: Vec<String> = discovery::local_addrs().iter().map(|ip| SocketAddr::new(*ip,self.port).to_string()).collect();
        json!({
            "id": self.id, "name": self.name(), "platform": self.platform, "port": self.port,
            "fingerprint": fingerprint(&self.me.public), "addrs": addrs,
        })
    }

    fn open_pairing(&self, cmd: &Value) -> Value {
        let mut addrs: Vec<String> = cmd["addrs"].as_array().into_iter().flatten().filter_map(|v|v.as_str()?.parse::<SocketAddr>().ok()).map(|a|a.to_string()).collect();
        if cmd["addrs"].as_array().is_some_and(|a| !a.is_empty() && addrs.len() != a.len()) { return json!({"ok":false,"error":"invalid address"}); }
        let code = secure::new_code();
        *self.pairing.lock().unwrap() = Some(Pairing { code: code.clone(), expires: Instant::now() + PAIRING_WINDOW, attempts: 0 });
        if addrs.is_empty() { addrs = discovery::local_addrs().iter().map(|ip| SocketAddr::new(*ip,self.port).to_string()).collect(); }
        let uri = format!(
            "weavelink://pair?v=1&id={}&n={}&p={}&a={}&c={}",
            self.id,
            pct(&self.name()),
            pct(&self.platform),
            pct(&addrs.join(",")),
            code
        );
        json!({"code": code, "uri": uri, "expiresIn": PAIRING_WINDOW.as_secs(), "addrs": addrs})
    }

    /// 配对窗口里的有效配对码；每次尝试计数，超过次数关闭。 The open code; each attempt counts, too many closes it.
    fn take_attempt(&self) -> Option<String> {
        let mut g = self.pairing.lock().unwrap();
        let p = g.as_mut()?;
        if Instant::now() > p.expires || p.attempts >= PAIRING_ATTEMPTS {
            *g = None;
            return None;
        }
        p.attempts += 1;
        Some(p.code.clone())
    }

    fn peers_json(&self) -> Value {
        let conns = self.conns.lock().unwrap();
        let nearby = self.nearby.lock().unwrap();
        let store = self.peers.lock().unwrap();
        let trusted: Vec<Value> = store
            .peers
            .iter()
            .map(|p| {
                json!({"id": p.id, "name": p.name, "platform": p.platform, "connected": conns.contains_key(&p.id),
                       "nearby": nearby.contains_key(&p.id), "addrs": p.addrs})
            })
            .collect();
        let others: Vec<Value> = nearby
            .values()
            .filter(|f| store.get(&f.id).is_none())
            .map(|f| json!({"id": f.id, "name": f.name, "platform": f.platform, "addrs": f.addrs.iter().map(|a| a.to_string()).collect::<Vec<_>>()}))
            .collect();
        json!({"trusted": trusted, "nearby": others})
    }

    fn targets(&self, to: Option<&str>) -> Vec<Arc<Conn>> {
        let conns = self.conns.lock().unwrap();
        match to {
            Some(id) => conns.get(id).cloned().into_iter().collect(),
            None => conns.values().cloned().collect(),
        }
    }

    fn send_file(self: &Arc<Self>, cmd: &Value) -> Value {
        let file = if let Some(p) = cmd["path"].as_str() {
            File::open(p).map_err(err)
        } else if let Some(fd) = cmd["fd"].as_i64() {
            from_fd(fd as i32)
        } else {
            Err("no file".into())
        };
        let file = match file {
            Ok(f) => f,
            Err(e) => return json!({"ok": false, "error": e}),
        };
        let targets = self.targets(cmd["to"].as_str());
        let Some(conn) = targets.into_iter().next() else {
            return json!({"ok": false, "error": "not connected"});
        };
        let size = file.metadata().map(|m| m.len()).unwrap_or(0);
        let name = cmd["name"]
            .as_str()
            .map(str::to_string)
            .or_else(|| cmd["path"].as_str().and_then(|p| Path::new(p).file_name()).map(|n| n.to_string_lossy().into_owned()))
            .unwrap_or_else(|| "file".into());
        let mime = cmd["mime"].as_str().unwrap_or("application/octet-stream").to_string();
        let clip = cmd["clip"].as_bool().unwrap_or(false);
        let id = secure::random_id();
        let cancel = Arc::new(AtomicBool::new(false));
        self.cancels.lock().unwrap().insert(id.clone(), cancel.clone());
        let inner = self.clone();
        let tid = id.clone();
        std::thread::spawn(move || {
            let r = send_file_body(&inner, &conn, file, &tid, &name, &mime, size, clip, &cancel);
            inner.cancels.lock().unwrap().remove(&tid);
            match r {
                Ok(()) => inner.emit(json!({"type": "fileDone", "id": tid, "to": conn.peer.id, "name": name, "incoming": false, "clip": clip})),
                Err(e) => inner.emit(json!({"type": "fileFailed", "id": tid, "to": conn.peer.id, "name": name, "incoming": false, "reason": e})),
            }
        });
        json!({"ok": true, "id": id})
    }
}

#[cfg(unix)]
fn from_fd(fd: i32) -> Result<File, String> {
    use std::os::unix::io::FromRawFd;
    if fd < 0 {
        return Err("bad fd".into());
    }
    // SAFETY: 宿主交出这个描述符的所有权（Android 端 detachFd）。 The host hands over ownership (detachFd).
    Ok(unsafe { File::from_raw_fd(fd) })
}

#[cfg(not(unix))]
fn from_fd(_fd: i32) -> Result<File, String> {
    Err("fd unsupported".into())
}

#[allow(clippy::too_many_arguments)]
fn send_file_body(
    inner: &Inner,
    conn: &Conn,
    mut file: File,
    id: &str,
    name: &str,
    mime: &str,
    size: u64,
    clip: bool,
    cancel: &AtomicBool,
) -> Result<(), String> {
    conn.ch.send(&Message::new(Kind::FileOffer, json!({"id": id, "name": name, "size": size, "mime": mime, "clip": clip})))?;
    inner.emit(json!({"type": "fileStart", "id": id, "to": conn.peer.id, "name": name, "size": size, "mime": mime, "incoming": false, "clip": clip}));
    let mut hash = Sha256::new();
    let mut buf = vec![0u8; CHUNK];
    let mut done = 0u64;
    let mut reported = Instant::now();
    loop {
        if cancel.load(Ordering::SeqCst) || !inner.running.load(Ordering::SeqCst) {
            let _ = conn.ch.send(&Message::new(Kind::FileCancel, json!({"id": id})));
            return Err("canceled".into());
        }
        let n = file.read(&mut buf).map_err(err)?;
        if n == 0 {
            break;
        }
        hash.update(&buf[..n]);
        conn.ch.send(&Message::with_payload(Kind::FileChunk, json!({"id": id}), buf[..n].to_vec()))?;
        done += n as u64;
        if reported.elapsed() >= PROGRESS_EVERY {
            reported = Instant::now();
            inner.emit(json!({"type": "fileProgress", "id": id, "done": done, "size": size, "incoming": false}));
        }
    }
    conn.ch.send(&Message::new(Kind::FileDone, json!({"id": id, "sha256": hex(&hash.finalize()), "size": done})))
}

fn accept_loop(inner: Arc<Inner>, listener: TcpListener) {
    for s in listener.incoming() {
        if !inner.running.load(Ordering::SeqCst) {
            break;
        }
        let Ok(s) = s else { continue };
        let inner = inner.clone();
        std::thread::spawn(move || {
            let mut asked = false;
            match secure::accept(s, &inner.me, || {
                asked = true;
                inner.take_attempt()
            }) {
                Ok((ch, pairing)) => {
                    if let Err(e) = establish(&inner, ch, false, pairing) {
                        inner.emit(json!({"type": "error", "message": e}));
                    }
                }
                Err(e) if asked => {
                    let open = inner.pairing.lock().unwrap().is_some();
                    inner.emit(json!({"type": "pairAttempt", "ok": false, "reason": e, "stillOpen": open}));
                }
                Err(_) => {}
            }
        });
    }
}

/// 握手完成后：交换问候、核对身份、登记连接并启动读线程。
/// After the handshake: exchange hellos, check identity, register the connection and start its reader.
fn establish(inner: &Arc<Inner>, ch: Channel, outbound: bool, pairing: bool) -> Result<Arc<Conn>, String> {
    let rid = device_id(&ch.remote_key);
    if !pairing && inner.peers.lock().unwrap().by_key(&ch.remote_key).is_none() {
        ch.shutdown();
        return Err(format!("untrusted device {rid}"));
    }
    ch.send(&Message::new(Kind::Hello, json!({"id": inner.id, "name": inner.name(), "platform": inner.platform, "v": 1})))?;
    let hello = ch.recv()?;
    if hello.kind != Kind::Hello || hello.header["id"].as_str() != Some(rid.as_str()) {
        ch.shutdown();
        return Err("bad hello".into());
    }
    let peer = Peer {
        id: rid.clone(),
        name: hello.header["name"].as_str().unwrap_or("").to_string(),
        platform: hello.header["platform"].as_str().unwrap_or("").to_string(),
        key: hex(&ch.remote_key),
        addrs: inner.peers.lock().unwrap().get(&rid).map(|p| p.addrs.clone()).unwrap_or_default(),
    };
    {
        let mut store = inner.peers.lock().unwrap();
        store.upsert(peer.clone());
        if outbound {
            store.remember_addr(&rid, &ch.addr.to_string());
        }
    }
    if pairing {
        *inner.pairing.lock().unwrap() = None;
    }
    let conn = Arc::new(Conn { peer, ch, outbound, incoming: Mutex::new(HashMap::new()) });
    // 重复连接：保留「id 较小的一方发起」的那条，两端取舍一致。
    // Duplicates: keep the one initiated by the smaller id, so both ends choose the same.
    let prefer_outbound = inner.id < rid;
    {
        let mut conns = inner.conns.lock().unwrap();
        if let Some(old) = conns.get(&rid) {
            if old.outbound == prefer_outbound && conn.outbound != prefer_outbound {
                conn.ch.shutdown();
                return Ok(old.clone());
            }
            old.ch.shutdown();
        }
        conns.insert(rid.clone(), conn.clone());
    }
    if pairing {
        inner.emit(json!({"type": "paired", "id": rid, "name": conn.peer.name, "platform": conn.peer.platform}));
    }
    inner.emit(json!({"type": "connected", "id": rid, "name": conn.peer.name, "platform": conn.peer.platform}));
    let (i2, c2) = (inner.clone(), conn.clone());
    std::thread::Builder::new().name("weavelink-read".into()).spawn(move || read_loop(i2, c2)).map_err(err)?;
    Ok(conn)
}

fn read_loop(inner: Arc<Inner>, conn: Arc<Conn>) {
    let reason = loop {
        match conn.ch.recv() {
            Ok(m) => {
                if let Err(e) = handle(&inner, &conn, m) {
                    inner.emit(json!({"type": "error", "message": e}));
                }
            }
            Err(e) => break e,
        }
    };
    // 丢弃未收完的文件。 Drop partially received files.
    for (id, inc) in conn.incoming.lock().unwrap().drain() {
        let _ = fs::remove_file(&inc.part);
        inner.emit(json!({"type": "fileFailed", "id": id, "from": conn.peer.id, "name": inc.name, "incoming": true, "reason": "disconnected"}));
    }
    let mut conns = inner.conns.lock().unwrap();
    if conns.get(&conn.peer.id).is_some_and(|c| Arc::ptr_eq(c, &conn)) {
        conns.remove(&conn.peer.id);
        drop(conns);
        inner.emit(json!({"type": "disconnected", "id": conn.peer.id, "reason": reason}));
    }
}

fn handle(inner: &Inner, conn: &Conn, m: Message) -> Result<(), String> {
    let from = conn.peer.id.as_str();
    match m.kind {
        Kind::Ping => conn.ch.send(&Message::new(Kind::Pong, json!({})))?,
        Kind::Pong | Kind::Hello => {}
        Kind::Text => inner.emit(json!({
            "type": "text", "from": from, "fromName": conn.peer.name,
            "text": m.header["text"].as_str().unwrap_or(""), "clip": m.header["clip"].as_bool().unwrap_or(false),
        })),
        Kind::FileOffer => {
            let id = m.header["id"].as_str().ok_or("offer id")?.to_string();
            let name = safe_name(m.header["name"].as_str().unwrap_or("file"));
            let clip = m.header["clip"].as_bool().unwrap_or(false);
            let dir = if m.header["mime"]=="application/x-weavetext-personal" {inner.state_dir.join("personal-inbox")} else if clip { inner.state_dir.join("clip") } else { inner.inbox.lock().unwrap().clone() };
            fs::create_dir_all(&dir).map_err(err)?;
            let part = dir.join(format!(".{id}.part"));
            let file = File::create(&part).map_err(err)?;
            let size = m.header["size"].as_u64().unwrap_or(0);
            let mime = m.header["mime"].as_str().unwrap_or("application/octet-stream").to_string();
            inner.emit(json!({"type": "fileStart", "id": id, "from": from, "fromName": conn.peer.name, "name": name, "size": size, "mime": mime, "incoming": true, "clip": clip}));
            conn.incoming.lock().unwrap().insert(
                id,
                Incoming { file, part, name, mime, size, got: 0, clip, hash: Sha256::new(), reported: Instant::now() },
            );
        }
        Kind::FileChunk => {
            let id = m.header["id"].as_str().ok_or("chunk id")?;
            let mut g = conn.incoming.lock().unwrap();
            let inc = g.get_mut(id).ok_or("unknown transfer")?;
            inc.file.write_all(&m.payload).map_err(err)?;
            inc.hash.update(&m.payload);
            inc.got += m.payload.len() as u64;
            if inc.reported.elapsed() >= PROGRESS_EVERY {
                inc.reported = Instant::now();
                inner.emit(json!({"type": "fileProgress", "id": id, "done": inc.got, "size": inc.size, "incoming": true}));
            }
        }
        Kind::FileDone => {
            let id = m.header["id"].as_str().ok_or("done id")?.to_string();
            let Some(mut inc) = conn.incoming.lock().unwrap().remove(&id) else { return Ok(()) };
            inc.file.flush().map_err(err)?;
            drop(inc.file);
            let ok = m.header["sha256"].as_str() == Some(hex(&inc.hash.finalize()).as_str());
            if !ok {
                let _ = fs::remove_file(&inc.part);
                inner.emit(json!({"type": "fileFailed", "id": id, "from": from, "name": inc.name, "incoming": true, "reason": "checksum"}));
                return Ok(());
            }
            let dir = inc.part.parent().map(Path::to_path_buf).unwrap_or_default();
            let dest = unique(&dir, &inc.name);
            fs::rename(&inc.part, &dest).map_err(err)?;
            if inc.clip {
                prune_clips(&dir);
            }
            inner.emit(json!({
                "type": "fileDone", "id": id, "from": from, "fromName": conn.peer.name, "name": inc.name,
                "path": dest.to_string_lossy(), "mime": inc.mime, "size": inc.got, "incoming": true, "clip": inc.clip,
            }));
        }
        Kind::FileCancel => {
            let id = m.header["id"].as_str().unwrap_or("");
            if let Some(inc) = conn.incoming.lock().unwrap().remove(id) {
                let _ = fs::remove_file(&inc.part);
                inner.emit(json!({"type": "fileFailed", "id": id, "from": from, "name": inc.name, "incoming": true, "reason": "canceled"}));
            }
        }
    }
    Ok(())
}

fn pair_with(inner: &Arc<Inner>, addrs: &[SocketAddr], code: &str) {
    let mut last = String::from("unreachable");
    for a in addrs {
        match secure::connect(*a, &inner.me, Some(code)) {
            Ok(ch) => match establish(inner, ch, true, true) {
                Ok(_) => return,
                Err(e) => last = e,
            },
            Err(e) => last = e,
        }
    }
    inner.emit(json!({"type": "pairFailed", "reason": last}));
}

/// 连接一台已配对设备：先试局域网里刚发现的地址，再试记住的地址。
/// Connect to a trusted device: freshly discovered addresses first, then remembered ones.
fn dial(inner: &Arc<Inner>, id: &str) {
    dial_to(inner, id, Vec::new());
}

fn dial_to(inner: &Arc<Inner>, id: &str, mut addrs: Vec<SocketAddr>) {
    if inner.conns.lock().unwrap().contains_key(id) || !inner.dialing.lock().unwrap().insert(id.to_string()) {
        return;
    }
    let Some(peer) = inner.peers.lock().unwrap().get(id).cloned() else {
        inner.dialing.lock().unwrap().remove(id);
        return;
    };
    for a in inner.nearby.lock().unwrap().get(id).map(|f| f.addrs.clone()).unwrap_or_default() {
        if !addrs.contains(&a) { addrs.push(a); }
    }
    for a in peer.addrs.iter().filter_map(|a| a.parse().ok()) {
        if !addrs.contains(&a) {
            addrs.push(a);
        }
    }
    let inner = inner.clone();
    let id = id.to_string();
    std::thread::spawn(move || {
        for a in addrs {
            if !inner.running.load(Ordering::SeqCst) || inner.conns.lock().unwrap().contains_key(&id) {
                break;
            }
            if let Ok(ch) = secure::connect(a, &inner.me, None) {
                // 对方公钥必须与配对时记下的一致。 The key must match the one recorded at pairing.
                if hex(&ch.remote_key) != peer.key {
                    ch.shutdown();
                    continue;
                }
                if establish(&inner, ch, true, false).is_ok() {
                    break;
                }
            }
        }
        inner.dialing.lock().unwrap().remove(&id);
    });
}

fn on_seen(inner: &Arc<Inner>, seen: Seen) {
    match seen {
        Seen::Found(f) => {
            let trusted = inner.peers.lock().unwrap().get(&f.id).is_some();
            inner.nearby.lock().unwrap().insert(f.id.clone(), f.clone());
            inner.emit(json!({"type": "peerFound", "id": f.id, "name": f.name, "platform": f.platform, "trusted": trusted,
                              "addrs": f.addrs.iter().map(|a| a.to_string()).collect::<Vec<_>>()}));
            let id = f.id.clone();
            if trusted {
                dial(inner, &id);
            }
        }
        Seen::Lost(full) => {
            let id = full.split('.').next().unwrap_or("").to_string();
            if inner.nearby.lock().unwrap().remove(&id).is_some() {
                inner.emit(json!({"type": "peerLost", "id": id}));
            }
        }
    }
}

/// 心跳与重连：每 20 秒给各连接发 ping，并重试未连上的已配对设备。
/// Heartbeats and reconnects: ping every connection every 20 s and retry trusted devices that are offline.
fn timer_loop(inner: Arc<Inner>) {
    let mut last = Instant::now();
    while inner.running.load(Ordering::SeqCst) {
        std::thread::sleep(Duration::from_millis(500));
        if last.elapsed() < HEARTBEAT {
            continue;
        }
        last = Instant::now();
        let conns: Vec<Arc<Conn>> = inner.conns.lock().unwrap().values().cloned().collect();
        for c in conns {
            if c.ch.send(&Message::new(Kind::Ping, json!({}))).is_err() {
                c.ch.shutdown();
            }
        }
        let ids: Vec<String> = inner.peers.lock().unwrap().peers.iter().map(|p| p.id.clone()).collect();
        for id in ids {
            dial(&inner, &id);
        }
        let mut p = inner.pairing.lock().unwrap();
        if p.as_ref().is_some_and(|p| Instant::now() > p.expires) {
            *p = None;
        }
    }
}

/// 去掉路径与控制字符，避免写到收件箱以外。 Strip paths and control chars so files stay in the inbox.
fn safe_name(name: &str) -> String {
    let base = name.rsplit(['/', '\\']).next().unwrap_or("");
    let s: String = base.chars().filter(|c| !c.is_control() && !matches!(c, ':' | '*' | '?' | '"' | '<' | '>' | '|')).collect();
    let s = s.trim().trim_start_matches('.').to_string();
    if s.is_empty() {
        "file".into()
    } else {
        s.chars().take(120).collect()
    }
}

fn unique(dir: &Path, name: &str) -> PathBuf {
    let p = dir.join(name);
    if !p.exists() {
        return p;
    }
    let (stem, ext) = match name.rfind('.') {
        Some(i) if i > 0 => (&name[..i], &name[i..]),
        _ => (name, ""),
    };
    (1..10_000).map(|i| dir.join(format!("{stem} ({i}){ext}"))).find(|p| !p.exists()).unwrap_or(p)
}

fn prune_clips(dir: &Path) {
    let mut files: Vec<(std::time::SystemTime, PathBuf)> = fs::read_dir(dir)
        .into_iter()
        .flatten()
        .flatten()
        .filter(|e| !e.file_name().to_string_lossy().starts_with('.'))
        .filter_map(|e| Some((e.metadata().ok()?.modified().ok()?, e.path())))
        .collect();
    files.sort_by_key(|f| std::cmp::Reverse(f.0));
    for (modified, p) in files.into_iter().skip(CLIP_KEEP) {
        // Hosts import completed clips asynchronously; keep bursts until they can own a copy.
        if modified.elapsed().unwrap_or_default() >= Duration::from_secs(300) {
            let _ = fs::remove_file(p);
        }
    }
}

fn pct(s: &str) -> String {
    s.bytes()
        .map(|b| if b.is_ascii_alphanumeric() || b"-_.~".contains(&b) { (b as char).to_string() } else { format!("%{b:02X}") })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cfg(tag: &str, dir: &Path) -> Config {
        let reserved = TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let port = reserved.local_addr().unwrap().port();
        Config {
            name: format!("dev-{tag}"),
            platform: tag.into(),
            state_dir: dir.join(tag).join("state"),
            inbox_dir: dir.join(tag).join("inbox"),
            port,
            mdns: false,
        }
    }

    fn wait(l: &Link, ty: &str) -> Value {
        let end = Instant::now() + Duration::from_secs(10);
        while Instant::now() < end {
            if let Some(v) = l.poll(Duration::from_millis(200)) {
                if v["type"] == ty {
                    return v;
                }
            }
        }
        panic!("no {ty} event");
    }

    #[test]
    fn pair_send_text_and_file_then_reconnect() {
        let dir = std::env::temp_dir().join(format!("weave-link-e2e-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        let mac = Link::start(cfg("mac", &dir)).unwrap();
        let phone = Link::start(cfg("android", &dir)).unwrap();
        let port = mac.call(&json!({"op": "info"}))["port"].as_u64().unwrap();
        let addr = format!("127.0.0.1:{port}");

        // 配对码错误：失败且窗口仍开着。 Wrong code: fails, window stays open.
        let code = mac.call(&json!({"op": "openPairing"}))["code"].as_str().unwrap().to_string();
        let wrong = if code == "000000" { "111111" } else { "000000" };
        assert_eq!(phone.call(&json!({"op": "pair", "addrs": [addr], "code": wrong}))["ok"], true);
        wait(&phone, "pairFailed");
        assert_eq!(wait(&mac, "pairAttempt")["stillOpen"], true);

        phone.call(&json!({"op": "pair", "addrs": [addr], "code": code}));
        let p = wait(&phone, "paired");
        assert_eq!(p["name"], "dev-mac");
        wait(&mac, "paired");

        phone.call(&json!({"op": "sendText", "text": "你好，电脑", "clip": true}));
        let t = wait(&mac, "text");
        assert_eq!(t["text"], "你好，电脑");
        assert_eq!(t["clip"], true);

        let src = dir.join("photo.bin");
        let body: Vec<u8> = (0..200_000u32).map(|i| (i * 7 % 251) as u8).collect();
        fs::write(&src, &body).unwrap();
        let r = mac.call(&json!({"op": "sendFile", "path": src.to_string_lossy(), "name": "../../照片.bin"}));
        assert_eq!(r["ok"], true);
        let done = wait(&phone, "fileDone");
        let got = PathBuf::from(done["path"].as_str().unwrap());
        assert_eq!(got.file_name().unwrap().to_string_lossy(), "照片.bin");
        assert!(got.starts_with(dir.join("android").join("inbox")));
        assert_eq!(fs::read(&got).unwrap(), body);

        // 电脑端重启后，手机用记住的地址重连。 After the Mac restarts, the phone reconnects via the remembered address.
        drop(mac);
        wait(&phone, "disconnected");
        let mac = Link::start(Config { port: port as u16, ..cfg("mac", &dir) }).unwrap();
        assert_eq!(mac.call(&json!({"op": "info"}))["port"], port, "restart must reclaim the remembered port");
        let id = p["id"].as_str().unwrap();
        phone.call(&json!({"op": "connect", "id": id}));
        wait(&phone, "connected");
        assert_eq!(phone.call(&json!({"op": "peers"}))["trusted"][0]["connected"], true);

        // 未配对的设备连不上：对方不发问候直接断开。 An untrusted device is dropped before any hello.
        let stranger = Identity::load_or_create(&dir.join("stranger")).unwrap();
        let ch = secure::connect(addr.parse().unwrap(), &stranger, None).unwrap();
        assert!(ch.recv().is_err());
        assert_eq!(phone.call(&json!({"op": "forget", "id": id}))["ok"], true);
        drop(mac);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn stopping_releases_the_listener_before_returning() {
        let dir = std::env::temp_dir().join(format!("weave-link-stop-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        let reserved = TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let port = reserved.local_addr().unwrap().port();
        drop(reserved);
        let config = Config { port, ..cfg("restart", &dir) };
        for _ in 0..32 {
            let link = Link::start(config.clone()).unwrap();
            assert_eq!(link.inner.port, port, "the stopped listener still owns the port");
            link.stop();
        }
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn occupied_ipv4_port_never_produces_a_shadow_ipv6_listener() {
        let ipv4 = TcpListener::bind(("0.0.0.0", 0)).unwrap();
        let port = ipv4.local_addr().unwrap().port();
        let listener = bind(port).unwrap();
        assert_ne!(listener.local_addr().unwrap().port(), port);
        let client = TcpStream::connect(("127.0.0.1", listener.local_addr().unwrap().port())).unwrap();
        assert!(listener.accept().is_ok());
        drop(client);
    }

    #[test]
    fn ipv6_direct_pair_custom_inbox_and_media_clips() {
        let dir = std::env::temp_dir().join(format!("weave-link-v6-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        let mac = Link::start(cfg("mac", &dir)).unwrap();
        let phone = Link::start(cfg("android", &dir)).unwrap();
        let info = mac.call(&json!({"op":"info"}));
        let endpoint = format!("[::1]:{}", info["port"]);
        let pairing = mac.call(&json!({"op":"openPairing", "addrs":[endpoint]}));
        assert_eq!(pairing["addrs"][0], endpoint);
        assert!(pairing["uri"].as_str().unwrap().contains("%5B"));
        phone.call(&json!({"op":"pair", "addrs":[endpoint], "code":pairing["code"]}));
        wait(&phone, "paired"); wait(&mac, "paired");
        assert_eq!(phone.call(&json!({"op":"discovered", "id":info["id"], "name":"Mac", "platform":"mac", "addrs":[endpoint]}))["ok"], true);
        assert_eq!(phone.call(&json!({"op":"peers"}))["trusted"][0]["nearby"], true);
        assert_eq!(phone.call(&json!({"op":"discovered", "id":"invalid", "addrs":[endpoint]}))["ok"], false);
        let inbox = dir.join("custom-downloads");
        assert_eq!(phone.call(&json!({"op":"setInbox", "path":inbox}))["ok"], true);
        assert_eq!(phone.call(&json!({"op":"setInbox", "path":"relative"}))["ok"], false);
        let src = dir.join("content.bin");
        let body: Vec<u8> = (0..170_000).map(|n| (n % 253) as u8).collect();
        fs::write(&src, &body).unwrap();
        for (clip, mime, name) in [(false,"application/pdf","report.pdf"), (true,"image/png","photo.png"), (true,"application/octet-stream","archive.bin")] {
            assert_eq!(mac.call(&json!({"op":"sendFile", "path":src, "clip":clip, "mime":mime, "name":name}))["ok"], true);
            let done = wait(&phone, "fileDone");
            assert_eq!(done["clip"], clip); assert_eq!(done["mime"], mime);
            let got = PathBuf::from(done["path"].as_str().unwrap());
            assert!(got.starts_with(if clip {dir.join("android/state/clip")} else {inbox.clone()}));
            assert_eq!(fs::read(got).unwrap(), body);
        }
        assert_eq!(phone.call(&json!({"op":"sendFile", "path":src, "name":"return.bin", "clip":true}))["ok"], true);
        let done = loop { let event = wait(&mac, "fileDone"); if event["incoming"] == true { break event; } };
        assert_eq!(fs::read(done["path"].as_str().unwrap()).unwrap(), body);
        phone.call(&json!({"op":"discoveryLost", "id":info["id"]}));
        assert_eq!(phone.call(&json!({"op":"peers"}))["trusted"][0]["nearby"], false);
        drop(phone); drop(mac); let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn names_stay_inside_the_inbox() {
        assert_eq!(safe_name("../../etc/passwd"), "passwd");
        assert_eq!(safe_name("..\\a\\b.txt"), "b.txt");
        assert_eq!(safe_name(".hidden"), "hidden");
        assert_eq!(safe_name(""), "file");
        let dir = std::env::temp_dir().join(format!("weave-link-uniq-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("a.txt"), b"x").unwrap();
        assert_eq!(unique(&dir, "a.txt").file_name().unwrap(), "a (1).txt");
        let _ = fs::remove_dir_all(&dir);
        assert_eq!(pct("织文 Mac"), "%E7%BB%87%E6%96%87%20Mac");
    }
}

#[cfg(test)]
mod mdns_tests {
    use super::*;

    /// 真实 mDNS：同机两个实例互相发现并自动连接（需要本地网络，默认不跑）。
    /// Real mDNS: two instances on one machine find and auto-connect to each other (needs a LAN; ignored by default).
    #[test]
    #[ignore]
    fn discovers_and_auto_connects_trusted_peer() {
        let dir = std::env::temp_dir().join(format!("weave-link-mdns-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        let mk = |t: &str| Config {
            name: format!("m-{t}"),
            platform: t.into(),
            state_dir: dir.join(t),
            inbox_dir: dir.join(t).join("in"),
            port: 0,
            mdns: true,
        };
        let a = Link::start(mk("a")).unwrap();
        let b = Link::start(mk("b")).unwrap();
        let end = Instant::now() + Duration::from_secs(15);
        let mut found = false;
        while Instant::now() < end && !found {
            if let Some(v) = b.poll(Duration::from_millis(200)) {
                found = v["type"] == "peerFound" && v["name"] == "m-a";
            }
        }
        assert!(found, "b never saw a");
        drop((a, b));
        let _ = fs::remove_dir_all(&dir);
    }
}
