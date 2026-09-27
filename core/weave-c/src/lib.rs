//! 织文内核的 C 接口，供桌面前端（macOS）调用；声明见 `include/weave.h`。
//! C ABI of the WeaveText engine for desktop front ends (macOS); declarations in `include/weave.h`.
//!
//! 约定 / Conventions:
//! - 句柄由 [weave_create] 返回，[weave_destroy] 释放；同一句柄可跨线程调用（内部加锁）。
//!   Handles come from [weave_create] and are freed by [weave_destroy]; calls are serialised by a lock.
//! - 返回 `char*` 的函数交出所有权，调用方用 [weave_string_free] 释放。
//!   Functions returning `char*` hand over ownership; free with [weave_string_free].
//! - 所有入口都拦截 panic，出错时返回默认值。 Every entry point catches panics and returns a default.

// C 入口按约定接收裸指针（有效性由调用方保证，见上）。 C entry points take raw pointers by contract (see above).
#![allow(clippy::not_unsafe_ptr_arg_deref)]

use std::ffi::{c_char, CStr, CString};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::PathBuf;
use std::sync::Mutex;

use serde_json::{json, Value};
use weave_engine::session::{paths_in, CandidateView, Engine, Schema, Snapshot};

/// 不透明句柄。 Opaque handle.
pub struct WeaveEngine(Mutex<Engine>);

fn with<R>(h: *mut WeaveEngine, default: R, f: impl FnOnce(&mut Engine) -> R) -> R {
    if h.is_null() {
        return default;
    }
    // SAFETY: h 来自 weave_create，且在 weave_destroy 之前有效（调用方保证）。
    // SAFETY: h comes from weave_create and stays valid until weave_destroy (caller's contract).
    let handle = unsafe { &*h };
    catch_unwind(AssertUnwindSafe(|| match handle.0.lock() {
        Ok(mut e) => f(&mut e),
        Err(poisoned) => f(&mut poisoned.into_inner()),
    }))
    .unwrap_or(default)
}

fn str_arg<'a>(p: *const c_char) -> Option<&'a str> {
    if p.is_null() {
        return None;
    }
    // SAFETY: 调用方传入以 NUL 结尾的 UTF-8 字符串。 Caller passes a NUL-terminated UTF-8 string.
    unsafe { CStr::from_ptr(p) }.to_str().ok()
}

fn out(v: Value) -> *mut c_char {
    CString::new(v.to_string()).map(CString::into_raw).unwrap_or(std::ptr::null_mut())
}

fn cand_json(c: &CandidateView) -> Value {
    json!({ "text": c.text, "comment": c.comment, "user": c.user })
}

/// 快照的 JSON 形式（字段与 Android 端一致）。 Snapshot as JSON, same fields as on Android.
pub fn snapshot_json(s: &Snapshot) -> Value {
    json!({
        "commit": s.commit,
        "preedit": s.preedit,
        "composing": s.composing,
        "total": s.total_candidates,
        "candidates": s.candidates.iter().map(cand_json).collect::<Vec<_>>(),
        "pinyinOptions": s.pinyin_options,
        "schema": s.schema,
        "marks": s.marks.iter().map(|m| json!({ "start": m.start, "end": m.end, "kind": m.kind.key(), "removed": m.removed })).collect::<Vec<_>>(),
    })
}

