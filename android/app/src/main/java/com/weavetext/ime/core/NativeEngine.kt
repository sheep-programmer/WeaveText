package com.weavetext.ime.core

import java.nio.ByteBuffer

/**
 * 输入控制器用到的内核接口（测试里可用假实现替换）。 The engine surface the input controller uses (fakeable in tests).
 */
interface KeyEngine {
    fun setSchema(key: String): Boolean
    fun setOption(key: String, value: Boolean): Boolean
    fun inputChar(codePoint: Int): Boolean
    /**
     * 带触点信息输入一个字母：[near] 为交界另一侧的字母，[closeness] 1 = 正压在交界上。全拼用来纠正误触。
     * A letter with touch info: [near] is the letter across the nearby border, [closeness] 1 = right on it.
     */
    fun inputKey(codePoint: Int, near: Int, closeness: Float): Boolean = inputChar(codePoint)
    fun backspace(): Boolean
    fun select(index: Int): Boolean
    fun selectPinyin(index: Int): Boolean
    fun forget(index: Int): Boolean
    fun commitFirst()
    fun commitRaw()
    fun clear()
    fun flush()
    fun isComposing(): Boolean
    fun setLearning(on: Boolean)
    fun setContext(prevWord: String?)
    fun snapshot(): EngineSnapshot
    fun candidates(offset: Int, limit: Int): List<Candidate>
    /**
     * 手写（方案 "hand"）：当前这个字的全部笔画，每笔为 x0,y0,x1,y1… 的点列（y 向下）；返回是否有候选。
     * Handwriting (schema "hand"): all strokes of the current char, each x0,y0,x1,y1…; y points down.
     */
    fun handInput(strokes: List<FloatArray>): Boolean = false
    /** 本地时区相对 UTC 的分钟数（rq/sj 日期时间候选）。 Local UTC offset in minutes, for date/time candidates. */
    fun setUtcOffset(minutes: Int) {}
    /** 计算算式（如 128*4），不是算式时返回 null。 Evaluate an expression like 128*4; null if it isn't one. */
    fun evaluate(expr: String): String? = null
    /** 输入法自己往编辑器写了字（标点、空格）：退格不再撤销学习。 The IME wrote text itself; no learning undo after it. */
    fun breakChain() {}
}

/**
 * Rust 内核的薄封装（JNI）。只能在同一线程（IME 主线程）上调用。
 * Thin JNI wrapper over the Rust engine. Call from a single thread (the IME main thread).
 */
class NativeEngine private constructor(private var handle: Long) : KeyEngine, AutoCloseable {

    val isValid: Boolean get() = handle != 0L

    /** 切换输入方案："pinyin"、"shuangpin:xiaohe"、"t9"、"t14"、"hand"、"wubi86"、"english"。 */
    override fun setSchema(key: String): Boolean = nativeSetSchema(handle, key)

    /** 设置选项，见 weave-ffi `nativeSetOption`。 */
    override fun setOption(key: String, value: Boolean): Boolean = nativeSetOption(handle, key, value.toString())

    /** 输入一个字符；false 表示引擎不处理，调用方应直接上屏。 */
    override fun inputChar(codePoint: Int): Boolean = nativeInputChar(handle, codePoint)
    override fun inputKey(codePoint: Int, near: Int, closeness: Float): Boolean = nativeInputKey(handle, codePoint, near, closeness)
    override fun setUtcOffset(minutes: Int) = nativeSetUtcOffset(handle, minutes)

    /** 载入（或替换）专业词库文件。 Load (or replace) a domain dictionary file. */
    fun loadPack(id: String, path: String): Boolean = nativeLoadPack(handle, id, path)
    fun unloadPack(id: String): Boolean = nativeUnloadPack(handle, id)
    /** 云端热词：验签后作为扩展词库挂上，返回词数，失败 -1。 Hot words: verified and attached; word count or -1. */
    fun loadHotwords(tsvPath: String, sigPath: String): Int = nativeLoadHotwords(handle, tsvPath, sigPath)
    override fun evaluate(expr: String): String? = nativeEval(expr)
    override fun breakChain() = nativeBreakChain(handle)

    /** 退格；false 表示没有组合内容，调用方应删除编辑器里的字符。 */
    override fun backspace(): Boolean = nativeBackspace(handle)
    override fun select(index: Int): Boolean = nativeSelect(handle, index)
    override fun selectPinyin(index: Int): Boolean = nativeSelectPinyin(handle, index)
    override fun forget(index: Int): Boolean = nativeForget(handle, index)
    override fun commitFirst() { nativeCommitFirst(handle) }

