//! Direct-only QUIC over the same UDP socket used for STUN and hole punching.
//! Connection information is exchanged by the users; there is no signaling or data relay.

use std::io::{self, Read, Write};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, ToSocketAddrs, UdpSocket};
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, Mutex, OnceLock,
};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use rustls::pki_types::{CertificateDer, PrivatePkcs8KeyDer};
use serde::{Deserialize, Serialize};
use tokio::runtime::Runtime;

use crate::{
    discovery,
    store::err,
    wire::{hex, unhex},
};

pub const WINDOW: Duration = Duration::from_secs(300);
pub const DEFAULT_STUN: &[&str] = &["stun.l.google.com:19302", "stun.cloudflare.com:3478"];
const PREFIX: &str = "weavelink://direct/";
const COOKIE: u32 = 0x2112_a442;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(25);

fn runtime() -> Result<&'static Runtime, String> {
    static RUNTIME: OnceLock<Result<Runtime, String>> = OnceLock::new();
    RUNTIME
        .get_or_init(|| {
            tokio::runtime::Builder::new_multi_thread()
                .worker_threads(2)
                .enable_all()
                .thread_name("weavelink-udp")
                .build()
                .map_err(err)
        })
        .as_ref()
        .map_err(Clone::clone)
}

fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Ticket {
    pub v: u8,
    pub id: String,
    pub name: String,
    pub addrs: Vec<SocketAddr>,
    pub cert: String,
    pub code: String,
    pub expires: u64,
}

impl Ticket {
    pub fn encode(&self) -> String {
        format!(
            "{PREFIX}{}",
            URL_SAFE_NO_PAD.encode(serde_json::to_vec(self).unwrap())
        )
    }

    pub fn decode(text: &str) -> Result<Self, String> {
        let text = text.trim();
        if text.len() > 16_384 {
            return Err("connection code is too large".into());
        }
        let bytes = URL_SAFE_NO_PAD
            .decode(text.strip_prefix(PREFIX).ok_or("invalid connection code")?)
            .map_err(err)?;
        let t: Self =
            serde_json::from_slice(&bytes).map_err(|_| "invalid connection code".to_string())?;
        if t.v != 1
            || t.id.len() != 16
            || !t.id.bytes().all(|c| c.is_ascii_hexdigit())
            || t.code.len() != 6
            || !t.code.bytes().all(|c| c.is_ascii_digit())
            || t.name.len() > 256
            || t.addrs.is_empty()
            || t.addrs.len() > 16
            || t.addrs.iter().any(|a| {
                a.port() == 0
                    || a.ip().is_unspecified()
                    || a.ip().is_multicast()
                    || matches!(a.ip(), IpAddr::V4(ip) if ip.is_broadcast())
            })
            || t.cert.len() > 8192
            || unhex(&t.cert).is_none()
        {
            return Err("invalid connection code".into());
        }
        if t.expires < now() || t.expires > now() + WINDOW.as_secs() + 60 {
            return Err("connection code expired; generate a new code on both devices".into());
        }
        Ok(t)
    }
}

fn bind() -> Result<UdpSocket, String> {
    if let Ok(s) = socket2::Socket::new(
        socket2::Domain::IPV6,
        socket2::Type::DGRAM,
        Some(socket2::Protocol::UDP),
    ) {
        if s.set_only_v6(false).is_ok() && s.bind(&SocketAddr::from(([0u16; 8], 0)).into()).is_ok()
        {
            return Ok(s.into());
        }
    }
    UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).map_err(err)
}

fn destination(socket: &UdpSocket, addr: SocketAddr) -> SocketAddr {
    if socket.local_addr().is_ok_and(|a| a.is_ipv6()) {
        if let IpAddr::V4(ip) = addr.ip() {
            return SocketAddr::new(ip.to_ipv6_mapped().into(), addr.port());
        }
    }
    addr
}

fn binding_request() -> ([u8; 20], [u8; 12]) {
    let mut tx = [0; 12];
    getrandom::getrandom(&mut tx).expect("system RNG");
    let mut packet = [0; 20];
    packet[..2].copy_from_slice(&1u16.to_be_bytes());
    packet[4..8].copy_from_slice(&COOKIE.to_be_bytes());
    packet[8..].copy_from_slice(&tx);
    (packet, tx)
}

