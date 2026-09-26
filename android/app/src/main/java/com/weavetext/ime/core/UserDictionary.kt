package com.weavetext.ime.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 用户词库管理（设置 App「词库」页，03 §8）。全部为挂起函数，实现负责切到后台线程。
 * User dictionary management for the settings app. All calls suspend; implementations move off
 * the main thread themselves.
 */
interface UserDictionary {
    data class ImportResult(val imported: Int, val skipped: Int)

    suspend fun count(): Int
    /** 最近使用在前，[query] 可按文字或拼音过滤。 Most recent first, filtered by text or pinyin. */
    suspend fun list(query: String = "", offset: Int = 0, limit: Int = 200): List<UserWord>
    suspend fun delete(word: UserWord): Boolean
    /** 每行 `词<TAB>拼音<TAB>次数`。 One `text<TAB>pinyin<TAB>count` per line. */
    suspend fun exportText(): String
    suspend fun importText(text: String): ImportResult
    /** @param pinyin 空格分隔的音节。 Space separated syllables. */
    suspend fun add(text: String, pinyin: String): Boolean
    suspend fun clear(): Boolean

    companion object {
        fun of(ctx: Context): UserDictionary = NativeUserDictionary(ctx.applicationContext)
    }
}

/**
 * 基于 [NativeEngine] 已有方法的实现；内核实例在后台线程经 [EngineHolder.getBlocking] 获取（与输入法共用）。
 * Implementation on top of [NativeEngine]; the shared engine is obtained off the main thread.
 */
class NativeUserDictionary(private val ctx: Context) : UserDictionary {
    private suspend fun <T> withEngine(fallback: T, f: (NativeEngine) -> T): T = withContext(Dispatchers.IO) {
        val e = EngineHolder.getBlocking(ctx) ?: return@withContext fallback
        runCatching { f(e) }.getOrDefault(fallback)
    }

    override suspend fun count() = withEngine(0) { it.userWordCount() }

    override suspend fun list(query: String, offset: Int, limit: Int) =
        withEngine(emptyList()) { it.userWords(query, offset, limit) }

    override suspend fun delete(word: UserWord) = withEngine(false) { it.deleteUserWord(word.pinyin, word.text) }

    override suspend fun exportText() = withEngine("") { it.exportUserWords() }

    override suspend fun importText(text: String): UserDictionary.ImportResult {
        val lines = text.lineSequence().count { it.isNotBlank() && !it.startsWith("#") }
        val n = withEngine(0) { it.importUserWords(text) }
        return UserDictionary.ImportResult(n, (lines - n).coerceAtLeast(0))
    }

    override suspend fun add(text: String, pinyin: String): Boolean {
        val py = pinyin.trim().split(Regex("[\\s']+")).filter { it.isNotEmpty() }.joinToString(" ")
        if (text.isBlank() || py.isEmpty()) return false
        return withEngine(0) { it.importUserWords("${text.trim()}\t$py\t1") } > 0
    }

    override suspend fun clear() = withEngine(false) { it.clearUserWords() }
}
