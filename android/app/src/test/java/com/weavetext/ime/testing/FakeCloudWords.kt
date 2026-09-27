package com.weavetext.ime.testing

import com.weavetext.ime.core.CloudStatus
import com.weavetext.ime.core.CloudWordsRepository

/** 云端热词的假仓库。 A fake cloud hot-words repository. */
class FakeCloudWords(var st: CloudStatus = CloudStatus(enabled = false)) : CloudWordsRepository {
    override fun status() = st
    override fun setEnabled(on: Boolean) { st = st.copy(enabled = on) }
    override fun refreshNow() {}
    override fun addListener(l: () -> Unit) {}
    override fun removeListener(l: () -> Unit) {}
}
