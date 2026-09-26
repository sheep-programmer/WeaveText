//! host.crypto 的纯函数实现（与 Lua 无关，便于单测）。
//! Pure implementations behind `host.crypto` (Lua-agnostic, unit-testable).

use base64::engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD};
use base64::engine::{DecodePaddingMode, GeneralPurpose, GeneralPurposeConfig};
use base64::Engine;
use cipher::block_padding::{NoPadding, Pkcs7};
use cipher::{BlockDecryptMut, BlockEncryptMut, KeyInit, KeyIvInit, StreamCipher};
use hmac::{Hmac, Mac};
use sha1::Sha1;
use sha2::{Digest, Sha256, Sha512};

// ---------------------------------------------------------------- 哈希 / digests

pub fn md5(data: &[u8]) -> Vec<u8> {
    md5::Md5::digest(data).to_vec()
}

pub fn sha1(data: &[u8]) -> Vec<u8> {
    Sha1::digest(data).to_vec()
}

pub fn sha256(data: &[u8]) -> Vec<u8> {
    Sha256::digest(data).to_vec()
}

pub fn sha512(data: &[u8]) -> Vec<u8> {
    Sha512::digest(data).to_vec()
}

pub fn hmac_sha1(key: &[u8], data: &[u8]) -> Vec<u8> {
    let mut m = <Hmac<Sha1> as Mac>::new_from_slice(key).expect("hmac accepts any key");
    m.update(data);
    m.finalize().into_bytes().to_vec()
}

pub fn hmac_sha256(key: &[u8], data: &[u8]) -> Vec<u8> {
    let mut m = <Hmac<Sha256> as Mac>::new_from_slice(key).expect("hmac accepts any key");
    m.update(data);
    m.finalize().into_bytes().to_vec()
}

pub fn hmac_sha512(key: &[u8], data: &[u8]) -> Vec<u8> {
    let mut m = <Hmac<Sha512> as Mac>::new_from_slice(key).expect("hmac accepts any key");
    m.update(data);
    m.finalize().into_bytes().to_vec()
}

pub fn hmac_md5(key: &[u8], data: &[u8]) -> Vec<u8> {
    let mut m = <Hmac<md5::Md5> as Mac>::new_from_slice(key).expect("hmac accepts any key");
    m.update(data);
    m.finalize().into_bytes().to_vec()
}

// ---------------------------------------------------------------- 编码 / encodings

/// 解码时容忍有无 `=` 填充。Decoding accepts both padded and unpadded input.
const LENIENT: GeneralPurposeConfig =
    GeneralPurposeConfig::new().with_decode_padding_mode(DecodePaddingMode::Indifferent);
const STD_LENIENT: GeneralPurpose = GeneralPurpose::new(&base64::alphabet::STANDARD, LENIENT);
const URL_LENIENT: GeneralPurpose = GeneralPurpose::new(&base64::alphabet::URL_SAFE, LENIENT);

pub fn base64_encode(data: &[u8]) -> String {
    STANDARD.encode(data)
}

/// 标准字母表解码，忽略空白（有些服务端会按 76 列折行）。
/// Standard alphabet; whitespace is ignored (some servers wrap at 76 columns).
pub fn base64_decode(s: &[u8]) -> Option<Vec<u8>> {
    let clean: Vec<u8> = s
        .iter()
        .copied()
        .filter(|c| !c.is_ascii_whitespace())
        .collect();
    STD_LENIENT.decode(&clean).ok()
}

/// URL 安全字母表、无填充（JWT 风格）。URL-safe alphabet, no padding (JWT style).
pub fn base64url_encode(data: &[u8]) -> String {
    URL_SAFE_NO_PAD.encode(data)
}

pub fn base64url_decode(s: &[u8]) -> Option<Vec<u8>> {
    let clean: Vec<u8> = s
        .iter()
        .copied()
        .filter(|c| !c.is_ascii_whitespace())
        .collect();
    URL_LENIENT.decode(&clean).ok()
}