    override fun handInput(strokes: List<FloatArray>): Boolean {
        val lens = IntArray(strokes.size) { strokes[it].size / 2 }
        val xy = FloatArray(lens.sum() * 2)
        var at = 0
        for (s in strokes) {
            val n = s.size / 2 * 2
            s.copyInto(xy, at, 0, n)
            at += n
        }
        return nativeHandInput(handle, xy, lens)
    }
    override fun commitRaw() { nativeCommitRaw(handle) }
    override fun clear() { nativeClear(handle) }
    override fun flush() { nativeFlush(handle) }
    override fun isComposing(): Boolean = nativeIsComposing(handle)
    override fun setLearning(on: Boolean) = nativeSetLearning(handle, on)
    override fun setContext(prevWord: String?) = nativeSetContext(handle, prevWord)

    /** 读取快照；其中的 commit 文本读取后即被清空。 Snapshot; commit text is drained. */
    override fun snapshot(): EngineSnapshot = EngineSnapshot.decode(nativeSnapshot(handle))

    override fun candidates(offset: Int, limit: Int): List<Candidate> {
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
        @JvmStatic private external fun nativeInputKey(h: Long, codePoint: Int, near: Int, closeness: Float): Boolean
        @JvmStatic private external fun nativeSetUtcOffset(h: Long, minutes: Int)
        @JvmStatic private external fun nativeLoadPack(h: Long, id: String, path: String): Boolean
        @JvmStatic private external fun nativeUnloadPack(h: Long, id: String): Boolean
        @JvmStatic private external fun nativeLoadHotwords(h: Long, tsv: String, sig: String): Int
        @JvmStatic private external fun nativeEval(expr: String): String?
        @JvmStatic private external fun nativeBreakChain(h: Long)
        @JvmStatic private external fun nativeBackspace(h: Long): Boolean
        @JvmStatic private external fun nativeSelect(h: Long, index: Int): Boolean
        @JvmStatic private external fun nativeSelectPinyin(h: Long, index: Int): Boolean
        @JvmStatic private external fun nativeForget(h: Long, index: Int): Boolean
        @JvmStatic private external fun nativeCommitFirst(h: Long): Boolean
        @JvmStatic private external fun nativeHandInput(h: Long, xy: FloatArray, lens: IntArray): Boolean
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

/**
 * 预编辑里一处自动纠错改动，[start]..[end] 是 preedit 的字符下标（UTF-16，左闭右开）。
 * DELETE 的 start == end，[removed] 是去掉的多打字母。
 * One auto-correction change in the preedit; [start]..[end] index the preedit (UTF-16, half-open). For DELETE
 * start == end and [removed] holds the dropped extra letter.
 */
data class PreeditMark(val start: Int, val end: Int, val kind: Kind, val removed: String = "") {
    /** 与内核的编号一致。 Same order as the engine's codes. */
    enum class Kind { SWAP, INSERT, REPLACE, DELETE }
}

/** 一次操作后的引擎状态。 Engine state after an operation. */
data class EngineSnapshot(
    val commit: String,
    val preedit: String,
    val composing: Boolean,
    val candidates: List<Candidate>,
    val totalCandidates: Int,
    val pinyinOptions: List<String>,
    val schema: String,
    val marks: List<PreeditMark> = emptyList(),
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
            val marks = if (b.remaining() >= 4) decodeMarks(b, preedit) else emptyList()
            return EngineSnapshot(commit, preedit, flags and 1 != 0, cands, total, opts, schema, marks)
        }

        /** 内核按 Unicode 标量计位置，这里换成 UTF-16 下标；越界的丢掉。 Scalar positions to UTF-16 indices; drops bad ones. */
        private fun decodeMarks(b: ByteBuffer, preedit: String): List<PreeditMark> {
            val n = b.int
            val scalars = preedit.codePointCount(0, preedit.length)
            val out = ArrayList<PreeditMark>(n)
            repeat(n) {
                val start = b.int
                val end = b.int
                val kind = PreeditMark.Kind.entries.getOrNull(b.get().toInt())
                val removed = b.str()
                if (kind != null && start in 0..end && end <= scalars) {
                    out += PreeditMark(preedit.offsetByCodePoints(0, start), preedit.offsetByCodePoints(0, end), kind, removed)
                }
            }
            return out
        }
    }
}
