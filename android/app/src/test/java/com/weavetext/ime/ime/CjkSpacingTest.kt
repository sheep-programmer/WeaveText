package com.weavetext.ime.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 中英之间自动加空格：只在中文与英文字母、数字相邻时加，标点与空白不加。 */
class CjkSpacingTest {
    @Test fun chineseNextToLatinOrDigitGetsASpaceInEitherOrder() {
        assertTrue(CjkSpacing.needsSpace('你', 'a'))
        assertTrue(CjkSpacing.needsSpace('a', '你'))
        assertTrue(CjkSpacing.needsSpace('好', '3'))
        assertTrue(CjkSpacing.needsSpace('2', '好'))
    }

    @Test fun punctuationSpacesAndSameScriptNeverGetOne() {
        assertFalse(CjkSpacing.needsSpace('你', '。'))
        assertFalse(CjkSpacing.needsSpace('。', 'a'))
        assertFalse(CjkSpacing.needsSpace('你', ' '))
        assertFalse(CjkSpacing.needsSpace('a', 'b'))
        assertFalse(CjkSpacing.needsSpace('你', '好'))
        assertFalse("start of the field needs no space", CjkSpacing.needsSpace(null, 'a'))
    }
}
