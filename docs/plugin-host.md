# 织文 Lua 插件宿主 / WeaveText Lua Plugin Host

`core/weave-plugin` 是织文输入法的 Lua 5.4 插件宿主：加载 `.xipk` 插件包，为插件提供 `host.*` API，
并对外（JNI / Kotlin）提供语音识别会话接口。宿主是通用的，本身不包含任何具体服务的插件。

`core/weave-plugin` is WeaveText's Lua 5.4 plugin host. It loads `.xipk` packages, exposes the
`host.*` API to plugins and offers a speech-session API to the app (JNI / Kotlin). The host is
generic and ships no service-specific plugins.

---

## 1. 插件包格式 / Package format

`.xipk` 是一个 zip，解包后：

A `.xipk` is a zip archive with this layout:

```
manifest.yaml      元数据 / metadata
main.lua           入口（可由 manifest.entry 改名）/ entry script (renamable via `entry`)
resources/         资源：图标、模型、词表…，经 host.resource 读取 / resources via host.resource
libs/*.lua         可选，require("x") 加载 libs/x.lua / optional modules for require()
```

所有文件也可以放在 zip 内的同一层子目录中；含 `..` 或绝对路径的条目会使整个包被拒绝。
单个条目解包上限 256 MiB。

Everything may also sit inside one top-level folder. Entries with `..` or absolute paths reject
the whole package. Each entry is capped at 256 MiB.

### manifest.yaml

```yaml
id: org.example.echo          # 必填，唯一，不能含 / \ 或以 . 开头 / required, unique
name: 回声                     # 显示名 / display name
description: 把音频时长回显成文字
version: 1.0.0                # 按字符串处理 / treated as a string
type: speech                  # 插件类型；语音插件为 speech / plugin kind
entry: main.lua               # 缺省 main.lua / default main.lua
icon: icon.png                # resources/ 下的 PNG，或者直接写一个字/emoji / PNG or a glyph
network:
  hosts:                      # 网络白名单，支持 *.example.com / allow-list, wildcards ok
    - api.example.com
    - "*.cdn.example.com"
  allowCustomHosts: false     # true = 不限域名（应由宿主 UI 让用户确认）/ unrestricted
permissions: []               # network_unrestricted 等同 allowCustomHosts
configSchema:                 # 设置项，宿主 UI 据此渲染 / settings rendered by the app
  - key: prefix
    type: text                # text / switch / select …（由 UI 解释 / interpreted by the UI）
    label: 前缀
    defaultValue: "echo"      # host.config.get 的缺省值，统一转成字符串 / stringified default
```

`PluginInfo.config_schema_json` 是 `configSchema` 原样转成的 JSON 数组（`defaultValue` 已转为字符串）。

`PluginInfo.config_schema_json` is `configSchema` as a JSON array (defaults stringified).

---

## 2. 架构与线程模型 / Architecture & threading

```
 Kotlin / JNI
     │  PluginManager (scan / install / config / start_speech …)
     ▼
 ┌──────────────────────── 每个插件实例 / per plugin instance ────────────────────────┐
 │  actor 线程：独占一个 Lua 5.4 状态，按顺序处理消息队列                               │
 │  actor thread: owns one Lua 5.4 state, processes its message queue in order         │
 │     ▲ Start / Audio / Stop / Cancel / Call / IsConfigured      （来自 App / from app）  │
 │     ▲ Ws(open/text/binary/error/close)       ← ws I/O 线程（每连接一条）/ per connection │
 │     ▲ Stream(data/done/error)                ← SSE 线程（每个流一条）/ per stream       │
 │     ▲ Timer(id)                              ← 定时线程（每实例一条）/ per instance     │
 └─────────────────────────────────────────────────────────────────────────────────────┘
```

- **一个插件实例 = 一个 Lua 状态 + 一条专属线程（actor）**。App 调用与所有网络/定时器回调都作为消息投递到
  这条线程串行执行；Lua 状态永不跨线程，插件代码无需考虑并发。
- 实例在第一次使用时创建（加载 `main.lua`，调 `initialize()` / `onLoad()`），之后被反复使用；
  `PluginManager::preload` 可提前创建，`unload` / `uninstall` / 重新安装会停止实例（调 `onUnload()`）。
- `SpeechListener` 的回调**都在插件线程上**调用，实现方应尽快返回（例如转投 UI 线程）。
- `host.http.request` 与 `host.ws.awaitBinary` 会阻塞插件线程（期间其他消息排队），请设合理超时。
- TLS 统一使用 rustls（ring）+ webpki-roots 内置根证书，不依赖系统证书库。

