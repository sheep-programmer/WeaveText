//! 网络原语：TLS 配置、WebSocket 连接线程、HTTP 同步请求与 SSE 流。
//! 全部走 rustls（ring）+ webpki-roots，不依赖系统证书库。
//!
//! Network primitives: TLS config, WebSocket I/O thread, blocking HTTP and SSE streaming.
//! rustls (ring) + webpki-roots throughout; the system trust store is never consulted.

use std::collections::VecDeque;
use std::io::{BufRead, BufReader, ErrorKind};
use std::net::{TcpStream, ToSocketAddrs};
use std::sync::atomic::{AtomicU8, Ordering};
use std::sync::mpsc::{self, Receiver, Sender, TryRecvError};
use std::sync::{Arc, Condvar, Mutex, OnceLock};
use std::time::{Duration, Instant};

use tungstenite::client::IntoClientRequest;
use tungstenite::http::{HeaderName, HeaderValue};
use tungstenite::stream::MaybeTlsStream;
use tungstenite::{Message, WebSocket};

pub fn tls_config() -> Arc<rustls::ClientConfig> {
    static CFG: OnceLock<Arc<rustls::ClientConfig>> = OnceLock::new();
    CFG.get_or_init(|| {
        let roots = rustls::RootCertStore {
            roots: webpki_roots::TLS_SERVER_ROOTS.to_vec(),
        };
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let cfg = rustls::ClientConfig::builder_with_provider(provider)
            .with_safe_default_protocol_versions()
            .expect("ring supports default TLS versions")
            .with_root_certificates(roots)
            .with_no_client_auth();
        Arc::new(cfg)
    })
    .clone()
}

/// 从 URL 里取主机名（用于白名单校验）。Extract the host name of a URL (for the allow-list).
pub fn url_host(url: &str) -> Option<String> {
    let rest = url.split_once("://")?.1;
    let authority = rest.split(['/', '?', '#']).next()?;
    let hostport = authority.rsplit('@').next()?;
    let host = if let Some(v6) = hostport.strip_prefix('[') {
        v6.split(']').next()?
    } else {
        hostport.split(':').next()?
    };
    (!host.is_empty()).then(|| host.to_ascii_lowercase())
}

// ================================================================ WebSocket

/// getState() 的取值。`2 == OPEN` 是插件写死的约定。
/// Values returned by getState(); plugins hard-code `2 == OPEN`.
pub const WS_CLOSED: u8 = 0;
pub const WS_CONNECTING: u8 = 1;
pub const WS_OPEN: u8 = 2;
pub const WS_CLOSING: u8 = 3;

/// awaitBinary 队列上限：只给同步取帧的插件用，其余插件不取，超出丢最旧的。
/// Cap of the awaitBinary queue; frames nobody awaits are dropped oldest-first.
const AWAIT_QUEUE_MAX: usize = 256;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
/// IO 线程轮询粒度：读超时这么久就回头看一眼发送队列。
/// I/O loop granularity: after this read timeout the thread services the send queue.
const POLL: Duration = Duration::from_millis(5);

pub enum WsEvent {
    Open,
    Text(Vec<u8>),
    Binary(Vec<u8>),
    Error(String),
    Close(u16, String),
}

pub struct ConnShared {
    state: AtomicU8,
    queue: Mutex<VecDeque<Vec<u8>>>,
    cv: Condvar,
}

impl ConnShared {
    pub fn state(&self) -> u8 {
        self.state.load(Ordering::SeqCst)
    }

    fn set_state(&self, s: u8) {
        self.state.store(s, Ordering::SeqCst);
        self.cv.notify_all();
    }

    fn push(&self, frame: Vec<u8>) {
        let mut q = self.queue.lock().unwrap();
        if q.len() >= AWAIT_QUEUE_MAX {
            q.pop_front();
        }
        q.push_back(frame);
        self.cv.notify_all();
    }

