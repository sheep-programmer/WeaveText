//! Checks a public market speech adapter against real host codecs and mocked network IO.
use std::{fs, path::PathBuf, time::Duration};
use weave_plugin::PluginManager;
fn main() {
    let market=PathBuf::from(std::env::args().nth(1).expect("usage: marketcheck <market repository>"));
    let plugin=market.join("plugins/http-pcm");
    let root=std::env::temp_dir().join(format!("weave-marketcheck-{}",std::process::id()));
    fs::create_dir_all(root.join("plugin")).unwrap();
    let source=fs::read_to_string(plugin.join("main.lua")).unwrap();
    let test=fs::read_to_string(plugin.join("tests.lua")).unwrap().replace("__PLUGIN_SOURCE__",&source);
    fs::write(root.join("plugin/manifest.yaml"),"id: org.weavetext.test.market\nname: Market protocol check\nversion: 1\ntype: test\n").unwrap();
    fs::write(root.join("plugin/main.lua"),test).unwrap();
    let mut manager=PluginManager::new(root.join("installed"),root.join("config"));
    let info=manager.add_path(&root.join("plugin")).unwrap();
    let result=manager.call(&info.id,"run","[]",Duration::from_secs(10)).unwrap();
    println!("{result}");
    assert_eq!(serde_json::from_str::<serde_json::Value>(&result).unwrap()["ok"],true);
    drop(manager);
    fs::remove_dir_all(root).unwrap();
}