/// Validate the response transaction and lengths before reading XOR-MAPPED-ADDRESS.
fn mapped_address(packet: &[u8], tx: &[u8; 12]) -> Option<SocketAddr> {
    if packet.len() < 20
        || packet[..2] != [1, 1]
        || packet[4..8] != COOKIE.to_be_bytes()
        || packet[8..20] != tx[..]
    {
        return None;
    }
    let len = u16::from_be_bytes([packet[2], packet[3]]) as usize;
    if !len.is_multiple_of(4) || packet.len() != 20 + len {
        return None;
    }
    let mut p = 20;
    while p + 4 <= packet.len() {
        let kind = u16::from_be_bytes([packet[p], packet[p + 1]]);
        let n = u16::from_be_bytes([packet[p + 2], packet[p + 3]]) as usize;
        let value = packet.get(p + 4..p + 4 + n)?;
        if kind == 0x0020 && value.len() >= 4 {
            let port = u16::from_be_bytes([value[2], value[3]]) ^ (COOKIE >> 16) as u16;
            let mask: Vec<u8> = COOKIE
                .to_be_bytes()
                .into_iter()
                .chain(tx.iter().copied())
                .collect();
            let ip = match (value[1], value.len()) {
                (1, 8) => IpAddr::V4(Ipv4Addr::new(
                    value[4] ^ mask[0],
                    value[5] ^ mask[1],
                    value[6] ^ mask[2],
                    value[7] ^ mask[3],
                )),
                (2, 20) => {
                    let mut bytes = [0; 16];
                    for i in 0..16 {
                        bytes[i] = value[4 + i] ^ mask[i];
                    }
                    IpAddr::V6(Ipv6Addr::from(bytes))
                }
                _ => return None,
            };
            return (port != 0 && !ip.is_unspecified()).then_some(SocketAddr::new(ip, port));
        }
        p += 4 + n.div_ceil(4) * 4;
    }
    None
}

pub struct Endpoint {
    endpoint: quinn::Endpoint,
    socket: UdpSocket,
    rt: &'static Runtime,
    cert: Vec<u8>,
    pub addrs: Vec<SocketAddr>,
    pub public: bool,
    running: Arc<AtomicBool>,
    expires: Mutex<Instant>,
}

impl Endpoint {
    pub fn start(
        servers: &[String],
        incoming: Arc<dyn Fn(Stream) + Send + Sync>,
    ) -> Result<Arc<Self>, String> {
        let rt = runtime()?;
        let socket = bind()?;
        socket
            .set_read_timeout(Some(Duration::from_millis(800)))
            .map_err(err)?;
        let mut addrs = Vec::new();
        let mut keepalives = Vec::new();
        for server in servers.iter().take(4) {
            let Ok(resolved) = server.to_socket_addrs() else {
                continue;
            };
            let Some(addr) = resolved.into_iter().find(|a| a.is_ipv4()) else {
                continue;
            };
            let addr = destination(&socket, addr);
            for _ in 0..2 {
                let (packet, tx) = binding_request();
                if socket.send_to(&packet, addr).is_err() {
                    break;
                }
                let mut reply = [0; 2048];
                if let Ok((n, from)) = socket.recv_from(&mut reply) {
                    if from == addr {
                        if let Some(mapped) = mapped_address(&reply[..n], &tx) {
                            if !addrs.contains(&mapped) {
                                addrs.push(mapped);
                            }
                            keepalives.push(addr);
                            break;
                        }
                    }
                }
            }
        }
        let public = !addrs.is_empty();
        let port = socket.local_addr().map_err(err)?.port();
        for ip in discovery::local_addrs() {
            if ip.is_ipv6() && socket.local_addr().is_ok_and(|a| a.is_ipv4()) {
                continue;
            }
            let addr = SocketAddr::new(ip, port);
            if !addrs.contains(&addr) {
                addrs.push(addr);
            }
        }
        addrs.truncate(16);
        socket.set_read_timeout(None).map_err(err)?;
        socket.set_nonblocking(true).map_err(err)?;
        let generated =
            rcgen::generate_simple_self_signed(vec!["weavelink".into()]).map_err(err)?;
        let cert = generated.cert.der().to_vec();
        let mut config = quinn::ServerConfig::with_single_cert(
            vec![CertificateDer::from(cert.clone())],
            PrivatePkcs8KeyDer::from(generated.signing_key.serialize_der()).into(),
        )
        .map_err(err)?;
        let mut transport = quinn::TransportConfig::default();
        transport
            .max_concurrent_bidi_streams(1u32.into())
            .max_concurrent_uni_streams(0u32.into())
            .keep_alive_interval(Some(Duration::from_secs(15)))
            .max_idle_timeout(Some(Duration::from_secs(60).try_into().map_err(err)?));
        config.transport_config(Arc::new(transport));
        config.max_incoming(16);
        let _enter = rt.enter();
        let endpoint = quinn::Endpoint::new(
            quinn::EndpointConfig::default(),
            Some(config),
            socket.try_clone().map_err(err)?,
            Arc::new(quinn::TokioRuntime),
        )
        .map_err(err)?;
        let running = Arc::new(AtomicBool::new(true));
        let this = Arc::new(Self {
            endpoint: endpoint.clone(),
            socket,
            rt,
            cert,
            addrs,
            public,
            running: running.clone(),
            expires: Mutex::new(Instant::now() + WINDOW),
        });
        rt.spawn(async move {
            while let Some(connecting) = endpoint.accept().await {
                let incoming = incoming.clone();
                rt.spawn(async move {
                    if let Ok(Ok(conn)) = tokio::time::timeout(CONNECT_TIMEOUT, connecting).await {
                        if let Ok(Ok((send, recv))) =
                            tokio::time::timeout(CONNECT_TIMEOUT, conn.accept_bi()).await
                        {
                            let stream = Stream::new(conn, send, recv, rt);
                            std::thread::spawn(move || incoming(stream));
                        }
                    }
                });
            }
        });
        let keep_socket = this.socket.try_clone().map_err(err)?;
        rt.spawn(async move {
            let mut tick = tokio::time::interval(Duration::from_secs(15));
            while running.load(Ordering::SeqCst) {
                tick.tick().await;
                for addr in &keepalives {
                    let _ = keep_socket.send_to(&binding_request().0, addr);
                }
            }
        });
        Ok(this)
    }