    /// 同步等下一帧二进制；超时或连接已关且队列为空时返回 None。
    /// Block for the next binary frame; None on timeout, or once closed with an empty queue.
    pub fn await_binary(&self, timeout: Duration) -> Option<Vec<u8>> {
        let deadline = Instant::now() + timeout;
        let mut q = self.queue.lock().unwrap();
        loop {
            if let Some(f) = q.pop_front() {
                return Some(f);
            }
            let st = self.state();
            if st == WS_CLOSED || st == WS_CLOSING {
                return None;
            }
            let now = Instant::now();
            if now >= deadline {
                return None;
            }
            q = self.cv.wait_timeout(q, deadline - now).unwrap().0;
        }
    }
}

enum Out {
    Text(String),
    Binary(Vec<u8>),
    Close,
}

/// 一条 WebSocket 连接。丢弃即关闭（且不再回调）。
/// One WebSocket connection. Dropping it closes the socket without further callbacks.
pub struct WsConn {
    out: Sender<Out>,
    pub shared: Arc<ConnShared>,
}

impl WsConn {
    /// 校验参数后立即返回，握手在后台线程进行；事件经 `post` 投递。
    /// Validates arguments and returns at once; the handshake runs on a background thread.
    pub fn connect(
        url: &str,
        headers: Vec<(String, Vec<u8>)>,
        last_error: Arc<Mutex<String>>,
        post: impl Fn(WsEvent) + Send + 'static,
    ) -> Result<WsConn, String> {
        let mut req = url
            .into_client_request()
            .map_err(|e| format!("invalid ws url: {e}"))?;
        for (k, v) in headers {
            let name = HeaderName::from_bytes(k.as_bytes())
                .map_err(|_| format!("invalid header name {k}"))?;
            let value =
                HeaderValue::from_bytes(&v).map_err(|_| format!("invalid header value for {k}"))?;
            req.headers_mut().insert(name, value);
        }
        let uri = req.uri().clone();
        let secure = match uri.scheme_str() {
            Some("wss") => true,
            Some("ws") => false,
            other => return Err(format!("unsupported ws scheme {other:?}")),
        };
        let host = uri
            .host()
            .ok_or("ws url has no host")?
            .trim_matches(['[', ']'])
            .to_string();
        let port = uri.port_u16().unwrap_or(if secure { 443 } else { 80 });

        let shared = Arc::new(ConnShared {
            state: AtomicU8::new(WS_CONNECTING),
            queue: Mutex::new(VecDeque::new()),
            cv: Condvar::new(),
        });
        let (out_tx, out_rx) = mpsc::channel();
        let sh = shared.clone();
        std::thread::Builder::new()
            .name("weave-ws".into())
            .spawn(move || {
                let fail = |msg: String| {
                    *last_error.lock().unwrap() = msg.clone();
                    sh.set_state(WS_CLOSED);
                    msg
                };
                let ws = match handshake(req, &host, port, secure) {
                    Ok(ws) => ws,
                    Err(e) => {
                        let msg = fail(e);
                        post(WsEvent::Error(msg));
                        return;
                    }
                };
                io_loop(ws, &sh, out_rx, &post, &last_error);
            })
            .map_err(|e| e.to_string())?;
        Ok(WsConn {
            out: out_tx,
            shared,
        })
    }

    pub fn state(&self) -> u8 {
        self.shared.state()
    }

    fn sendable(&self) -> bool {
        matches!(self.state(), WS_CONNECTING | WS_OPEN)
    }

    pub fn send_text(&self, s: String) -> bool {
        self.sendable() && self.out.send(Out::Text(s)).is_ok()
    }

    pub fn send_binary(&self, b: Vec<u8>) -> bool {
        self.sendable() && self.out.send(Out::Binary(b)).is_ok()
    }
}

impl Drop for WsConn {
    fn drop(&mut self) {
        let _ = self.out.send(Out::Close);
        if self.sendable() {
            self.shared.set_state(WS_CLOSING);
        }
    }
}

type Ws = WebSocket<MaybeTlsStream<TcpStream>>;

