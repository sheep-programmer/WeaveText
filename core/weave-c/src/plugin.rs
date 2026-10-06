//! Desktop binding of the same sandboxed Lua host used on Android.
use super::{out, str_arg};
use serde_json::{json, Value};
use std::ffi::{c_char, c_void};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::Path;
use std::sync::{Arc, Mutex};
use weave_plugin::{Manifest, PluginManager, SpeechListener, SpeechSession};

pub struct WeavePluginHost(Mutex<PluginManager>);
pub struct WeaveSpeech(SpeechSession);

fn info(m: &Manifest) -> Value {
    json!({"id":m.id,"name":m.name,"description":m.description,"version":m.version,
        "kind":m.kind,"configSchema":m.config_schema,"networkHosts":m.network_hosts,
        "unrestrictedNetwork":m.allow_custom_hosts || m.permissions.iter().any(|p|p=="network_unrestricted")})
}

#[no_mangle]
pub extern "C" fn weave_plugin_create(p: *const c_char, c: *const c_char) -> *mut WeavePluginHost {
    catch_unwind(|| {
        let (Some(p), Some(c)) = (str_arg(p), str_arg(c)) else {return std::ptr::null_mut()};
        let mut m=PluginManager::new(p,c); m.scan();
        Box::into_raw(Box::new(WeavePluginHost(Mutex::new(m))))
    }).unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub extern "C" fn weave_plugin_destroy(h: *mut WeavePluginHost) {
    if !h.is_null() {let _=catch_unwind(AssertUnwindSafe(|| drop(unsafe {Box::from_raw(h)})));}
}
#[no_mangle]
pub extern "C" fn weave_plugin_inspect(p: *const c_char) -> *mut c_char {
    let v=catch_unwind(|| str_arg(p).ok_or("missing path".into())
        .and_then(|p| weave_plugin::inspect_package(Path::new(p)))
        .map(|m|json!({"plugin":info(&m)})).unwrap_or_else(|e|json!({"error":e})))
        .unwrap_or(json!({"error":"cannot inspect plugin"}));
    out(v)
}
#[no_mangle]
pub extern "C" fn weave_plugin_command(h: *mut WeavePluginHost, command: *const c_char) -> *mut c_char {
    let result=catch_unwind(AssertUnwindSafe(|| -> Result<Value,String> {
        if h.is_null() {return Err("plugin host unavailable".into())}
        let cmd:Value=serde_json::from_str(str_arg(command).ok_or("missing command")?).map_err(|e|e.to_string())?;
        let mut m=unsafe {&*h}.0.lock().unwrap_or_else(|p|p.into_inner());
        let id=cmd["id"].as_str().unwrap_or(""); let key=cmd["key"].as_str().unwrap_or("");
        Ok(match cmd["op"].as_str().unwrap_or("") {
            "scan" => {let ps=m.scan(); json!({"plugins":ps.iter().filter_map(|p|m.manifest(&p.id).map(|v|info(&v))).collect::<Vec<_>>()})},
            "install" => {let p=m.install(Path::new(cmd["path"].as_str().ok_or("missing package")?))?; json!({"plugin":info(m.manifest(&p.id).ok_or("missing manifest")?.as_ref())})},
            "uninstall" => {m.uninstall(id)?; json!({"ok":true})},
            "getConfig" => json!({"value":m.get_config(id,key)}),
            "setConfig" => {if let Some(v)=cmd["value"].as_str() {m.set_config(id,key,v)} else {m.remove_config(id,key)}; json!({"ok":true})},
            "configured" => json!({"ok":m.is_configured(id)}),
            _ => return Err("unknown plugin command".into()),
        })
    })).unwrap_or(Err("plugin command failed".into()));
    out(result.unwrap_or_else(|e|json!({"error":e})))
}

#[no_mangle]
pub extern "C" fn weave_plugin_package(source: *const c_char, output: *const c_char) -> *mut c_char {
    let result=catch_unwind(AssertUnwindSafe(|| -> Result<(),String> {
        let root=Path::new(str_arg(source).ok_or("missing source")?);
        let output=Path::new(str_arg(output).ok_or("missing output")?);
        if output.starts_with(root) {return Err("output must be outside source".into())}
        let mut files=Vec::new(); let mut total=0u64;
        fn walk(root:&Path, dir:&Path, files:&mut Vec<std::path::PathBuf>,total:&mut u64)->Result<(),String> {
            for e in std::fs::read_dir(dir).map_err(|e|e.to_string())? {
                let p=e.map_err(|e|e.to_string())?.path();
                let meta=std::fs::symlink_metadata(&p).map_err(|e|e.to_string())?;
                if meta.is_symlink() {return Err("symlinks are not supported".into())}
                if meta.is_dir() {walk(root,&p,files,total)?} else if meta.is_file() {
                    *total+=meta.len();
                    if files.len()>=4096 || meta.len()>64*1024*1024 || *total>256*1024*1024 {return Err("plugin source is too large".into())}
                    files.push(p.strip_prefix(root).map_err(|e|e.to_string())?.to_path_buf());
                }
            }
            Ok(())
        }
        walk(root,root,&mut files,&mut total)?; files.sort();
        let f=std::fs::File::create(output).map_err(|e|e.to_string())?;
        let mut z=zip::ZipWriter::new(f);
        for p in files {
            z.start_file(p.to_string_lossy(),zip::write::SimpleFileOptions::default()).map_err(|e|e.to_string())?;
            std::io::copy(&mut std::fs::File::open(root.join(p)).map_err(|e|e.to_string())?, &mut z).map_err(|e|e.to_string())?;
        }
        z.finish().map_err(|e|e.to_string())?;
        weave_plugin::inspect_package(output)?;
        Ok(())
    })).unwrap_or(Err("packaging failed".into()));
    match result {Ok(())=>std::ptr::null_mut(),Err(e)=>std::ffi::CString::new(e).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())}
}

