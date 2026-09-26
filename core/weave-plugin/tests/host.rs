//! 端到端测试：本文件自写的模拟插件 + 本地模拟 WebSocket / HTTP 服务端，只走公开 API。
//! End-to-end tests: self-written mock plugins against local mock WebSocket / HTTP servers,
//! using the public API only.

use std::fs;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use tungstenite::handshake::server::{Request, Response};
use tungstenite::Message;
use weave_plugin::{PluginManager, SpeechListener};

// ---------------------------------------------------------------- 工具 / helpers

fn temp_dir(tag: &str) -> PathBuf {
    use std::sync::atomic::{AtomicU32, Ordering};
    static N: AtomicU32 = AtomicU32::new(0);
    let d = std::env::temp_dir().join(format!(
        "weave-plugin-it-{tag}-{}-{}",
        std::process::id(),
        N.fetch_add(1, Ordering::SeqCst)
    ));
    let _ = fs::remove_dir_all(&d);
    fs::create_dir_all(&d).unwrap();
    d
}

fn write_plugin(dir: &Path, manifest: &str, main: &str, resources: &[(&str, &[u8])]) {
    fs::create_dir_all(dir.join("resources")).unwrap();
    fs::write(dir.join("manifest.yaml"), manifest).unwrap();
    fs::write(dir.join("main.lua"), main).unwrap();
    for (name, data) in resources {
        let p = dir.join("resources").join(name);
        fs::create_dir_all(p.parent().unwrap()).unwrap();
        fs::write(p, data).unwrap();
    }
}

#[derive(Debug, Clone, PartialEq)]
enum Ev {
    Partial(String),
    Final(String),
    Replace(String, String),
    Error(String),
    End,
    Log(u8, String),
}

struct Recorder {
    events: Mutex<Vec<Ev>>,
    tx: Mutex<Sender<Ev>>,
}

impl Recorder {
    fn new() -> (Arc<Recorder>, Receiver<Ev>) {
        let (tx, rx) = mpsc::channel();
        (
            Arc::new(Recorder {
                events: Mutex::new(Vec::new()),
                tx: Mutex::new(tx),
            }),
            rx,
        )
    }

    fn push(&self, e: Ev) {
        self.events.lock().unwrap().push(e.clone());
        let _ = self.tx.lock().unwrap().send(e);
    }

    fn events(&self) -> Vec<Ev> {
        self.events.lock().unwrap().clone()
    }

    /// 不含日志的事件。Events without logs.
    fn results(&self) -> Vec<Ev> {
        self.events()
            .into_iter()
            .filter(|e| !matches!(e, Ev::Log(..)))
            .collect()
    }
}

impl SpeechListener for Recorder {
    fn on_partial(&self, text: &str) {
        self.push(Ev::Partial(text.into()));
    }
    fn on_final(&self, text: &str) {
        self.push(Ev::Final(text.into()));
    }
    fn on_replace(&self, old: &str, new: &str) {
        self.push(Ev::Replace(old.into(), new.into()));
    }
    fn on_error(&self, msg: &str) {
        self.push(Ev::Error(msg.into()));
    }
    fn on_end(&self) {
        self.push(Ev::End);
    }
    fn on_log(&self, level: u8, msg: &str) {
        self.push(Ev::Log(level, msg.into()));
    }
}

/// 等到满足条件的事件出现。Wait until an event matching `pred` arrives.
fn wait_for(rx: &Receiver<Ev>, secs: u64, pred: impl Fn(&Ev) -> bool) -> Ev {
    let deadline = std::time::Instant::now() + Duration::from_secs(secs);
    loop {
        let left = deadline.saturating_duration_since(std::time::Instant::now());
        match rx.recv_timeout(left) {
            Ok(e) if pred(&e) => return e,
            Ok(_) => {}
            Err(_) => panic!("timed out waiting for event"),
        }
    }
}

// ---------------------------------------------------------------- WebSocket 流式识别 / ws streaming