fn handshake(
    req: tungstenite::handshake::client::Request,
    host: &str,
    port: u16,
    secure: bool,
) -> Result<Ws, String> {
    let addrs: Vec<_> = (host, port)
        .to_socket_addrs()
        .map_err(|e| format!("dns {host}: {e}"))?
        .collect();
    let mut last = format!("dns {host}: no address");
    let mut tcp = None;
    for a in addrs {
        match TcpStream::connect_timeout(&a, CONNECT_TIMEOUT) {
            Ok(s) => {
                tcp = Some(s);
                break;
            }
            Err(e) => last = format!("connect {a}: {e}"),
        }
    }
    let tcp = tcp.ok_or(last)?;
    let _ = tcp.set_nodelay(true);
    tcp.set_read_timeout(Some(CONNECT_TIMEOUT))
        .map_err(|e| e.to_string())?;
    tcp.set_write_timeout(Some(CONNECT_TIMEOUT))
        .map_err(|e| e.to_string())?;
    let connector = if secure {
        tungstenite::Connector::Rustls(tls_config())
    } else {
        tungstenite::Connector::Plain
    };
    let (ws, _resp) = tungstenite::client_tls_with_config(req, tcp, None, Some(connector))
        .map_err(|e| match e {
            tungstenite::HandshakeError::Failure(tungstenite::Error::Http(resp)) => {
                let body = resp
                    .body()
                    .as_ref()
                    .map(|b| String::from_utf8_lossy(&b[..b.len().min(300)]).into_owned())
                    .unwrap_or_default();
                format!(
                    "ws handshake rejected: HTTP {} {}",
                    resp.status().as_u16(),
                    body.trim()
                )
            }
            tungstenite::HandshakeError::Failure(e) => format!("ws handshake failed: {e}"),
            tungstenite::HandshakeError::Interrupted(_) => "ws handshake timed out".into(),
        })?;
    let sock = match ws.get_ref() {
        MaybeTlsStream::Plain(s) => s,
        MaybeTlsStream::Rustls(s) => s.get_ref(),
        _ => return Err("unexpected stream type".into()),
    };
    sock.set_read_timeout(Some(POLL))
        .map_err(|e| e.to_string())?;
    Ok(ws)
}

fn is_timeout(e: &tungstenite::Error) -> bool {
    matches!(e, tungstenite::Error::Io(io) if matches!(io.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut))
}

fn io_loop(
    mut ws: Ws,
    sh: &ConnShared,
    out_rx: Receiver<Out>,
    post: &dyn Fn(WsEvent),
    last_error: &Mutex<String>,
) {
    // 握手期间宿主可能已经 close()：直接退出，不回调。
    // The host may have closed us during the handshake: exit quietly.
    match out_rx.try_recv() {
        Ok(Out::Close) | Err(TryRecvError::Disconnected) => {
            let _ = ws.close(None);
            let _ = ws.flush();
            sh.set_state(WS_CLOSED);
            return;
        }
        Ok(first) => {
            sh.set_state(WS_OPEN);
            post(WsEvent::Open);
            if !send_out(&mut ws, first, sh, post, last_error) {
                return;
            }
        }
        Err(TryRecvError::Empty) => {
            sh.set_state(WS_OPEN);
            post(WsEvent::Open);
        }
    }
    let mut remote_closed = false;
    loop {
        loop {
            match out_rx.try_recv() {
                Ok(o) => {
                    if !send_out(&mut ws, o, sh, post, last_error) {
                        return;
                    }
                }
                Err(TryRecvError::Empty) => break,
                Err(TryRecvError::Disconnected) => {
                    host_close(&mut ws, sh);
                    return;
                }
            }
        }
        match ws.read() {
            Ok(Message::Text(t)) => post(WsEvent::Text(t.as_bytes().to_vec())),
            Ok(Message::Binary(b)) => {
                let v = b.to_vec();
                sh.push(v.clone());
                post(WsEvent::Binary(v));
            }
            Ok(Message::Close(frame)) => {
                if !remote_closed {
                    remote_closed = true;
                    sh.set_state(WS_CLOSED);
                    let (code, reason) = frame
                        .map(|f| (u16::from(f.code), f.reason.to_string()))
                        .unwrap_or((1005, String::new()));
                    post(WsEvent::Close(code, reason));
                }
            }
            Ok(_) => {}
            Err(e) if is_timeout(&e) => {}
            Err(tungstenite::Error::ConnectionClosed) | Err(tungstenite::Error::AlreadyClosed) => {
                if !remote_closed {
                    sh.set_state(WS_CLOSED);
                    post(WsEvent::Close(1000, String::new()));
                }
                return;
            }
            Err(e) => {
                if remote_closed {
                    return;
                }
                let msg = format!("ws: {e}");
                *last_error.lock().unwrap() = msg.clone();
                sh.set_state(WS_CLOSED);
                post(WsEvent::Error(msg));
                return;
            }
        }
    }
}