/// 小写 hex。Lowercase hex.
pub fn hex_encode(data: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(data.len() * 2);
    for b in data {
        out.push(HEX[(b >> 4) as usize] as char);
        out.push(HEX[(b & 15) as usize] as char);
    }
    out
}

/// 大小写皆可；长度为奇数或含非 hex 字符返回 None。
/// Case-insensitive; odd length or non-hex characters yield None.
pub fn hex_decode(s: &[u8]) -> Option<Vec<u8>> {
    fn nib(c: u8) -> Option<u8> {
        match c {
            b'0'..=b'9' => Some(c - b'0'),
            b'a'..=b'f' => Some(c - b'a' + 10),
            b'A'..=b'F' => Some(c - b'A' + 10),
            _ => None,
        }
    }
    if !s.len().is_multiple_of(2) {
        return None;
    }
    s.chunks(2)
        .map(|p| Some(nib(p[0])? << 4 | nib(p[1])?))
        .collect()
}

/// RFC 3986 百分号编码：只保留 `A-Z a-z 0-9 - _ . ~`，空格编成 `%20`。
/// RFC 3986 percent-encoding: keeps only unreserved characters; space becomes `%20`.
pub fn url_encode(data: &[u8]) -> String {
    let mut out = String::with_capacity(data.len() * 3);
    for &b in data {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b'~') {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{:02X}", b));
        }
    }
    out
}

/// `%XX` 解码；`+` 按空格处理（兼容表单编码）。`%XX` decoding; `+` decodes to space.
pub fn url_decode(data: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(data.len());
    let mut i = 0;
    while i < data.len() {
        match data[i] {
            b'%' if i + 2 < data.len() => {
                if let Some(v) = hex_decode(&data[i + 1..i + 3]) {
                    out.push(v[0]);
                    i += 3;
                    continue;
                }
                out.push(b'%');
            }
            b'+' => out.push(b' '),
            c => out.push(c),
        }
        i += 1;
    }
    out
}

pub fn random_bytes(n: usize) -> Vec<u8> {
    let mut v = vec![0u8; n];
    getrandom::getrandom(&mut v).expect("OS random source unavailable");
    v
}

/// 随机 UUID v4（小写、带连字符）。Random UUID v4, lowercase with dashes.
pub fn uuid_v4() -> String {
    let mut b = random_bytes(16);
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    let h = hex_encode(&b);
    format!(
        "{}-{}-{}-{}-{}",
        &h[0..8],
        &h[8..12],
        &h[12..16],
        &h[16..20],
        &h[20..32]
    )
}

pub fn epoch_seconds() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

pub fn epoch_millis() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

// ---------------------------------------------------------------- 对称加密 / symmetric

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Mode {
    Cbc,
    Ecb,
    Ctr,
}

/// 解析 JCE 风格的变换串，例如 `AES/CBC/PKCS5Padding`、`AES/ECB/NoPadding`、`AES/CTR/NoPadding`。
/// 返回 (模式, 是否 PKCS#7 填充)。
/// Parses a JCE-style transformation string; returns (mode, pkcs7?).
fn parse_transformation(t: &str) -> Result<(Mode, bool), String> {
    let parts: Vec<String> = t
        .split('/')
        .map(|s| s.trim().to_ascii_uppercase())
        .collect();
    let alg = parts.first().map(String::as_str).unwrap_or("");
    if !(alg == "AES" || alg.starts_with("AES_") || alg.starts_with("AES-")) {
        return Err(format!("unsupported cipher: {t}"));
    }
    let mode = match parts.get(1).map(String::as_str).unwrap_or("ECB") {
        "CBC" => Mode::Cbc,
        "ECB" => Mode::Ecb,
        "CTR" => Mode::Ctr,
        m => return Err(format!("unsupported cipher mode: {m}")),
    };
    let pad = match parts.get(2).map(String::as_str) {
        None => mode != Mode::Ctr,
        Some("PKCS5PADDING") | Some("PKCS7PADDING") => true,
        Some("NOPADDING") => false,
        Some(p) => return Err(format!("unsupported padding: {p}")),
    };
    if mode == Mode::Ctr && pad {
        return Err("CTR mode does not use padding".into());
    }
    Ok((mode, pad))
}