    pub fn ticket(&self, id: &str, name: &str, code: &str) -> Ticket {
        *self.expires.lock().unwrap() = Instant::now() + WINDOW;
        Ticket {
            v: 1,
            id: id.into(),
            name: name.chars().take(40).collect(),
            addrs: self.addrs.clone(),
            cert: hex(&self.cert),
            code: code.into(),
            expires: now() + WINDOW.as_secs(),
        }
    }

    pub fn punch(&self, addrs: &[SocketAddr]) {
        let Ok(socket) = self.socket.try_clone() else {
            return;
        };
        let addrs: Vec<_> = addrs.iter().map(|a| destination(&socket, *a)).collect();
        let running = self.running.clone();
        self.rt.spawn(async move {
            for _ in 0..300 {
                if !running.load(Ordering::SeqCst) {
                    break;
                }
                for addr in &addrs {
                    let _ = socket.send_to(b"WLKP1", addr);
                }
                tokio::time::sleep(Duration::from_millis(200)).await;
            }
        });
    }

    pub fn connect(&self, ticket: &Ticket) -> Result<Stream, String> {
        let cert = unhex(&ticket.cert).ok_or("invalid certificate")?;
        let mut roots = rustls::RootCertStore::empty();
        roots.add(CertificateDer::from(cert)).map_err(err)?;
        let config = quinn::ClientConfig::with_root_certificates(Arc::new(roots)).map_err(err)?;
        self.rt.block_on(async {
            let mut attempts = tokio::task::JoinSet::new();
            for addr in &ticket.addrs {
                let endpoint = self.endpoint.clone();
                let config = config.clone();
                let addr = *addr;
                attempts.spawn(async move {
                    let connecting = endpoint
                        .connect_with(config, addr, "weavelink")
                        .map_err(err)?;
                    let conn = connecting.await.map_err(err)?;
                    let (send, recv) = conn.open_bi().await.map_err(err)?;
                    Ok::<_, String>(Stream::new(conn, send, recv, runtime()?))
                });
            }
            tokio::time::timeout(CONNECT_TIMEOUT, async {
                while let Some(result) = attempts.join_next().await {
                    if let Ok(Ok(stream)) = result {
                        attempts.abort_all();
                        return Ok(stream);
                    }
                }
                Err("direct connection failed; this network may block UDP or NAT traversal".into())
            })
            .await
            .map_err(|_| "direct connection timed out; no relay was used".to_string())?
        })
    }

    pub fn stop(&self) {
        self.running.store(false, Ordering::SeqCst);
        self.endpoint.close(0u32.into(), b"closed");
    }

    pub fn ticket_expired(&self) -> bool {
        Instant::now() >= *self.expires.lock().unwrap()
    }
}

impl Drop for Endpoint {
    fn drop(&mut self) {
        self.stop();
    }
}

#[derive(Clone)]
pub struct Stream {
    recv: Arc<Mutex<quinn::RecvStream>>,
    send: Arc<Mutex<quinn::SendStream>>,
    conn: quinn::Connection,
    rt: &'static Runtime,
    pub read_timeout: Duration,
}

