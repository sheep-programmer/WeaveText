//! 加密通道：连接用 Noise XX（双方静态公钥互认），配对先用 SPAKE2 把 6 位配对码变成强密钥，
//! 再作为 Noise XXpsk3 的预共享密钥——配对码只能在线猜一次，截获的握手也无法离线穷举。
//! Encrypted channel: connections use Noise XX (both static keys checked against the trusted list);
//! pairing first turns the 6-digit code into a strong key with SPAKE2 and uses it as the Noise XXpsk3 PSK,
//! so the code can only be guessed online, once per attempt, and a captured handshake can't be brute-forced.

use std::io::Write;
use std::net::{SocketAddr, TcpStream};
use std::sync::Mutex;
use std::time::Duration;

use spake2::{Ed25519Group, Identity as SpakeId, Password, Spake2};

use crate::store::{err, Identity, NOISE_XX, NOISE_XX_PSK};
use crate::wire::{self, read_frame, write_frame, Message, MAGIC, MAX_FRAME, MODE_CONNECT, MODE_PAIR, TAG};

const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);
/// 连接空闲上限：心跳每 20 秒一次，超过即判定断开。 Idle limit; heartbeats run every 20 s.
pub const IDLE_TIMEOUT: Duration = Duration::from_secs(60);
const SPAKE_A: &[u8] = b"weavelink-initiator";
const SPAKE_B: &[u8] = b"weavelink-responder";

pub struct Channel {
    reader: Mutex<TcpStream>,
    writer: Mutex<TcpStream>,
    noise: Mutex<snow::TransportState>,
    pub remote_key: Vec<u8>,
    pub addr: SocketAddr,
}

impl Channel {
    pub fn send(&self, m: &Message) -> Result<(), String> {
        let plain = m.encode();
        if plain.len() + TAG > MAX_FRAME {
            return Err("message too large".into());
        }
        let mut buf = vec![0u8; plain.len() + TAG];
        // 写锁同时覆盖加密与写出，保证 nonce 顺序与线上顺序一致。
        // The write lock covers both encryption and the write so nonces go out in order.
        let mut w = self.writer.lock().map_err(err)?;
        let n = self.noise.lock().map_err(err)?.write_message(&plain, &mut buf).map_err(err)?;
        write_frame(&mut *w, &buf[..n]).map_err(err)
    }

    pub fn recv(&self) -> Result<Message, String> {
        let frame = read_frame(&mut *self.reader.lock().map_err(err)?).map_err(err)?;
        let mut buf = vec![0u8; frame.len()];
        let n = self.noise.lock().map_err(err)?.read_message(&frame, &mut buf).map_err(err)?;
        Message::decode(&buf[..n]).ok_or_else(|| "bad message".to_string())
    }

    pub fn shutdown(&self) {
        if let Ok(w) = self.writer.lock() {
            let _ = w.shutdown(std::net::Shutdown::Both);
        }
    }
}

fn spake_key(stream: &mut TcpStream, code: &str, initiator: bool) -> Result<Vec<u8>, String> {
    let pw = Password::new(code.as_bytes());
    let (a, b) = (SpakeId::new(SPAKE_A), SpakeId::new(SPAKE_B));
    let (state, msg) = if initiator {
        Spake2::<Ed25519Group>::start_a(&pw, &a, &b)
    } else {
        Spake2::<Ed25519Group>::start_b(&pw, &a, &b)
    };
    write_frame(stream, &msg).map_err(err)?;
    let other = read_frame(stream).map_err(err)?;
    state.finish(&other).map_err(|e| format!("pairing: {e:?}"))
}

fn finish(stream: TcpStream, hs: snow::HandshakeState, addr: SocketAddr) -> Result<Channel, String> {
    let remote_key = hs.get_remote_static().ok_or("no remote key")?.to_vec();
    let noise = hs.into_transport_mode().map_err(err)?;
    stream.set_read_timeout(Some(IDLE_TIMEOUT)).map_err(err)?;
    let _ = stream.set_nodelay(true);
    let reader = stream.try_clone().map_err(err)?;
    Ok(Channel { reader: Mutex::new(reader), writer: Mutex::new(stream), noise: Mutex::new(noise), remote_key, addr })
}

/// 主动方：`pair_code` 为 Some 时走配对。 Initiator; pairs when `pair_code` is given.
pub fn connect(addr: SocketAddr, me: &Identity, pair_code: Option<&str>) -> Result<Channel, String> {
    let mut s = TcpStream::connect_timeout(&addr, Duration::from_secs(4)).map_err(err)?;
    s.set_read_timeout(Some(HANDSHAKE_TIMEOUT)).map_err(err)?;
    s.set_write_timeout(Some(HANDSHAKE_TIMEOUT)).map_err(err)?;
    let mode = if pair_code.is_some() { MODE_PAIR } else { MODE_CONNECT };
    s.write_all(&[&MAGIC[..], &[mode]].concat()).map_err(err)?;
    let mut hs = match pair_code {
        Some(code) => {
            let psk = spake_key(&mut s, code, true)?;
            snow::Builder::new(NOISE_XX_PSK.parse().map_err(err)?)
                .local_private_key(&me.private)
                .psk(3, &psk)
                .build_initiator()
                .map_err(err)?
        }
        None => snow::Builder::new(NOISE_XX.parse().map_err(err)?).local_private_key(&me.private).build_initiator().map_err(err)?,
    };
    let mut buf = vec![0u8; MAX_FRAME];
    let n = hs.write_message(&[], &mut buf).map_err(err)?;
    write_frame(&mut s, &buf[..n]).map_err(err)?;
    let m2 = read_frame(&mut s).map_err(err)?;
    hs.read_message(&m2, &mut buf).map_err(|_| "handshake rejected (wrong code?)".to_string())?;
    let n = hs.write_message(&[], &mut buf).map_err(err)?;
    write_frame(&mut s, &buf[..n]).map_err(err)?;
    s.set_write_timeout(None).map_err(err)?;
    finish(s, hs, addr)
}