/// 返回 false 表示连接已结束，IO 线程应退出。Returns false when the thread should exit.
fn send_out(
    ws: &mut Ws,
    o: Out,
    sh: &ConnShared,
    post: &dyn Fn(WsEvent),
    last_error: &Mutex<String>,
) -> bool {
    let msg = match o {
        Out::Text(t) => Message::text(t),
        Out::Binary(b) => Message::binary(b),
        Out::Close => {
            host_close(ws, sh);
            return false;
        }
    };
    match ws.send(msg) {
        Ok(()) => true,
        Err(e) if is_timeout(&e) => true,
        Err(e) => {
            if sh.state() == WS_OPEN {
                let m = format!("ws send: {e}");
                *last_error.lock().unwrap() = m.clone();
                sh.set_state(WS_CLOSED);
                post(WsEvent::Error(m));
            }
            false
        }
    }
}

/// 宿主主动关闭：发 Close 帧，最多等 500ms 对端回应，不回调。
/// Host-initiated close: send a Close frame, wait ≤500 ms for the reply, no callbacks.
fn host_close(ws: &mut Ws, sh: &ConnShared) {
    sh.set_state(WS_CLOSING);
    let _ = ws.close(None);
    let deadline = Instant::now() + Duration::from_millis(500);
    while Instant::now() < deadline {
        match ws.read() {
            Ok(_) => {}
            Err(e) if is_timeout(&e) => {}
            Err(_) => break,
        }
    }
    sh.set_state(WS_CLOSED);
}

// ================================================================ HTTP

pub struct HttpResponse {
    pub status: u16,
    pub body: Vec<u8>,
    pub headers: Vec<(String, String)>,
}

pub fn agent() -> ureq::Agent {
    ureq::Agent::config_builder()
        .http_status_as_error(false)
        // 重定向由我们手动跟随，每一跳都重新校验白名单。 Redirects are followed manually.
        .max_redirects(0)
        .max_redirects_will_error(false)
        .timeout_global(Some(Duration::from_secs(30)))
        .build()
        .into()
}

const MAX_BODY: u64 = 64 * 1024 * 1024;

/// 最多跟随几次重定向。 Max redirects followed.
const MAX_REDIRECTS: usize = 5;

/// 把 Location 解析成绝对 URL。 Resolve a Location header against the current URL.
fn resolve_location(base: &str, loc: &str) -> Option<String> {
    if loc.starts_with("http://") || loc.starts_with("https://") {
        return Some(loc.to_string());
    }
    let (scheme, rest) = base.split_once("://")?;
    let authority = rest.split(['/', '?', '#']).next()?;
    if let Some(r) = loc.strip_prefix("//") {
        return Some(format!("{scheme}://{r}"));
    }
    if loc.starts_with('/') {
        return Some(format!("{scheme}://{authority}{loc}"));
    }
    let path = &rest[authority.len()..];
    let path = path.split(['?', '#']).next().unwrap_or("");
    let dir = path.rsplit_once('/').map(|(d, _)| d).unwrap_or("");
    Some(format!("{scheme}://{authority}{dir}/{loc}"))
}

fn send_once(
    agent: &ureq::Agent,
    method: &str,
    url: &str,
    headers: &[(String, Vec<u8>)],
    body: Option<&[u8]>,
    timeout: Duration,
) -> Result<ureq::http::Response<ureq::Body>, String> {
    let mut b = ureq::http::Request::builder().method(method).uri(url);
    for (k, v) in headers {
        b = b.header(k.as_str(), v.as_slice());
    }
    let res = match body {
        Some(body)
            if !body.is_empty() || !matches!(method, "GET" | "HEAD" | "DELETE" | "OPTIONS") =>
        {
            let req = b
                .body(body.to_vec())
                .map_err(|e| format!("invalid request: {e}"))?;
            let req = agent
                .configure_request(req)
                .timeout_global(Some(timeout))
                .build();
            agent.run(req)
        }
        _ => {
            let req = b.body(()).map_err(|e| format!("invalid request: {e}"))?;
            let req = agent
                .configure_request(req)
                .timeout_global(Some(timeout))
                .build();
            agent.run(req)
        }
    };
    res.map_err(|e| format!("http: {e}"))
}