/// 用数据目录与用户目录创建引擎；失败返回 NULL。 Create from a data dir and a user dir; NULL on failure.
#[no_mangle]
pub extern "C" fn weave_create(data_dir: *const c_char, user_dir: *const c_char) -> *mut WeaveEngine {
    let (Some(d), Some(u)) = (str_arg(data_dir), str_arg(user_dir)) else {
        return std::ptr::null_mut();
    };
    catch_unwind(|| {
        let mut e = Engine::new(&paths_in(&PathBuf::from(d), &PathBuf::from(u)));
        e.warm_up();
        Box::into_raw(Box::new(WeaveEngine(Mutex::new(e))))
    })
    .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn weave_destroy(h: *mut WeaveEngine) {
    if h.is_null() {
        return;
    }
    // SAFETY: 由 weave_create 分配，只释放一次。 Allocated by weave_create, freed once.
    let b = unsafe { Box::from_raw(h) };
    let _ = catch_unwind(AssertUnwindSafe(move || {
        if let Ok(mut e) = b.0.lock() {
            e.flush();
        }
        drop(b);
    }));
}

#[no_mangle]
pub extern "C" fn weave_string_free(s: *mut c_char) {
    if !s.is_null() {
        // SAFETY: 由本库的 CString::into_raw 产生。 Produced by CString::into_raw in this library.
        drop(unsafe { CString::from_raw(s) });
    }
}

/// 方案键："pinyin"、"shuangpin:xiaohe"、"wubi86"、"english"、"t9"… Schema keys as on Android.
#[no_mangle]
pub extern "C" fn weave_set_schema(h: *mut WeaveEngine, key: *const c_char) -> bool {
    let Some(schema) = str_arg(key).and_then(Schema::from_key) else {
        return false;
    };
    with(h, false, |e| {
        if !e.has_lexicon(schema) {
            return false;
        }
        e.set_schema(schema);
        true
    })
}

/// 该方案的数据是否可用（不切换）。 Whether the data for a schema is available (without switching).
#[no_mangle]
pub extern "C" fn weave_has_schema(h: *mut WeaveEngine, key: *const c_char) -> bool {
    let Some(schema) = str_arg(key).and_then(Schema::from_key) else {
        return false;
    };
    with(h, false, |e| e.has_lexicon(schema))
}

#[no_mangle]
pub extern "C" fn weave_set_option(h: *mut WeaveEngine, key: *const c_char, on: bool) -> bool {
    let Some(k) = str_arg(key) else { return false };
    with(h, false, |e| e.options.set_flag(k, on))
}

#[no_mangle]
pub extern "C" fn weave_input_char(h: *mut WeaveEngine, code_point: u32) -> bool {
    let Some(c) = char::from_u32(code_point) else { return false };
    with(h, false, |e| e.input_char(c))
}

/// 载入（或替换）一个专业词库文件（.wvz / .wvl）。 Load (or replace) a domain dictionary file.
#[no_mangle]
pub extern "C" fn weave_load_pack(h: *mut WeaveEngine, id: *const c_char, path: *const c_char) -> bool {
    let (Some(id), Some(p)) = (str_arg(id), str_arg(path)) else { return false };
    with(h, false, |e| e.load_pack(id, &weave_dict::blob::Source::file(p)))
}

#[no_mangle]
pub extern "C" fn weave_unload_pack(h: *mut WeaveEngine, id: *const c_char) -> bool {
    let Some(id) = str_arg(id) else { return false };
    with(h, false, |e| e.unload_pack(id))
}

/// 载入云端热词（文件路径）；返回词数，签名不对或读不到返回 -1。 Load hot words from files; word count or -1.
#[no_mangle]
pub extern "C" fn weave_load_hotwords(h: *mut WeaveEngine, tsv_path: *const c_char, sig_path: *const c_char) -> i32 {
    let (Some(t), Some(s)) = (str_arg(tsv_path), str_arg(sig_path)) else { return -1 };
    let (Ok(t), Ok(s)) = (std::fs::read(t), std::fs::read_to_string(s)) else { return -1 };
    with(h, -1, |e| e.load_hotwords(&t, &s).map(|n| n as i32).unwrap_or(-1))
}

/// 宿主自己往编辑器写了字（标点、空格、符号）：之后的退格不撤销学习，也不与前面连成新词。
/// The host wrote text itself (punctuation, a space): later backspaces don't undo learning; no chaining.
#[no_mangle]
pub extern "C" fn weave_break_chain(h: *mut WeaveEngine) {
    with(h, (), |e| e.break_chain())
}

/// 已载入的扩展词库 id（JSON 数组，含热词 "cloud"）。 Ids of the loaded extra lexicons as JSON, "cloud" included.
#[no_mangle]
pub extern "C" fn weave_pack_ids_json(h: *mut WeaveEngine) -> *mut c_char {
    with(h, std::ptr::null_mut(), |e| out(Value::Array(e.pack_ids().iter().map(|i| Value::String(i.clone())).collect())))
}

/// 本地时区相对 UTC 的分钟数（日期时间候选）。 Local UTC offset in minutes, for date/time candidates.
#[no_mangle]
pub extern "C" fn weave_set_utc_offset(h: *mut WeaveEngine, minutes: i32) {
    with(h, (), |e| e.options.utc_offset_min = minutes.clamp(-14 * 60, 14 * 60))
}

/// 计算算式；不是有效算式时返回 NULL。 Evaluate an expression; NULL when it isn't one.
#[no_mangle]
pub extern "C" fn weave_eval(expr: *const c_char) -> *mut c_char {
    let Some(x) = str_arg(expr) else { return std::ptr::null_mut() };
    catch_unwind(|| {
        weave_engine::special::is_expression(x)
            .then(|| weave_engine::special::eval(x))
            .flatten()
            .map(weave_engine::special::format_number)
    })
    .ok()
    .flatten()
    .and_then(|v| CString::new(v).ok())
    .map(CString::into_raw)
    .unwrap_or(std::ptr::null_mut())
}

/// 带邻键信息的按键（触屏用；实体键盘传 near = 0）。 A key with its tap neighbour (touch; pass 0 for hardware keys).
#[no_mangle]
pub extern "C" fn weave_input_key(h: *mut WeaveEngine, code_point: u32, near: u32, closeness: f32) -> bool {
    let Some(c) = char::from_u32(code_point) else { return false };
    let alt = char::from_u32(near).filter(|_| near != 0);
    with(h, false, |e| e.input_key(c, alt, closeness))
}

#[no_mangle]
pub extern "C" fn weave_backspace(h: *mut WeaveEngine) -> bool {
    with(h, false, |e| e.backspace())
}

#[no_mangle]
pub extern "C" fn weave_select(h: *mut WeaveEngine, index: u32) -> bool {
    with(h, false, |e| e.select(index as usize))
}

#[no_mangle]
pub extern "C" fn weave_select_pinyin(h: *mut WeaveEngine, index: u32) -> bool {
    with(h, false, |e| e.select_pinyin_option(index as usize))
}

#[no_mangle]
pub extern "C" fn weave_forget(h: *mut WeaveEngine, index: u32) -> bool {
    with(h, false, |e| e.forget_candidate(index as usize))
}

#[no_mangle]
pub extern "C" fn weave_commit_first(h: *mut WeaveEngine) {
    with(h, (), |e| e.commit_first())
}

#[no_mangle]
pub extern "C" fn weave_commit_raw(h: *mut WeaveEngine) {
    with(h, (), |e| e.commit_raw())
}

#[no_mangle]
pub extern "C" fn weave_clear(h: *mut WeaveEngine) {
    with(h, (), |e| e.clear())
}

#[no_mangle]
pub extern "C" fn weave_flush(h: *mut WeaveEngine) {
    with(h, (), |e| e.flush())
}

/// 收起联想词（空格、回车、光标移动等不选联想的操作）。 Dismiss predictions (space, enter, cursor moves…).
#[no_mangle]
pub extern "C" fn weave_dismiss_predictions(h: *mut WeaveEngine) {
    with(h, (), |e| e.drop_predictions())
}

#[no_mangle]
pub extern "C" fn weave_is_composing(h: *mut WeaveEngine) -> bool {
    with(h, false, |e| e.is_composing())
}

#[no_mangle]
pub extern "C" fn weave_set_learning(h: *mut WeaveEngine, on: bool) {
    with(h, (), |e| e.set_learning(on))
}

/// 光标前的上文（最后一个词），NULL 表示清空。 The word before the cursor; NULL clears it.
#[no_mangle]
pub extern "C" fn weave_set_context(h: *mut WeaveEngine, prev_word: *const c_char) {
    let w = str_arg(prev_word).map(str::to_string);
    with(h, (), |e| e.set_context(w))
}

/// 当前状态（JSON），读取后 commit 清空。 Current state as JSON; `commit` is drained.
#[no_mangle]
pub extern "C" fn weave_snapshot_json(h: *mut WeaveEngine) -> *mut c_char {
    with(h, std::ptr::null_mut(), |e| {
        let predicting = e.is_predicting();
        let mut v = snapshot_json(&e.snapshot());
        v["predicting"] = Value::Bool(predicting);
        out(v)
    })
}

/// 候选分页（JSON 数组）。 A page of candidates as a JSON array.
#[no_mangle]
pub extern "C" fn weave_candidates_json(h: *mut WeaveEngine, offset: u32, limit: u32) -> *mut c_char {
    with(h, std::ptr::null_mut(), |e| {
        out(Value::Array(e.candidates(offset as usize, limit as usize).iter().map(cand_json).collect()))
    })
}

/// 用户词数。 Number of learned user words.
#[no_mangle]
pub extern "C" fn weave_user_word_count(h: *mut WeaveEngine) -> u32 {
    with(h, 0, |e| e.user_word_count() as u32)
}

/// 用户词分页（JSON 数组，按 query 过滤）。 A page of user words as JSON, filtered by `query`.
#[no_mangle]
pub extern "C" fn weave_user_words_json(h: *mut WeaveEngine, query: *const c_char, offset: u32, limit: u32) -> *mut c_char {
    let q = str_arg(query).unwrap_or("").to_string();
    with(h, std::ptr::null_mut(), |e| {
        let v: Vec<Value> = e
            .user_words(&q, offset as usize, limit as usize)
            .into_iter()
            .map(|w| json!({ "pinyin": w.pinyin, "text": w.text, "count": w.count }))
            .collect();
        out(Value::Array(v))
    })
}

#[no_mangle]
pub extern "C" fn weave_delete_user_word(h: *mut WeaveEngine, pinyin: *const c_char, text: *const c_char) -> bool {
    let (Some(p), Some(t)) = (str_arg(pinyin), str_arg(text)) else { return false };
    with(h, false, |e| e.delete_user_word(p, t))
}

#[no_mangle]
pub extern "C" fn weave_clear_user_words(h: *mut WeaveEngine) -> bool {
    with(h, false, |e| e.clear_user_words())
}

// ------------------------------------------------------------------ 织文互联 / WeaveLink

/// 互联句柄。 WeaveLink handle.
pub struct WeaveLink(weave_link::Link);

/// 启动互联：配置 JSON `{name, platform, stateDir, inboxDir, port?, mdns?}`；失败返回 NULL。
/// Start WeaveLink from a JSON config; NULL on failure.
#[no_mangle]
pub extern "C" fn weave_link_start(config_json: *const c_char) -> *mut WeaveLink {
    let Some(cfg) = str_arg(config_json)
        .and_then(|s| serde_json::from_str::<Value>(s).ok())
        .and_then(|v| weave_link::Config::from_json(&v))
    else {
        return std::ptr::null_mut();
    };
    catch_unwind(|| match weave_link::Link::start(cfg) {
        Ok(l) => Box::into_raw(Box::new(WeaveLink(l))),
        Err(_) => std::ptr::null_mut(),
    })
    .unwrap_or(std::ptr::null_mut())
}

/// 等待下一个事件（JSON），最多 timeout_ms；没有事件时返回 `{"type":"idle"}`，停止后返回 NULL。
/// Wait up to timeout_ms for the next event (JSON); `{"type":"idle"}` on timeout, NULL once stopped.
#[no_mangle]
pub extern "C" fn weave_link_poll(h: *mut WeaveLink, timeout_ms: u32) -> *mut c_char {
    if h.is_null() {
        return std::ptr::null_mut();
    }
    // SAFETY: h 来自 weave_link_start，在 weave_link_destroy 之前有效。 Valid until weave_link_destroy.
    let l = unsafe { &*h };
    catch_unwind(AssertUnwindSafe(|| l.0.poll(std::time::Duration::from_millis(timeout_ms as u64))))
        .ok()
        .flatten()
        .map(out)
        .unwrap_or(std::ptr::null_mut())
}

/// 执行命令（JSON，见 weave-link 文档），返回结果 JSON。 Run a JSON command; returns the JSON result.
#[no_mangle]
pub extern "C" fn weave_link_call(h: *mut WeaveLink, command_json: *const c_char) -> *mut c_char {
    if h.is_null() {
        return std::ptr::null_mut();
    }
    // SAFETY: 同上。 As above.
    let l = unsafe { &*h };
    let cmd = str_arg(command_json).and_then(|s| serde_json::from_str::<Value>(s).ok()).unwrap_or(Value::Null);
    catch_unwind(AssertUnwindSafe(|| out(l.0.call(&cmd)))).unwrap_or(std::ptr::null_mut())
}

/// 停止并释放；此后 poll 返回 NULL。先让轮询线程退出再调用。 Stop and free; stop the polling thread first.
#[no_mangle]
pub extern "C" fn weave_link_destroy(h: *mut WeaveLink) {
    if h.is_null() {
        return;
    }
    // SAFETY: 由 weave_link_start 分配，只释放一次。 Allocated by weave_link_start, freed once.
    let b = unsafe { Box::from_raw(h) };
    let _ = catch_unwind(AssertUnwindSafe(move || {
        b.0.stop();
        drop(b);
    }));
}

/// 只停止（不释放）：让另一线程里阻塞的 poll 尽快返回 NULL。 Stop without freeing, so a blocked poll returns NULL.
#[no_mangle]
pub extern "C" fn weave_link_stop(h: *mut WeaveLink) {
    if h.is_null() {
        return;
    }
    // SAFETY: 同上。 As above.
    let l = unsafe { &*h };
    let _ = catch_unwind(AssertUnwindSafe(|| l.0.stop()));
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cstr(s: &str) -> CString {
        CString::new(s).unwrap()
    }

    fn take(p: *mut c_char) -> Value {
        assert!(!p.is_null());
        // SAFETY: 本库返回的字符串。 A string returned by this library.
        let v = serde_json::from_str(unsafe { CStr::from_ptr(p) }.to_str().unwrap()).unwrap();
        weave_string_free(p);
        v
    }

    #[test]
    fn link_starts_answers_and_stops() {
        let dir = std::env::temp_dir().join(format!("weave-c-link-{}", std::process::id()));
        let cfg = json!({"name": "测试", "platform": "mac", "stateDir": dir.join("s"), "inboxDir": dir.join("i"), "mdns": false});
        let h = weave_link_start(cstr(&cfg.to_string()).as_ptr());
        assert!(!h.is_null());
        let info = take(weave_link_call(h, cstr(r#"{"op":"info"}"#).as_ptr()));
        assert_eq!(info["name"], "测试");
        let p = take(weave_link_call(h, cstr(r#"{"op":"openPairing"}"#).as_ptr()));
        assert_eq!(p["code"].as_str().unwrap().len(), 6);
        assert_eq!(take(weave_link_poll(h, 10))["type"], "idle");
        weave_link_stop(h);
        assert!(weave_link_poll(h, 10).is_null());
        weave_link_destroy(h);
        assert!(weave_link_start(cstr("{}").as_ptr()).is_null());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn types_a_sentence_through_the_c_abi() {
        let data = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../data/build");
        if !data.join("pinyin.wvz").exists() && !data.join("pinyin.wvl").exists() {
            eprintln!("skip: no data/build");
            return;
        }
        let user = std::env::temp_dir().join(format!("weave-c-{}", std::process::id()));
        std::fs::create_dir_all(&user).unwrap();
        let h = weave_create(cstr(data.to_str().unwrap()).as_ptr(), cstr(user.to_str().unwrap()).as_ptr());
        assert!(!h.is_null());
        assert!(weave_set_schema(h, cstr("pinyin").as_ptr()));
        for c in "nihao".chars() {
            assert!(weave_input_char(h, c as u32));
        }
        let s = take(weave_snapshot_json(h));
        assert_eq!(s["composing"], true);
        assert_eq!(s["candidates"][0]["text"], "你好");
        assert!(weave_select(h, 0));
        assert_eq!(take(weave_snapshot_json(h))["commit"], "你好");
        assert!(!weave_set_option(h, cstr("no.such").as_ptr(), true));
        assert!(weave_set_option(h, cstr("output.traditional").as_ptr(), true));
        weave_destroy(h);
        // 空句柄不崩。 Null handles are harmless.
        assert!(!weave_backspace(std::ptr::null_mut()));
        let _ = std::fs::remove_dir_all(&user);
    }
}
