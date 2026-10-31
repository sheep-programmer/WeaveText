package com.weavetext.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/** 选中文字后按 Shift 的大小写循环：只改英文字母，中文和标点不动。 */
class CaseCycleTest {
    @Test fun lowerThenCapitalisedThenUpperThenLower() {
        assertEquals("Hello world", CaseCycle.next("hello world"))
        assertEquals("HELLO WORLD", CaseCycle.next("Hello world"))
        assertEquals("hello world", CaseCycle.next("HELLO WORLD"))
    }

    @Test fun mixedCaseFallsBackToLowerAndNeverTouchesChineseOrPunctuation() {
        assertEquals("iphone", CaseCycle.next("iPhone"))
        assertEquals("你好 Hello, world!", CaseCycle.next("你好 hello, world!"))
    }

    @Test fun selectionWithoutLettersIsUnchanged() {
        assertEquals("你好。", CaseCycle.next("你好。"))
        assertEquals("", CaseCycle.next(""))
    }
}
