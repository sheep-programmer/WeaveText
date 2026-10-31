package com.weavetext.ime.ui

import com.weavetext.ime.ui.keyboard.GlideTrace
import org.junit.Assert.assertEquals
import org.junit.Test

class GlideTraceTest {
    @Test fun consecutiveRepeatsCollapseAndNonLettersAreDropped() {
        val path = listOf('h', 'h', 'e', null, 'l', 'l', 'l', 'o', null)
        assertEquals("helo", GlideTrace.letters(path))
    }

    @Test fun a_single_key_is_not_a_glide() {
        assertEquals("", GlideTrace.letters(listOf('a', 'a', 'a')))
        assertEquals("", GlideTrace.letters(listOf(null, null)))
    }

    @Test fun a_non_repeating_return_to_an_earlier_key_is_kept() {
        assertEquals("aba", GlideTrace.letters(listOf('a', 'b', 'a')))
    }
}