macro_rules! with_aes {
    ($key:expr, $K:ident => $body:expr) => {
        match $key.len() {
            16 => {
                type $K = aes::Aes128;
                $body
            }
            24 => {
                type $K = aes::Aes192;
                $body
            }
            32 => {
                type $K = aes::Aes256;
                $body
            }
            n => Err(format!("invalid AES key length {n}")),
        }
    };
}

fn need_iv(iv: Option<&[u8]>) -> Result<&[u8], String> {
    match iv {
        Some(v) if v.len() == 16 => Ok(v),
        Some(v) => Err(format!("invalid IV length {}", v.len())),
        None => Err("IV required".into()),
    }
}

/// AES 加密（参数顺序与插件一致：变换串、密钥、数据、IV）。
/// AES encryption; argument order follows the plugins: transformation, key, data, iv.
pub fn sym_encrypt(
    transformation: &str,
    key: &[u8],
    data: &[u8],
    iv: Option<&[u8]>,
) -> Result<Vec<u8>, String> {
    let (mode, pad) = parse_transformation(transformation)?;
    if !pad && mode != Mode::Ctr && !data.len().is_multiple_of(16) {
        return Err("data length must be a multiple of 16 with NoPadding".into());
    }
    with_aes!(key, K => {
        match mode {
            Mode::Cbc => {
                let c = cbc::Encryptor::<K>::new_from_slices(key, need_iv(iv)?).map_err(|e| e.to_string())?;
                Ok(if pad { c.encrypt_padded_vec_mut::<Pkcs7>(data) } else { c.encrypt_padded_vec_mut::<NoPadding>(data) })
            }
            Mode::Ecb => {
                let c = ecb::Encryptor::<K>::new_from_slice(key).map_err(|e| e.to_string())?;
                Ok(if pad { c.encrypt_padded_vec_mut::<Pkcs7>(data) } else { c.encrypt_padded_vec_mut::<NoPadding>(data) })
            }
            Mode::Ctr => {
                let mut c = ctr::Ctr128BE::<K>::new_from_slices(key, need_iv(iv)?).map_err(|e| e.to_string())?;
                let mut buf = data.to_vec();
                c.apply_keystream(&mut buf);
                Ok(buf)
            }
        }
    })
}

pub fn sym_decrypt(
    transformation: &str,
    key: &[u8],
    data: &[u8],
    iv: Option<&[u8]>,
) -> Result<Vec<u8>, String> {
    let (mode, pad) = parse_transformation(transformation)?;
    if mode != Mode::Ctr && !data.len().is_multiple_of(16) {
        return Err("ciphertext length must be a multiple of 16".into());
    }
    with_aes!(key, K => {
        match mode {
            Mode::Cbc => {
                let c = cbc::Decryptor::<K>::new_from_slices(key, need_iv(iv)?).map_err(|e| e.to_string())?;
                if pad { c.decrypt_padded_vec_mut::<Pkcs7>(data).map_err(|_| "bad padding".to_string()) }
                else { c.decrypt_padded_vec_mut::<NoPadding>(data).map_err(|_| "decrypt failed".to_string()) }
            }
            Mode::Ecb => {
                let c = ecb::Decryptor::<K>::new_from_slice(key).map_err(|e| e.to_string())?;
                if pad { c.decrypt_padded_vec_mut::<Pkcs7>(data).map_err(|_| "bad padding".to_string()) }
                else { c.decrypt_padded_vec_mut::<NoPadding>(data).map_err(|_| "decrypt failed".to_string()) }
            }
            Mode::Ctr => {
                let mut c = ctr::Ctr128BE::<K>::new_from_slices(key, need_iv(iv)?).map_err(|e| e.to_string())?;
                let mut buf = data.to_vec();
                c.apply_keystream(&mut buf);
                Ok(buf)
            }
        }
    })
}

// ---------------------------------------------------------------- RSA

