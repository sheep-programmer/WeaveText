//! 按需下载的端侧语音识别运行时：在运行时加载 sherpa-onnx 的 C 接口库（及其依赖的 onnxruntime），
//! 轻量版因此不必把约 27 MB 的运行时打进安装包——用户第一次用语音时再下载（压缩后约 9 MB）。
//!
//! Downloadable on-device speech runtime: the sherpa-onnx C API library (and the onnxruntime it needs)
//! is loaded at run time, so the lite build doesn't ship the ~27 MB runtime; it is fetched (~9 MB
//! compressed) the first time voice is used.
//!
//! 下面的结构体逐字段对应 sherpa-onnx **v1.13.8** 的 `c-api.h`。加载后先核对版本号，不符就拒绝使用，
//! 以免按错误的布局读写内存。未用到的字段保持为零，库会取默认值。
//! The structs below mirror `c-api.h` of sherpa-onnx **v1.13.8** field by field. The version string is
//! checked right after loading and anything else is rejected, so a different layout is never used.
//! Unused fields stay zero, which the library treats as defaults.

use std::ffi::{c_char, c_void, CStr, CString};
use std::path::Path;
use std::sync::{Arc, Mutex};

use libloading::Library;

/// 绑定所对应的 sherpa-onnx 版本。 sherpa-onnx version these bindings were written for.
pub const RUNTIME_VERSION: &str = "1.13.8";

type P = *const c_char;
type H = *const c_void;

