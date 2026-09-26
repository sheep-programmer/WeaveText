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