- **One instance = one Lua state + one dedicated thread (actor).** App calls and every
  network/timer callback are posted to that thread and run serially; the Lua state never changes
  threads, so plugin code needs no locking.
- Instances are created on first use (load `main.lua`, call `initialize()` / `onLoad()`) and then
  reused. `preload` creates one ahead of time; `unload` / `uninstall` / reinstall stop it
  (`onUnload()`).
- All `SpeechListener` callbacks run on the plugin thread — return quickly.
- `host.http.request` and `host.ws.awaitBinary` block the plugin thread; use sensible timeouts.
- TLS is rustls (ring) with the bundled webpki-roots; the system trust store is never used.

### 语音会话生命周期 / Speech session lifecycle

```
start_speech ──► plugin.start()            返回 false 或抛错 → on_error + on_end
feed(pcm)    ──► plugin.processAudioChunk(pcm)      （stop 之后的音频被丢弃）
stop()       ──► plugin.stop()             插件在结果出完后调 host.asr.emitEnd()
                   └─ 6 秒（STOP_TIMEOUT）内没有 emitEnd → 宿主自行结束会话
结束 / end   ──► listener.on_end()（恰好一次）──► plugin.cancel()（恰好一次）
cancel() / Drop(SpeechSession) ──► 立即结束：on_end + plugin.cancel()
```

- 每个实例同时只有一个会话；新的 `start_speech` 会先结束旧会话。
- `emitPartial` / `emitFinal` / `emitError` 只在会话进行中（`on_end` 之前）送达。
- `emitReplace(old, new)` 例外：它常在会话结束后才到（后台整理），发给最近一次会话的监听者；
  App 只在 `old` 仍原样停在光标前时才替换，否则丢弃。
- `plugin.cancel()` 在每次会话结束时都会被调用，必须幂等。

- One session per instance at a time; a new `start_speech` ends the previous one first.
- `emitPartial` / `emitFinal` / `emitError` are delivered only while the session is live.
- `emitReplace(old, new)` is the exception: it may arrive after `on_end` and goes to the latest
  session's listener; the app replaces only if `old` is still right before the cursor.
- `plugin.cancel()` is called at the end of every session and must be idempotent.

---

## 3. 对外 Rust API / Public Rust API

```rust
let mut mgr = PluginManager::new(plugins_dir, config_dir);
mgr.scan() -> Vec<PluginInfo>              // 登记 plugins_dir 下的目录；解包新的/版本变化的 .xipk
mgr.install(&xipk) -> Result<PluginInfo>   // 解包到 plugins_dir/<id>/（先写临时目录再改名）
mgr.add_path(&dir_or_xipk)                 // 原地登记任意目录（联调用）/ register in place
mgr.uninstall(id)                          // 删除解包目录与 config_dir/<id>.json
mgr.list() -> Vec<PluginInfo>
mgr.get_config(id, key) -> Option<String>  // 已设置的值，否则 defaultValue
mgr.set_config(id, key, value)             // 立即落盘（写临时文件后改名）
mgr.is_configured(id) -> bool              // 调 plugin.isConfigured()；未定义视为 true
mgr.start_speech(id, Arc<dyn SpeechListener>) -> Result<SpeechSession>
mgr.call(id, method, args_json, timeout)   // 通用调用 plugin.<method>(...)，参数/返回值为 JSON

PluginInfo { id, name, description, version, kind, icon_png: Option<Vec<u8>>,
             icon_text: Option<String>, config_schema_json: String, path }

trait SpeechListener: Send + Sync {
    fn on_partial(&self, text: &str);
    fn on_final(&self, text: &str);
    fn on_replace(&self, old: &str, new: &str);
    fn on_error(&self, msg: &str);
    fn on_end(&self);                      // 每个会话恰好一次 / exactly once per session
    fn on_log(&self, level: u8, msg: &str); // 0 debug / 1 info / 2 warn / 3 error
}

SpeechSession::feed(&[u8])   // 16 kHz、16 bit、单声道、小端 PCM / 16 kHz mono s16le
SpeechSession::stop()
SpeechSession::cancel()      // Drop 时自动 cancel / dropping it cancels
```

