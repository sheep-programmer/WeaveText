package com.weavetext.ime.core

import java.nio.ByteBuffer

/**
 * 输入控制器用到的内核接口（测试里可用假实现替换）。 The engine surface the input controller uses (fakeable in tests).
 */
interface KeyEngine {
    fun features(command: String): String = "{}"
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
    /** 以词取字：取第 [index] 个候选的首字或末字（[last]）。 Take the first or the [last] char of a candidate. */
    fun selectChar(index: Int, last: Boolean): Boolean = false
    /** 组合串光标左右移动若干按键；false 表示这里不能移动。 Move the preedit caret; false when it can't. */
    fun moveCaret(delta: Int): Boolean = false
    /** 把组合串光标放到显示串第 [index] 个字符附近。 Put the preedit caret near display char [index]. */
    fun setCaret(index: Int): Boolean = false
    /** 删除组合串光标后的按键（Delete 键）。 Delete the key after the preedit caret. */
    fun deleteForward(): Boolean = false
    /** 组合串光标按音节移动（Ctrl+方向键）。 Move the preedit caret by syllables (Ctrl+arrows). */
    fun moveCaretSyllable(delta: Int): Boolean = false
    /** 删掉光标前（[forward] 时为光标后）的一个音节。 Delete the syllable before (or, [forward], after) the caret. */
    fun deleteSyllable(forward: Boolean): Boolean = false
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
    /** 各字最后一笔之后的下标（递增）；空数组保留旧分组行为。 Exclusive stroke ends per character; empty keeps legacy grouping. */
    fun handInput(strokes: List<FloatArray>, groupEnds: IntArray): Boolean = handInput(strokes)
    /**
     * 只识别、不改引擎状态，可在任何线程上调用；返回候选字的码位，不支持时为 null（调用方改用 [handInput]）。
     * Recognise only, without touching the engine state; safe on any thread. Returns candidate code points, or
     * null when unsupported (the caller then uses [handInput]).
     */
    fun handRecognize(strokes: List<FloatArray>): IntArray? = null
    fun handRecognize(strokes: List<FloatArray>, groupEnds: IntArray): IntArray? = handRecognize(strokes)
    /** 交回 [handRecognize] 的结果（主线程）。 Hand back a [handRecognize] result, on the main thread. */
    fun handApply(strokes: List<FloatArray>, cands: IntArray): Boolean = handInput(strokes)
    fun handApply(strokes: List<FloatArray>, groupEnds: IntArray, cands: IntArray): Boolean = handApply(strokes, cands)
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
    override fun features(command: String): String = nativeFeatures(handle, command).orEmpty()

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
    override fun selectChar(index: Int, last: Boolean): Boolean = nativeSelectChar(handle, index, last)
    override fun moveCaret(delta: Int): Boolean = nativeMoveCaret(handle, delta)
    override fun setCaret(index: Int): Boolean = index >= 0 && nativeSetCaret(handle, index)
    override fun deleteForward(): Boolean = nativeDeleteForward(handle)
    override fun moveCaretSyllable(delta: Int): Boolean = nativeMoveCaretSyllable(handle, delta)
    override fun deleteSyllable(forward: Boolean): Boolean = nativeDeleteSyllable(handle, forward)
    override fun commitFirst() { nativeCommitFirst(handle) }

    override fun handInput(strokes: List<FloatArray>): Boolean {
        val (xy, lens) = pack(strokes)
        return nativeHandInput(handle, xy, lens)
    }

    override fun handInput(strokes: List<FloatArray>, groupEnds: IntArray): Boolean {
        if (groupEnds.isEmpty()) return handInput(strokes)
        val (xy, lens) = pack(strokes)
        return nativeHandInputGrouped(handle, xy, lens, groupEnds)
    }

    /** 手写模型的独立引用（首次用时向引擎要一次）；0 = 没有模型。 Models reference, fetched once; 0 = none. */
    @Volatile private var handModels = -1L

    override fun handRecognize(strokes: List<FloatArray>): IntArray? = recognizeHand(strokes, intArrayOf())

    override fun handRecognize(strokes: List<FloatArray>, groupEnds: IntArray): IntArray? = recognizeHand(strokes, groupEnds)

