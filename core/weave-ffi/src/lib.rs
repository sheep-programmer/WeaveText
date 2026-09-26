//! JNI 绑定：给 Android 端 `com.weavetext.ime.core.NativeEngine` 用。
//! JNI bindings for `com.weavetext.ime.core.NativeEngine`.
//!
//! 引擎句柄是 `Box<Mutex<Engine>>` 的裸指针（jlong）。引擎内的解压缓存是单线程结构（`Engine` 不是
//! `Send`），因此**所有访问都必须经过这把 Mutex**：它保证同一时刻只有一个线程接触引擎及其缓存，
//! 且缓存的全部副本都在该引擎内部。任何 panic 都被捕获，绝不让 native 崩溃带崩输入法进程。
//! The handle is a raw `Box<Mutex<Engine>>` pointer. The engine's decode caches are single-threaded
//! (`Engine` is not `Send`), so **every access must go through this mutex**: it guarantees one thread
//! at a time touches the engine and its caches, all of which live inside that engine. Panics are
//! caught so native code never kills the IME.
//!
//! 快照编码（大端，Java ByteBuffer 默认序）/ Snapshot encoding (big endian):
//! ```text
//! u8 version(=1) u8 flags(bit0=composing) str commit str preedit
//! i32 total i32 n { str text str comment u8 user }*n i32 m { str pinyin }*m str schema
//! str = i32 byte_len + utf-8
//! ```

pub mod archive;
pub mod plugin;

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::PathBuf;
use std::sync::Mutex;

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use weave_engine::session::{paths_in, CandidateView, Engine, Paths, Schema, Snapshot};

type Handle = Mutex<Engine>;

fn with_engine<R>(h: jlong, default: R, f: impl FnOnce(&mut Engine) -> R) -> R {
    if h == 0 {
        return default;
    }
    // SAFETY: h 由 nativeCreate 返回且在 nativeDestroy 之前有效（Kotlin 端保证）。
    let handle = unsafe { &*(h as *const Handle) };
    let result = catch_unwind(AssertUnwindSafe(|| match handle.lock() {
        Ok(mut e) => Some(f(&mut e)),
        Err(poisoned) => Some(f(&mut poisoned.into_inner())),
    }));
    result.ok().flatten().unwrap_or(default)
}

