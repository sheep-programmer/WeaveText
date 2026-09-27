//! 织文互联的 JNI 绑定（`com.weavetext.ime.link.NativeLink`）：JSON 命令进、JSON 事件出。
//! JNI bindings of WeaveLink (`com.weavetext.ime.link.NativeLink`): JSON commands in, JSON events out.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::time::Duration;

use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;
use serde_json::Value;
use weave_link::{Config, Link};

fn get(env: &mut JNIEnv, s: &JString) -> Option<String> {
    if s.is_null() {
        return None;
    }
    env.get_string(s).ok().map(Into::into)
}

fn jstr(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn link<'a>(h: jlong) -> Option<&'a Link> {
    // SAFETY: h 由 nativeStart 返回，nativeDestroy 之前有效（Kotlin 端保证）。 Valid until nativeDestroy.
    (h != 0).then(|| unsafe { &*(h as *const Link) })
}

#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_link_NativeLink_nativeStart(mut env: JNIEnv, _c: JClass, config: JString) -> jlong {
    let Some(cfg) = get(&mut env, &config)
        .and_then(|s| serde_json::from_str::<Value>(&s).ok())
        .and_then(|v| Config::from_json(&v))
    else {
        return 0;
    };
    catch_unwind(|| Link::start(cfg).map(|l| Box::into_raw(Box::new(l)) as jlong).unwrap_or(0)).unwrap_or(0)
}

/// 阻塞至多 timeout_ms 取一个事件；停止后返回 null。 Blocks up to timeout_ms; null once stopped.
#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_link_NativeLink_nativePoll(mut env: JNIEnv, _c: JClass, h: jlong, timeout_ms: jint) -> jstring {
    let Some(l) = link(h) else { return std::ptr::null_mut() };
    let ev = catch_unwind(AssertUnwindSafe(|| l.poll(Duration::from_millis(timeout_ms.max(0) as u64)))).ok().flatten();
    match ev {
        Some(v) => jstr(&mut env, &v.to_string()),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_link_NativeLink_nativeCall(mut env: JNIEnv, _c: JClass, h: jlong, command: JString) -> jstring {
    let Some(l) = link(h) else { return std::ptr::null_mut() };
    let cmd = get(&mut env, &command).and_then(|s| serde_json::from_str::<Value>(&s).ok()).unwrap_or(Value::Null);
    let r = catch_unwind(AssertUnwindSafe(|| l.call(&cmd))).unwrap_or(Value::Null);
    jstr(&mut env, &r.to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_link_NativeLink_nativeStop(_env: JNIEnv, _c: JClass, h: jlong) {
    if let Some(l) = link(h) {
        let _ = catch_unwind(AssertUnwindSafe(|| l.stop()));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_link_NativeLink_nativeDestroy(_env: JNIEnv, _c: JClass, h: jlong) {
    if h != 0 {
        // SAFETY: 由 nativeStart 分配，只释放一次。 Allocated by nativeStart, freed once.
        let b = unsafe { Box::from_raw(h as *mut Link) };
        let _ = catch_unwind(AssertUnwindSafe(move || drop(b)));
    }
}
