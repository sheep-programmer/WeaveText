package com.weavetext.ime.ui

import com.weavetext.ime.ui.keyboard.ContactFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactFilterTest {
    @Test fun fingertipsAreAcceptedWhileTheBaselineForms() {
        val f = ContactFilter()
        repeat(20) { assertFalse(f.isPalm(40f + it % 3)) }
    }

    @Test fun aPalmSizedContactIsRejectedOnceTheBaselineExists() {
        val f = ContactFilter()
        repeat(10) { f.isPalm(40f) }
        assertTrue(f.isPalm(120f))
    }

    @Test fun rejectedPalmsDoNotRaiseTheBaseline() {
        val f = ContactFilter()
        repeat(10) { f.isPalm(40f) }
        repeat(50) { assertTrue(f.isPalm(120f)) }
        // 正常指尖仍然被接受。 Normal fingertips are still accepted after a long palm contact.
        assertFalse(f.isPalm(45f))
    }

    @Test fun unknownOrZeroSizesAreAlwaysAccepted() {
        val f = ContactFilter()
        repeat(10) { f.isPalm(40f) }
        assertFalse(f.isPalm(0f))
        assertFalse(f.isPalm(Float.NaN))
    }

    @Test fun aThumbPressHarderIsNotAPalm() {
        val f = ContactFilter()
        repeat(10) { f.isPalm(40f) }
        assertFalse(f.isPalm(70f))
    }
}