fn jbool(b: bool) -> jboolean {
    if b {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

fn get_string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    if s.is_null() {
        return None;
    }
    env.get_string(s).ok().map(|s| s.into())
}

struct Enc(Vec<u8>);

impl Enc {
    fn u8(&mut self, v: u8) {
        self.0.push(v);
    }
    fn i32(&mut self, v: i32) {
        self.0.extend_from_slice(&v.to_be_bytes());
    }
    fn str(&mut self, s: &str) {
        self.i32(s.len() as i32);
        self.0.extend_from_slice(s.as_bytes());
    }
    fn cands(&mut self, c: &[CandidateView]) {
        self.i32(c.len() as i32);
        for c in c {
            self.str(&c.text);
            self.str(&c.comment);
            self.u8(c.user as u8);
        }
    }
}

pub fn encode_snapshot(s: &Snapshot) -> Vec<u8> {
    let mut e = Enc(Vec::with_capacity(1024));
    e.u8(1);
    e.u8(s.composing as u8);
    e.str(&s.commit);
    e.str(&s.preedit);
    e.i32(s.total_candidates as i32);
    e.cands(&s.candidates);
    e.i32(s.pinyin_options.len() as i32);
    for p in &s.pinyin_options {
        e.str(p);
    }
    e.str(&s.schema);
    e.0
}

fn to_jbytes(env: &mut JNIEnv, bytes: &[u8]) -> jbyteArray {
    match env.byte_array_from_slice(bytes) {
        Ok(a) => a.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_core_NativeEngine_nativeCreate(
    mut env: JNIEnv,
    _c: JClass,
    data_dir: JString,
    user_dir: JString,
) -> jlong {
    let (Some(data), Some(user)) = (
        get_string(&mut env, &data_dir),
        get_string(&mut env, &user_dir),
    ) else {
        return 0;
    };
    catch_unwind(|| {
        let engine = Engine::new(&paths_in(&PathBuf::from(data), &PathBuf::from(user)));
        Box::into_raw(Box::new(Mutex::new(engine))) as jlong
    })
    .unwrap_or(0)
}

/// 从 APK 内的资源区间创建：`spec` 为 `key=path@offset+len;…`，`cache_kb` 为每个分块压缩文件的缓存预算。
/// Create from asset ranges inside the APK: `spec` is `key=path@offset+len;…`; `cache_kb` is the
/// cache budget per block-compressed file.
#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_core_NativeEngine_nativeCreateFromSpec(
    mut env: JNIEnv,
    _c: JClass,
    spec: JString,
    user_dir: JString,
    cache_kb: jint,
) -> jlong {
    let (Some(spec), Some(user)) = (get_string(&mut env, &spec), get_string(&mut env, &user_dir))
    else {
        return 0;
    };
    catch_unwind(|| {
        if cache_kb > 0 {
            weave_dict::blob::set_cache_budget(cache_kb as usize * 1024);
        }
        let engine = Engine::new(&Paths::from_spec(&spec, &PathBuf::from(user)));
        Box::into_raw(Box::new(Mutex::new(engine))) as jlong
    })
    .unwrap_or(0)
}

/// 清空解压缓存（系统内存紧张时）。 Drop decode caches under memory pressure.
#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_core_NativeEngine_nativeTrim(
    _env: JNIEnv,
    _c: JClass,
    h: jlong,
) {
    with_engine(h, (), |e| e.trim_caches());
}

#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_core_NativeEngine_nativeDestroy(
    _env: JNIEnv,
    _c: JClass,
    h: jlong,
) {
    if h != 0 {
        // SAFETY: 与 nativeCreate 配对，只调用一次。 Paired with nativeCreate, called once.
        let _ = catch_unwind(|| drop(unsafe { Box::from_raw(h as *mut Handle) }));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_core_NativeEngine_nativeSetSchema(
    mut env: JNIEnv,
    _c: JClass,
    h: jlong,
    key: JString,
) -> jboolean {
    let Some(k) = get_string(&mut env, &key) else {
        return JNI_FALSE;
    };
    let Some(schema) = Schema::from_key(&k) else {
        return JNI_FALSE;
    };
    jbool(with_engine(h, false, |e| {
        if !e.has_lexicon(schema) {
            return false;
        }
        e.set_schema(schema);
        true
    }))
}

/// 选项：`fuzzy.z_zh` … `fuzzy.uan_uang`、`wubi.auto_commit`、`wubi.pinyin_lookup`、
/// `wubi.completion`、`output.traditional`、`candidates.emoji`，值为 "true"/"false"。
#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_core_NativeEngine_nativeSetOption(
    mut env: JNIEnv,
    _c: JClass,
    h: jlong,
    key: JString,
    value: JString,
) -> jboolean {
    let (Some(k), Some(v)) = (get_string(&mut env, &key), get_string(&mut env, &value)) else {
        return JNI_FALSE;
    };
    let on = v == "true" || v == "1";
    jbool(with_engine(h, false, |e| {
        let o = &mut e.options;
        let f = &mut o.fuzzy;
        let slot: &mut bool = match k.as_str() {
            "fuzzy.z_zh" => &mut f.z_zh,
            "fuzzy.c_ch" => &mut f.c_ch,
            "fuzzy.s_sh" => &mut f.s_sh,
            "fuzzy.n_l" => &mut f.n_l,
            "fuzzy.f_h" => &mut f.f_h,
            "fuzzy.r_l" => &mut f.r_l,
            "fuzzy.an_ang" => &mut f.an_ang,
            "fuzzy.en_eng" => &mut f.en_eng,
            "fuzzy.in_ing" => &mut f.in_ing,
            "fuzzy.ian_iang" => &mut f.ian_iang,
            "fuzzy.uan_uang" => &mut f.uan_uang,
            "wubi.auto_commit" => &mut o.wubi_auto_commit,
            "wubi.pinyin_lookup" => &mut o.wubi_pinyin_lookup,
            "wubi.completion" => &mut o.wubi_completion,
            "output.traditional" => &mut o.traditional,
            "candidates.emoji" => &mut o.emoji,
            _ => return false,
        };
        *slot = on;
        true
    }))
}

#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_core_NativeEngine_nativeInputChar(
    _env: JNIEnv,
    _c: JClass,
    h: jlong,
    code_point: jint,
) -> jboolean {
    let Some(ch) = char::from_u32(code_point as u32) else {
        return JNI_FALSE;
    };
    jbool(with_engine(h, false, |e| e.input_char(ch)))
}

macro_rules! bool_op {
    ($name:ident, |$e:ident| $body:expr) => {
        #[no_mangle]
        pub extern "system" fn $name(_env: JNIEnv, _c: JClass, h: jlong) -> jboolean {
            jbool(with_engine(h, false, |$e| $body))
        }
    };
}

macro_rules! index_op {
    ($name:ident, |$e:ident, $i:ident| $body:expr) => {
        #[no_mangle]
        pub extern "system" fn $name(_env: JNIEnv, _c: JClass, h: jlong, index: jint) -> jboolean {
            if index < 0 {
                return JNI_FALSE;
            }
            let $i = index as usize;
            jbool(with_engine(h, false, |$e| $body))
        }
    };
}