`SpeechSession` 的方法只投递消息，从不阻塞；`is_configured` / `call` 会等待插件线程回复（带超时）。
没有会话时插件日志发往 `set_logger` 设置的回调（否则打印到 stderr）。

Session methods only post messages and never block; `is_configured` / `call` wait for the plugin
thread (with a timeout). Logs outside a session go to the `set_logger` sink (else stderr).

---

## 4. 插件接口 / Plugin interface

`main.lua` 必须 `return` 一个表，宿主按需调用其中的函数（都不带 `self`，用 `function plugin.x()` 定义）：

`main.lua` must return a table; the host calls these functions on it (no `self`):

| 函数 / function | 时机 / when | 说明 / notes |
|---|---|---|
| `initialize()` / `onLoad()` | 实例创建后 / after load | 返回 `false` 只记警告 / `false` only logs a warning |
| `isConfigured()` | `PluginManager::is_configured` | 返回真值表示可用 / truthy = ready |
| `start()` | 会话开始 / session start | 返回 `false` 表示失败 / `false` = failed |
| `processAudioChunk(pcm)` | 每块音频 / per chunk | 16 kHz mono s16le 字节串 / byte string |
| `stop()` | 用户松手 / user released | 出完结果后调 `host.asr.emitEnd()` |
| `cancel()` | 每次会话结束 / every session end | 必须幂等 / must be idempotent |
| `onUnload()` | 实例停止 / instance stops | |

---

## 5. host API 参考 / host API reference

字节串即 Lua string（可含 `\0`）。数字参数也接受数字字符串。除特别说明外，出错时返回 `nil`（或 `nil, err`），
不抛 Lua 错误。

"Bytes" are Lua strings (may contain `\0`). Numeric arguments accept numeric strings. Unless noted,
failures return `nil` (or `nil, err`) instead of raising.

### 5.1 基础 / basics

| API | 说明 / notes |
|---|---|
| `host.sdkVersion` | 插件 API 级别（字符串，当前 `0.8.0`）/ API level string |
| `host.hostVersion` / `host.pluginId` | 宿主版本 / 当前插件 id |
| `host.log(...)` / `host.logError(...)` / `print(...)` | 参数以空格拼接；info / error / info 级 |
| `host.uuid()` | 随机 UUID v4（小写带连字符）/ random UUID v4 |
| `require(name)` | 依次找 `libs/<name>.lua`、`<name>.lua`、`libs/<name>/init.lua`（`.` 换成 `/`），结果缓存 |

沙箱：标准库只开 `table string math utf8 coroutine os`；`os` 只保留时间函数（`time clock date difftime`）；
没有 `io`、`debug`、`dofile`、`loadfile`。

Sandbox: only `table string math utf8 coroutine os`; `os` keeps only time functions; no `io`,
`debug`, `dofile`, `loadfile`.

### 5.2 host.json

| API | 说明 / notes |
|---|---|
| `encode(v) → str \| nil, err` | 键恰好为 `1..n` 的表 → 数组，其余（含空表）→ 对象；整数值的浮点输出为整数；函数/循环引用报错 |
| `decode(str) → v \| nil, err` | `null` → `nil`；整数保持 Lua 整数（`code == 0` 可直接比较） |

Tables keyed exactly `1..n` encode as arrays, everything else (including `{}`) as objects;
`null` decodes to `nil`; integers stay Lua integers.

### 5.3 host.config

按插件 id 持久化到 `config_dir/<id>.json`，值一律为字符串。Per-plugin string store.

| API | 说明 / notes |
|---|---|
| `get(key) → str \| nil` | 已设置的值，否则 manifest `defaultValue`，否则 `nil` |
| `set(key, value) → true` | `value` 为 `nil` 时等同 remove；布尔/数字转为字符串 |
| `remove(key) → true` | 删除后 `get` 回落到 `defaultValue` |

### 5.4 host.crypto

