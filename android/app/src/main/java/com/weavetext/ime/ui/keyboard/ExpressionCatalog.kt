package com.weavetext.ime.ui.keyboard

import android.content.Context
import android.graphics.Paint
import org.json.JSONObject

/** Offline Unicode/CLDR names and curated kaomoji, shared with the Mac build. */
class ExpressionCatalog private constructor(json: JSONObject) {
    data class Item(val text: String, val name: String, val group: String)
    private fun read(json: JSONObject, key: String): List<Item> {
        val array=json.getJSONArray(key)
        return (0 until array.length()).map {i->val o=array.getJSONObject(i);Item(o.getString("text"),o.getString("name"),o.getString("group"))}
    }
    val emoji=read(json,"emoji")
    /** Filter once for this system font so older phones never show missing-glyph squares. */
    val supportedEmoji:List<Item> by lazy {val paint=Paint(Paint.ANTI_ALIAS_FLAG);emoji.filter{paint.hasGlyph(it.text)}}
    val kaomoji=read(json,"kaomoji")
    private val emojiKeys=emoji.map{normalize(it.text)}.toSet()
    private val supportedKeys by lazy{supportedEmoji.map{normalize(it.text)}.toSet()}
    fun displayable(text:String)=normalize(text) !in emojiKeys || (normalize(text) in supportedKeys && Paint().hasGlyph(text))
    val skinToneBases=json.getJSONArray("skinToneBases").let {a->(0 until a.length()).map{a.getString(it)}.toSet()}
    private val items=(emoji+kaomoji).associateBy{normalize(it.text)}
    fun entry(text: String): Item? = items[normalize(text)]
    fun name(text: String): String? {
        val item=entry(text) ?: return null
        val tone=SymbolData.SKIN_TONES.indexOfFirst {it.isNotEmpty() && text.contains(it)}
        return item.name + if(tone>0) " · "+TONE_NAMES[tone] else ""
    }
    companion object {
        private val TONE_NAMES=listOf("默认肤色","较浅肤色","中等偏浅肤色","中等肤色","中等偏深肤色","较深肤色")
        private fun normalize(text: String): String = buildString {
            text.codePoints().forEach {cp->if(cp!=0xfe0f && cp !in 0x1f3fb..0x1f3ff) appendCodePoint(cp)}
        }
        @Volatile private var cached: ExpressionCatalog? = null
        fun load(ctx: Context): ExpressionCatalog? {
            cached?.let{return it}
            return synchronized(this) {
                cached ?: runCatching {ctx.assets.open("expressions/catalog.json").use {ExpressionCatalog(JSONObject(it.bufferedReader().readText()))}}.getOrNull()?.also {cached=it}
            }
        }
    }
}