    private fun recognizeHand(strokes: List<FloatArray>, groupEnds: IntArray): IntArray? {
        var m = handModels
        if (m == -1L) {
            // 首次在主线程之外取也安全：引擎调用自带锁。 Safe off the main thread: engine calls take the lock.
            m = nativeHandModels(handle)
            handModels = m
        }
        if (m == 0L) return null
        val (xy, lens) = pack(strokes)
        return if (groupEnds.isEmpty()) nativeHandRecognize(m, xy, lens)
        else nativeHandRecognizeGrouped(m, xy, lens, groupEnds)
    }

    override fun handApply(strokes: List<FloatArray>, cands: IntArray): Boolean {
        val (xy, lens) = pack(strokes)
        return nativeHandApply(handle, xy, lens, cands)
    }

    override fun handApply(strokes: List<FloatArray>, groupEnds: IntArray, cands: IntArray): Boolean {
        if (groupEnds.isEmpty()) return handApply(strokes, cands)
        val (xy, lens) = pack(strokes)
        return nativeHandApplyGrouped(handle, xy, lens, groupEnds, cands)
    }

    /** 笔画打包为一个 x,y 数组与每笔点数。 Strokes packed into one x,y array plus per-stroke counts. */
    private fun pack(strokes: List<FloatArray>): Pair<FloatArray, IntArray> {
        val lens = IntArray(strokes.size) { strokes[it].size / 2 }
        val xy = FloatArray(lens.sum() * 2)
        var at = 0
        for (s in strokes) {
            val n = s.size / 2 * 2
            s.copyInto(xy, at, 0, n)
            at += n
        }
        return xy to lens
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
        @JvmStatic private external fun nativeFeatures(h: Long, command: String): String?
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
        @JvmStatic private external fun nativeSelectChar(h: Long, index: Int, last: Boolean): Boolean
        @JvmStatic private external fun nativeMoveCaret(h: Long, delta: Int): Boolean
        @JvmStatic private external fun nativeSetCaret(h: Long, index: Int): Boolean
        @JvmStatic private external fun nativeDeleteForward(h: Long): Boolean
        @JvmStatic private external fun nativeMoveCaretSyllable(h: Long, delta: Int): Boolean
        @JvmStatic private external fun nativeDeleteSyllable(h: Long, forward: Boolean): Boolean
        @JvmStatic private external fun nativeCommitFirst(h: Long): Boolean
        @JvmStatic private external fun nativeHandInput(h: Long, xy: FloatArray, lens: IntArray): Boolean
        @JvmStatic private external fun nativeHandInputGrouped(h: Long, xy: FloatArray, lens: IntArray, ends: IntArray): Boolean
        @JvmStatic private external fun nativeHandModels(h: Long): Long
        @JvmStatic private external fun nativeHandRecognize(m: Long, xy: FloatArray, lens: IntArray): IntArray?
        @JvmStatic private external fun nativeHandRecognizeGrouped(m: Long, xy: FloatArray, lens: IntArray, ends: IntArray): IntArray?
        @JvmStatic private external fun nativeHandApply(h: Long, xy: FloatArray, lens: IntArray, cands: IntArray): Boolean
        @JvmStatic private external fun nativeHandApplyGrouped(h: Long, xy: FloatArray, lens: IntArray, ends: IntArray, cands: IntArray): Boolean
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
data class Candidate(val text: String, val comment: String, val isUser: Boolean, val isCloud: Boolean = false, val pinyin: String = "")

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
    /** 组合串光标（preedit 的 UTF-16 下标）；null 表示在末尾。 Preedit caret (UTF-16 index); null at the end. */
    val caret: Int? = null,
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
            return List(n) {
                val text=b.str();val comment=b.str();val flags=b.get().toInt() and 255
                val pinyin = if (flags and 4 != 0) b.str() else ""
                Candidate(text,comment,flags and 1 != 0,flags and 2 != 0,pinyin)
            }
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
            val caret = if (b.remaining() >= 4) b.int.takeIf { it in 0 until preedit.codePointCount(0, preedit.length) }
                ?.let { preedit.offsetByCodePoints(0, it) } else null
            return EngineSnapshot(commit, preedit, flags and 1 != 0, cands, total, opts, schema, marks, caret)
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
