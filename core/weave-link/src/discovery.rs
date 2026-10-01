//! 局域网发现：mDNS / DNS-SD 服务 `_weavelink._tcp`，TXT 记录带设备 id、名称与平台。
//! LAN discovery: mDNS / DNS-SD service `_weavelink._tcp`, TXT records carry the device id, name and platform.

use std::net::{IpAddr, SocketAddr};

use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo};

use crate::store::err;

pub const SERVICE: &str = "_weavelink._tcp.local.";

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Found {
    pub id: String,
    pub name: String,
    pub platform: String,
    pub addrs: Vec<SocketAddr>,
}

pub enum Seen {
    Found(Found),
    /// mDNS 实例全名（以设备 id 开头）。 The instance full name (starts with the device id).
    Lost(String),
}

pub struct Discovery {
    daemon: ServiceDaemon,
}

impl Discovery {
    /// 广播本机并开始浏览；每发现 / 失去一台设备调用一次 [on_seen]（在 mDNS 线程上）。
    /// Advertise this device and browse; [on_seen] runs on the mDNS thread for every change.
    pub fn start(id: &str, name: &str, platform: &str, port: u16, on_seen: impl Fn(Seen) + Send + 'static) -> Result<Discovery, String> {
        let daemon = ServiceDaemon::new().map_err(err)?;
        let host = format!("weave-{id}.local.");
        let props = [("id", id), ("n", name), ("p", platform), ("v", "1")];
        let info = ServiceInfo::new(SERVICE, id, &host, "", port, &props[..]).map_err(err)?.enable_addr_auto();
        daemon.register(info).map_err(err)?;
        let rx = daemon.browse(SERVICE).map_err(err)?;
        let own = id.to_string();
        std::thread::Builder::new()
            .name("weavelink-mdns".into())
            .spawn(move || {
                while let Ok(ev) = rx.recv() {
                    match ev {
                        ServiceEvent::ServiceResolved(info) => {
                            let Some(pid) = info.get_property_val_str("id").map(str::to_string) else { continue };
                            if pid == own {
                                continue;
                            }
                            let mut addrs: Vec<SocketAddr> = info
                                .get_addresses()
                                .iter()
                                .filter(|ip| usable(ip))
                                .map(|ip| SocketAddr::new(*ip, info.get_port()))
                                .collect();
                            // IPv4 优先（手机热点、家用路由上更可靠）。 IPv4 first; more reliable on home networks.
                            addrs.sort_by_key(|a| !a.is_ipv4());
                            on_seen(Seen::Found(Found {
                                id: pid,
                                name: info.get_property_val_str("n").unwrap_or("").to_string(),
                                platform: info.get_property_val_str("p").unwrap_or("").to_string(),
                                addrs,
                            }));
                        }
                        ServiceEvent::ServiceRemoved(_, full) => on_seen(Seen::Lost(full)),
                        _ => {}
                    }
                }
            })
            .map_err(err)?;
        Ok(Discovery { daemon })
    }

    pub fn stop(&self) {
        let _ = self.daemon.shutdown();
    }
}

fn usable(ip: &IpAddr) -> bool {
    match ip {
        IpAddr::V4(v) => !v.is_loopback() && !v.is_link_local() && !v.is_unspecified() && !v.is_multicast(),
        IpAddr::V6(v) => !v.is_loopback() && !v.is_unspecified() && !v.is_multicast() && (v.segments()[0] & 0xffc0) != 0xfe80,
    }
}

/// 本机可用于二维码的 IPv4 地址。 Local IPv4 addresses worth putting into the QR code.
pub fn local_addrs() -> Vec<IpAddr> {
    let mut v: Vec<IpAddr> = if_addrs::get_if_addrs()
        .unwrap_or_default()
        .into_iter()
        .filter(|i| !i.is_loopback())
        .map(|i| i.ip())
        .filter(usable)
        .collect();
    v.sort_by_key(|ip| (!ip.is_ipv4(), *ip));
    v.dedup();
    v
}
