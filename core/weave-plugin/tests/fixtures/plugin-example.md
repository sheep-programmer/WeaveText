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
