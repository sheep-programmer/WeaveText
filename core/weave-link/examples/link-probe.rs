//! Test peer for device transport checks: emits a pairing code, returns text and two files.
use std::{fs, path::PathBuf, time::Duration};
use serde_json::json;
use weave_link::{Config, Link};
fn main() {
    let dir = PathBuf::from(std::env::args().nth(1).expect("isolated state directory"));
    let link = Link::start(Config { name:"WeaveLink test Mac".into(), platform:"mac".into(), state_dir:dir.join("state"), inbox_dir:dir.join("inbox"), port:1, mdns:true }).unwrap();
    let info = link.call(&json!({"op":"info"}));
    let pairing = link.call(&json!({"op":"openPairing"}));
    fs::write(dir.join("pair.json"), json!({"port":info["port"], "code":pairing["code"]}).to_string()).unwrap();
    // A real, decodable 1x1 PNG and arbitrary bytes.
    let png: Vec<u8> = vec![137,80,78,71,13,10,26,10,0,0,0,13,73,72,68,82,0,0,0,1,0,0,0,1,8,6,0,0,0,31,21,196,137,0,0,0,13,73,68,65,84,120,156,99,248,207,192,240,31,0,5,0,1,255,137,153,61,29,0,0,0,0,73,69,78,68,174,66,96,130];
    let binary: Vec<u8> = (0..90_000).map(|n|(n%253) as u8).collect();
    fs::write(dir.join("photo.png"), png).unwrap(); fs::write(dir.join("payload.bin"), binary).unwrap();
    loop {
        if let Some(event) = link.poll(Duration::from_secs(1)) {
            if event["type"] != "idle" { println!("{event}"); }
            if event["type"] == "text" && event["text"] == "device-ready" {
                for (name,mime,clip) in [("photo.png","image/png",true),("payload.bin","application/octet-stream",true),("payload.bin","application/octet-stream",false)] {
                    link.call(&json!({"op":"sendFile","path":dir.join(name),"name":name,"mime":mime,"clip":clip}));
                }
            }
            if event["type"] == "text" && event["text"] == "device-finished" { break; }
        }
    }
}
