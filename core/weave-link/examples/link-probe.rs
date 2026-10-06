//! Test peer for device transport checks: emits a pairing code, returns text and two files.
use std::{fs, path::PathBuf, time::Duration};
use serde_json::json;
use weave_link::{Config, Link};
fn main() {
    let direct = std::env::args().any(|a| a == "--direct");
    let public = std::env::args().any(|a| a == "--public");
    let dir = PathBuf::from(std::env::args().nth(1).expect("isolated state directory"));
    let link = Link::start(Config { name:"WeaveLink test Mac".into(), platform:"mac".into(), state_dir:dir.join("state"), inbox_dir:dir.join("inbox"), port:1, mdns:true }).unwrap();
    let info = link.call(&json!({"op":"info"}));
    if direct {
        link.call(&if public { json!({"op":"openDirect"}) } else { json!({"op":"openDirect","stun":[]}) });
        loop {
            if let Some(event) = link.poll(Duration::from_secs(1)) {
                if event["type"] == "directReady" {
                    fs::write(dir.join("pair.json"), json!({"ticket":event["ticket"],"public":event["public"]}).to_string()).unwrap(); break;
                }
                assert_ne!(event["type"], "directFailed", "{event}");
            }
        }
    } else {
        let pairing = link.call(&json!({"op":"openPairing"}));
        fs::write(dir.join("pair.json"), json!({"port":info["port"], "code":pairing["code"]}).to_string()).unwrap();
    }
    // A real, decodable 1x1 PNG and arbitrary bytes.
    let png: Vec<u8> = vec![137,80,78,71,13,10,26,10,0,0,0,13,73,72,68,82,0,0,0,1,0,0,0,1,8,6,0,0,0,31,21,196,137,0,0,0,13,73,68,65,84,120,156,99,248,207,192,240,31,0,5,0,1,255,137,153,61,29,0,0,0,0,73,69,78,68,174,66,96,130];
    let binary: Vec<u8> = (0..90_000).map(|n|(n%253) as u8).collect();
    let profile=json!({"format":"weavetext-personal-1","personal":{"device":"test-mac","clock":1000,"records":{
        "snippet:pc":{"clock":999,"device":"test-mac","value":"电脑 {date}"},
        "pin:pinyin:shi":{"clock":1000,"device":"test-mac","value":"嗜"}
    }}});
    fs::write(dir.join("personal.weaveprofile"),profile.to_string()).unwrap();
    fs::write(dir.join("photo.png"), png).unwrap(); fs::write(dir.join("payload.bin"), binary).unwrap();
    let mut joined = false;
    loop {
        if direct && !joined {
            if let Ok(ticket) = fs::read_to_string(dir.join("phone-ticket")) {
                assert_eq!(link.call(&json!({"op":"joinDirect","ticket":ticket}))["ok"],true);
                joined = true;
            }
        }
        if let Some(event) = link.poll(Duration::from_millis(200)) {
            if event["type"] != "idle" { println!("{event}"); }
            if event["type"] == "text" && event["text"] == "device-ready" {
                for (name,mime,clip) in [("photo.png","image/png",true),("payload.bin","application/octet-stream",true),("payload.bin","application/octet-stream",false),("personal.weaveprofile","application/x-weavetext-personal",false)] {
                    link.call(&json!({"op":"sendFile","path":dir.join(name),"name":name,"mime":mime,"clip":clip}));
                }
            }
            if event["type"] == "text" && event["text"] == "device-finished" { break; }
        }
    }
}
