//! 插件宿主的 JNI 绑定：给 `com.weavetext.ime.voice.NativePluginHost` 用。
//! JNI bindings of the plugin host for `com.weavetext.ime.voice.NativePluginHost`.
//!
//! - 宿主句柄：`Box<Mutex<PluginManager>>`；会话句柄：`Box<SpeechSession>`。
//! - 插件信息以 JSON 返回（调用频率低）；图标单独取字节。
//! - 语音回调在插件线程上发生：线程以守护线程方式挂到 JVM，调用 Kotlin 回调对象。
//! Host handle is `Box<Mutex<PluginManager>>`, session handle is `Box<SpeechSession>`. Plugin
//! info is JSON; icons are fetched separately. Speech callbacks run on plugin threads, which are
//! attached to the JVM as daemons.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use jni::objects::{GlobalRef, JByteArray, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::{JNIEnv, JavaVM};
use serde_json::json;

use weave_plugin::{Manifest, PluginInfo, PluginManager, SpeechListener, SpeechSession};

type Host = Mutex<PluginManager>;

fn with_host<R>(h: jlong, default: R, f: impl FnOnce(&mut PluginManager) -> R) -> R {
    if h == 0 {
        return default;
    }
    // SAFETY: h 来自 nativeCreate，在进程生命周期内有效（宿主不销毁）。
    let host = unsafe { &*(h as *const Host) };
    catch_unwind(AssertUnwindSafe(|| {
        let mut g = host.lock().unwrap_or_else(|p| p.into_inner());
        f(&mut g)
    }))
    .unwrap_or(default)
}

fn jstr(env: &mut JNIEnv, s: &JString) -> Option<String> {
    if s.is_null() {
        return None;
    }
    env.get_string(s).ok().map(Into::into)
}

fn out_str(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s)
        .map(|j| j.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn network_json(m: Option<&Manifest>) -> (serde_json::Value, bool) {
    match m {
        Some(m) => (
            json!(m.network_hosts),
            m.allow_custom_hosts || m.permissions.iter().any(|p| p == "network_unrestricted"),
        ),
        None => (json!([]), false),
    }
}

fn info_json(p: &PluginInfo, m: Option<&Manifest>) -> serde_json::Value {
    let schema: serde_json::Value =
        serde_json::from_str(&p.config_schema_json).unwrap_or(json!([]));
    let (hosts, unrestricted) = network_json(m);
    json!({
        "id": p.id,
        "name": p.name,
        "description": p.description,
        "version": p.version,
        "kind": p.kind,
        "iconText": p.icon_text,
        "hasIcon": p.icon_png.is_some(),
        "configSchema": schema,
        "networkHosts": hosts,
        "unrestrictedNetwork": unrestricted,
    })
}

/// 导入前预览 `.xipk`：返回 `{"plugin":{…}}` 或 `{"error":"…"}`（不安装）。 Preview a package.
#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_voice_NativePluginHost_nativeInspect(
    mut env: JNIEnv,
    _c: JClass,
    path: JString,
) -> jstring {
    let Some(p) = jstr(&mut env, &path) else {
        return std::ptr::null_mut();
    };
    let s = catch_unwind(
        || match weave_plugin::inspect_package(std::path::Path::new(&p)) {
            Ok(m) => {
                let (hosts, unrestricted) = network_json(Some(&m));
                json!({ "plugin": {
                    "id": m.id, "name": m.name, "description": m.description, "version": m.version,
                    "kind": m.kind, "configSchema": m.config_schema, "networkHosts": hosts,
                    "unrestrictedNetwork": unrestricted, "minHostVersion": m.min_host_version,
                }})
                .to_string()
            }
            Err(e) => json!({ "error": e }).to_string(),
        },
    )
    .unwrap_or_else(|_| json!({"error": "inspect failed"}).to_string());
    out_str(&mut env, &s)
}

#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_voice_NativePluginHost_nativeCreate(
    mut env: JNIEnv,
    _c: JClass,
    plugins_dir: JString,
    config_dir: JString,
) -> jlong {
    let (Some(p), Some(c)) = (jstr(&mut env, &plugins_dir), jstr(&mut env, &config_dir)) else {
        return 0;
    };
    catch_unwind(|| {
        let mut m = PluginManager::new(PathBuf::from(p), PathBuf::from(c));
        m.scan();
        Box::into_raw(Box::new(Mutex::new(m))) as jlong
    })
    .unwrap_or(0)
}