| API | 说明 / notes |
|---|---|
| `md5 / sha1 / sha256 / sha512(data)` | 返回原始摘要字节 / raw digest bytes |
| `hmacMd5 / hmacSha1 / hmacSha256 / hmacSha512(key, data)` | 原始字节 / raw bytes |
| `base64(data)`（= `base64Encode`）/ `base64Decode(s)` | 标准字母表；解码容忍缺省填充与空白 |
| `base64Url(data)`（= `base64UrlEncode`）/ `base64UrlDecode(s)` | URL 安全字母表、无填充 |
| `hex(data)`（= `hexEncode`）/ `hexDecode(s)` | 小写输出；解码大小写均可 |
| `urlEncode(s)` / `urlDecode(s)` | RFC 3986（空格 → `%20`）；解码时 `+` → 空格 |
| `randomBytes(n = 16)` | 系统 CSPRNG |
| `epochSeconds()` / `epochMillis()` | Unix 时间 |
| `symEncrypt(transformation, key, data, iv?) → bytes \| nil, err` | AES-128/192/256；`AES/CBC/PKCS5Padding`、`AES/ECB/NoPadding`、`AES/CTR/NoPadding` 等 JCE 风格变换串 |
| `symDecrypt(transformation, key, data, iv?)` | 同上 / same |
| `rsaEncrypt(der, data, padding = "PKCS1") → bytes \| nil, err` | `der`：X.509 SubjectPublicKeyInfo 或 PKCS#1；`padding`：`PKCS1`、`OAEP`（SHA-1）、`OAEP-SHA256`（MGF1-SHA256）、`OAEP-SHA256-MGF1SHA1` |
| `ecGenerateKeypair("secp128r1") → {privateHex, publicHex}` | 公钥为 33 字节未压缩格式的 hex |
| `ecdhSharedX("secp128r1", privateHex, peerPublicHex) → hex` | 共享点 X 坐标；对端公钥不在曲线上返回 `nil` |

### 5.5 host.asr

| API | 说明 / notes |
|---|---|
| `emitPartial(text)` | 中间结果（整段替换当前候选）/ interim text, replaces the previous one |
| `emitFinal(text)` | 定稿上屏；一个会话可有多段 / committed text; may occur several times |
| `emitError(msg)` | 报错，不结束会话 / reports an error, does not end the session |
| `emitEnd()` | 会话结束，服务端不会再有结果；幂等 / session done; idempotent |
| `emitReplace(old, new)` | 请求把光标前的 `old` 换成 `new`；可在会话结束后调用 |

### 5.6 host.ws

每个插件实例同时最多一条 WebSocket 连接。One connection per plugin instance.

| API | 说明 / notes |
|---|---|
| `connect(url, headers, callbacks) → bool` | 立即返回，握手在后台进行。已有连接（CONNECTING/OPEN）时**直接复用**且不再触发 `onOpen`，只替换回调表——需要新连接请先 `close()` |
| `sendText(str) → bool` / `sendBinary(bytes) → bool` | 连接中/已打开时入队（握手完成后发出）；否则 `false` |
| `close()` | 关闭；之后这条连接**不再有任何回调**（包括 `onClose`） |
| `getState() → int` | `0` CLOSED、`1` CONNECTING、`2` OPEN、`3` CLOSING |
| `lastError() → str \| nil` | 最近一次连接/收发错误（含握手被拒时的 HTTP 状态码） |
| `awaitBinary(timeoutMs = 5000) → bytes \| nil` | 同步取下一帧二进制（阻塞插件线程）；超时或连接已关返回 `nil` |

`callbacks`：`onOpen()`、`onMessage(text)`、`onBinary(bytes)`（未提供时二进制帧交给 `onMessage`）、
`onError(msg)`、`onClose(code, reason)`。URL 的主机必须在 `network.hosts` 中。

Callbacks: `onOpen()`, `onMessage(text)`, `onBinary(bytes)` (falls back to `onMessage`),
`onError(msg)`, `onClose(code, reason)`. The URL host must be allow-listed.

### 5.7 host.http

| API | 说明 / notes |
|---|---|
| `request(method, url, headers?, body?, timeoutMs = 15000) → resp \| nil` | 同步请求（阻塞插件线程）。`resp = {status, code, ok, body, text, headers}`，`headers` 键为小写；非 2xx 也返回 `resp`；网络错误返回 `nil` |
| `lastError() → str \| nil` | 最近一次失败原因（含白名单拒绝）|
| `stream(url, headers, callbacks, timeoutMs?, method?, body?) → id \| false` | SSE 流，在后台线程读取。`method` 缺省：有 body 为 POST，否则 GET |

`stream` 回调：`onData(payload)` —— 每个 `data:` 行的内容（去掉前缀和一个空格，不含事件名）；
`onDone(raw)` —— 完整原始响应体；`onError(msg)` —— 连接失败或非 2xx（附响应体开头）。
`onDone` / `onError` 二者只触发其一。

Stream callbacks: `onData(payload)` per `data:` line, `onDone(raw)` with the whole body, or
`onError(msg)` (connection failure / non-2xx). Exactly one of `onDone` / `onError` fires.