impl Stream {
    fn new(
        conn: quinn::Connection,
        send: quinn::SendStream,
        recv: quinn::RecvStream,
        rt: &'static Runtime,
    ) -> Self {
        Self {
            recv: Arc::new(Mutex::new(recv)),
            send: Arc::new(Mutex::new(send)),
            conn,
            rt,
            read_timeout: Duration::from_secs(10),
        }
    }
    pub fn addr(&self) -> SocketAddr {
        self.conn.remote_address()
    }
    pub fn shutdown(&self) {
        self.conn.close(0u32.into(), b"closed");
    }
}

impl Read for Stream {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let mut reader = self
            .recv
            .lock()
            .map_err(|e| io::Error::other(e.to_string()))?;
        self.rt.block_on(async {
            tokio::time::timeout(self.read_timeout, reader.read(buf))
                .await
                .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "direct read timed out"))?
                .map(|n| n.unwrap_or(0))
                .map_err(io::Error::other)
        })
    }
}

impl Write for Stream {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        let mut writer = self
            .send
            .lock()
            .map_err(|e| io::Error::other(e.to_string()))?;
        self.rt.block_on(async {
            tokio::time::timeout(Duration::from_secs(60), writer.write(buf))
                .await
                .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "direct write timed out"))?
                .map_err(io::Error::other)
        })
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_different_certificate_cannot_establish_a_direct_stream() {
        let a = Endpoint::start(&[], Arc::new(|_| {})).unwrap();
        let accepted = Arc::new(AtomicBool::new(false));
        let seen = accepted.clone();
        let b = Endpoint::start(
            &[],
            Arc::new(move |_| {
                seen.store(true, Ordering::SeqCst);
            }),
        )
        .unwrap();
        let mut ticket = b.ticket("0123456789abcdef", "peer", "123456");
        ticket.cert = hex(&a.cert);
        assert!(
            a.connect(&ticket).is_err(),
            "a certificate other than the shared ticket's must fail"
        );
        assert!(!accepted.load(Ordering::SeqCst));
        a.stop();
        b.stop();
    }

    #[test]
    fn stun_transactions_and_malformed_packets_are_checked() {
        let (_, tx) = binding_request();
        let mut reply = vec![1, 1, 0, 12];
        reply.extend(COOKIE.to_be_bytes());
        reply.extend(tx);
        reply.extend([0, 0x20, 0, 8, 0, 1]);
        reply.extend((47811u16 ^ (COOKIE >> 16) as u16).to_be_bytes());
        reply.extend(
            [203, 0, 113, 7]
                .iter()
                .zip(COOKIE.to_be_bytes())
                .map(|(a, b)| a ^ b),
        );
        assert_eq!(
            mapped_address(&reply, &tx),
            Some("203.0.113.7:47811".parse().unwrap())
        );
        assert_eq!(mapped_address(&reply, &[0; 12]), None);
        for n in 0..reply.len() {
            assert_eq!(mapped_address(&reply[..n], &tx), None);
        }
        reply[22] = 255;
        assert_eq!(mapped_address(&reply, &tx), None);
    }

    #[test]
    fn expired_or_unbounded_connection_codes_are_rejected() {
        let mut ticket = Ticket {
            v: 1,
            id: "0123456789abcdef".into(),
            name: "peer".into(),
            addrs: vec!["127.0.0.1:47811".parse().unwrap()],
            cert: "01".into(),
            code: "123456".into(),
            expires: now() + 300,
        };
        assert!(Ticket::decode(&ticket.encode()).is_ok());
        ticket.expires = now() - 1;
        assert!(Ticket::decode(&ticket.encode())
            .unwrap_err()
            .contains("expired"));
        ticket.expires = now() + 300;
        ticket.addrs = vec!["0.0.0.0:47811".parse().unwrap()];
        assert!(Ticket::decode(&ticket.encode()).is_err());
        assert!(Ticket::decode(&"a".repeat(16_385)).is_err());
        assert!(Ticket::decode("weavelink://direct/invalid").is_err());
    }

    #[test]
    fn stun_ipv6_xor_address_is_decoded() {
        let (_, tx) = binding_request();
        let address: Ipv6Addr = "2001:db8::7".parse().unwrap();
        let mut reply = vec![1, 1, 0, 24];
        reply.extend(COOKIE.to_be_bytes());
        reply.extend(tx);
        reply.extend([0, 0x20, 0, 20, 0, 2]);
        reply.extend((47811u16 ^ (COOKIE >> 16) as u16).to_be_bytes());
        let mask: Vec<u8> = COOKIE.to_be_bytes().into_iter().chain(tx).collect();
        reply.extend(address.octets().iter().zip(mask).map(|(a, b)| a ^ b));
        assert_eq!(
            mapped_address(&reply, &tx),
            Some(SocketAddr::new(address.into(), 47811))
        );
    }
}
