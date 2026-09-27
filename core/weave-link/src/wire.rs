//! 线路格式：长度前缀帧与应用消息。
//! Wire format: length-prefixed frames and application messages.
//!
//! ```text
//! 连接开头（明文）/ connection preamble (plain):  "WLK1" | mode u8 (0 = 连接 connect, 1 = 配对 pair)
//! 帧 / frame:                                      len u16 BE | bytes
//! 应用消息（Noise 加密后装进一帧）/ message (inside one Noise frame):
//!                                                  kind u8 | header_len u16 BE | header JSON | payload
//! ```

use std::io::{self, Read, Write};

use serde_json::Value;

pub const MAGIC: &[u8; 4] = b"WLK1";
pub const MODE_CONNECT: u8 = 0;
pub const MODE_PAIR: u8 = 1;

/// Noise 单条消息上限。 Maximum Noise message size.
pub const MAX_FRAME: usize = 65535;
/// Noise 认证标签长度。 Noise AEAD tag length.
pub const TAG: usize = 16;
/// 文件分块大小（加上头部仍在一帧之内）。 File chunk size; header included it still fits one frame.
pub const CHUNK: usize = 60 * 1024;

pub fn write_frame(w: &mut impl Write, bytes: &[u8]) -> io::Result<()> {
    if bytes.len() > MAX_FRAME {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "frame too large"));
    }
    let mut buf = Vec::with_capacity(2 + bytes.len());
    buf.extend_from_slice(&(bytes.len() as u16).to_be_bytes());
    buf.extend_from_slice(bytes);
    w.write_all(&buf)?;
    w.flush()
}

pub fn read_frame(r: &mut impl Read) -> io::Result<Vec<u8>> {
    let mut len = [0u8; 2];
    r.read_exact(&mut len)?;
    let n = u16::from_be_bytes(len) as usize;
    let mut buf = vec![0u8; n];
    r.read_exact(&mut buf)?;
    Ok(buf)
}

/// 应用消息种类。 Application message kinds.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum Kind {
    Hello = 1,
    Ping = 2,
    Pong = 3,
    /// 文字（剪贴板或直接发送）。 Text (clipboard or a direct send).
    Text = 10,
    FileOffer = 20,
    FileChunk = 21,
    FileDone = 22,
    FileCancel = 23,
}

impl Kind {
    pub fn from_u8(b: u8) -> Option<Kind> {
        Some(match b {
            1 => Kind::Hello,
            2 => Kind::Ping,
            3 => Kind::Pong,
            10 => Kind::Text,
            20 => Kind::FileOffer,
            21 => Kind::FileChunk,
            22 => Kind::FileDone,
            23 => Kind::FileCancel,
            _ => return None,
        })
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct Message {
    pub kind: Kind,
    pub header: Value,
    pub payload: Vec<u8>,
}

impl Message {
    pub fn new(kind: Kind, header: Value) -> Message {
        Message { kind, header, payload: Vec::new() }
    }

    pub fn with_payload(kind: Kind, header: Value, payload: Vec<u8>) -> Message {
        Message { kind, header, payload }
    }

    pub fn encode(&self) -> Vec<u8> {
        let h = self.header.to_string();
        let mut out = Vec::with_capacity(3 + h.len() + self.payload.len());
        out.push(self.kind as u8);
        out.extend_from_slice(&(h.len() as u16).to_be_bytes());
        out.extend_from_slice(h.as_bytes());
        out.extend_from_slice(&self.payload);
        out
    }

    pub fn decode(b: &[u8]) -> Option<Message> {
        let kind = Kind::from_u8(*b.first()?)?;
        let hl = u16::from_be_bytes([*b.get(1)?, *b.get(2)?]) as usize;
        let header = serde_json::from_slice(b.get(3..3 + hl)?).ok()?;
        Some(Message { kind, header, payload: b[3 + hl..].to_vec() })
    }
}

pub fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

pub fn unhex(s: &str) -> Option<Vec<u8>> {
    if !s.len().is_multiple_of(2) {
        return None;
    }
    (0..s.len()).step_by(2).map(|i| u8::from_str_radix(s.get(i..i + 2)?, 16).ok()).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn message_round_trip() {
        let m = Message::with_payload(Kind::FileChunk, json!({"id": "a1", "off": 7}), vec![1, 2, 3]);
        assert_eq!(Message::decode(&m.encode()), Some(m));
        assert_eq!(Message::decode(&[99, 0, 0]), None);
        assert_eq!(Message::decode(&[1, 0, 9, b'{']), None);
    }

    #[test]
    fn frames_and_hex() {
        let mut buf = Vec::new();
        write_frame(&mut buf, b"hello").unwrap();
        write_frame(&mut buf, b"").unwrap();
        let mut r = &buf[..];
        assert_eq!(read_frame(&mut r).unwrap(), b"hello");
        assert_eq!(read_frame(&mut r).unwrap(), b"");
        assert!(write_frame(&mut Vec::new(), &vec![0; MAX_FRAME + 1]).is_err());
        assert_eq!(unhex(&hex(&[0, 255, 16])).unwrap(), vec![0, 255, 16]);
        assert_eq!(unhex("abc"), None);
    }
}