struct Listener {context:usize,event:extern "C" fn(*mut c_void,*const c_char),free:extern "C" fn(*mut c_void)}
impl Drop for Listener {fn drop(&mut self) {(self.free)(self.context as *mut c_void)}}
impl Listener {fn emit(&self,v:Value) {if let Ok(s)=std::ffi::CString::new(v.to_string()) {(self.event)(self.context as *mut c_void,s.as_ptr())}}}
impl SpeechListener for Listener {
    fn on_partial(&self,s:&str) {self.emit(json!({"event":"partial","text":s}))}
    fn on_final(&self,s:&str) {self.emit(json!({"event":"final","text":s}))}
    fn on_replace(&self,old:&str,new:&str) {self.emit(json!({"event":"replace","old":old,"text":new}))}
    fn on_error(&self,s:&str) {self.emit(json!({"event":"error","text":s}))}
    fn on_end(&self) {self.emit(json!({"event":"end"}))}
    fn on_log(&self,_:u8,_:&str) {}
}
#[no_mangle]
pub extern "C" fn weave_plugin_speech(h:*mut WeavePluginHost,id:*const c_char,context:*mut c_void,
    event:extern "C" fn(*mut c_void,*const c_char),free:extern "C" fn(*mut c_void))->*mut WeaveSpeech {
    let listener=Arc::new(Listener {context:context as usize,event,free});
    let result=catch_unwind(AssertUnwindSafe(|| ->Result<SpeechSession,String> {
        if h.is_null() {return Err("plugin host unavailable".into())}
        let mut m=unsafe {&*h}.0.lock().unwrap_or_else(|p|p.into_inner());
        m.start_speech(str_arg(id).ok_or("missing plugin id")?,listener.clone())
    })).unwrap_or(Err("speech failed".into()));
    match result {
        Ok(s)=>Box::into_raw(Box::new(WeaveSpeech(s))),
        Err(e)=>{listener.on_error(&e);listener.on_end();std::ptr::null_mut()}
    }
}
#[no_mangle]
pub extern "C" fn weave_speech_feed(s:*mut WeaveSpeech,pcm:*const u8,len:usize) {
    if !s.is_null() && !pcm.is_null() && len<=1024*1024 {let _=catch_unwind(AssertUnwindSafe(|| unsafe {&*s}.0.feed(unsafe {std::slice::from_raw_parts(pcm,len)})));}
}
#[no_mangle]
pub extern "C" fn weave_speech_stop(s:*mut WeaveSpeech) {
    if !s.is_null() {let _=catch_unwind(AssertUnwindSafe(|| unsafe {&*s}.0.stop()));}
}
#[no_mangle]
pub extern "C" fn weave_speech_destroy(s:*mut WeaveSpeech) {
    if !s.is_null() {let _=catch_unwind(AssertUnwindSafe(|| drop(unsafe {Box::from_raw(s)})));}
}