/// 重新扫描并返回插件列表 JSON 数组。 Rescan and return the plugin list as a JSON array.
#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_voice_NativePluginHost_nativeScan(
    mut env: JNIEnv,
    _c: JClass,
    h: jlong,
) -> jstring {
    let s = with_host(h, "[]".to_string(), |m| {
        let infos = m.scan();
        let list: Vec<_> = infos
            .iter()
            .map(|p| info_json(p, m.manifest(&p.id).as_deref()))
            .collect();
        serde_json::Value::Array(list).to_string()
    });
    out_str(&mut env, &s)
}

#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_voice_NativePluginHost_nativeIcon(
    mut env: JNIEnv,
    _c: JClass,
    h: jlong,
    id: JString,
) -> jbyteArray {
    let Some(id) = jstr(&mut env, &id) else {
        return std::ptr::null_mut();
    };
    let bytes = with_host(h, None, |m| {
        m.list()
            .into_iter()
            .find(|p| p.id == id)
            .and_then(|p| p.icon_png)
    });
    match bytes {
        Some(b) => env
            .byte_array_from_slice(&b)
            .map(|a| a.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

/// 安装 .xipk：返回 `{"plugin":{…}}` 或 `{"error":"…"}`。 Install a package.
#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_voice_NativePluginHost_nativeInstall(
    mut env: JNIEnv,
    _c: JClass,
    h: jlong,
    path: JString,
) -> jstring {
    let Some(p) = jstr(&mut env, &path) else {
        return std::ptr::null_mut();
    };
    let s = with_host(
        h,
        json!({"error": "host unavailable"}).to_string(),
        |m| match m.install(std::path::Path::new(&p)) {
            Ok(info) => {
                json!({ "plugin": info_json(&info, m.manifest(&info.id).as_deref()) }).to_string()
            }
            Err(e) => json!({ "error": e }).to_string(),
        },
    );
    out_str(&mut env, &s)
}

/// 卸载：成功返回 null，失败返回错误信息。 Uninstall; null on success, else the error.
#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_voice_NativePluginHost_nativeUninstall(
    mut env: JNIEnv,
    _c: JClass,
    h: jlong,
    id: JString,
) -> jstring {
    let Some(id) = jstr(&mut env, &id) else {
        return std::ptr::null_mut();
    };
    match with_host(h, Err("host unavailable".to_string()), |m| m.uninstall(&id)) {
        Ok(()) => std::ptr::null_mut(),
        Err(e) => out_str(&mut env, &e),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_voice_NativePluginHost_nativeGetConfig(
    mut env: JNIEnv,
    _c: JClass,
    h: jlong,
    id: JString,
    key: JString,
) -> jstring {
    let (Some(id), Some(key)) = (jstr(&mut env, &id), jstr(&mut env, &key)) else {
        return std::ptr::null_mut();
    };
    match with_host(h, None, |m| m.get_config(&id, &key)) {
        Some(v) => out_str(&mut env, &v),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_voice_NativePluginHost_nativeSetConfig(
    mut env: JNIEnv,
    _c: JClass,
    h: jlong,
    id: JString,
    key: JString,
    value: JString,
) {
    let (Some(id), Some(key)) = (jstr(&mut env, &id), jstr(&mut env, &key)) else {
        return;
    };
    match jstr(&mut env, &value) {
        Some(v) => with_host(h, (), |m| m.set_config(&id, &key, &v)),
        None => with_host(h, (), |m| m.remove_config(&id, &key)),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_voice_NativePluginHost_nativeIsConfigured(
    mut env: JNIEnv,
    _c: JClass,
    h: jlong,
    id: JString,
) -> jboolean {
    let Some(id) = jstr(&mut env, &id) else {
        return JNI_FALSE;
    };
    if with_host(h, false, |m| m.is_configured(&id)) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

// ---------------------------------------------------------------- speech

/// 把回调转给 Kotlin 的 `NativeSpeechCallback`。 Forwards callbacks to Kotlin.
struct JniListener {
    vm: JavaVM,
    target: GlobalRef,
}

impl JniListener {
    fn call(&self, method: &str, sig: &str, args: &[&str], int_arg: Option<i32>) {
        let _ = catch_unwind(AssertUnwindSafe(|| {
            let Ok(mut env) = self.vm.attach_current_thread_as_daemon() else {
                return;
            };
            let _ = env.with_local_frame(8, |env| -> jni::errors::Result<()> {
                let mut jargs: Vec<JValue> = Vec::new();
                let mut holders: Vec<JObject> = Vec::new();
                if let Some(i) = int_arg {
                    jargs.push(JValue::Int(i));
                }
                for a in args {
                    holders.push(env.new_string(a)?.into());
                }
                for o in &holders {
                    jargs.push(JValue::Object(o));
                }
                env.call_method(&self.target, method, sig, &jargs)?;
                Ok(())
            });
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_describe();
                let _ = env.exception_clear();
            }
        }));
    }
}

impl SpeechListener for JniListener {
    fn on_partial(&self, text: &str) {
        self.call("onPartial", "(Ljava/lang/String;)V", &[text], None);
    }
    fn on_final(&self, text: &str) {
        self.call("onFinal", "(Ljava/lang/String;)V", &[text], None);
    }
    fn on_replace(&self, old: &str, new: &str) {
        self.call(
            "onReplace",
            "(Ljava/lang/String;Ljava/lang/String;)V",
            &[old, new],
            None,
        );
    }
    fn on_error(&self, msg: &str) {
        self.call("onError", "(Ljava/lang/String;)V", &[msg], None);
    }
    fn on_end(&self) {
        self.call("onEnd", "()V", &[], None);
    }
    fn on_log(&self, level: u8, msg: &str) {
        self.call(
            "onLog",
            "(ILjava/lang/String;)V",
            &[msg],
            Some(level as i32),
        );
    }
}

/// 开始识别会话；失败时返回 0 并同步回调 onError + onEnd。
/// Start a session; on failure returns 0 after calling onError + onEnd.
#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_voice_NativePluginHost_nativeStartSpeech(
    mut env: JNIEnv,
    _c: JClass,
    h: jlong,
    id: JString,
    callback: JObject,
) -> jlong {
    let Some(id) = jstr(&mut env, &id) else {
        return 0;
    };
    let (Ok(vm), Ok(target)) = (env.get_java_vm(), env.new_global_ref(&callback)) else {
        return 0;
    };
    let listener = Arc::new(JniListener { vm, target });
    let l2 = listener.clone();
    match with_host(h, Err("host unavailable".to_string()), move |m| {
        m.start_speech(&id, l2)
    }) {
        Ok(session) => Box::into_raw(Box::new(session)) as jlong,
        Err(e) => {
            listener.on_error(&e);
            listener.on_end();
            0
        }
    }
}

fn with_session(s: jlong, f: impl FnOnce(&SpeechSession)) {
    if s != 0 {
        // SAFETY: s 来自 nativeStartSpeech，在 nativeRelease 之前有效。
        let session = unsafe { &*(s as *const SpeechSession) };
        let _ = catch_unwind(AssertUnwindSafe(|| f(session)));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_voice_NativePluginHost_nativeFeed(
    env: JNIEnv,
    _c: JClass,
    s: jlong,
    pcm: JByteArray,
    len: jint,
) {
    let Ok(bytes) = env.convert_byte_array(&pcm) else {
        return;
    };
    let n = (len.max(0) as usize).min(bytes.len());
    with_session(s, |ss| ss.feed(&bytes[..n]));
}

#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_voice_NativePluginHost_nativeStop(
    _env: JNIEnv,
    _c: JClass,
    s: jlong,
) {
    with_session(s, |ss| ss.stop());
}

#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_voice_NativePluginHost_nativeCancel(
    _env: JNIEnv,
    _c: JClass,
    s: jlong,
) {
    with_session(s, |ss| ss.cancel());
}

/// 释放会话句柄。释放会取消会话，所以必须在收到 onEnd 之后再调用。
/// Release a session handle. Dropping cancels the session, so call it only after onEnd.
#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_voice_NativePluginHost_nativeRelease(
    _env: JNIEnv,
    _c: JClass,
    s: jlong,
) {
    if s != 0 {
        // SAFETY: 与 nativeStartSpeech 配对，只调用一次。
        let _ = catch_unwind(|| drop(unsafe { Box::from_raw(s as *mut SpeechSession) }));
    }
}
