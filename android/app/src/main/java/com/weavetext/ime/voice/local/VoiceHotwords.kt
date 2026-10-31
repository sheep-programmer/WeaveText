package com.weavetext.ime.voice.local

import android.content.Context
import com.weavetext.ime.core.EngineHolder
import com.weavetext.ime.models.ModelLocation
import com.weavetext.ime.models.ModelSpec
import org.json.JSONObject
import java.io.File

/** Biasing stays local and is enabled explicitly. Every tokenizer must match its acoustic model. */
internal object VoiceHotwords {
    const val ENABLED="personal_hotwords"
    const val TEXT="custom_hotwords"
    fun prepare(ctx:Context,spec:ModelSpec?,loc:ModelLocation?):HotwordConfig? {
        if(spec?.arch!="zipformer-transducer" || loc==null)return null
        val prefs=ctx.getSharedPreferences(LocalAsrChoice.PREFS,Context.MODE_PRIVATE)
        if(!prefs.getBoolean(ENABLED,false))return null
        val privateDir=File(ctx.filesDir,"voice-vocabulary").apply {mkdirs()}
        // 词表与声学模型一一对应：X-ASR 是 sentencepiece BPE，旧双语模型是另一套 BPE。
        // The vocabulary must match the acoustic model: X-ASR uses its own sentencepiece BPE, the old
        // bilingual model another.
        val vocabulary=when(spec.id) {
            "asr-x-asr-960"->File(privateDir,"x-asr.vocab").also { dest->
                if(!dest.isFile)ctx.assets.open("models/vocab-x-asr.txt").use {input->dest.outputStream().use {input.copyTo(it)}}
            }
            else->null
        }
        val personal=runCatching {JSONObject(EngineHolder.peek()?.features("""{"op":"voiceWords"}""") ?: "{}").optJSONArray("words")}.getOrNull()
        val words=buildList {
            addAll(prefs.getString(TEXT,"").orEmpty().split('\n',',','，').map {it.trim()})
            if(personal!=null)for(i in 0 until personal.length())add(personal.optString(i))
        }.filter {word->word.length in 2..80 && word.all {c->c.isLetterOrDigit() || c==' ' || c=='\''}}
            .filter {word->vocabulary!=null || word.all {it.code in 0x3400..0x9fff}}
            // 旧双语模型的词表全大写，英文热词要大写才能命中；X-ASR 词表保留原大小写。
            // The old bilingual vocab is all-caps so English hotwords must be uppercased; the X-ASR
            // vocabulary keeps natural casing.
            .map {if(vocabulary==null) it.uppercase(java.util.Locale.ROOT) else it}.distinct().take(256).sorted()
        if(words.isEmpty()){File(privateDir,"${spec.id}-hotwords.txt").delete();return null}
        val text=words.joinToString("\n")+"\n"
        val file=File(privateDir,"${spec.id}-hotwords.txt")
        if(!file.isFile || file.readText()!=text)file.writeText(text)
        return HotwordConfig(file.path,if(vocabulary==null) "cjkchar" else "cjkchar+bpe",vocabulary?.path.orEmpty(),"${spec.id}:${vocabulary?.path}:${words.isNotEmpty()}")
    }
}