bool_op!(
    Java_com_weavetext_ime_core_NativeEngine_nativeBackspace,
    |e| e.backspace()
);
bool_op!(
    Java_com_weavetext_ime_core_NativeEngine_nativeCommitFirst,
    |e| {
        e.commit_first();
        true
    }
);
bool_op!(
    Java_com_weavetext_ime_core_NativeEngine_nativeCommitRaw,
    |e| {
        e.commit_raw();
        true
    }
);
bool_op!(Java_com_weavetext_ime_core_NativeEngine_nativeClear, |e| {
    e.clear();
    true
});
bool_op!(Java_com_weavetext_ime_core_NativeEngine_nativeFlush, |e| {
    e.flush();
    true
});
bool_op!(
    Java_com_weavetext_ime_core_NativeEngine_nativeIsComposing,
    |e| e.is_composing()
);
index_op!(
    Java_com_weavetext_ime_core_NativeEngine_nativeSelect,
    |e, i| e.select(i)
);
index_op!(
    Java_com_weavetext_ime_core_NativeEngine_nativeSelectPinyin,
    |e, i| e.select_pinyin_option(i)
);
index_op!(
    Java_com_weavetext_ime_core_NativeEngine_nativeForget,
    |e, i| e.forget_candidate(i)
);

#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_core_NativeEngine_nativeSetLearning(
    _env: JNIEnv,
    _c: JClass,
    h: jlong,
    on: jboolean,
) {
    with_engine(h, (), |e| e.set_learning(on != JNI_FALSE));
}

#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_core_NativeEngine_nativeSetContext(
    mut env: JNIEnv,
    _c: JClass,
    h: jlong,
    prev: JString,
) {
    let p = get_string(&mut env, &prev);
    with_engine(h, (), |e| e.set_context(p));
}

#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_core_NativeEngine_nativeSnapshot(
    mut env: JNIEnv,
    _c: JClass,
    h: jlong,
) -> jbyteArray {
    let bytes = with_engine(h, Vec::new(), |e| encode_snapshot(&e.snapshot()));
    to_jbytes(&mut env, &bytes)
}

/// 分页候选，编码为 `i32 n { str text str comment u8 user }*n`。
#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_core_NativeEngine_nativeCandidates(
    mut env: JNIEnv,
    _c: JClass,
    h: jlong,
    offset: jint,
    limit: jint,
) -> jbyteArray {
    let bytes = with_engine(h, Vec::new(), |e| {
        let mut enc = Enc(Vec::new());
        enc.cands(&e.candidates(offset.max(0) as usize, limit.max(0) as usize));
        enc.0
    });
    to_jbytes(&mut env, &bytes)
}

// ---------------------------------------------------------------- user dictionary

fn to_jstring(env: &mut JNIEnv, s: &str) -> jni::sys::jstring {
    env.new_string(s)
        .map(|j| j.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// 用户词列表，编码为 `i32 n { str text str pinyin i32 count }*n`。
#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_core_NativeEngine_nativeUserWords(
    mut env: JNIEnv,
    _c: JClass,
    h: jlong,
    query: JString,
    offset: jint,
    limit: jint,
) -> jbyteArray {
    let q = get_string(&mut env, &query).unwrap_or_default();
    let bytes = with_engine(h, Vec::new(), |e| {
        let words = e.user_words(&q, offset.max(0) as usize, limit.max(0) as usize);
        let mut enc = Enc(Vec::new());
        enc.i32(words.len() as i32);
        for w in &words {
            enc.str(&w.text);
            enc.str(&w.pinyin);
            enc.i32(w.count as i32);
        }
        enc.0
    });
    to_jbytes(&mut env, &bytes)
}

#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_core_NativeEngine_nativeUserWordCount(
    _env: JNIEnv,
    _c: JClass,
    h: jlong,
) -> jint {
    with_engine(h, 0, |e| e.user_word_count() as jint)
}

#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_core_NativeEngine_nativeDeleteUserWord(
    mut env: JNIEnv,
    _c: JClass,
    h: jlong,
    pinyin: JString,
    text: JString,
) -> jboolean {
    let (Some(p), Some(t)) = (get_string(&mut env, &pinyin), get_string(&mut env, &text)) else {
        return JNI_FALSE;
    };
    jbool(with_engine(h, false, |e| e.delete_user_word(&p, &t)))
}

#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_core_NativeEngine_nativeExportUserWords(
    mut env: JNIEnv,
    _c: JClass,
    h: jlong,
) -> jni::sys::jstring {
    let s = with_engine(h, String::new(), |e| e.export_user_words());
    to_jstring(&mut env, &s)
}

#[no_mangle]
pub extern "system" fn Java_com_weavetext_ime_core_NativeEngine_nativeImportUserWords(
    mut env: JNIEnv,
    _c: JClass,
    h: jlong,
    text: JString,
) -> jint {
    let Some(t) = get_string(&mut env, &text) else {
        return 0;
    };
    with_engine(h, 0, |e| e.import_user_words(&t) as jint)
}

bool_op!(
    Java_com_weavetext_ime_core_NativeEngine_nativeClearUserWords,
    |e| e.clear_user_words()
);
