package com.weavetext.ime.testing

import com.weavetext.ime.core.DictPack
import com.weavetext.ime.core.DictPackRepository
import com.weavetext.ime.core.PackState
import com.weavetext.ime.models.Progress

/** 专业词库的假仓库。 A fake domain dictionary repository. */
class FakeDictPacks(
    override val packs: List<DictPack> = SAMPLE,
    val states: MutableMap<String, PackState> = mutableMapOf(
        "med" to PackState.Installed,
        "law" to PackState.Downloading(Progress(80_000, 145_965, 200_000, "GitHub")),
        "it" to PackState.Failed("下载失败，请检查网络后重试"),
    ),
) : DictPackRepository {
    override fun state(id: String) = states[id] ?: PackState.NotInstalled
    override fun install(id: String) { states[id] = PackState.Installed }
    override fun cancel(id: String) { states[id] = PackState.NotInstalled }
    override fun remove(id: String): Boolean { states[id] = PackState.NotInstalled; return true }
    override fun addListener(l: () -> Unit) {}
    override fun removeListener(l: () -> Unit) {}

    companion object {
        private fun p(id: String, name: String, desc: String, words: Int, bytes: Long) =
            DictPack(id, name, desc, "MIT", "THUOCL", words, bytes, "0".repeat(64))
        val SAMPLE = listOf(
            p("med", "医学", "疾病、症状、解剖与诊疗术语", 22570, 191_838),
            p("law", "法律", "法律法规与司法用语", 8083, 145_965),
            p("it", "IT 互联网", "编程、软件、硬件与互联网用语", 6240, 62_535),
            p("places", "地名", "国内外地名", 21694, 158_058),
            p("food", "饮食", "菜名、食材与饮食用语", 5153, 53_260),
        )
    }
}