#[repr(C)]
#[derive(Clone, Copy)]
struct FeatureConfig {
    sample_rate: i32,
    feature_dim: i32,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct VadConfig {
    model: P,
    threshold: f32,
    min_silence: f32,
    min_speech: f32,
    window_size: i32,
    max_speech: f32,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct VadModelConfig {
    silero: VadConfig,
    sample_rate: i32,
    num_threads: i32,
    provider: P,
    debug: i32,
    ten: VadConfig,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct Three {
    a: P,
    b: P,
    c: P,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct Two {
    a: P,
    b: P,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct One {
    model: P,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct OnlineModelConfig {
    transducer: Three,
    paraformer: Two,
    zipformer2_ctc: One,
    tokens: P,
    num_threads: i32,
    provider: P,
    debug: i32,
    model_type: P,
    modeling_unit: P,
    bpe_vocab: P,
    tokens_buf: P,
    tokens_buf_size: i32,
    nemo_ctc: One,
    t_one_ctc: One,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct CtcFstDecoderConfig {
    graph: P,
    max_active: i32,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct HomophoneReplacerConfig {
    dict_dir: P,
    lexicon: P,
    rule_fsts: P,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct OnlineRecognizerConfig {
    feat_config: FeatureConfig,
    model_config: OnlineModelConfig,
    decoding_method: P,
    max_active_paths: i32,
    enable_endpoint: i32,
    rule1_min_trailing_silence: f32,
    rule2_min_trailing_silence: f32,
    rule3_min_utterance_length: f32,
    hotwords_file: P,
    hotwords_score: f32,
    ctc_fst_decoder_config: CtcFstDecoderConfig,
    rule_fsts: P,
    rule_fars: P,
    blank_penalty: f32,
    hotwords_buf: P,
    hotwords_buf_size: i32,
    hr: HomophoneReplacerConfig,
}

/// 只读取首个字段 `text`。 Only the leading `text` field is read.
#[repr(C)]
struct ResultHead {
    text: P,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct WhisperConfig {
    encoder: P,
    decoder: P,
    language: P,
    task: P,
    tail_paddings: i32,
    enable_token_timestamps: i32,
    enable_segment_timestamps: i32,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct SenseVoiceConfig {
    model: P,
    language: P,
    use_itn: i32,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct MoonshineConfig {
    preprocessor: P,
    encoder: P,
    uncached_decoder: P,
    cached_decoder: P,
    merged_decoder: P,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct CanaryConfig {
    encoder: P,
    decoder: P,
    src_lang: P,
    tgt_lang: P,
    use_pnc: i32,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct FunAsrNanoConfig {
    encoder_adaptor: P,
    llm: P,
    embedding: P,
    tokenizer: P,
    system_prompt: P,
    user_prompt: P,
    max_new_tokens: i32,
    temperature: f32,
    top_p: f32,
    seed: i32,
    language: P,
    itn: i32,
    hotwords: P,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct Qwen3AsrConfig {
    conv_frontend: P,
    encoder: P,
    decoder: P,
    tokenizer: P,
    max_total_len: i32,
    max_new_tokens: i32,
    temperature: f32,
    top_p: f32,
    seed: i32,
    hotwords: P,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct CohereTranscribeConfig {
    encoder: P,
    decoder: P,
    language: P,
    use_punct: i32,
    use_itn: i32,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct OfflineModelConfig {
    transducer: Three,
    paraformer: One,
    nemo_ctc: One,
    whisper: WhisperConfig,
    tdnn: One,
    tokens: P,
    num_threads: i32,
    debug: i32,
    provider: P,
    model_type: P,
    modeling_unit: P,
    bpe_vocab: P,
    telespeech_ctc: P,
    sense_voice: SenseVoiceConfig,
    moonshine: MoonshineConfig,
    fire_red_asr: Two,
    dolphin: One,
    zipformer_ctc: One,
    canary: CanaryConfig,
    wenet_ctc: One,
    omnilingual: One,
    medasr: One,
    funasr_nano: FunAsrNanoConfig,
    fire_red_asr_ctc: One,
    qwen3_asr: Qwen3AsrConfig,
    cohere_transcribe: CohereTranscribeConfig,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct OfflineLmConfig {
    model: P,
    scale: f32,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct OfflineRecognizerConfig {
    feat_config: FeatureConfig,
    model_config: OfflineModelConfig,
    lm_config: OfflineLmConfig,
    decoding_method: P,
    max_active_paths: i32,
    hotwords_file: P,
    hotwords_score: f32,
    rule_fsts: P,
    rule_fars: P,
    blank_penalty: f32,
    hr: HomophoneReplacerConfig,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct PunctuationConfig {
    ct_transformer: P,
    num_threads: i32,
    debug: i32,
    provider: P,
}

/// 全零的配置（C 端把零/空指针当作默认值）。 An all-zero config; C treats zero/null as defaults.
fn zeroed<T: Copy>() -> T {
    // SAFETY: 这些结构体只含指针、整数和浮点数，全零是合法值。 Only pointers, ints and floats.
    unsafe { std::mem::zeroed() }
}

macro_rules! api {
    ($($field:ident : $sym:literal => fn($($arg:ty),*) $(-> $ret:ty)?;)*) => {
        struct Api {
            _ort: Library,
            _lib: Library,
            $($field: unsafe extern "C" fn($($arg),*) $(-> $ret)?,)*
        }

        impl Api {
            fn load(dir: &Path) -> Result<Api, String> {
                let (ort_name, lib_name) = if cfg!(target_os = "macos") {
                    ("libonnxruntime.dylib", "libsherpa-onnx-c-api.dylib")
                } else {
                    ("libonnxruntime.so", "libsherpa-onnx-c-api.so")
                };
                // 先按绝对路径载入依赖：之后 C 接口库按 soname 找 onnxruntime 时会复用它。
                // Load the dependency by absolute path first so the C API's soname lookup reuses it.
                // SAFETY: 载入的是经过 SHA-256 校验的官方库，初始化代码不依赖我们的状态。
                // Loading the checksum-verified upstream libraries; their initializers don't touch our state.
                let ort = unsafe { Library::new(dir.join(ort_name)) }.map_err(|e| format!("load onnxruntime: {e}"))?;
                let lib = unsafe { Library::new(dir.join(lib_name)) }.map_err(|e| format!("load sherpa-onnx: {e}"))?;
                unsafe {
                    let version: unsafe extern "C" fn() -> P = *lib
                        .get(b"SherpaOnnxGetVersionStr\0")
                        .map_err(|e| format!("missing SherpaOnnxGetVersionStr: {e}"))?;
                    let v = CStr::from_ptr(version()).to_string_lossy().into_owned();
                    if v != RUNTIME_VERSION {
                        return Err(format!("sherpa-onnx {v} found, {RUNTIME_VERSION} required"));
                    }
                    $(let $field = *lib.get(concat!($sym, "\0").as_bytes()).map_err(|e| format!("missing {}: {e}", $sym))?;)*
                    Ok(Api { _ort: ort, _lib: lib, $($field,)* })
                }
            }
        }
    };
}

api! {
    create_vad: "SherpaOnnxCreateVoiceActivityDetector" => fn(*const VadModelConfig, f32) -> H;
    destroy_vad: "SherpaOnnxDestroyVoiceActivityDetector" => fn(H);
    vad_accept: "SherpaOnnxVoiceActivityDetectorAcceptWaveform" => fn(H, *const f32, i32);
    vad_detected: "SherpaOnnxVoiceActivityDetectorDetected" => fn(H) -> i32;
    vad_empty: "SherpaOnnxVoiceActivityDetectorEmpty" => fn(H) -> i32;
    vad_reset: "SherpaOnnxVoiceActivityDetectorReset" => fn(H);
    vad_clear: "SherpaOnnxVoiceActivityDetectorClear" => fn(H);
    create_online: "SherpaOnnxCreateOnlineRecognizer" => fn(*const OnlineRecognizerConfig) -> H;
    destroy_online: "SherpaOnnxDestroyOnlineRecognizer" => fn(H);
    create_online_stream: "SherpaOnnxCreateOnlineStream" => fn(H) -> H;
    destroy_online_stream: "SherpaOnnxDestroyOnlineStream" => fn(H);
    online_accept: "SherpaOnnxOnlineStreamAcceptWaveform" => fn(H, i32, *const f32, i32);
    online_ready: "SherpaOnnxIsOnlineStreamReady" => fn(H, H) -> i32;
    online_decode: "SherpaOnnxDecodeOnlineStream" => fn(H, H);
    online_result: "SherpaOnnxGetOnlineStreamResult" => fn(H, H) -> *const ResultHead;
    online_result_free: "SherpaOnnxDestroyOnlineRecognizerResult" => fn(*const ResultHead);
    online_endpoint: "SherpaOnnxOnlineStreamIsEndpoint" => fn(H, H) -> i32;
    online_reset: "SherpaOnnxOnlineStreamReset" => fn(H, H);
    online_finished: "SherpaOnnxOnlineStreamInputFinished" => fn(H);
    create_offline: "SherpaOnnxCreateOfflineRecognizer" => fn(*const OfflineRecognizerConfig) -> H;
    destroy_offline: "SherpaOnnxDestroyOfflineRecognizer" => fn(H);
    create_offline_stream: "SherpaOnnxCreateOfflineStream" => fn(H) -> H;
    destroy_offline_stream: "SherpaOnnxDestroyOfflineStream" => fn(H);
    offline_accept: "SherpaOnnxAcceptWaveformOffline" => fn(H, i32, *const f32, i32);
    offline_decode: "SherpaOnnxDecodeOfflineStream" => fn(H, H);
    offline_result: "SherpaOnnxGetOfflineStreamResult" => fn(H) -> *const ResultHead;
    offline_result_free: "SherpaOnnxDestroyOfflineRecognizerResult" => fn(*const ResultHead);
    create_punct: "SherpaOnnxCreateOfflinePunctuation" => fn(*const PunctuationConfig) -> H;
    destroy_punct: "SherpaOnnxDestroyOfflinePunctuation" => fn(H);
    punct_add: "SherpaOfflinePunctuationAddPunct" => fn(H, P) -> P;
    punct_free: "SherpaOfflinePunctuationFreeText" => fn(P);
}

static API: Mutex<Option<Arc<Api>>> = Mutex::new(None);

/// 载入运行时（重复调用直接返回）。 Load the runtime once; later calls are no-ops.
pub fn load(dir: &Path) -> Result<(), String> {
    let mut g = API.lock().unwrap_or_else(|e| e.into_inner());
    if g.is_none() {
        *g = Some(Arc::new(Api::load(dir)?));
    }
    Ok(())
}

pub fn is_loaded() -> bool {
    API.lock().unwrap_or_else(|e| e.into_inner()).is_some()
}

pub struct Vad {
    api: Arc<Api>,
    detector: H,
}

// SAFETY: The JNI wrapper serializes access with a mutex, just as for Online and Offline.
unsafe impl Send for Vad {}

impl Vad {
    pub fn new(model: &str) -> Result<Self, String> {
        let api = api()?;
        let (model, cpu) = (cstr(model)?, cstr("cpu")?);
        let mut config: VadModelConfig = zeroed();
        config.silero = VadConfig {
            model: model.as_ptr(),
            threshold: 0.35,
            min_silence: 1.6,
            min_speech: 0.1,
            window_size: 512,
            max_speech: 30.0,
        };
        config.sample_rate = 16000;
        config.num_threads = 1;
        config.provider = cpu.as_ptr();
        let detector = unsafe { (api.create_vad)(&config, 60.0) };
        if detector.is_null() {
            return Err("failed to create speech detector".into());
        }
        Ok(Self { api, detector })
    }

    pub fn accept(&self, samples: &[f32]) -> i32 {
        unsafe {
            (self.api.vad_accept)(self.detector, samples.as_ptr(), samples.len() as i32);
            let speech = (self.api.vad_detected)(self.detector) != 0;
            let ended = (self.api.vad_empty)(self.detector) == 0;
            i32::from(speech) | (i32::from(ended) << 1)
        }
    }

    pub fn reset(&self) {
        unsafe {
            (self.api.vad_reset)(self.detector);
            (self.api.vad_clear)(self.detector);
        }
    }
}

impl Drop for Vad {
    fn drop(&mut self) {
        unsafe { (self.api.destroy_vad)(self.detector) };
    }
}

fn api() -> Result<Arc<Api>, String> {
    API.lock()
        .unwrap_or_else(|e| e.into_inner())
        .clone()
        .ok_or_else(|| "speech runtime not loaded".to_string())
}

fn cstr(s: &str) -> Result<CString, String> {
    CString::new(s).map_err(|_| "string contains NUL".to_string())
}

unsafe fn take_text(p: P) -> String {
    if p.is_null() {
        String::new()
    } else {
        CStr::from_ptr(p).to_string_lossy().into_owned()
    }
}

/// 端点检测规则（秒）。 Endpoint rules in seconds.
#[derive(Clone, Copy, Debug)]
pub struct Endpoint {
    pub no_speech: f32,
    pub after_speech: f32,
    pub max_utterance: f32,
}

/// 流式识别器（含一条流）。 A streaming recognizer with one stream.
pub struct Online {
    api: Arc<Api>,
    rec: H,
    stream: H,
}

// SAFETY: sherpa-onnx 的识别器与流可以在线程间移动；我们保证同一时刻只有一个线程使用（外层互斥）。
// Recognizer and stream may move between threads; callers serialise access.
unsafe impl Send for Online {}

impl Online {
    /// 目前支持 zipformer2-ctc 流式模型。 Currently zipformer2-ctc streaming models.
    pub fn new(
        arch: &str,
        model: &str,
        tokens: &str,
        threads: i32,
        ep: Endpoint,
    ) -> Result<Online, String> {
        let api = api()?;
        if arch != "zipformer2-ctc" && arch != "zipformer-transducer" {
            return Err(format!("unsupported streaming arch {arch}"));
        }
        let paths = if arch == "zipformer-transducer" {
            let paths: Vec<String> = serde_json::from_str(model).map_err(|e| format!("invalid transducer files: {e}"))?;
            if paths.len() != 3 { return Err("transducer requires encoder, decoder and joiner".into()); }
            paths
        } else { vec![model.to_string()] };
        let models = paths.iter().map(|p| cstr(p)).collect::<Result<Vec<_>, _>>()?;
        let tokens = cstr(tokens)?;
        let (cpu, greedy) = (cstr("cpu")?, cstr("greedy_search")?);
        let mut c: OnlineRecognizerConfig = zeroed();
        c.feat_config = FeatureConfig {
            sample_rate: 16000,
            feature_dim: 80,
        };
        if arch == "zipformer-transducer" {
            c.model_config.transducer = Three { a: models[0].as_ptr(), b: models[1].as_ptr(), c: models[2].as_ptr() };
        } else { c.model_config.zipformer2_ctc.model = models[0].as_ptr(); }
        c.model_config.tokens = tokens.as_ptr();
        c.model_config.num_threads = threads.max(1);
        c.model_config.provider = cpu.as_ptr();
        c.decoding_method = greedy.as_ptr();
        c.enable_endpoint = 1;
        c.rule1_min_trailing_silence = ep.no_speech;
        c.rule2_min_trailing_silence = ep.after_speech;
        c.rule3_min_utterance_length = ep.max_utterance;
        // SAFETY: 配置与其中的字符串在调用期间有效；库会复制所需内容。 Config outlives the call.
        let rec = unsafe { (api.create_online)(&c) };
        if rec.is_null() {
            return Err("failed to create streaming recognizer".into());
        }
        let stream = unsafe { (api.create_online_stream)(rec) };
        if stream.is_null() {
            unsafe { (api.destroy_online)(rec) };
            return Err("failed to create stream".into());
        }
        Ok(Online { api, rec, stream })
    }

    /// 送入 16 kHz 单声道样本并解码到当前。 Feed 16 kHz mono samples and decode what is ready.
    pub fn accept(&mut self, samples: &[f32]) {
        unsafe {
            (self.api.online_accept)(self.stream, 16000, samples.as_ptr(), samples.len() as i32);
            while (self.api.online_ready)(self.rec, self.stream) != 0 {
                (self.api.online_decode)(self.rec, self.stream);
            }
        }
    }

    pub fn text(&self) -> String {
        unsafe {
            let r = (self.api.online_result)(self.rec, self.stream);
            if r.is_null() {
                return String::new();
            }
            let t = take_text((*r).text);
            (self.api.online_result_free)(r);
            t
        }
    }

    pub fn is_endpoint(&self) -> bool {
        unsafe { (self.api.online_endpoint)(self.rec, self.stream) != 0 }
    }

    pub fn reset(&mut self) {
        unsafe { (self.api.online_reset)(self.rec, self.stream) }
    }

    /// 补 0.5 秒静音并解码完剩余部分。 Pad 0.5 s of silence and decode the rest.
    pub fn finish(&mut self) {
        self.accept(&[0f32; 8000]);
    }

    /// 标记输入结束（之后只能 reset）。 Mark input finished (reset before reuse).
    pub fn input_finished(&mut self) {
        unsafe { (self.api.online_finished)(self.stream) }
    }
}

impl Drop for Online {
    fn drop(&mut self) {
        unsafe {
            (self.api.destroy_online_stream)(self.stream);
            (self.api.destroy_online)(self.rec);
        }
    }
}

/// 非流式识别器（终稿）。 Offline recognizer for the final pass.
pub struct Offline {
    api: Arc<Api>,
    rec: H,
}

// SAFETY: 同 [Online]。 Same as [Online].
unsafe impl Send for Offline {}

impl Offline {
    /// arch：`zipformer-ctc` / `sense-voice` / `paraformer`。
    pub fn new(arch: &str, model: &str, tokens: &str, threads: i32) -> Result<Offline, String> {
        let api = api()?;
        let (model, tokens) = (cstr(model)?, cstr(tokens)?);
        let (cpu, greedy, auto) = (cstr("cpu")?, cstr("greedy_search")?, cstr("auto")?);
        let mut c: OfflineRecognizerConfig = zeroed();
        c.feat_config = FeatureConfig {
            sample_rate: 16000,
            feature_dim: 80,
        };
        match arch {
            "zipformer-ctc" => c.model_config.zipformer_ctc.model = model.as_ptr(),
            "paraformer" => c.model_config.paraformer.model = model.as_ptr(),
            "dolphin" => c.model_config.dolphin.model = model.as_ptr(),
            "telespeech-ctc" => c.model_config.telespeech_ctc = model.as_ptr(),
            "wenet-ctc" => c.model_config.wenet_ctc.model = model.as_ptr(),
            "sense-voice" => {
                c.model_config.sense_voice.model = model.as_ptr();
                c.model_config.sense_voice.language = auto.as_ptr();
                c.model_config.sense_voice.use_itn = 1;
            }
            _ => return Err(format!("unsupported offline arch {arch}")),
        }
        c.model_config.tokens = tokens.as_ptr();
        c.model_config.num_threads = threads.max(1);
        c.model_config.provider = cpu.as_ptr();
        c.decoding_method = greedy.as_ptr();
        let rec = unsafe { (api.create_offline)(&c) };
        if rec.is_null() {
            return Err("failed to create offline recognizer".into());
        }
        Ok(Offline { api, rec })
    }

    pub fn decode(&self, samples: &[f32]) -> String {
        unsafe {
            let s = (self.api.create_offline_stream)(self.rec);
            if s.is_null() {
                return String::new();
            }
            (self.api.offline_accept)(s, 16000, samples.as_ptr(), samples.len() as i32);
            (self.api.offline_decode)(self.rec, s);
            let r = (self.api.offline_result)(s);
            let t = if r.is_null() {
                String::new()
            } else {
                take_text((*r).text)
            };
            if !r.is_null() {
                (self.api.offline_result_free)(r);
            }
            (self.api.destroy_offline_stream)(s);
            t
        }
    }
}

impl Drop for Offline {
    fn drop(&mut self) {
        unsafe { (self.api.destroy_offline)(self.rec) }
    }
}

/// 智能标点。 Punctuation restoration.
pub struct Punct {
    api: Arc<Api>,
    h: H,
}

// SAFETY: 同 [Online]。 Same as [Online].
unsafe impl Send for Punct {}

impl Punct {
    pub fn new(model: &str, threads: i32) -> Result<Punct, String> {
        let api = api()?;
        let model = cstr(model)?;
        let cpu = cstr("cpu")?;
        let mut c: PunctuationConfig = zeroed();
        c.ct_transformer = model.as_ptr();
        c.num_threads = threads.max(1);
        c.provider = cpu.as_ptr();
        let h = unsafe { (api.create_punct)(&c) };
        if h.is_null() {
            return Err("failed to create punctuation model".into());
        }
        Ok(Punct { api, h })
    }

    pub fn punctuate(&self, text: &str) -> String {
        let Ok(t) = cstr(text) else {
            return text.to_string();
        };
        unsafe {
            let out = (self.api.punct_add)(self.h, t.as_ptr());
            if out.is_null() {
                return text.to_string();
            }
            let s = take_text(out);
            (self.api.punct_free)(out);
            s
        }
    }
}

impl Drop for Punct {
    fn drop(&mut self) {
        unsafe { (self.api.destroy_punct)(self.h) }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 结构体大小与字段偏移与 v1.13.8 头文件一致（数值由 clang 编译官方 c-api.h 得到，LP64）。
    /// Sizes and offsets match the v1.13.8 header (values from clang on the official c-api.h, LP64).
    #[test]
    #[cfg(target_pointer_width = "64")]
    fn layout_matches_header() {
        use std::mem::{offset_of, size_of};
        assert_eq!(size_of::<FeatureConfig>(), 8);
        assert_eq!(size_of::<OnlineModelConfig>(), 136);
        assert_eq!(size_of::<CtcFstDecoderConfig>(), 16);
        assert_eq!(size_of::<HomophoneReplacerConfig>(), 24);
        assert_eq!(size_of::<OnlineRecognizerConfig>(), 272);
        assert_eq!(size_of::<WhisperConfig>(), 48);
        assert_eq!(size_of::<SenseVoiceConfig>(), 24);
        assert_eq!(size_of::<MoonshineConfig>(), 40);
        assert_eq!(size_of::<CanaryConfig>(), 40);
        assert_eq!(size_of::<FunAsrNanoConfig>(), 88);
        assert_eq!(size_of::<Qwen3AsrConfig>(), 64);
        assert_eq!(size_of::<CohereTranscribeConfig>(), 32);
        assert_eq!(size_of::<OfflineModelConfig>(), 504);
        assert_eq!(size_of::<OfflineLmConfig>(), 16);
        assert_eq!(size_of::<OfflineRecognizerConfig>(), 608);
        assert_eq!(size_of::<PunctuationConfig>(), 24);
        assert_eq!(offset_of!(OfflineModelConfig, zipformer_ctc), 240);
        assert_eq!(offset_of!(OfflineModelConfig, sense_voice), 152);
        assert_eq!(offset_of!(OfflineModelConfig, tokens), 96);
        assert_eq!(
            offset_of!(OnlineRecognizerConfig, rule1_min_trailing_silence),
            160
        );
        assert_eq!(offset_of!(OfflineRecognizerConfig, decoding_method), 528);
    }

    /// 真实运行时 + 真实模型（环境变量给出路径时才跑）。 Real runtime and model, when paths are provided.
    #[test]
    fn real_runtime_roundtrip() {
        let (Ok(dir), Ok(model_dir)) = (
            std::env::var("WEAVE_ASR_RUNTIME"),
            std::env::var("WEAVE_ASR_MODEL"),
        ) else {
            return;
        };
        load(Path::new(&dir)).expect("load runtime");
        let m = Path::new(&model_dir);
        let mut o = Online::new(
            "zipformer2-ctc",
            m.join("model.int8.onnx").to_str().unwrap(),
            m.join("tokens.txt").to_str().unwrap(),
            2,
            Endpoint {
                no_speech: 2.4,
                after_speech: 0.8,
                max_utterance: 20.0,
            },
        )
        .expect("create");
        let silence = vec![0f32; 16000];
        o.accept(&silence);
        o.finish();
        assert!(
            o.text().chars().count() < 4,
            "silence should give (almost) nothing"
        );
        o.reset();
        if let Ok(wav) = std::env::var("WEAVE_ASR_WAV") {
            let bytes = std::fs::read(wav).unwrap();
            let pcm: Vec<f32> = bytes[44..]
                .chunks_exact(2)
                .map(|c| i16::from_le_bytes([c[0], c[1]]) as f32 / 32768.0)
                .collect();
            o.accept(&pcm);
            o.finish();
            let t = o.text();
            eprintln!("recognized: {t}");
            assert!(!t.is_empty());
            if let Ok(fin) = std::env::var("WEAVE_ASR_FINAL") {
                let f = Path::new(&fin);
                let off = Offline::new(
                    "zipformer-ctc",
                    f.join("model.int8.onnx").to_str().unwrap(),
                    f.join("tokens.txt").to_str().unwrap(),
                    2,
                )
                .expect("create offline");
                let t2 = off.decode(&pcm);
                eprintln!("final: {t2}");
                assert!(!t2.is_empty());
            }
        }
    }
}

// ------------------------------------------------------------------ JNI (com.weavetext.ime.voice.local.NativeAsr)

mod jni_api {
    use std::panic::{catch_unwind, AssertUnwindSafe};
    use std::path::Path;
    use std::sync::Mutex;

    use jni::objects::{JClass, JFloatArray, JString};
    use jni::sys::{jboolean, jfloat, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
    use jni::JNIEnv;

    use super::{Endpoint, Offline, Online, Punct, Vad};

    static LAST_ERROR: Mutex<String> = Mutex::new(String::new());

    fn set_error(e: String) {
        *LAST_ERROR.lock().unwrap_or_else(|p| p.into_inner()) = e;
    }

    fn string(env: &mut JNIEnv, s: &JString) -> Option<String> {
        if s.is_null() {
            return None;
        }
        env.get_string(s).ok().map(|s| s.into())
    }

    fn out(env: &mut JNIEnv, s: &str) -> jstring {
        env.new_string(s)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut())
    }

    fn floats(env: &mut JNIEnv, a: &JFloatArray, n: jint) -> Vec<f32> {
        let len = env.get_array_length(a).unwrap_or(0).min(n.max(0));
        let mut buf = vec![0f32; len as usize];
        if len > 0 && env.get_float_array_region(a, 0, &mut buf).is_err() {
            buf.clear();
        }
        buf
    }

    /// 句柄 = Box<Mutex<T>> 的裸指针。 Handle = raw Box<Mutex<T>> pointer.
    fn with<T, R>(h: jlong, default: R, f: impl FnOnce(&mut T) -> R) -> R {
        if h == 0 {
            return default;
        }
        // SAFETY: h 由对应的 create 返回，destroy 之前有效（Kotlin 端保证）。 Valid until destroy.
        let m = unsafe { &*(h as *const Mutex<T>) };
        catch_unwind(AssertUnwindSafe(|| {
            let mut g = m.lock().unwrap_or_else(|p| p.into_inner());
            f(&mut g)
        }))
        .unwrap_or(default)
    }

    fn boxed<T>(r: Result<T, String>) -> jlong {
        match r {
            Ok(v) => Box::into_raw(Box::new(Mutex::new(v))) as jlong,
            Err(e) => {
                set_error(e);
                0
            }
        }
    }

    fn destroy<T>(h: jlong) {
        if h != 0 {
            // SAFETY: 与 create 配对，只调用一次。 Paired with create, called once.
            let _ = catch_unwind(|| drop(unsafe { Box::from_raw(h as *mut Mutex<T>) }));
        }
    }

    /// 载入运行时；成功返回 null，失败返回原因。 Load the runtime; null on success, else the reason.
    #[no_mangle]
    pub extern "system" fn Java_com_weavetext_ime_voice_local_NativeAsr_nativeLoad(
        mut env: JNIEnv,
        _c: JClass,
        dir: JString,
    ) -> jstring {
        let Some(dir) = string(&mut env, &dir) else {
            return out(&mut env, "no dir");
        };
        match catch_unwind(|| super::load(Path::new(&dir))) {
            Ok(Ok(())) => std::ptr::null_mut(),
            Ok(Err(e)) => out(&mut env, &e),
            Err(_) => out(&mut env, "panic while loading"),
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_com_weavetext_ime_voice_local_NativeAsr_nativeIsLoaded(
        _env: JNIEnv,
        _c: JClass,
    ) -> jboolean {
        if super::is_loaded() {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_com_weavetext_ime_voice_local_NativeAsr_nativeLastError(
        mut env: JNIEnv,
        _c: JClass,
    ) -> jstring {
        let e = LAST_ERROR.lock().unwrap_or_else(|p| p.into_inner()).clone();
        out(&mut env, &e)
    }

    #[no_mangle]
    #[allow(clippy::too_many_arguments)]
    pub extern "system" fn Java_com_weavetext_ime_voice_local_NativeAsr_nativeOnlineCreate(
        mut env: JNIEnv,
        _c: JClass,
        arch: JString,
        model: JString,
        tokens: JString,
        threads: jint,
        no_speech: jfloat,
        after_speech: jfloat,
        max_utterance: jfloat,
    ) -> jlong {
        let (Some(arch), Some(model), Some(tokens)) = (
            string(&mut env, &arch),
            string(&mut env, &model),
            string(&mut env, &tokens),
        ) else {
            return 0;
        };
        let ep = Endpoint {
            no_speech,
            after_speech,
            max_utterance,
        };
        boxed(
            catch_unwind(|| Online::new(&arch, &model, &tokens, threads, ep))
                .unwrap_or(Err("panic".into())),
        )
    }

    #[no_mangle]
    pub extern "system" fn Java_com_weavetext_ime_voice_local_NativeAsr_nativeOnlineAccept(
        mut env: JNIEnv,
        _c: JClass,
        h: jlong,
        samples: JFloatArray,
        n: jint,
    ) {
        let buf = floats(&mut env, &samples, n);
        with::<Online, ()>(h, (), |o| o.accept(&buf));
    }

    #[no_mangle]
    pub extern "system" fn Java_com_weavetext_ime_voice_local_NativeAsr_nativeOnlineText(
        mut env: JNIEnv,
        _c: JClass,
        h: jlong,
    ) -> jstring {
        let t = with::<Online, String>(h, String::new(), |o| o.text());
        out(&mut env, &t)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_weavetext_ime_voice_local_NativeAsr_nativeOnlineIsEndpoint(
        _env: JNIEnv,
        _c: JClass,
        h: jlong,
    ) -> jboolean {
        if with::<Online, bool>(h, false, |o| o.is_endpoint()) {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_com_weavetext_ime_voice_local_NativeAsr_nativeOnlineReset(
        _env: JNIEnv,
        _c: JClass,
        h: jlong,
    ) {
        with::<Online, ()>(h, (), |o| o.reset());
    }

    #[no_mangle]
    pub extern "system" fn Java_com_weavetext_ime_voice_local_NativeAsr_nativeOnlineFinish(
        _env: JNIEnv,
        _c: JClass,
        h: jlong,
    ) {
        with::<Online, ()>(h, (), |o| o.finish());
    }

    #[no_mangle]
    pub extern "system" fn Java_com_weavetext_ime_voice_local_NativeAsr_nativeOnlineDestroy(
        _env: JNIEnv,
        _c: JClass,
        h: jlong,
    ) {
        destroy::<Online>(h);
    }

    #[no_mangle]
    pub extern "system" fn Java_com_weavetext_ime_voice_local_NativeAsr_nativeOfflineCreate(
        mut env: JNIEnv,
        _c: JClass,
        arch: JString,
        model: JString,
        tokens: JString,
        threads: jint,
    ) -> jlong {
        let (Some(arch), Some(model), Some(tokens)) = (
            string(&mut env, &arch),
            string(&mut env, &model),
            string(&mut env, &tokens),
        ) else {
            return 0;
        };
        boxed(
            catch_unwind(|| Offline::new(&arch, &model, &tokens, threads))
                .unwrap_or(Err("panic".into())),
        )
    }

    #[no_mangle]
    pub extern "system" fn Java_com_weavetext_ime_voice_local_NativeAsr_nativeOfflineDecode(
        mut env: JNIEnv,
        _c: JClass,
        h: jlong,
        samples: JFloatArray,
        n: jint,
    ) -> jstring {
        let buf = floats(&mut env, &samples, n);
        let t = with::<Offline, String>(h, String::new(), |o| o.decode(&buf));
        out(&mut env, &t)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_weavetext_ime_voice_local_NativeAsr_nativeOfflineDestroy(
        _env: JNIEnv,
        _c: JClass,
        h: jlong,
    ) {
        destroy::<Offline>(h);
    }

    #[no_mangle]
    pub extern "system" fn Java_com_weavetext_ime_voice_local_NativeAsr_nativeVadCreate(
        mut env: JNIEnv,
        _c: JClass,
        model: JString,
    ) -> jlong {
        let Some(model) = string(&mut env, &model) else {
            return 0;
        };
        boxed(catch_unwind(|| Vad::new(&model)).unwrap_or(Err("panic".into())))
    }

    #[no_mangle]
    pub extern "system" fn Java_com_weavetext_ime_voice_local_NativeAsr_nativeVadAccept(
        mut env: JNIEnv,
        _c: JClass,
        h: jlong,
        samples: JFloatArray,
        n: jint,
    ) -> jint {
        let buf = floats(&mut env, &samples, n);
        with::<Vad, i32>(h, 0, |v| v.accept(&buf))
    }

    #[no_mangle]
    pub extern "system" fn Java_com_weavetext_ime_voice_local_NativeAsr_nativeVadReset(
        _env: JNIEnv,
        _c: JClass,
        h: jlong,
    ) {
        with::<Vad, ()>(h, (), |v| v.reset());
    }

    #[no_mangle]
    pub extern "system" fn Java_com_weavetext_ime_voice_local_NativeAsr_nativeVadDestroy(
        _env: JNIEnv,
        _c: JClass,
        h: jlong,
    ) {
        destroy::<Vad>(h);
    }

    #[no_mangle]
    pub extern "system" fn Java_com_weavetext_ime_voice_local_NativeAsr_nativePunctCreate(
        mut env: JNIEnv,
        _c: JClass,
        model: JString,
        threads: jint,
    ) -> jlong {
        let Some(model) = string(&mut env, &model) else {
            return 0;
        };
        boxed(catch_unwind(|| Punct::new(&model, threads)).unwrap_or(Err("panic".into())))
    }

    #[no_mangle]
    pub extern "system" fn Java_com_weavetext_ime_voice_local_NativeAsr_nativePunctuate(
        mut env: JNIEnv,
        _c: JClass,
        h: jlong,
        text: JString,
    ) -> jstring {
        let Some(t) = string(&mut env, &text) else {
            return std::ptr::null_mut();
        };
        let r = with::<Punct, String>(h, t.clone(), |p| p.punctuate(&t));
        out(&mut env, &r)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_weavetext_ime_voice_local_NativeAsr_nativePunctDestroy(
        _env: JNIEnv,
        _c: JClass,
        h: jlong,
    ) {
        destroy::<Punct>(h);
    }
}
