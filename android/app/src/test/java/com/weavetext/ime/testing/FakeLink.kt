package com.weavetext.ime.testing

import com.weavetext.ime.link.LinkController
import com.weavetext.ime.link.LinkUiState
import com.weavetext.ime.link.PendingPair
import kotlinx.coroutines.flow.MutableStateFlow

/** 设置页截图用的互联假实现。 A fake link controller for settings screenshots. */
class FakeLink(initial: LinkUiState = LinkUiState()) : LinkController {
    override val state = MutableStateFlow(initial)
    val sent = ArrayList<String>()
    override fun setEnabled(on: Boolean) { state.value = state.value.copy(enabled = on, running = on) }
    override fun setClipSync(on: Boolean) { state.value = state.value.copy(clipSync = on) }
    override fun rename(name: String) { state.value = state.value.copy(name = name) }
    override fun pair(addrs: List<String>, code: String) { sent += "pair:$code" }
    override fun forget(id: String) { state.value = state.value.copy(trusted = state.value.trusted.filter { it.id != id }) }
    override fun sendText(to: String?, text: String, clip: Boolean): Boolean { sent += "text:$text"; return true }
    override fun sendFd(to: String?, fd: Int, name: String, mime: String): Boolean { sent += "file:$name"; return true }
    override fun offerPair(p: PendingPair?) { state.value = state.value.copy(pendingPair = p) }
    override fun offerDirect(ticket: String?) { state.value = state.value.copy(pendingDirect = ticket) }
    override fun resetPairing() {}
}
