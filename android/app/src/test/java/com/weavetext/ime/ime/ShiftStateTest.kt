package com.weavetext.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftStateTest {
    @Test fun singleTapIsOnceThenConsumed() {
        val s = ShiftState()
        assertTrue(s.tap(1000))
        assertEquals(ShiftState.ONCE, s.value)
        assertTrue(s.upper)
        assertTrue(s.consume())
        assertEquals(ShiftState.OFF, s.value)
    }

    @Test fun secondSlowTapTurnsOff() {
        val s = ShiftState()
        s.tap(1000); s.tap(2000)
        assertEquals(ShiftState.OFF, s.value)
    }

    @Test fun doubleTapLocksAndSurvivesLetters() {
        val s = ShiftState()
        s.tap(1000); s.tap(1200)
        assertEquals(ShiftState.LOCK, s.value)
        assertFalse(s.consume())
        assertEquals(ShiftState.LOCK, s.value)
        s.tap(5000)
        assertEquals(ShiftState.OFF, s.value)
    }

    @Test fun tapAfterLockDoesNotImmediatelyRelock() {
        val s = ShiftState()
        s.tap(1000); s.tap(1100) // lock
        s.tap(1200)              // off
        assertEquals(ShiftState.OFF, s.value)
        s.tap(3000)
        assertEquals(ShiftState.ONCE, s.value)
    }

    @Test fun lockCanBeDisabled() {
        val s = ShiftState()
        s.tap(1000, allowLock = false); s.tap(1100, allowLock = false)
        assertEquals(ShiftState.OFF, s.value)
    }

    @Test fun autoCapOnlyFromOff() {
        val s = ShiftState()
        assertTrue(s.autoCap(true))
        assertEquals(ShiftState.ONCE, s.value)
        s.consume(); s.tap(1000); s.tap(1100)
        assertFalse(s.autoCap(true))
        assertEquals(ShiftState.LOCK, s.value)
        assertFalse(ShiftState().autoCap(false))
    }

    @Test fun autoCapIsTakenBackWhenItNoLongerApplies() {
        val s = ShiftState()
        s.autoCap(true)
        // 删掉了句号后的空格：不再是句首。 The space after the period was deleted: no longer a sentence start.
        assertTrue(s.autoCap(false))
        assertEquals(ShiftState.OFF, s.value)
        // 用户自己按的单次大写不收回。 A ONCE the user tapped stays.
        s.tap(1000)
        assertFalse(s.autoCap(false))
        assertEquals(ShiftState.ONCE, s.value)
    }

    @Test fun resetClearsDoubleTapWindow() {
        val s = ShiftState()
        s.tap(1000); s.reset(); s.tap(1100)
        assertEquals(ShiftState.ONCE, s.value)
    }
}
