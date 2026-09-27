//! 本机身份（Noise 静态密钥）与已配对设备列表，存放在状态目录。
//! Local identity (the Noise static key) and the trusted-device list, kept in the state directory.

use std::fs;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::wire::{hex, unhex};

pub const NOISE_XX: &str = "Noise_XX_25519_ChaChaPoly_BLAKE2s";
pub const NOISE_XX_PSK: &str = "Noise_XXpsk3_25519_ChaChaPoly_BLAKE2s";

#[derive(Clone)]
pub struct Identity {
    pub private: Vec<u8>,
    pub public: Vec<u8>,
}

impl Identity {
    /// 读取或生成；私钥文件仅本用户可读。 Load or create; the key file is readable by the owner only.
    pub fn load_or_create(dir: &Path) -> Result<Identity, String> {
        let path = dir.join("identity.key");
        if let Ok(text) = fs::read_to_string(&path) {
            let mut lines = text.lines();
            if let (Some(a), Some(b)) = (lines.next().and_then(unhex), lines.next().and_then(unhex)) {
                if a.len() == 32 && b.len() == 32 {
                    return Ok(Identity { private: a, public: b });
                }
            }
        }
        let builder = snow::Builder::new(NOISE_XX.parse().map_err(err)?);
        let kp = builder.generate_keypair().map_err(err)?;
        fs::create_dir_all(dir).map_err(err)?;
        let tmp = dir.join("identity.key.tmp");
        fs::write(&tmp, format!("{}\n{}\n", hex(&kp.private), hex(&kp.public))).map_err(err)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = fs::set_permissions(&tmp, fs::Permissions::from_mode(0o600));
        }
        fs::rename(&tmp, &path).map_err(err)?;
        Ok(Identity { private: kp.private, public: kp.public })
    }

    pub fn id(&self) -> String {
        device_id(&self.public)
    }
}

/// 设备 id：公钥 SHA-256 的前 8 字节（16 位十六进制）。 Device id: first 8 bytes of SHA-256(public key).
pub fn device_id(public: &[u8]) -> String {
    hex(&Sha256::digest(public)[..8])
}

/// 便于人工核对的指纹：4 组 4 位。 A human-checkable fingerprint: four groups of four.
pub fn fingerprint(public: &[u8]) -> String {
    let h = hex(&Sha256::digest(public)[..8]).to_uppercase();
    (0..4).map(|i| &h[i * 4..i * 4 + 4]).collect::<Vec<_>>().join("-")
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
pub struct Peer {
    pub id: String,
    pub name: String,
    pub platform: String,
    /// 对方静态公钥（十六进制）。 The peer's static public key (hex).
    pub key: String,
    /// 最近一次可用的地址（ip:port）。 Last known addresses (ip:port).
    #[serde(default)]
    pub addrs: Vec<String>,
}

pub struct PeerStore {
    path: PathBuf,
    pub peers: Vec<Peer>,
}

impl PeerStore {
    pub fn load(dir: &Path) -> PeerStore {
        let path = dir.join("peers.json");
        let peers = fs::read(&path).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
        PeerStore { path, peers }
    }

    pub fn save(&self) {
        let tmp = self.path.with_extension("json.tmp");
        if let Ok(b) = serde_json::to_vec_pretty(&self.peers) {
            if fs::write(&tmp, b).is_ok() {
                let _ = fs::rename(&tmp, &self.path);
            }
        }
    }

    pub fn by_key(&self, key: &[u8]) -> Option<&Peer> {
        let k = hex(key);
        self.peers.iter().find(|p| p.key == k)
    }

    pub fn get(&self, id: &str) -> Option<&Peer> {
        self.peers.iter().find(|p| p.id == id)
    }

    /// 新增或更新（同 id 覆盖）。 Insert or replace by id.
    pub fn upsert(&mut self, p: Peer) {
        match self.peers.iter_mut().find(|q| q.id == p.id) {
            Some(q) => *q = p,
            None => self.peers.push(p),
        }
        self.save();
    }

    pub fn remember_addr(&mut self, id: &str, addr: &str) {
        if let Some(p) = self.peers.iter_mut().find(|p| p.id == id) {
            if p.addrs.first().map(String::as_str) != Some(addr) {
                p.addrs.retain(|a| a != addr);
                p.addrs.insert(0, addr.to_string());
                p.addrs.truncate(4);
                self.save();
            }
        }
    }

    pub fn remove(&mut self, id: &str) -> bool {
        let n = self.peers.len();
        self.peers.retain(|p| p.id != id);
        let changed = self.peers.len() != n;
        if changed {
            self.save();
        }
        changed
    }
}

pub fn err(e: impl std::fmt::Display) -> String {
    e.to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identity_persists_and_peers_round_trip() {
        let dir = std::env::temp_dir().join(format!("weave-link-store-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        let a = Identity::load_or_create(&dir).unwrap();
        let b = Identity::load_or_create(&dir).unwrap();
        assert_eq!(a.public, b.public);
        assert_eq!(a.id().len(), 16);
        assert_eq!(fingerprint(&a.public).len(), 19);
        let mut s = PeerStore::load(&dir);
        s.upsert(Peer { id: "p1".into(), name: "手机".into(), platform: "android".into(), key: hex(&[7; 32]), addrs: vec![] });
        s.remember_addr("p1", "192.168.1.9:5000");
        let s2 = PeerStore::load(&dir);
        assert_eq!(s2.peers[0].addrs, vec!["192.168.1.9:5000".to_string()]);
        assert!(s2.by_key(&[7; 32]).is_some());
        let mut s3 = s2;
        assert!(s3.remove("p1"));
        assert!(PeerStore::load(&dir).peers.is_empty());
        let _ = fs::remove_dir_all(&dir);
    }
}