/// 发请求并手动跟随重定向：每一跳都用 `allow` 重新校验主机，只允许 http/https，
/// 换主机时去掉凭据类请求头；303（以及 301/302 的非 GET）按规范改为 GET 且不带正文。
/// Send and follow redirects manually: every hop's host is re-checked with `allow`, only
/// http/https are allowed, credential headers are dropped across hosts, and 303 (or 301/302 on
/// non-GET) becomes a body-less GET.
fn build_request(
    agent: &ureq::Agent,
    method: &str,
    url: &str,
    headers: &[(String, Vec<u8>)],
    body: Option<Vec<u8>>,
    timeout: Duration,
    allow: &dyn Fn(&str) -> bool,
) -> Result<ureq::http::Response<ureq::Body>, String> {
    let mut method = method.to_ascii_uppercase();
    let mut url = url.to_string();
    let mut headers: Vec<(String, Vec<u8>)> = headers.to_vec();
    let mut body = body;
    let origin = url_host(&url);
    for _ in 0..=MAX_REDIRECTS {
        let resp = send_once(agent, &method, &url, &headers, body.as_deref(), timeout)?;
        let status = resp.status().as_u16();
        if !matches!(status, 301 | 302 | 303 | 307 | 308) {
            return Ok(resp);
        }
        let Some(loc) = resp.headers().get("location").and_then(|v| v.to_str().ok()) else {
            return Ok(resp);
        };
        let next = resolve_location(&url, loc).ok_or_else(|| format!("bad redirect: {loc}"))?;
        if !(next.starts_with("https://") || next.starts_with("http://")) {
            return Err(format!("redirect to unsupported scheme: {next}"));
        }
        let host = url_host(&next).ok_or_else(|| format!("bad redirect: {next}"))?;
        if !allow(&host) {
            return Err(format!(
                "redirect to host not declared in manifest.network.hosts: {host}"
            ));
        }
        if Some(&host) != origin.as_ref() {
            headers.retain(|(k, _)| {
                !matches!(
                    k.to_ascii_lowercase().as_str(),
                    "authorization" | "cookie" | "proxy-authorization"
                )
            });
        }
        if status == 303 || (matches!(status, 301 | 302) && method != "GET" && method != "HEAD") {
            method = "GET".into();
            body = None;
        }
        url = next;
    }
    Err(format!("too many redirects (>{MAX_REDIRECTS})"))
}

fn response_headers(resp: &ureq::http::Response<ureq::Body>) -> Vec<(String, String)> {
    resp.headers()
        .iter()
        .map(|(k, v)| {
            (
                k.as_str().to_string(),
                String::from_utf8_lossy(v.as_bytes()).into_owned(),
            )
        })
        .collect()
}

pub fn request(
    agent: &ureq::Agent,
    method: &str,
    url: &str,
    headers: &[(String, Vec<u8>)],
    body: Option<Vec<u8>>,
    timeout: Duration,
    allow: &dyn Fn(&str) -> bool,
) -> Result<HttpResponse, String> {
    let mut resp = build_request(agent, method, url, headers, body, timeout, allow)?;
    let status = resp.status().as_u16();
    let headers = response_headers(&resp);
    let body = resp
        .body_mut()
        .with_config()
        .limit(MAX_BODY)
        .read_to_vec()
        .map_err(|e| format!("http body: {e}"))?;
    Ok(HttpResponse {
        status,
        body,
        headers,
    })
}

pub enum StreamEvent {
    Data(Vec<u8>),
    Done(Vec<u8>),
    Error(String),
}

