package com.weavetext.ime.captions

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.weavetext.ime.voice.local.LocalAsrEngine
import com.weavetext.ime.voice.local.TwoPassListener
import java.io.ByteArrayOutputStream

/** 字幕事件：临时文字、一句定稿（带说话人编号），或错误。 Caption events: partial text, a final line, or an error. */
sealed class CaptionEvent {
    data class Partial(val text: String) : CaptionEvent()
    data class Line(val speaker: Int, val text: String) : CaptionEvent()
    data class Error(val message: String) : CaptionEvent()
}

/**
 * 实时字幕：麦克风（16 kHz 单声道）→ 离线语音识别会话 → 定稿句子。每句话的说话人，用上一次定稿之后的音频算嵌入后归类。
 * 这是近似做法：句子切分由识别会话决定，嵌入取两次定稿之间的音频，短于半秒时沿用上一位说话人。
 *
 * Live captions: microphone (16 kHz mono) → offline recognizer session → final sentences. A sentence's speaker comes
 * from an embedding of the audio since the previous final. This is approximate: the recognizer decides sentence breaks,
 * and a stretch shorter than half a second keeps the previous speaker.
 */
class CaptionSession(private val ctx: Context, private val sink: (CaptionEvent) -> Unit) {
    private val tracker = SpeakerTracker()
    private val embedder = SpeakerRuntime.embedder(ctx, CaptionsPlugin.model(ctx))
    private val pending = ByteArrayOutputStream()
    private var lastSpeaker = 1
    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var session: LocalAsrEngine.Session? = null
    private var thread: Thread? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        if (!hasPermission()) {
            sink(CaptionEvent.Error("需要先允许麦克风权限"))
            return false
        }
        val bufferBytes = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(3_200)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes * 2)
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            sink(CaptionEvent.Error("无法打开麦克风"))
            return false
        }
        val asr = LocalAsrEngine(ctx)
        session = asr.Session(
            object : TwoPassListener {
                override fun onPartial(text: String) = sink(CaptionEvent.Partial(text))
                override fun onFinal(text: String) = sink(CaptionEvent.Line(speakerFor(), text))
            },
            onEnd = { },
            onError = { msg -> sink(CaptionEvent.Error(msg)); stop() },
        )
        recorder = rec
        running = true
        rec.startRecording()
        thread = Thread({
            val buf = ByteArray(bufferBytes)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                synchronized(pending) { pending.write(buf, 0, n) }
                session?.feed(buf, n)
            }
        }, "weave-captions").apply { isDaemon = true; start() }
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
        session?.stop()
        session = null
    }

    /** 上一次定稿之后的音频的说话人。Speaker of the audio since the previous final. */
    private fun speakerFor(): Int {
        val pcm = synchronized(pending) { pending.toByteArray().also { pending.reset() } }
        val samples = FloatArray(pcm.size / 2) { i ->
            val lo = pcm[2 * i].toInt() and 0xff
            val hi = pcm[2 * i + 1].toInt()
            ((hi shl 8) or lo).toShort() / 32768f
        }
        val emb = embedder ?: return 1
        if (samples.size < RATE / 2) return lastSpeaker
        return runCatching { tracker.assign(emb(samples)) }.getOrElse { lastSpeaker }.also { lastSpeaker = it }
    }

    companion object {
        const val RATE = 16_000
    }
}