/// RSA 公钥加密。`der` 为 X.509 SubjectPublicKeyInfo（也接受 PKCS#1 RSAPublicKey）DER。
/// `padding`：`OAEP-SHA256`（MGF1 同为 SHA-256）、
/// `OAEP-SHA256-MGF1SHA1`（MGF1 用 SHA-1）、`OAEP-SHA1` / `OAEP`、`PKCS1`。
///
/// RSA public-key encryption. `der` is an X.509 SubjectPublicKeyInfo (PKCS#1 also accepted).
/// Padding names: see above; `OAEP-SHA256` uses MGF1-SHA256, `-MGF1SHA1` uses MGF1-SHA1.
pub fn rsa_encrypt(der: &[u8], data: &[u8], padding: &str) -> Result<Vec<u8>, String> {
    use rsa::pkcs1::DecodeRsaPublicKey;
    use rsa::pkcs8::DecodePublicKey;
    use rsa::{Oaep, Pkcs1v15Encrypt, RsaPublicKey};
    let key = RsaPublicKey::from_public_key_der(der)
        .or_else(|_| RsaPublicKey::from_pkcs1_der(der))
        .map_err(|e| format!("invalid RSA public key: {e}"))?;
    let mut rng = rand_core::OsRng;
    let norm: String = padding
        .to_ascii_uppercase()
        .chars()
        .filter(|c| c.is_ascii_alphanumeric())
        .collect();
    let r = match norm.as_str() {
        "OAEPSHA256" | "OAEPWITHSHA256ANDMGF1PADDING" | "RSAECBOAEPWITHSHA256ANDMGF1PADDING" => {
            key.encrypt(&mut rng, Oaep::new::<Sha256>(), data)
        }
        "OAEPSHA256MGF1SHA1" => {
            key.encrypt(&mut rng, Oaep::new_with_mgf_hash::<Sha256, Sha1>(), data)
        }
        "OAEP" | "OAEPSHA1" | "OAEPWITHSHA1ANDMGF1PADDING" | "RSAECBOAEPWITHSHA1ANDMGF1PADDING" => {
            key.encrypt(&mut rng, Oaep::new::<Sha1>(), data)
        }
        "" | "PKCS1" | "PKCS1PADDING" | "RSAECBPKCS1PADDING" => {
            key.encrypt(&mut rng, Pkcs1v15Encrypt, data)
        }
        _ => return Err(format!("unsupported RSA padding: {padding}")),
    };
    r.map_err(|e| e.to_string())
}

// ---------------------------------------------------------------- secp128r1

/// 极简 secp128r1（SEC 2）实现，供 `host.crypto` 的 ECDH 使用：仿射坐标、u128 域运算。
/// 不追求常数时间——私钥是一次性的会话密钥，且曲线本身只有 64 位安全强度。
///
/// Minimal secp128r1 (SEC 2) behind `host.crypto`'s ECDH: affine coordinates, u128 field.
/// Not constant-time; keys are ephemeral and the curve only offers ~64-bit security anyway.
pub mod secp128r1 {
    const P: u128 = 0xFFFFFFFD_FFFFFFFF_FFFFFFFF_FFFFFFFF;
    const A: u128 = 0xFFFFFFFD_FFFFFFFF_FFFFFFFF_FFFFFFFC;
    const B: u128 = 0xE87579C1_1079F43D_D824993C_2CEE5ED3;
    const GX: u128 = 0x161FF752_8B899B2D_0C28607C_A52C5B86;
    const GY: u128 = 0xCF5AC839_5BAFEB13_C02DA292_DDED7A83;
    pub const N: u128 = 0xFFFFFFFE_00000000_75A30D1B_9038A115;

    fn add(a: u128, b: u128) -> u128 {
        let (s, carry) = a.overflowing_add(b);
        if carry || s >= P {
            s.wrapping_sub(P)
        } else {
            s
        }
    }

    fn sub(a: u128, b: u128) -> u128 {
        if a >= b {
            a - b
        } else {
            a.wrapping_sub(b).wrapping_add(P)
        }
    }

