//! 云端热词：维护者在公开仓库里整理的新词、热词，客户端只下载（默认关闭）。
//! Cloud hot words: new and trending words curated in a public repository; clients only download (off by default).
//!
//! 文件 / Files: `hotwords.tsv`（UTF-8）与 `hotwords.tsv.sig`（Ed25519 签名的十六进制，签的是 tsv 的原始字节）。
//! ```text
//! #! weavetext-hotwords 1
//! #! version 2026092701
//! 词<TAB>拼音（空格分隔，无声调，ü 写 v）<TAB>权重 1–1000<TAB>到期日 YYYY-MM-DD（可空）
//! ```
//! 客户端先验签（公钥固定在这里），再把未到期的词编译成一个扩展词库：计分与基础词库同尺度、整体靠后
//! [CLOUD_BIAS]，热词出现在候选里但不抢常用词的首位。
//! The client verifies the signature against the key pinned here, then compiles the unexpired words into an extra
//! lexicon scored on the base scale and [CLOUD_BIAS] behind it: hot words show up without taking over first place.

use ed25519_dalek::{Signature, VerifyingKey};
use weave_dict::lexicon::{prob_to_cost, Builder, Kind};
use weave_dict::syllable;

/// 热词文件签名公钥（私钥由维护者保管，只在热词仓库的发布流程里使用）。
/// Public key of the hot-words signature; the private key is kept by the maintainer for the publishing workflow.
pub const HOTWORDS_KEY: [u8; 32] = [
    0x36, 0xe1, 0x14, 0xbf, 0xf3, 0x4e, 0x50, 0xe1, 0xc9, 0x9a, 0x2d, 0x90, 0x94, 0x84, 0x01, 0xf3, 0x2f, 0x1f, 0xe4,
    0xfa, 0xc7, 0x3a, 0xc3, 0x09, 0x0f, 0xcf, 0x41, 0x6b, 0xc9, 0xa9, 0x7c, 0x7b,
];

/// 基础词库的总权重（data/build.sh 的源；`dictgen total` 可复算）。 Total weight of the base lexicon.
pub const BASE_TOTAL: f64 = 1_206_215_396.0;
/// 热词整体靠后的 cost。 How far hot words sit behind the base.
pub const CLOUD_BIAS: u32 = 300;
/// 条数上限（防止异常文件占满内存）。 Row limit, guarding against a runaway file.
pub const MAX_ROWS: usize = 50_000;

/// 编译结果。 Compilation result.
pub struct Compiled {
    pub lexicon: Vec<u8>,
    pub words: usize,
    pub expired: usize,
    pub version: String,
}

/// 校验签名（十六进制，可带空白）。 Verify the hex signature (whitespace allowed).
pub fn verify(tsv: &[u8], sig_hex: &str, key: &[u8; 32]) -> bool {
    let hex: String = sig_hex.chars().filter(|c| !c.is_whitespace()).collect();
    if hex.len() != 128 {
        return false;
    }
    let mut sig = [0u8; 64];
    for (i, b) in sig.iter_mut().enumerate() {
        match u8::from_str_radix(&hex[i * 2..i * 2 + 2], 16) {
            Ok(v) => *b = v,
            Err(_) => return false,
        }
    }
    let Ok(k) = VerifyingKey::from_bytes(key) else { return false };
    k.verify_strict(tsv, &Signature::from_bytes(&sig)).is_ok()
}