/// 被动方。[pair_code] 在收到配对请求时调用，返回当前有效的配对码（未开放配对时返回 None 即拒绝）。
/// Responder. [pair_code] is asked when a pairing request arrives and returns the open code, or None to refuse.
pub fn accept(mut s: TcpStream, me: &Identity, pair_code: impl FnOnce() -> Option<String>) -> Result<(Channel, bool), String> {
    let addr = s.peer_addr().map_err(err)?;
    s.set_read_timeout(Some(HANDSHAKE_TIMEOUT)).map_err(err)?;
    s.set_write_timeout(Some(HANDSHAKE_TIMEOUT)).map_err(err)?;
    let mut pre = [0u8; 5];
    std::io::Read::read_exact(&mut s, &mut pre).map_err(err)?;
    if &pre[..4] != MAGIC {
        return Err("not a WeaveLink peer".into());
    }
    let pairing = pre[4] == MODE_PAIR;
    let mut hs = if pairing {
        let code = pair_code().ok_or("pairing is not open")?;
        let psk = spake_key(&mut s, &code, false)?;
        snow::Builder::new(NOISE_XX_PSK.parse().map_err(err)?)
            .local_private_key(&me.private)
            .psk(3, &psk)
            .build_responder()
            .map_err(err)?
    } else {
        snow::Builder::new(NOISE_XX.parse().map_err(err)?).local_private_key(&me.private).build_responder().map_err(err)?
    };
    let mut buf = vec![0u8; MAX_FRAME];
    let m1 = read_frame(&mut s).map_err(err)?;
    hs.read_message(&m1, &mut buf).map_err(err)?;
    let n = hs.write_message(&[], &mut buf).map_err(err)?;
    write_frame(&mut s, &buf[..n]).map_err(err)?;
    let m3 = read_frame(&mut s).map_err(err)?;
    hs.read_message(&m3, &mut buf).map_err(|_| "handshake failed (wrong code?)".to_string())?;
    s.set_write_timeout(None).map_err(err)?;
    Ok((finish(s, hs, addr)?, pairing))
}

/// 6 位配对码。 A 6-digit pairing code.
pub fn new_code() -> String {
    let mut b = [0u8; 4];
    let _ = getrandom::getrandom(&mut b);
    format!("{:06}", u32::from_le_bytes(b) % 1_000_000)
}

pub fn random_id() -> String {
    let mut b = [0u8; 8];
    let _ = getrandom::getrandom(&mut b);
    wire::hex(&b)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wire::Kind;
    use serde_json::json;
    use std::net::TcpListener;

    fn ids() -> (Identity, Identity) {
        let dir = std::env::temp_dir().join(format!("weave-link-sec-{}-{}", std::process::id(), random_id()));
        let a = Identity::load_or_create(&dir.join("a")).unwrap();
        let b = Identity::load_or_create(&dir.join("b")).unwrap();
        let _ = std::fs::remove_dir_all(&dir);
        (a, b)
    }

    fn run(code_server: Option<&'static str>, code_client: Option<&'static str>) -> Result<(Vec<u8>, Vec<u8>, bool), String> {
        let (a, b) = ids();
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = l.local_addr().unwrap();
        let bk = b.clone();
        let t = std::thread::spawn(move || {
            let (s, _) = l.accept().unwrap();
            let r = accept(s, &bk, || code_server.map(str::to_string));
            r.map(|(ch, pairing)| {
                let m = ch.recv().unwrap();
                ch.send(&Message::new(Kind::Pong, m.header.clone())).unwrap();
                (ch.remote_key.clone(), pairing)
            })
        });
        let res = connect(addr, &a, code_client).and_then(|ch| {
            ch.send(&Message::new(Kind::Ping, json!({"n": 1})))?;
            let back = ch.recv()?;
            assert_eq!(back.kind, Kind::Pong);
            Ok(ch.remote_key.clone())
        });
        let server = t.join().unwrap();
        let (server_saw, pairing) = server?;
        let client_saw = res?;
        assert_eq!(server_saw, a.public);
        assert_eq!(client_saw, b.public);
        Ok((client_saw, server_saw, pairing))
    }

    #[test]
    fn connect_and_pair() {
        assert!(!run(None, None).unwrap().2);
        assert!(run(Some("123456"), Some("123456")).unwrap().2);
        // 配对码不符或未开放配对：握手失败。 Wrong code or pairing closed: the handshake fails.
        assert!(run(Some("123456"), Some("654321")).is_err());
        assert!(run(None, Some("123456")).is_err());
    }

    #[test]
    fn codes_look_right() {
        let c = new_code();
        assert_eq!(c.len(), 6);
        assert!(c.bytes().all(|b| b.is_ascii_digit()));
        assert_eq!(random_id().len(), 16);
    }
}