    fn mul(a: u128, b: u128) -> u128 {
        let mut r = 0u128;
        for i in (0..128).rev() {
            r = add(r, r);
            if (b >> i) & 1 == 1 {
                r = add(r, a);
            }
        }
        r
    }

    fn inv(a: u128) -> u128 {
        // 费马小定理 a^(p-2)。Fermat: a^(p-2).
        let e = P - 2;
        let mut r = 1u128;
        for i in (0..128).rev() {
            r = mul(r, r);
            if (e >> i) & 1 == 1 {
                r = mul(r, a);
            }
        }
        r
    }

    #[derive(Clone, Copy, PartialEq, Eq, Debug)]
    pub enum Point {
        Infinity,
        Affine(u128, u128),
    }

    pub const G: Point = Point::Affine(GX, GY);

    pub fn on_curve(p: Point) -> bool {
        match p {
            Point::Infinity => true,
            Point::Affine(x, y) => {
                x < P && y < P && mul(y, y) == add(add(mul(mul(x, x), x), mul(A, x)), B)
            }
        }
    }

    fn padd(p: Point, q: Point) -> Point {
        match (p, q) {
            (Point::Infinity, o) | (o, Point::Infinity) => o,
            (Point::Affine(x1, y1), Point::Affine(x2, y2)) => {
                let l = if x1 == x2 {
                    if add(y1, y2) == 0 {
                        return Point::Infinity;
                    }
                    // 倍点 λ = (3x² + a) / 2y
                    let num = add(mul(3, mul(x1, x1)), A);
                    mul(num, inv(add(y1, y1)))
                } else {
                    mul(sub(y2, y1), inv(sub(x2, x1)))
                };
                let x3 = sub(sub(mul(l, l), x1), x2);
                let y3 = sub(mul(l, sub(x1, x3)), y1);
                Point::Affine(x3, y3)
            }
        }
    }

    pub fn scalar_mul(k: u128, p: Point) -> Point {
        let mut r = Point::Infinity;
        for i in (0..128).rev() {
            r = padd(r, r);
            if (k >> i) & 1 == 1 {
                r = padd(r, p);
            }
        }
        r
    }

    pub fn encode_uncompressed(p: Point) -> Option<[u8; 33]> {
        match p {
            Point::Infinity => None,
            Point::Affine(x, y) => {
                let mut out = [0u8; 33];
                out[0] = 4;
                out[1..17].copy_from_slice(&x.to_be_bytes());
                out[17..33].copy_from_slice(&y.to_be_bytes());
                Some(out)
            }
        }
    }

    pub fn decode_uncompressed(b: &[u8]) -> Option<Point> {
        if b.len() != 33 || b[0] != 4 {
            return None;
        }
        let x = u128::from_be_bytes(b[1..17].try_into().ok()?);
        let y = u128::from_be_bytes(b[17..33].try_into().ok()?);
        let p = Point::Affine(x, y);
        on_curve(p).then_some(p)
    }

    /// 生成密钥对：(私钥 16 字节, 未压缩公钥 33 字节)。Generate (private 16 B, public 33 B).
    pub fn generate() -> ([u8; 16], [u8; 33]) {
        loop {
            let raw = super::random_bytes(16);
            let k = u128::from_be_bytes(raw[..16].try_into().unwrap());
            if k == 0 || k >= N {
                continue;
            }
            if let Some(pubk) = encode_uncompressed(scalar_mul(k, G)) {
                return (k.to_be_bytes(), pubk);
            }
        }
    }