/// 模拟识别服务：每收到一帧二进制音频回一条 partial（累计字节数），
/// 收到文本 `{"cmd":"end"}` 回 final 并关闭。握手时记录 `X-Token` 请求头。
///
/// Mock recognizer: one partial (running byte count) per binary frame; on `{"cmd":"end"}`
/// it sends the final result and closes. Records the `X-Token` handshake header.
fn mock_asr_server() -> (u16, Arc<Mutex<Vec<String>>>) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let tokens = Arc::new(Mutex::new(Vec::new()));
    let tk = tokens.clone();
    std::thread::spawn(move || {
        for stream in listener.incoming() {
            let Ok(s) = stream else { break };
            let tk = tk.clone();
            std::thread::spawn(move || {
                let cb = |req: &Request, resp: Response| {
                    let t = req
                        .headers()
                        .get("X-Token")
                        .and_then(|v| v.to_str().ok())
                        .unwrap_or("")
                        .to_string();
                    tk.lock().unwrap().push(t);
                    Ok(resp)
                };
                let Ok(mut ws) = tungstenite::accept_hdr(s, cb) else {
                    return;
                };
                let mut total = 0usize;
                loop {
                    match ws.read() {
                        Ok(Message::Binary(b)) => {
                            total += b.len();
                            let _ = ws.send(Message::text(format!(r#"{{"partial":"{total}"}}"#)));
                        }
                        Ok(Message::Text(t)) if t.as_str().contains("end") => {
                            let _ = ws
                                .send(Message::text(format!(r#"{{"final":"got {total} bytes"}}"#)));
                            let _ = ws.close(None);
                        }
                        Ok(_) => {}
                        Err(_) => break,
                    }
                }
            });
        }
    });
    (port, tokens)
}

const WS_MANIFEST: &str = r#"
id: org.example.mock.ws
name: Mock WS
description: mock streaming recognizer
version: 1.0.0
type: speech
icon: icon.png
network:
  hosts:
    - 127.0.0.1
configSchema:
  - key: url
    type: text
  - key: token
    type: text
    defaultValue: ""
"#;

const WS_MAIN: &str = r#"
local plugin = {}
local ready, stopping, buf = false, false, {}

local function send_end()
    host.ws.sendText(host.json.encode({ cmd = "end" }))
end

function plugin.isConfigured()
    local t = host.config.get("token")
    return t ~= nil and t ~= ""
end

function plugin.start()
    ready, stopping, buf = false, false, {}
    host.ws.close()
    return host.ws.connect(host.config.get("url"), { ["X-Token"] = host.config.get("token") }, {
        onOpen = function()
            ready = true
            for _, c in ipairs(buf) do host.ws.sendBinary(c) end
            buf = {}
            if stopping then send_end() end
        end,
        onMessage = function(text)
            local o = host.json.decode(text)
            if o.partial then
                host.asr.emitPartial(o.partial)
            elseif o.final then
                host.asr.emitFinal(o.final)
                -- 会话结束后才回填：宿主应仍然转交给监听者
                host.timer.setTimeout(50, function()
                    host.asr.emitReplace(o.final, string.upper(o.final))
                end)
            end
        end,
        onError = function(msg) host.asr.emitError(msg); host.asr.emitEnd() end,
        onClose = function() host.asr.emitEnd() end,
    })
end

function plugin.processAudioChunk(pcm)
    if ready then host.ws.sendBinary(pcm) else buf[#buf + 1] = pcm end
end

function plugin.stop()
    stopping = true
    if ready then send_end() end
end

function plugin.cancel()
    host.ws.close()
    host.log("cancelled")
end

return plugin
"#;

#[test]
fn ws_streaming_session() {
    let (port, tokens) = mock_asr_server();
    let root = temp_dir("ws");
    let pdir = root.join("src/mock-ws");
    write_plugin(&pdir, WS_MANIFEST, WS_MAIN, &[("icon.png", b"\x89PNG\r\n")]);

    let mut mgr = PluginManager::new(root.join("plugins"), root.join("config"));
    let info = mgr.add_path(&pdir).unwrap();
    assert_eq!(info.id, "org.example.mock.ws");
    assert_eq!(info.kind, "speech");
    assert_eq!(info.icon_png.as_deref(), Some(&b"\x89PNG\r\n"[..]));
    assert!(info.config_schema_json.contains("\"token\""));

    // isConfigured 跟着配置变化。isConfigured follows the config.
    assert!(!mgr.is_configured(&info.id));
    mgr.set_config(&info.id, "token", "secret-1");
    mgr.set_config(&info.id, "url", &format!("ws://127.0.0.1:{port}/asr"));
    assert!(mgr.is_configured(&info.id));

    for round in 0..2 {
        let (rec, rx) = Recorder::new();
        let session = mgr.start_speech(&info.id, rec.clone()).unwrap();
        for _ in 0..5 {
            session.feed(&[0u8; 640]);
        }
        wait_for(&rx, 5, |e| e == &Ev::Partial("3200".into()));
        session.stop();
        wait_for(&rx, 5, |e| e == &Ev::End);
        let rep = wait_for(&rx, 5, |e| matches!(e, Ev::Replace(..)));
        assert_eq!(
            rep,
            Ev::Replace("got 3200 bytes".into(), "GOT 3200 BYTES".into()),
            "round {round}"
        );
        drop(session);
        std::thread::sleep(Duration::from_millis(100));

        let res = rec.results();
        let finals: Vec<_> = res.iter().filter(|e| matches!(e, Ev::Final(_))).collect();
        assert_eq!(finals, vec![&Ev::Final("got 3200 bytes".into())]);
        assert_eq!(
            res.iter().filter(|e| **e == Ev::End).count(),
            1,
            "exactly one on_end: {res:?}"
        );
        let end_at = res.iter().position(|e| *e == Ev::End).unwrap();
        let final_at = res.iter().position(|e| matches!(e, Ev::Final(_))).unwrap();
        assert!(final_at < end_at);
        assert!(!res.iter().any(|e| matches!(e, Ev::Error(_))), "{res:?}");
        // 宿主收尾时调了 plugin.cancel()。The host called plugin.cancel() when finishing.
        assert!(rec.events().contains(&Ev::Log(1, "cancelled".into())));
    }
    assert_eq!(
        *tokens.lock().unwrap(),
        vec!["secret-1".to_string(), "secret-1".to_string()]
    );
    let _ = fs::remove_dir_all(&root);
}

#[test]
fn drop_session_cancels() {
    let (port, _) = mock_asr_server();
    let root = temp_dir("drop");
    let pdir = root.join("plugins/mock-ws");
    write_plugin(&pdir, WS_MANIFEST, WS_MAIN, &[]);
    let mut mgr = PluginManager::new(root.join("plugins"), root.join("config"));
    mgr.scan();
    let id = "org.example.mock.ws";
    mgr.set_config(id, "token", "t");
    mgr.set_config(id, "url", &format!("ws://127.0.0.1:{port}/"));
    let (rec, rx) = Recorder::new();
    let session = mgr.start_speech(id, rec.clone()).unwrap();
    session.feed(&[1u8; 320]);
    wait_for(&rx, 5, |e| matches!(e, Ev::Partial(_)));
    drop(session);
    wait_for(&rx, 5, |e| e == &Ev::End);
    std::thread::sleep(Duration::from_millis(100));
    let ev = rec.events();
    assert!(ev.contains(&Ev::Log(1, "cancelled".into())));
    assert!(!ev.iter().any(|e| matches!(e, Ev::Final(_))));
    assert_eq!(ev.iter().filter(|e| **e == Ev::End).count(), 1);
    let _ = fs::remove_dir_all(&root);
}

// ---------------------------------------------------------------- HTTP 与 SSE / http + SSE

/// 极简 HTTP/1.1 服务端：`GET /hello` 回显请求头 X-A；`POST /sse` 以 SSE 推三条数据再加 `[DONE]`。
/// Tiny HTTP/1.1 server: `GET /hello` echoes header X-A; `POST /sse` streams three SSE events.
fn mock_http_server() -> (u16, Arc<Mutex<Vec<String>>>) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let bodies = Arc::new(Mutex::new(Vec::new()));
    let b2 = bodies.clone();
    std::thread::spawn(move || {
        for stream in listener.incoming() {
            let Ok(s) = stream else { break };
            let b2 = b2.clone();
            std::thread::spawn(move || handle_http(s, &b2));
        }
    });
    (port, bodies)
}

fn handle_http(s: TcpStream, bodies: &Mutex<Vec<String>>) {
    let mut r = BufReader::new(s.try_clone().unwrap());
    let mut line = String::new();
    r.read_line(&mut line).unwrap();
    let mut parts = line.split_whitespace();
    let (method, path) = (
        parts.next().unwrap_or("").to_string(),
        parts.next().unwrap_or("").to_string(),
    );
    let mut len = 0usize;
    let mut xa = String::new();
    loop {
        let mut h = String::new();
        r.read_line(&mut h).unwrap();
        let h = h.trim_end();
        if h.is_empty() {
            break;
        }
        let (k, v) = h.split_once(':').unwrap_or((h, ""));
        match k.to_ascii_lowercase().as_str() {
            "content-length" => len = v.trim().parse().unwrap_or(0),
            "x-a" => xa = v.trim().to_string(),
            _ => {}
        }
    }
    let mut body = vec![0u8; len];
    r.read_exact(&mut body).unwrap();
    bodies.lock().unwrap().push(format!(
        "{method} {path} {}",
        String::from_utf8_lossy(&body)
    ));
    let mut w = s;
    match (method.as_str(), path.as_str()) {
        ("GET", "/hello") => {
            let b = format!("hello {xa}");
            let _ = write!(w, "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nX-Reply: yes\r\nConnection: close\r\n\r\n{b}", b.len());
        }
        ("GET", "/redir-in") => {
            let _ = write!(w, "HTTP/1.1 302 Found\r\nLocation: /hello\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        }
        ("GET", "/redir-out") => {
            // 指向未声明的主机：宿主必须拒绝而不是跟过去。 Points to an undeclared host.
            let _ = write!(w, "HTTP/1.1 302 Found\r\nLocation: http://localhost:1/hello\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        }
        ("GET", "/redir-loop") => {
            let _ = write!(w, "HTTP/1.1 302 Found\r\nLocation: /redir-loop\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        }
        ("POST", "/redir-303") => {
            let _ = write!(w, "HTTP/1.1 303 See Other\r\nLocation: /hello\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        }
        ("POST", "/sse") => {
            let _ = write!(
                w,
                "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n"
            );
            for t in ["今天", "今天天气", "今天天气好"] {
                let _ = write!(w, "event: delta\ndata: {{\"t\":\"{t}\"}}\n\n");
                let _ = w.flush();
                std::thread::sleep(Duration::from_millis(20));
            }
            let _ = write!(w, "data: [DONE]\n\n");
        }
        _ => {
            let _ = write!(
                w,
                "HTTP/1.1 404 Not Found\r\nContent-Length: 4\r\nConnection: close\r\n\r\nnope"
            );
        }
    }
}

const HTTP_MANIFEST: &str = r#"
id: org.example.mock.http
name: Mock HTTP
version: "2"
type: speech
network:
  hosts: [127.0.0.1]
"#;

const HTTP_MAIN: &str = r#"
local plugin = {}
function plugin.start()
    local base = host.config.get("base")
    local r = host.http.request("GET", base .. "/hello", { ["X-A"] = "abc" }, nil, 3000)
    host.asr.emitPartial(r.status .. ":" .. r.body .. ":" .. tostring(r.headers["x-reply"]))
    local nf = host.http.request("GET", base .. "/missing")
    host.asr.emitPartial("missing:" .. nf.status)
    local denied = host.http.request("GET", "http://example.org/")
    host.asr.emitPartial("denied:" .. tostring(denied == nil) .. ":" .. tostring(host.http.lastError()))
    local ok = host.http.stream(base .. "/sse", { ["Accept"] = "text/event-stream" }, {
        onData = function(d)
            if d == "[DONE]" then return end
            host.asr.emitPartial(host.json.decode(d).t)
        end,
        onDone = function(all)
            host.asr.emitFinal("done " .. tostring(select(2, all:gsub("data:", ""))))
            host.asr.emitEnd()
        end,
        onError = function(m) host.asr.emitError(m); host.asr.emitEnd() end,
    }, 5000, "POST", '{"q":1}')
    return ok ~= false
end
function plugin.stop() end
function plugin.cancel() end
return plugin
"#;

#[test]
fn http_request_and_sse_stream() {
    let (port, bodies) = mock_http_server();
    let root = temp_dir("http");
    let pdir = root.join("p");
    write_plugin(&pdir, HTTP_MANIFEST, HTTP_MAIN, &[]);
    let mut mgr = PluginManager::new(root.join("plugins"), root.join("config"));
    let info = mgr.add_path(&pdir).unwrap();
    assert_eq!(info.version, "2");
    mgr.set_config(&info.id, "base", &format!("http://127.0.0.1:{port}"));
    let (rec, rx) = Recorder::new();
    let _session = mgr.start_speech(&info.id, rec.clone()).unwrap();
    wait_for(&rx, 10, |e| e == &Ev::End);
    let res = rec.results();
    assert_eq!(res[0], Ev::Partial("200:hello abc:yes".into()));
    assert_eq!(res[1], Ev::Partial("missing:404".into()));
    match &res[2] {
        Ev::Partial(p) => assert!(
            p.starts_with("denied:true:") && p.contains("example.org"),
            "{p}"
        ),
        other => panic!("{other:?}"),
    }
    assert_eq!(
        &res[3..],
        &[
            Ev::Partial("今天".into()),
            Ev::Partial("今天天气".into()),
            Ev::Partial("今天天气好".into()),
            Ev::Final("done 4".into()),
            Ev::End,
        ]
    );
    assert!(bodies
        .lock()
        .unwrap()
        .contains(&"POST /sse {\"q\":1}".to_string()));
    let _ = fs::remove_dir_all(&root);
}

const REDIRECT_MAIN: &str = r#"
local plugin = {}
local function show(tag, r)
    if r == nil then
        host.asr.emitPartial(tag .. ":nil:" .. tostring(host.http.lastError()))
    else
        host.asr.emitPartial(tag .. ":" .. r.status .. ":" .. r.body)
    end
end
function plugin.start()
    local base = host.config.get("base")
    show("in", host.http.request("GET", base .. "/redir-in", { ["X-A"] = "k" }))
    show("out", host.http.request("GET", base .. "/redir-out"))
    show("loop", host.http.request("GET", base .. "/redir-loop"))
    show("see-other", host.http.request("POST", base .. "/redir-303", { ["X-A"] = "p" }, "body"))
    local ok = host.http.stream(base .. "/redir-out", {}, {
        onData = function(d) host.asr.emitPartial("sse-data") end,
        onDone = function() host.asr.emitPartial("sse-done"); host.asr.emitEnd() end,
        onError = function(m) host.asr.emitPartial("sse-error:" .. m); host.asr.emitEnd() end,
    }, 3000, "GET")
    return ok ~= false
end
function plugin.stop() end
function plugin.cancel() end
return plugin
"#;

/// 重定向：同主机可跟随；跳到未声明主机、死循环都必须失败；303 改为 GET。
/// Redirects: same-host is followed; undeclared hosts and loops fail; 303 becomes GET.
#[test]
fn redirects_are_rechecked_against_allow_list() {
    let (port, bodies) = mock_http_server();
    let root = temp_dir("redirect");
    let pdir = root.join("p");
    write_plugin(&pdir, HTTP_MANIFEST, REDIRECT_MAIN, &[]);
    let mut mgr = PluginManager::new(root.join("plugins"), root.join("config"));
    let info = mgr.add_path(&pdir).unwrap();
    mgr.set_config(&info.id, "base", &format!("http://127.0.0.1:{port}"));
    let (rec, rx) = Recorder::new();
    let _session = mgr.start_speech(&info.id, rec.clone()).unwrap();
    wait_for(&rx, 10, |e| e == &Ev::End);
    let res: Vec<String> = rec
        .results()
        .into_iter()
        .filter_map(|e| {
            if let Ev::Partial(p) = e {
                Some(p)
            } else {
                None
            }
        })
        .collect();
    assert_eq!(res[0], "in:200:hello k");
    assert!(
        res[1].starts_with("out:nil:") && res[1].contains("localhost"),
        "{}",
        res[1]
    );
    assert!(
        res[2].starts_with("loop:nil:") && res[2].contains("too many redirects"),
        "{}",
        res[2]
    );
    assert_eq!(res[3], "see-other:200:hello p");
    assert!(
        res[4].starts_with("sse-error:") && res[4].contains("localhost"),
        "{}",
        res[4]
    );
    // 303 之后以 GET 请求 /hello。 After 303 the follow-up is a GET.
    assert!(bodies
        .lock()
        .unwrap()
        .iter()
        .any(|b| b.starts_with("GET /hello")));
    let _ = fs::remove_dir_all(&root);
}

// ---------------------------------------------------------------- 定时器 / timers

const TIMER_MAIN: &str = r#"
local plugin = {}
function plugin.start()
    local n = 0
    local id
    id = host.timer.setInterval(15, function()
        n = n + 1
        host.asr.emitPartial(tostring(n))
        if n == 3 then
            host.timer.cancel(id)
            host.timer.setTimeout(60, function()
                host.asr.emitFinal("ticks " .. n)
                host.asr.emitEnd()
            end)
        end
    end)
    local dead = host.timer.setTimeout(10, function() host.asr.emitError("cancelled timer fired") end)
    host.timer.cancel(dead)
    return true
end
function plugin.stop() end
return plugin
"#;

#[test]
fn timers_interval_timeout_cancel() {
    let root = temp_dir("timer");
    let pdir = root.join("p");
    write_plugin(
        &pdir,
        "id: org.example.mock.timer\ntype: speech\n",
        TIMER_MAIN,
        &[],
    );
    let mut mgr = PluginManager::new(root.join("plugins"), root.join("config"));
    let id = mgr.add_path(&pdir).unwrap().id;
    let (rec, rx) = Recorder::new();
    let _s = mgr.start_speech(&id, rec.clone()).unwrap();
    wait_for(&rx, 5, |e| e == &Ev::End);
    std::thread::sleep(Duration::from_millis(80));
    assert_eq!(
        rec.results(),
        vec![
            Ev::Partial("1".into()),
            Ev::Partial("2".into()),
            Ev::Partial("3".into()),
            Ev::Final("ticks 3".into()),
            Ev::End
        ]
    );
    let _ = fs::remove_dir_all(&root);
}

// ---------------------------------------------------------------- config / resource / crypto / json

const PROBE_MANIFEST: &str = r#"
id: org.example.mock.probe
name: Probe
version: 0.1
type: tool
configSchema:
  - key: lang
    type: select
    defaultValue: zh
  - key: fast
    type: switch
    defaultValue: true
"#;

const PROBE_MAIN: &str = r#"
local plugin = {}
local C = host.crypto

function plugin.config()
    local before = { lang = host.config.get("lang"), fast = host.config.get("fast"), missing = host.config.get("nope") }
    host.config.set("fromLua", "值")
    host.config.set("tmp", "x")
    host.config.remove("tmp")
    return { before = before, tmp = host.config.get("tmp") }
end

function plugin.resource()
    return {
        read = host.resource.read("data/words.txt"),
        at = host.resource.readAt("data/words.txt", 6, 5),
        exists = host.resource.exists("data/words.txt"),
        missing = host.resource.exists("nope.bin"),
        escape = host.resource.read("../manifest.yaml") == nil,
    }
end

function plugin.crypto()
    local key = C.hexDecode("2b7e151628aed2a6abf7158809cf4f3c")
    local iv = C.hexDecode("000102030405060708090a0b0c0d0e0f")
    local pt = C.hexDecode("6bc1bee22e409f96e93d7e117393172a")
    local ct = C.symEncrypt("AES/CBC/NoPadding", key, pt, iv)
    local padded = C.symEncrypt("AES/CBC/PKCS5Padding", key, "hello", iv)
    local ok, err = C.symEncrypt("AES/CBC/PKCS5Padding", "short", "x", iv)
    local kp = C.ecGenerateKeypair("secp128r1")
    local kp2 = C.ecGenerateKeypair("secp128r1")
    return {
        md5 = C.hex(C.md5("abc")),
        sha1 = C.hex(C.sha1("abc")),
        sha256 = C.hex(C.sha256("abc")),
        hmac = C.hex(C.hmacSha256("Jefe", "what do ya want for nothing?")),
        b64 = C.base64("foobar"),
        b64d = C.base64Decode("Zm9vYmE="),
        b64url = C.base64UrlEncode("\251\255"),
        url = C.urlEncode("a b&c=中"),
        aes = C.hex(ct),
        aesBack = C.hex(C.symDecrypt("AES/CBC/NoPadding", key, ct, iv)),
        padded = C.symDecrypt("AES/CBC/PKCS5Padding", key, padded, iv),
        badKey = (ok == nil and type(err) == "string"),
        rand = #C.randomBytes(24),
        ecdh = C.ecdhSharedX("secp128r1", kp.privateHex, kp2.publicHex) == C.ecdhSharedX("secp128r1", kp2.privateHex, kp.publicHex),
        uuid = #host.uuid(),
        epoch = C.epochSeconds() > 1700000000,
    }
end

function plugin.json(text)
    local v, err = host.json.decode(text)
    if v == nil then return { err = err } end
    local back = host.json.encode(v)
    local again = host.json.encode(host.json.decode(back))
    return { back = back, stable = back == again, empty = host.json.encode({}), arr = host.json.encode({ 1, 2, "x" }),
             intType = math.type(v.n) }
end

function plugin.sandbox()
    return { io = io == nil, exec = os.execute == nil, dofile = dofile == nil, time = type(os.time()) }
end

return plugin
"#;

fn probe_mgr(tag: &str) -> (PathBuf, PluginManager, String) {
    let root = temp_dir(tag);
    let pdir = root.join("probe");
    write_plugin(
        &pdir,
        PROBE_MANIFEST,
        PROBE_MAIN,
        &[("data/words.txt", "hello world\n".as_bytes())],
    );
    let mut mgr = PluginManager::new(root.join("plugins"), root.join("config"));
    let id = mgr.add_path(&pdir).unwrap().id;
    (root, mgr, id)
}

fn call(mgr: &mut PluginManager, id: &str, method: &str, args: &str) -> serde_json::Value {
    let out = mgr.call(id, method, args, Duration::from_secs(5)).unwrap();
    serde_json::from_str(&out).unwrap()
}

#[test]
fn config_defaults_and_persistence() {
    let (root, mut mgr, id) = probe_mgr("cfg");
    assert_eq!(mgr.get_config(&id, "lang").as_deref(), Some("zh"));
    mgr.set_config(&id, "lang", "en");
    let v = call(&mut mgr, &id, "config", "[]");
    assert_eq!(v["before"]["lang"], "en");
    assert_eq!(v["before"]["fast"], "true");
    assert!(v["before"].get("missing").is_none());
    assert!(v.get("tmp").is_none());
    assert_eq!(mgr.get_config(&id, "fromLua").as_deref(), Some("值"));
    // 非语音插件不能开语音会话。Non-speech plugins can't start speech sessions.
    let (rec, _rx) = Recorder::new();
    assert!(mgr.start_speech(&id, rec).is_err());
    drop(mgr);

    // 重新打开：配置落盘了。Reopen: values were persisted.
    let mut mgr = PluginManager::new(root.join("plugins"), root.join("config"));
    mgr.add_path(&root.join("probe")).unwrap();
    assert_eq!(mgr.get_config(&id, "lang").as_deref(), Some("en"));
    assert_eq!(mgr.get_config(&id, "fromLua").as_deref(), Some("值"));
    // 没有 isConfigured() 的插件视为已配置。No isConfigured() means configured.
    assert!(mgr.is_configured(&id));
    let _ = fs::remove_dir_all(&root);
}

#[test]
fn resources_and_sandbox() {
    let (root, mut mgr, id) = probe_mgr("res");
    let v = call(&mut mgr, &id, "resource", "[]");
    assert_eq!(v["read"], "hello world\n");
    assert_eq!(v["at"], "world");
    assert_eq!(v["exists"], true);
    assert_eq!(v["missing"], false);
    assert_eq!(v["escape"], true);
    let s = call(&mut mgr, &id, "sandbox", "[]");
    assert_eq!(
        s,
        serde_json::json!({"io": true, "exec": true, "dofile": true, "time": "number"})
    );
    let _ = fs::remove_dir_all(&root);
}

#[test]
fn crypto_vectors_from_lua() {
    let (root, mut mgr, id) = probe_mgr("crypto");
    let v = call(&mut mgr, &id, "crypto", "[]");
    assert_eq!(v["md5"], "900150983cd24fb0d6963f7d28e17f72");
    assert_eq!(v["sha1"], "a9993e364706816aba3e25717850c26c9cd0d89d");
    assert_eq!(
        v["sha256"],
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    );
    // RFC 4231 test case 2
    assert_eq!(
        v["hmac"],
        "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
    );
    assert_eq!(v["b64"], "Zm9vYmFy");
    assert_eq!(v["b64d"], "fooba");
    assert_eq!(v["b64url"], "-_8");
    assert_eq!(v["url"], "a%20b%26c%3D%E4%B8%AD");
    // NIST SP 800-38A F.2.1
    assert_eq!(v["aes"], "7649abac8119b246cee98e9b12e9197d");
    assert_eq!(v["aesBack"], "6bc1bee22e409f96e93d7e117393172a");
    assert_eq!(v["padded"], "hello");
    assert_eq!(v["badKey"], true);
    assert_eq!(v["rand"], 24);
    assert_eq!(v["ecdh"], true);
    assert_eq!(v["uuid"], 36);
    assert_eq!(v["epoch"], true);
    let _ = fs::remove_dir_all(&root);
}

#[test]
fn json_roundtrip_from_lua() {
    let (root, mut mgr, id) = probe_mgr("json");
    let input = r#"{"n":7,"f":1.5,"s":"中文\n","a":[1,{"x":null}],"o":{},"b":false}"#;
    let v = call(
        &mut mgr,
        &id,
        "json",
        &serde_json::to_string(&vec![input]).unwrap(),
    );
    assert_eq!(v["stable"], true);
    assert_eq!(v["empty"], "{}");
    assert_eq!(v["arr"], r#"[1,2,"x"]"#);
    assert_eq!(v["intType"], "integer");
    let back: serde_json::Value = serde_json::from_str(v["back"].as_str().unwrap()).unwrap();
    assert_eq!(
        back,
        serde_json::json!({"n":7,"f":1.5,"s":"中文\n","a":[1,{}],"o":{},"b":false})
    );
    let bad = call(&mut mgr, &id, "json", r#"["{oops"]"#);
    assert!(bad["err"].as_str().unwrap().contains("json"));
    let _ = fs::remove_dir_all(&root);
}

// ---------------------------------------------------------------- xipk / 生命周期 / lifecycle

fn make_xipk(path: &Path, files: &[(&str, &[u8])]) {
    let mut w = zip::ZipWriter::new(fs::File::create(path).unwrap());
    let opts = zip::write::SimpleFileOptions::default();
    for (name, data) in files {
        w.start_file(*name, opts).unwrap();
        w.write_all(data).unwrap();
    }
    w.finish().unwrap();
}

const ECHO_MAIN: &str = r#"
local lib = require("fmt")
local plugin = {}
local bytes = 0
function plugin.start() bytes = 0 return true end
function plugin.processAudioChunk(pcm) bytes = bytes + #pcm end
function plugin.stop()
    host.asr.emitFinal(lib.describe(bytes))
    host.asr.emitEnd()
end
function plugin.cancel() end
return plugin
"#;

const ECHO_LIB: &str = r#"
local M = {}
function M.describe(n) return string.format("%d ms", n // 32) end
return M
"#;

#[test]
fn xipk_install_scan_uninstall() {
    let root = temp_dir("xipk");
    let plugins = root.join("plugins");
    let config = root.join("config");
    let pkg = root.join("echo.xipk");
    let manifest =
        "id: org.example.echo\nname: Echo\nversion: 1.0.0\ntype: speech\nicon: icon.png\n";
    make_xipk(
        &pkg,
        &[
            ("manifest.yaml", manifest.as_bytes()),
            ("main.lua", ECHO_MAIN.as_bytes()),
            ("libs/fmt.lua", ECHO_LIB.as_bytes()),
            ("resources/icon.png", b"\x89PNG-echo"),
        ],
    );
    let mut mgr = PluginManager::new(&plugins, &config);
    let info = mgr.install(&pkg).unwrap();
    assert_eq!(info.path, plugins.join("org.example.echo"));
    assert_eq!(info.icon_png.as_deref(), Some(&b"\x89PNG-echo"[..]));
    assert!(plugins.join("org.example.echo/libs/fmt.lua").is_file());

    let (rec, rx) = Recorder::new();
    let s = mgr.start_speech(&info.id, rec.clone()).unwrap();
    s.feed(&[0u8; 3200]);
    s.feed(&[0u8; 3200]);
    s.stop();
    wait_for(&rx, 5, |e| e == &Ev::End);
    assert_eq!(rec.results(), vec![Ev::Final("200 ms".into()), Ev::End]);
    drop(s);

    // 新管理器扫描目录能找到已解包的插件。A fresh manager finds the unpacked plugin.
    let mut mgr2 = PluginManager::new(&plugins, &config);
    let list = mgr2.scan();
    assert_eq!(
        list.iter().map(|p| p.id.as_str()).collect::<Vec<_>>(),
        vec!["org.example.echo"]
    );
    mgr2.set_config("org.example.echo", "k", "v");
    assert!(config.join("org.example.echo.json").is_file());
    mgr2.uninstall("org.example.echo").unwrap();
    assert!(!plugins.join("org.example.echo").exists());
    assert!(!config.join("org.example.echo.json").exists());
    assert!(mgr2.list().is_empty());

    // 把 .xipk 直接放进 plugins_dir，scan 会解包。A .xipk dropped into plugins_dir gets unpacked.
    fs::copy(&pkg, plugins.join("echo.xipk")).unwrap();
    let list = mgr2.scan();
    assert_eq!(list.len(), 1);
    assert!(plugins.join("org.example.echo/main.lua").is_file());

    // 带路径穿越的包被拒绝。Archives with path traversal are rejected.
    let evil = root.join("evil.xipk");
    make_xipk(
        &evil,
        &[
            ("manifest.yaml", b"id: org.example.evil\n"),
            ("main.lua", b"return {}"),
            ("../x.lua", b""),
        ],
    );
    assert!(mgr2.install(&evil).is_err());
    assert!(!plugins.join("org.example.evil").exists());
    let _ = fs::remove_dir_all(&root);
}

#[test]
fn broken_plugin_reports_error_and_ends() {
    let root = temp_dir("broken");
    let pdir = root.join("p");
    write_plugin(
        &pdir,
        "id: org.example.broken\ntype: speech\n",
        "this is not lua",
        &[],
    );
    let mut mgr = PluginManager::new(root.join("plugins"), root.join("config"));
    let id = mgr.add_path(&pdir).unwrap().id;
    assert!(!mgr.is_configured(&id));
    let (rec, rx) = Recorder::new();
    let _s = mgr.start_speech(&id, rec.clone()).unwrap();
    wait_for(&rx, 5, |e| e == &Ev::End);
    let res = rec.results();
    assert!(
        matches!(&res[0], Ev::Error(m) if m.contains("main.lua")),
        "{res:?}"
    );
    assert_eq!(res.last(), Some(&Ev::End));

    // ws 目标不在白名单：connect 返回 false，lastError 说明原因。
    // ws target not in the allow-list: connect returns false and lastError explains.
    let pdir = root.join("q");
    let main = r#"
        local plugin = {}
        function plugin.start()
            if not host.ws.connect("wss://not-declared.example/", {}, {}) then
                host.asr.emitError(host.ws.lastError())
            end
            return false
        end
        return plugin
    "#;
    write_plugin(&pdir, "id: org.example.nonet\ntype: speech\n", main, &[]);
    let id = mgr.add_path(&pdir).unwrap().id;
    let (rec, rx) = Recorder::new();
    let _s = mgr.start_speech(&id, rec.clone()).unwrap();
    wait_for(&rx, 5, |e| e == &Ev::End);
    let res = rec.results();
    assert!(
        matches!(&res[0], Ev::Error(m) if m.contains("not-declared.example")),
        "{res:?}"
    );
    assert_eq!(res.iter().filter(|e| **e == Ev::End).count(), 1);
    let _ = fs::remove_dir_all(&root);
}

#[test]
fn stop_without_emit_end_times_out() {
    let root = temp_dir("stoptimeout");
    let pdir = root.join("p");
    let main = r#"
        local plugin = {}
        function plugin.start() return true end
        function plugin.stop() host.asr.emitPartial("stopping") end
        function plugin.cancel() host.log("cancel called") end
        return plugin
    "#;
    write_plugin(&pdir, "id: org.example.silent\ntype: speech\n", main, &[]);
    let mut mgr = PluginManager::new(root.join("plugins"), root.join("config"));
    let id = mgr.add_path(&pdir).unwrap().id;
    let (rec, rx) = Recorder::new();
    let s = mgr.start_speech(&id, rec.clone()).unwrap();
    let t0 = std::time::Instant::now();
    s.stop();
    wait_for(&rx, weave_plugin::STOP_TIMEOUT.as_secs() + 3, |e| {
        e == &Ev::End
    });
    assert!(t0.elapsed() >= weave_plugin::STOP_TIMEOUT - Duration::from_millis(100));
    std::thread::sleep(Duration::from_millis(50));
    let ev = rec.events();
    assert!(ev.contains(&Ev::Partial("stopping".into())));
    assert!(ev.contains(&Ev::Log(1, "cancel called".into())));
    let _ = fs::remove_dir_all(&root);
}

/// docs/plugin-host.md 里的示例插件必须能跑。The example plugin in docs/plugin-host.md must work.
#[test]
fn doc_example_plugin() {
    let doc = include_str!("../../../docs/plugin-host.md");
    let example = &doc[doc.find("### 示例").expect("example section")..];
    let block = |lang: &str| {
        let start = example.find(&format!("```{lang}\n")).unwrap() + lang.len() + 4;
        let len = example[start..].find("```").unwrap();
        example[start..start + len].to_string()
    };
    let root = temp_dir("doc");
    let pdir = root.join("echo");
    write_plugin(&pdir, &block("yaml"), &block("lua"), &[]);
    let mut mgr = PluginManager::new(root.join("plugins"), root.join("config"));
    let id = mgr.add_path(&pdir).unwrap().id;
    assert_eq!(id, "org.example.echo");
    assert!(mgr.is_configured(&id));
    mgr.set_config(&id, "prefix", "got");
    let (rec, rx) = Recorder::new();
    let s = mgr.start_speech(&id, rec.clone()).unwrap();
    for _ in 0..60 {
        s.feed(&[0u8; 1280]); // 60 × 40 ms = 2.4 s
    }
    s.stop();
    wait_for(&rx, 5, |e| e == &Ev::End);
    assert_eq!(
        rec.results(),
        vec![
            Ev::Partial("got 1 s".into()),
            Ev::Partial("got 2 s".into()),
            Ev::Final("got 2.4 s".into()),
            Ev::End
        ]
    );
    let _ = fs::remove_dir_all(&root);
}

// ---------------------------------------------------------------- 配额 / quotas

const QUOTA_MANIFEST: &str = r#"
id: org.example.mock.quota
name: Mock quota
version: "1"
type: speech
"#;

const QUOTA_MAIN: &str = r#"
local plugin = {}
local mode = "spin"
function plugin.start()
    if mode == "spin" then
        mode = "alloc"
        while true do end
    elseif mode == "alloc" then
        mode = "ok"
        local t = {}
        for i = 1, 1e9 do t[i] = string.rep("x", 1024) end
    else
        host.asr.emitFinal("still alive")
        host.asr.emitEnd()
    end
    return true
end
function plugin.stop() end
function plugin.cancel() end
return plugin
"#;

/// 死循环与内存爆炸都会被中止，会话照常结束，同一实例之后仍可用。
/// Spinning and memory blow-ups are aborted, sessions still end, the instance stays usable.
#[test]
fn runaway_scripts_are_stopped() {
    let root = temp_dir("quota");
    let pdir = root.join("p");
    write_plugin(&pdir, QUOTA_MANIFEST, QUOTA_MAIN, &[]);
    let mut mgr = PluginManager::new(root.join("plugins"), root.join("config"));
    let info = mgr.add_path(&pdir).unwrap();
    for expect in ["instruction budget", "memory"] {
        let (rec, rx) = Recorder::new();
        let _s = mgr.start_speech(&info.id, rec.clone()).unwrap();
        wait_for(&rx, 30, |e| e == &Ev::End);
        let res = rec.results();
        assert!(
            res.iter()
                .any(|e| matches!(e, Ev::Error(m) if m.to_lowercase().contains(expect))),
            "expected {expect} error, got {res:?}"
        );
    }
    let (rec, rx) = Recorder::new();
    let _s = mgr.start_speech(&info.id, rec.clone()).unwrap();
    wait_for(&rx, 10, |e| e == &Ev::End);
    assert!(rec.results().contains(&Ev::Final("still alive".into())));
    let _ = fs::remove_dir_all(&root);
}