/// SSE：逐行读，`data:` 行的载荷（去掉前缀与一个空格）立即回调；结束时回调完整原文。
/// 非 2xx 状态直接报错（附带响应体开头）。
///
/// SSE: read line by line; each `data:` payload (prefix and one space stripped) is emitted at
/// once; the whole raw body is passed to Done. Non-2xx responses become errors.
pub fn stream(
    agent: &ureq::Agent,
    method: &str,
    url: &str,
    headers: &[(String, Vec<u8>)],
    body: Option<Vec<u8>>,
    timeout: Duration,
    allow: &dyn Fn(&str) -> bool,
    emit: &dyn Fn(StreamEvent),
) {
    let mut resp = match build_request(agent, method, url, headers, body, timeout, allow) {
        Ok(r) => r,
        Err(e) => return emit(StreamEvent::Error(e)),
    };
    let status = resp.status().as_u16();
    let mut reader = BufReader::new(resp.body_mut().with_config().limit(MAX_BODY).reader());
    if !(200..300).contains(&status) {
        let mut buf = Vec::new();
        let _ = std::io::Read::read_to_end(&mut std::io::Read::take(&mut reader, 512), &mut buf);
        return emit(StreamEvent::Error(format!(
            "HTTP {status} {}",
            String::from_utf8_lossy(&buf).trim()
        )));
    }
    let mut all = Vec::new();
    let mut line = Vec::new();
    loop {
        line.clear();
        match reader.read_until(b'\n', &mut line) {
            Ok(0) => break,
            Ok(_) => {
                all.extend_from_slice(&line);
                let mut l: &[u8] = &line;
                while let Some(x) = l.strip_suffix(b"\n").or_else(|| l.strip_suffix(b"\r")) {
                    l = x;
                }
                if let Some(payload) = l.strip_prefix(b"data:") {
                    let payload = payload.strip_prefix(b" ").unwrap_or(payload);
                    emit(StreamEvent::Data(payload.to_vec()));
                }
            }
            Err(e) => return emit(StreamEvent::Error(format!("http stream: {e}"))),
        }
    }
    emit(StreamEvent::Done(all));
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hosts() {
        assert_eq!(url_host("wss://a.B.com/x?y").as_deref(), Some("a.b.com"));
        assert_eq!(
            url_host("https://u:p@h.com:8443/").as_deref(),
            Some("h.com")
        );
        assert_eq!(url_host("http://[::1]:80/").as_deref(), Some("::1"));
        assert_eq!(url_host("h.com"), None);
    }

    /// 本地起一个 ws 回显服务端，验证收发、awaitBinary、关闭事件。
    /// Local echo server: send/receive, awaitBinary and close events.
    #[test]
    fn ws_local_echo() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        std::thread::spawn(move || {
            let (s, _) = listener.accept().unwrap();
            let mut ws = tungstenite::accept(s).unwrap();
            loop {
                match ws.read() {
                    Ok(Message::Text(t)) if t.as_str() == "bye" => {
                        ws.close(None).unwrap();
                    }
                    Ok(m @ (Message::Text(_) | Message::Binary(_))) => ws.send(m).unwrap(),
                    Ok(_) => {}
                    Err(_) => break,
                }
            }
        });
        let (tx, rx) = mpsc::channel();
        let err = Arc::new(Mutex::new(String::new()));
        let conn = WsConn::connect(
            &format!("ws://127.0.0.1:{port}/"),
            vec![("X-Test".into(), b"1".to_vec())],
            err,
            move |e| {
                let _ = tx.send(e);
            },
        )
        .unwrap();
        assert!(matches!(
            rx.recv_timeout(Duration::from_secs(5)).unwrap(),
            WsEvent::Open
        ));
        assert_eq!(conn.state(), WS_OPEN);
        assert!(conn.send_text("hi".into()));
        assert!(
            matches!(rx.recv_timeout(Duration::from_secs(5)).unwrap(), WsEvent::Text(t) if t == b"hi")
        );
        assert!(conn.send_binary(vec![1, 2, 3]));
        assert_eq!(
            conn.shared.await_binary(Duration::from_secs(5)).unwrap(),
            vec![1, 2, 3]
        );
        assert!(conn.send_text("bye".into()));
        let mut closed = false;
        while let Ok(ev) = rx.recv_timeout(Duration::from_secs(5)) {
            if let WsEvent::Close(..) = ev {
                closed = true;
                break;
            }
        }
        assert!(closed);
        assert_eq!(conn.state(), WS_CLOSED);
        assert!(!conn.send_text("x".into()));
        assert!(conn
            .shared
            .await_binary(Duration::from_millis(10))
            .is_none());
    }
}