    /// ECDH 共享点 X 坐标（16 字节大端）。Shared point X coordinate (16 bytes, big-endian).
    pub fn shared_x(private: &[u8], peer_public: &[u8]) -> Option<[u8; 16]> {
        if private.is_empty() || private.len() > 16 {
            return None;
        }
        let mut kb = [0u8; 16];
        kb[16 - private.len()..].copy_from_slice(private);
        let k = u128::from_be_bytes(kb);
        if k == 0 || k >= N {
            return None;
        }
        let q = decode_uncompressed(peer_public)?;
        match scalar_mul(k, q) {
            Point::Affine(x, _) => Some(x.to_be_bytes()),
            Point::Infinity => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn h(s: &str) -> Vec<u8> {
        hex_decode(s.as_bytes()).unwrap()
    }

    #[test]
    fn digests() {
        assert_eq!(hex_encode(&md5(b"")), "d41d8cd98f00b204e9800998ecf8427e");
        assert_eq!(hex_encode(&md5(b"abc")), "900150983cd24fb0d6963f7d28e17f72");
        assert_eq!(
            hex_encode(&sha1(b"abc")),
            "a9993e364706816aba3e25717850c26c9cd0d89d"
        );
        assert_eq!(
            hex_encode(&sha256(b"abc")),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
        assert!(hex_encode(&sha512(b"abc")).starts_with("ddaf35a193617aba"));
    }

    #[test]
    fn hmac_rfc4231_and_2202() {
        // RFC 4231 test case 2
        assert_eq!(
            hex_encode(&hmac_sha256(b"Jefe", b"what do ya want for nothing?")),
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
        );
        // RFC 2202 test case 2
        assert_eq!(
            hex_encode(&hmac_sha1(b"Jefe", b"what do ya want for nothing?")),
            "effcdf6ae5eb2fa2d27416d5f184df9c259a7c79"
        );
        assert_eq!(
            hex_encode(&hmac_md5(b"Jefe", b"what do ya want for nothing?")),
            "750c783e6ab0b503eaa86e310a5db738"
        );
    }

    #[test]
    fn encodings() {
        assert_eq!(base64_encode(b"foobar"), "Zm9vYmFy");
        assert_eq!(base64_encode(b"fo"), "Zm8=");
        assert_eq!(base64_decode(b"Zm8=").unwrap(), b"fo");
        assert_eq!(base64_decode(b"Zm8").unwrap(), b"fo");
        assert_eq!(base64_decode(b"Zm9v\nYmFy").unwrap(), b"foobar");
        assert!(base64_decode(b"!!!").is_none());
        assert_eq!(base64url_encode(&[0xfb, 0xff]), "-_8");
        assert_eq!(base64url_decode(b"-_8=").unwrap(), vec![0xfb, 0xff]);
        assert_eq!(hex_encode(&[0, 0xab, 0x10]), "00ab10");
        assert_eq!(hex_decode(b"00AB10").unwrap(), vec![0, 0xab, 0x10]);
        assert!(hex_decode(b"abc").is_none());
        assert_eq!(
            url_encode("Mon, 02 Mar 2026 09:30:00 GMT".as_bytes()),
            "Mon%2C%2002%20Mar%202026%2009%3A30%3A00%20GMT"
        );
        assert_eq!(url_encode("a+b=c/~_-.".as_bytes()), "a%2Bb%3Dc%2F~_-.");
        assert_eq!(url_encode("中".as_bytes()), "%E4%B8%AD");
        assert_eq!(url_decode(b"a%2Bb+c%E4%B8%AD"), "a+b c中".as_bytes());
    }

    #[test]
    fn uuid_shape() {
        let u = uuid_v4();
        assert_eq!(u.len(), 36);
        assert_eq!(&u[14..15], "4");
        assert_ne!(u, uuid_v4());
    }

    #[test]
    fn aes_nist_vectors() {
        // NIST SP 800-38A F.2.1 CBC-AES128.Encrypt（第一块）
        let key = h("2b7e151628aed2a6abf7158809cf4f3c");
        let iv = h("000102030405060708090a0b0c0d0e0f");
        let pt = h("6bc1bee22e409f96e93d7e117393172a");
        let ct = sym_encrypt("AES/CBC/NoPadding", &key, &pt, Some(&iv)).unwrap();
        assert_eq!(hex_encode(&ct), "7649abac8119b246cee98e9b12e9197d");
        assert_eq!(
            sym_decrypt("AES/CBC/NoPadding", &key, &ct, Some(&iv)).unwrap(),
            pt
        );
        // F.1.1 ECB-AES128
        let ct = sym_encrypt("AES/ECB/NoPadding", &key, &pt, None).unwrap();
        assert_eq!(hex_encode(&ct), "3ad77bb40d7a3660a89ecaf32466ef97");
        // F.5.1 CTR-AES128
        let ctr_iv = h("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff");
        let ct = sym_encrypt("AES/CTR/NoPadding", &key, &pt, Some(&ctr_iv)).unwrap();
        assert_eq!(hex_encode(&ct), "874d6191b620e3261bef6864990db6ce");
        assert_eq!(
            sym_decrypt("AES/CTR/NoPadding", &key, &ct, Some(&ctr_iv)).unwrap(),
            pt
        );
        // F.2.5 CBC-AES256
        let key256 = h("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4");
        let ct = sym_encrypt("AES/CBC/NoPadding", &key256, &pt, Some(&iv)).unwrap();
        assert_eq!(hex_encode(&ct), "f58c4c04d6e5f1ba779eabfb5f7bfbd6");
    }

    #[test]
    fn aes_pkcs7_roundtrip() {
        let key = b"0123456789abcdef";
        let ct = sym_encrypt("AES/CBC/PKCS5Padding", key, b"hello", Some(key)).unwrap();
        assert_eq!(ct.len(), 16);
        assert_eq!(
            sym_decrypt("AES/CBC/PKCS5Padding", key, &ct, Some(key)).unwrap(),
            b"hello"
        );
        let k32 = b"an example 32-byte AES-256 key!!";
        let ct = sym_encrypt("AES/ECB/PKCS5Padding", k32, &[7u8; 32], None).unwrap();
        assert_eq!(ct.len(), 48);
        assert_eq!(
            sym_decrypt("AES/ECB/PKCS5Padding", k32, &ct, None).unwrap(),
            vec![7u8; 32]
        );
        assert!(sym_decrypt("AES/ECB/PKCS5Padding", k32, &[0u8; 15], None).is_err());
        assert!(sym_encrypt("DES/CBC/PKCS5Padding", key, b"x", Some(key)).is_err());
        assert!(sym_encrypt("AES/CBC/PKCS5Padding", b"short", b"x", Some(key)).is_err());
    }

    #[test]
    fn rsa_oaep_decrypts_back() {
        use rsa::pkcs8::EncodePublicKey;
        use rsa::{Oaep, RsaPrivateKey};
        let mut rng = rand_core::OsRng;
        let sk = RsaPrivateKey::new(&mut rng, 1024).unwrap();
        let der = sk.to_public_key().to_public_key_der().unwrap();
        let ct = rsa_encrypt(der.as_bytes(), b"secret", "OAEP-SHA256").unwrap();
        assert_eq!(ct.len(), 128);
        assert_eq!(sk.decrypt(Oaep::new::<Sha256>(), &ct).unwrap(), b"secret");
        let ct = rsa_encrypt(der.as_bytes(), b"secret", "OAEP-SHA256-MGF1SHA1").unwrap();
        assert_eq!(
            sk.decrypt(Oaep::new_with_mgf_hash::<Sha256, Sha1>(), &ct)
                .unwrap(),
            b"secret"
        );
        assert!(rsa_encrypt(b"garbage", b"x", "OAEP-SHA256").is_err());
    }

    #[test]
    fn secp128r1_ecdh() {
        use secp128r1::*;
        assert!(on_curve(G));
        assert_eq!(scalar_mul(N, G), Point::Infinity);
        assert_eq!(scalar_mul(N + 1, G), G);
        let (a_priv, a_pub) = generate();
        let (b_priv, b_pub) = generate();
        let s1 = shared_x(&a_priv, &b_pub).unwrap();
        let s2 = shared_x(&b_priv, &a_pub).unwrap();
        assert_eq!(s1, s2);
        let mut bad = a_pub;
        bad[20] ^= 1;
        assert!(shared_x(&b_priv, &bad).is_none());
    }
}
