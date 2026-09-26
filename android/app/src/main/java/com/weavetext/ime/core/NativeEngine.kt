package com.weavetext.ime.core

import java.nio.ByteBuffer

/**
 * Rust 内核的薄封装（JNI）。只能在同一线程（IME 主线程）上调用。
 * Thin JNI wrapper over the Rust engine. Call from a single thread (the IME main thread).
 */
class NativeEngine private constructor(private var handle: Long) : AutoCloseable {

    val isValid: Boolean get() = handle != 0L

    /** 切换输入方案："pinyin"、"shuangpin:xiaohe"、"t9"、"wubi86"、"english"。 */
    fun setSchema(key: String): Boolean = nativeSetSchema(handle, key)

    /** 设置选项，见 weave-ffi `nativeSetOption`。 */
    fun setOption(key: String, value: Boolean): Boolean = nativeSetOption(handle, key, value.toString())

    /** 输入一个字符；false 表示引擎不处理，调用方应直接上屏。 */
    fun inputChar(codePoint: Int): Boolean = nativeInputChar(handle, codePoint)

    /** 退格；false 表示没有组合内容，调用方应删除编辑器里的字符。 */
    fun backspace(): Boolean = nativeBackspace(handle)
    fun select(index: Int): Boolean = nativeSelect(handle, index)
    fun selectPinyin(index: Int): Boolean = nativeSelectPinyin(handle, index)
    fun forget(index: Int): Boolean = nativeForget(handle, index)
    fun commitFirst() { nativeCommitFirst(handle) }
    fun commitRaw() { nativeCommitRaw(handle) }
    fun clear() { nativeClear(handle) }
    fun flush() { nativeFlush(handle) }
    fun isComposing(): Boolean = nativeIsComposing(handle)
    fun setLearning(on: Boolean) = nativeSetLearning(handle, on)
    fun setContext(prevWord: String?) = nativeSetContext(handle, prevWord)

    /** 读取快照；其中的 commit 文本读取后即被清空。 Snapshot; commit text is drained. */
    fun snapshot(): EngineSnapshot = EngineSnapshot.decode(nativeSnapshot(handle))

    fun candidates(offset: Int, limit: Int): List<Candidate> {
        val bytes = nativeCandidates(handle, offset, limit) ?: return emptyList()
        return EngineSnapshot.decodeCandidates(ByteBuffer.wrap(bytes))
    }

    // ------------------------------------------------------------ user dictionary

    /** 用户词（最近使用在前），可按文字/拼音过滤。 User words, most recent first. */
    fun userWords(query: String = "", offset: Int = 0, limit: Int = 200): List<UserWord> {
        val bytes = nativeUserWords(handle, query, offset, limit) ?: return emptyList()
        val b = ByteBuffer.wrap(bytes)
        val n = b.int
        return List(n) { UserWord(b.utf8(), b.utf8(), b.int) }
    }

    fun userWordCount(): Int = nativeUserWordCount(handle)
    /** @param pinyin 空格分隔的音节，如 "zhi wen"。 Space separated syllables. */
    fun deleteUserWord(pinyin: String, text: String): Boolean = nativeDeleteUserWord(handle, pinyin, text)
    /** 每行 `词<TAB>拼音<TAB>次数`。 One `text<TAB>pinyin<TAB>count` per line. */
    fun exportUserWords(): String = nativeExportUserWords(handle).orEmpty()
    /** 返回导入条数。 Returns the number of imported rows. */
    fun importUserWords(text: String): Int = nativeImportUserWords(handle, text)
    fun clearUserWords(): Boolean = nativeClearUserWords(handle)