/// 把 YYYY-MM-DD 换成自 1970-01-01 起的天数。 YYYY-MM-DD → days since 1970-01-01.
fn days(date: &str) -> Option<i64> {
    let mut it = date.trim().split('-');
    let (y, m, d): (i64, i64, i64) = (it.next()?.parse().ok()?, it.next()?.parse().ok()?, it.next()?.parse().ok()?);
    if !(1..=12).contains(&m) || !(1..=31).contains(&d) {
        return None;
    }
    let (y2, m2) = if m <= 2 { (y - 1, m + 12) } else { (y, m) };
    let era = y2.div_euclid(400);
    let yoe = y2 - era * 400;
    let doy = (153 * (m2 - 3) + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    Some(era * 146_097 + doe - 719_468)
}

/// 验签后编译；`today` 为今天距 1970-01-01 的天数（到期日早于今天的词丢弃）。
/// Verify, then compile; `today` is days since 1970-01-01 (words past their expiry are dropped).
pub fn compile(tsv: &[u8], sig_hex: &str, key: &[u8; 32], today: i64) -> Result<Compiled, String> {
    if !verify(tsv, sig_hex, key) {
        return Err("signature".into());
    }
    let text = std::str::from_utf8(tsv).map_err(|_| "encoding".to_string())?;
    let mut version = String::new();
    let mut b = Builder::new(Kind::Pinyin);
    let (mut words, mut expired) = (0usize, 0usize);
    for line in text.lines() {
        if let Some(meta) = line.strip_prefix("#!") {
            if let Some(v) = meta.trim().strip_prefix("version") {
                version = v.trim().to_string();
            }
            continue;
        }
        if line.trim().is_empty() || line.starts_with('#') {
            continue;
        }
        let cols: Vec<&str> = line.split('\t').collect();
        let (Some(word), Some(py)) = (cols.first().map(|s| s.trim()), cols.get(1)) else { continue };
        let Some(key) = syllable::parse_seq(py) else { continue };
        if word.is_empty() || key.is_empty() || key.len() > 16 {
            continue;
        }
        if let Some(exp) = cols.get(3).filter(|s| !s.trim().is_empty()) {
            match days(exp) {
                Some(d) if d < today => {
                    expired += 1;
                    continue;
                }
                None => continue,
                _ => {}
            }
        }
        let w: f64 = cols.get(2).and_then(|s| s.trim().parse().ok()).unwrap_or(100.0f64).clamp(1.0, 1000.0);
        let cost = (prob_to_cost((w + 1.0) / BASE_TOTAL) as u32 + CLOUD_BIAS).min(u16::MAX as u32 - 1) as u16;
        if b.insert(&key, word, cost) {
            words += 1;
        }
        if words >= MAX_ROWS {
            break;
        }
    }
    Ok(Compiled { lexicon: b.build(), words, expired, version })
}

#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::{Signer, SigningKey};

    fn signed(text: &str) -> (Vec<u8>, String, [u8; 32]) {
        let sk = SigningKey::from_bytes(&[7u8; 32]);
        let sig = sk.sign(text.as_bytes());
        let hex: String = sig.to_bytes().iter().map(|b| format!("{b:02x}")).collect();
        (text.as_bytes().to_vec(), hex, sk.verifying_key().to_bytes())
    }

    #[test]
    fn verifies_filters_and_compiles() {
        let tsv = "#! weavetext-hotwords 1\n#! version 2026092701\n织文输入法\tzhi wen shu ru fa\t600\t\n旧热词\tjiu re ci\t500\t2026-01-01\n坏行\n";
        let (bytes, sig, key) = signed(tsv);
        let today = days("2026-09-27").unwrap();
        let c = compile(&bytes, &sig, &key, today).unwrap();
        assert_eq!((c.words, c.expired), (1, 1));
        assert_eq!(c.version, "2026092701");
        assert!(weave_dict::lexicon::Lexicon::from_bytes(c.lexicon).is_ok());
        // 改一个字节签名就失效。 One changed byte breaks the signature.
        let mut bad = bytes.clone();
        bad[30] ^= 1;
        assert!(compile(&bad, &sig, &key, today).is_err());
        assert!(compile(&bytes, &sig, &HOTWORDS_KEY, today).is_err());
        assert!(!verify(&bytes, "abc", &key));
    }

    #[test]
    fn dates() {
        assert_eq!(days("1970-01-01"), Some(0));
        assert_eq!(days("2026-09-27"), Some(20_723));
        assert_eq!(days("2026-13-01"), None);
        assert_eq!(crate::special::civil(days("2024-02-29").unwrap()), (2024, 2, 29));
    }
}
