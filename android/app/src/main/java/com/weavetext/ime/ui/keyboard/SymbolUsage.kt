package com.weavetext.ime.ui.keyboard

import android.content.SharedPreferences
import com.weavetext.ime.settings.WeavePrefs
import org.json.JSONObject

/** Recent explicit choices lead; longer-term use supplies the remaining common symbols. */
object SymbolUsage {
    private const val COUNTS="symbol_usage_counts"
    private fun counts(p:SharedPreferences)=runCatching{JSONObject(p.getString(COUNTS,"{}") ?: "{}")} .getOrDefault(JSONObject())
    fun recent(p:SharedPreferences)=p.getString(WeavePrefs.SYMBOL_RECENT,"").orEmpty().split('\u0001').filter{it.isNotEmpty()}
    fun order(p:SharedPreferences):List<String> {
        val recent=recent(p)
        val counts=counts(p)
        val frequent=counts.keys().asSequence().toList().sortedByDescending{counts.optInt(it)}
        return (recent.take(6)+frequent+recent).distinct().take(24)
    }
    fun record(p:SharedPreferences,text:String) {
        if(text.isEmpty() || text.length>256 || text.contains('\u0001'))return
        val recent=(listOf(text)+recent(p).filter{it!=text}).take(24)
        val counts=counts(p)
        counts.put(text,counts.optInt(text).coerceIn(0,999_999)+1)
        if(counts.length()>256) {
            val keep=(recent+counts.keys().asSequence().toList().sortedByDescending{counts.optInt(it)}).distinct().take(256).toSet()
            counts.keys().asSequence().toList().filter{it !in keep}.forEach(counts::remove)
        }
        p.edit().putString(WeavePrefs.SYMBOL_RECENT,recent.joinToString("\u0001")).putString(COUNTS,counts.toString()).apply()
    }
}