### 5.8 host.resource / host.fs / host.timer / host.sync

| API | 说明 / notes |
|---|---|
| `host.resource.read(name) → bytes \| nil` | 读 `resources/<name>`；路径不能越出 resources/ |
| `host.resource.readAt(name, offset, len)` | 读一段（适合大模型文件）/ ranged read |
| `host.resource.exists(name) → bool` | |
| `host.fs.read / write(name, data) / exists / remove` | 插件私有可写目录 `config_dir/<id>.files/` |
| `host.timer.setTimeout(ms, fn) → id` | 一次性；回调在插件线程执行 / runs on the plugin thread |
| `host.timer.setInterval(ms, fn) → id` | 周期（最小 10 ms）/ periodic, ≥ 10 ms |
| `host.timer.cancel(id) → bool` | |
| `host.sync.notifyChanged()` | 通知 App 插件数据有变化（`set_sync_notifier`）|

---

## 6. 插件开发指南 / Plugin development guide

1. **状态放模块局部变量**，每次 `start()` 重置；`cancel()` 做清理且必须幂等。
   Keep state in module locals, reset it in `start()`; `cancel()` cleans up idempotently.
2. **音频先缓冲**：`start()` 返回后宿主立刻开始送音频，此时连接多半还在握手。在 `onOpen` 里再把缓冲发出。
   Buffer audio until `onOpen` — audio starts flowing right after `start()` returns.
3. **一定要 `emitEnd()`**：最终结果出来、连接关闭或出错时都调用；否则用户松手后要等 6 秒超时。
   Always call `emitEnd()` (final result, close, or error), or the user waits for the 6 s timeout.
4. **需要新连接时先 `host.ws.close()`**，否则 `connect` 会复用旧连接且不触发 `onOpen`。
   Call `host.ws.close()` before `connect` when you need a fresh connection.
5. **在 manifest 声明所有域名**；未声明的请求直接失败，原因见 `lastError()`。
   Declare every host in the manifest; others fail and `lastError()` says why.
6. 打包：在插件目录执行 `zip -r ../myplugin.xipk manifest.yaml main.lua resources libs`。
   Pack with `zip -r ../myplugin.xipk manifest.yaml main.lua resources libs`.
7. 桌面联调：`cargo run -p weave-plugin --example speechtest -- <插件目录或.xipk> <16k单声道.wav> [key=value ...]`，
   按 40 ms 实时节奏喂音频并打印所有事件（额外参数写入插件配置）。
   Desktop testing: the `speechtest` example feeds a 16 kHz mono WAV in real time and prints events.

### 示例：回声语音插件 / Example: echo speech plugin

不联网，把收到的音频时长回显成文字：每满一秒出一次中间结果，`stop()` 时定稿。
（本示例由 `tests/host.rs` 的 `doc_example_plugin` 直接从本文档加载测试。）

Offline; echoes the audio duration as text — a partial every full second, final on `stop()`.
(`doc_example_plugin` in `tests/host.rs` loads and tests this example straight from this file.)

`manifest.yaml`:

```yaml
id: org.example.echo
name: 回声 Echo
description: 把音频时长回显成文字 / echoes the audio length as text
version: 1.0.0
type: speech
configSchema:
  - key: prefix
    type: text
    label: 前缀 / Prefix
    defaultValue: "echo"
```

`main.lua`:

```lua
local plugin = {}

local bytes = 0          -- 本次会话收到的 PCM 字节数 / PCM bytes this session
local seconds = 0        -- 已报告的整秒数 / whole seconds reported so far

local function prefix()
    return host.config.get("prefix") or "echo"
end

function plugin.isConfigured()
    return true
end

function plugin.start()
    bytes, seconds = 0, 0
    host.log("echo: start")
    return true
end

-- 16 kHz × 16 bit × 单声道 = 每秒 32000 字节 / 32000 bytes per second
function plugin.processAudioChunk(pcm)
    bytes = bytes + #pcm
    local s = bytes // 32000
    if s > seconds then
        seconds = s
        host.asr.emitPartial(string.format("%s %d s", prefix(), s))
    end
end

function plugin.stop()
    host.asr.emitFinal(string.format("%s %.1f s", prefix(), bytes / 32000))
    host.asr.emitEnd()
end

function plugin.cancel()
    bytes, seconds = 0, 0
end

return plugin
```