    /** 清空解压缓存。 Drop decode caches. */
    fun trim() = nativeTrim(handle)

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    companion object {
        init {
            System.loadLibrary("weave")
        }

        /** @param dataDir 系统词库目录；@param userDir 用户数据目录。 */
        fun create(dataDir: String, userDir: String): NativeEngine? {
            val h = nativeCreate(dataDir, userDir)
            return if (h == 0L) null else NativeEngine(h)
        }

        /**
         * 从 APK 内的资源区间创建。 Create from asset ranges inside the APK.
         * @param spec `key=path@offset+len;…`；@param cacheKb 每个分块压缩文件的缓存预算。
         */
        fun createFromSpec(spec: String, userDir: String, cacheKb: Int): NativeEngine? {
            val h = nativeCreateFromSpec(spec, userDir, cacheKb)
            return if (h == 0L) null else NativeEngine(h)
        }

        @JvmStatic private external fun nativeCreate(dataDir: String, userDir: String): Long
        @JvmStatic private external fun nativeCreateFromSpec(spec: String, userDir: String, cacheKb: Int): Long
        @JvmStatic private external fun nativeTrim(h: Long)
        @JvmStatic private external fun nativeDestroy(h: Long)
        @JvmStatic private external fun nativeSetSchema(h: Long, key: String): Boolean
        @JvmStatic private external fun nativeSetOption(h: Long, key: String, value: String): Boolean
        @JvmStatic private external fun nativeInputChar(h: Long, codePoint: Int): Boolean
        @JvmStatic private external fun nativeBackspace(h: Long): Boolean
        @JvmStatic private external fun nativeSelect(h: Long, index: Int): Boolean
        @JvmStatic private external fun nativeSelectPinyin(h: Long, index: Int): Boolean
        @JvmStatic private external fun nativeForget(h: Long, index: Int): Boolean
        @JvmStatic private external fun nativeCommitFirst(h: Long): Boolean
        @JvmStatic private external fun nativeCommitRaw(h: Long): Boolean
        @JvmStatic private external fun nativeClear(h: Long): Boolean
        @JvmStatic private external fun nativeFlush(h: Long): Boolean
        @JvmStatic private external fun nativeIsComposing(h: Long): Boolean
        @JvmStatic private external fun nativeSetLearning(h: Long, on: Boolean)
        @JvmStatic private external fun nativeSetContext(h: Long, prev: String?)
        @JvmStatic private external fun nativeSnapshot(h: Long): ByteArray?
        @JvmStatic private external fun nativeCandidates(h: Long, offset: Int, limit: Int): ByteArray?
        @JvmStatic private external fun nativeUserWords(h: Long, query: String, offset: Int, limit: Int): ByteArray?
        @JvmStatic private external fun nativeUserWordCount(h: Long): Int
        @JvmStatic private external fun nativeDeleteUserWord(h: Long, pinyin: String, text: String): Boolean
        @JvmStatic private external fun nativeExportUserWords(h: Long): String?
        @JvmStatic private external fun nativeImportUserWords(h: Long, text: String): Int
        @JvmStatic private external fun nativeClearUserWords(h: Long): Boolean

        private fun ByteBuffer.utf8(): String {
            val bytes = ByteArray(int)
            get(bytes)
            return String(bytes, Charsets.UTF_8)
        }
    }
}

/** 用户词。 A learned user word. */
data class UserWord(val text: String, val pinyin: String, val count: Int)

/** 候选项。 A candidate. */
data class Candidate(val text: String, val comment: String, val isUser: Boolean)

/** 一次操作后的引擎状态。 Engine state after an operation. */
data class EngineSnapshot(
    val commit: String,
    val preedit: String,
    val composing: Boolean,
    val candidates: List<Candidate>,
    val totalCandidates: Int,
    val pinyinOptions: List<String>,
    val schema: String,
) {
    companion object {
        val EMPTY = EngineSnapshot("", "", false, emptyList(), 0, emptyList(), "pinyin")

        private fun ByteBuffer.str(): String {
            val len = int
            val bytes = ByteArray(len)
            get(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        internal fun decodeCandidates(b: ByteBuffer): List<Candidate> {
            val n = b.int
            return List(n) { Candidate(b.str(), b.str(), b.get().toInt() != 0) }
        }

        fun decode(bytes: ByteArray?): EngineSnapshot {
            if (bytes == null || bytes.isEmpty()) return EMPTY
            val b = ByteBuffer.wrap(bytes)
            b.get() // version
            val flags = b.get().toInt()
            val commit = b.str()
            val preedit = b.str()
            val total = b.int
            val cands = decodeCandidates(b)
            val m = b.int
            val opts = List(m) { b.str() }
            val schema = b.str()
            return EngineSnapshot(commit, preedit, flags and 1 != 0, cands, total, opts, schema)
        }
    }
}
