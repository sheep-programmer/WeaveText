//! 桌面联调工具：`speechtest <插件目录或.xipk> <16k单声道wav> [配置键=值 ...]`
//! 按 40ms 实时节奏喂 PCM，打印全部事件。
//!
//! Desktop test driver: feeds a 16 kHz mono WAV to a speech plugin at real-time pace (40 ms
//! chunks) and prints every event. Extra `key=value` arguments are written to the plugin config.

use std::path::{Path, PathBuf};
use std::sync::mpsc;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use weave_plugin::{PluginManager, SpeechListener};

struct Printer {
    t0: Instant,
    ended: mpsc::Sender<()>,
    finals: Mutex<Vec<String>>,
}

impl Printer {
    fn p(&self, tag: &str, msg: &str) {
        println!("[{:>6.2}s] {tag:<8} {msg}", self.t0.elapsed().as_secs_f64());
    }
}

impl SpeechListener for Printer {
    fn on_partial(&self, text: &str) {
        self.p("partial", text);
    }
    fn on_final(&self, text: &str) {
        self.finals.lock().unwrap().push(text.to_string());
        self.p("FINAL", text);
    }
    fn on_replace(&self, old: &str, new: &str) {
        self.p("REPLACE", &format!("{old:?} -> {new:?}"));
    }
    fn on_error(&self, msg: &str) {
        self.p("ERROR", msg);
    }
    fn on_end(&self) {
        self.p("END", "");
        let _ = self.ended.send(());
    }
    fn on_log(&self, level: u8, msg: &str) {
        let lv = ["debug", "info", "warn", "error"]
            .get(level as usize)
            .copied()
            .unwrap_or("?");
        self.p(&format!("log.{lv}"), msg);
    }
}

/// 读 WAV，返回 16 kHz 单声道 s16le PCM（逐块解析，跳过 LIST/FLLR 等扩展块）。
/// Reads a WAV file; returns 16 kHz mono s16le PCM (walks the chunks, skipping extras).
fn read_wav(path: &Path) -> Result<Vec<u8>, String> {
    let b = std::fs::read(path).map_err(|e| format!("{}: {e}", path.display()))?;
    if b.len() < 12 || &b[0..4] != b"RIFF" || &b[8..12] != b"WAVE" {
        return Err("not a RIFF/WAVE file".into());
    }
    let mut i = 12;
    let mut fmt = None;
    while i + 8 <= b.len() {
        let id = &b[i..i + 4];
        let len = u32::from_le_bytes(b[i + 4..i + 8].try_into().unwrap()) as usize;
        let body = &b[i + 8..(i + 8 + len).min(b.len())];
        match id {
            b"fmt " if body.len() >= 16 => {
                let format = u16::from_le_bytes([body[0], body[1]]);
                let channels = u16::from_le_bytes([body[2], body[3]]);
                let rate = u32::from_le_bytes(body[4..8].try_into().unwrap());
                let bits = u16::from_le_bytes([body[14], body[15]]);
                fmt = Some((format, channels, rate, bits));
            }
            b"data" => match fmt {
                Some((1, 1, 16000, 16)) => return Ok(body.to_vec()),
                Some(f) => {
                    return Err(format!(
                        "need PCM 16 kHz mono 16-bit, got (format, ch, rate, bits) = {f:?}"
                    ))
                }
                None => return Err("data chunk before fmt chunk".into()),
            },
            _ => {}
        }
        i += 8 + len + (len & 1);
    }
    Err("no data chunk".into())
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 3 {
        eprintln!("usage: speechtest <plugin dir | .xipk> <16k-mono.wav> [key=value ...]");
        std::process::exit(2);
    }
    let plugin = PathBuf::from(&args[1]);
    let pcm = read_wav(Path::new(&args[2])).unwrap_or_else(|e| {
        eprintln!("wav: {e}");
        std::process::exit(2);
    });
    let work = std::env::temp_dir().join("weave-speechtest");
    let mut mgr = PluginManager::new(work.join("plugins"), work.join("config"));
    let info = mgr.add_path(&plugin).unwrap_or_else(|e| {
        eprintln!("load: {e}");
        std::process::exit(1);
    });
    for kv in &args[3..] {
        if let Some((k, v)) = kv.split_once('=') {
            mgr.set_config(&info.id, k, v);
        }
    }
    println!(
        "plugin {} ({}) v{} kind={} icon={}",
        info.id,
        info.name,
        info.version,
        info.kind,
        info.icon_png
            .as_ref()
            .map_or("none".to_string(), |b| format!("{} bytes", b.len()))
    );
    println!("configured: {}", mgr.is_configured(&info.id));
    println!("audio: {:.2}s", pcm.len() as f64 / 32000.0);

    let (tx, rx) = mpsc::channel();
    let listener = Arc::new(Printer {
        t0: Instant::now(),
        ended: tx,
        finals: Mutex::new(Vec::new()),
    });
    let session = mgr
        .start_speech(&info.id, listener.clone())
        .unwrap_or_else(|e| {
            eprintln!("start: {e}");
            std::process::exit(1);
        });
    // 40ms = 1280 字节，按墙钟节奏喂，模拟麦克风。40 ms chunks paced by the wall clock.
    let t0 = Instant::now();
    for (n, chunk) in pcm.chunks(1280).enumerate() {
        if rx.try_recv().is_ok() {
            println!("(session ended while feeding)");
            break;
        }
        session.feed(chunk);
        let due = t0 + Duration::from_millis(40 * (n as u64 + 1));
        if let Some(d) = due.checked_duration_since(Instant::now()) {
            std::thread::sleep(d);
        }
    }
    listener.p("stop()", "");
    session.stop();
    let _ = rx.recv_timeout(Duration::from_secs(15));
    // 留一点时间给会话结束后才到的 emitReplace。Leave room for late emitReplace.
    std::thread::sleep(Duration::from_millis(1500));
    drop(session);
    let finals = listener.finals.lock().unwrap().concat();
    println!("RESULT: {finals}");
    std::process::exit(if finals.is_empty() { 1 } else { 0 });
}
