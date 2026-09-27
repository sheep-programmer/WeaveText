package com.weavetext.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipExtractTest {
    @Test fun findsCodesNearKeywords() {
        assertEquals("482913", ClipExtract.code("【示例】您的验证码是 482913，5 分钟内有效，请勿泄露。"))
        assertEquals("7731", ClipExtract.code("校验码7731，用于登录"))
        assertEquals("A7K2Q9", ClipExtract.code("Your verification code is A7K2Q9."))
        assertEquals("305812", ClipExtract.code("尊敬的用户，您本次操作的动态码为305812，订单金额 1999 元"))
    }

    @Test fun doesNotGuessWithoutAKeyword() {
        assertNull(ClipExtract.code("明天下午 3 点在 1203 会议室，电话 13800138000"))
        assertNull(ClipExtract.code("订单金额 1999 元"))
        assertNull(ClipExtract.code("验证码已发送"))
    }
}
