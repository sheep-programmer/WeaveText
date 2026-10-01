package com.weavetext.ime.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.core.KeyEngine
import com.weavetext.ime.testing.FakeEngine
import com.weavetext.ime.testing.FakeInputConnection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HandRecognitionAsyncTest {
    @Test fun newStrokesReplacePendingRecognitionWithoutBlocking() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val fake = FakeEngine()
        val applied = mutableListOf<Int>()
        val recognitions = mutableListOf<Int>()
        val engine = object : KeyEngine by fake {
            override fun handRecognize(strokes: List<FloatArray>): IntArray {
                synchronized(recognitions) { recognitions += strokes.size }
                if (strokes.size == 1) { started.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)) }
                return intArrayOf('字'.code)
            }
            override fun handApply(strokes: List<FloatArray>, cands: IntArray): Boolean {
                applied += strokes.size
                return fake.handInput(strokes)
            }
        }
        val ic = FakeInputConnection(FrameLayout(ApplicationProvider.getApplicationContext()))
        val controller = InputController { ic }
        val posted = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        controller.attachEngine(engine)
        controller.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, false)
        controller.setPreferredSchema("hand")
        controller.handWorker = worker
        controller.postMain = { posted += it }
        try {
            val stroke = floatArrayOf(0f, 0f, 1f, 1f)
            controller.onHandStrokes(listOf(stroke))
            assertTrue(started.await(1, TimeUnit.SECONDS))
            // A watchdog makes the old blocking behavior fail without hanging the test.
            val watchdog = Executors.newSingleThreadScheduledExecutor()
            val forced = java.util.concurrent.atomic.AtomicBoolean()
            val timer = watchdog.schedule({ forced.set(true); release.countDown() }, 1, TimeUnit.SECONDS)
            try {
                controller.onHandStrokes(listOf(stroke, stroke))
                controller.onHandStrokes(listOf(stroke, stroke, stroke))
                assertFalse("pen lifts must not wait for the previous recognition", forced.get())
            } finally { timer.cancel(false); watchdog.shutdownNow(); release.countDown() }
            worker.submit {}.get(2, TimeUnit.SECONDS)
            while (true) (posted.poll() ?: break).run()
            assertEquals(listOf(3), applied)
            assertEquals(listOf(1, 3), recognitions)
            assertEquals(3, fake.hand.size)
            controller.onCandidate(0)
            assertEquals("3笔0", ic.text)
            assertFalse(fake.isComposing())
        } finally { release.countDown(); worker.shutdownNow() }
    }
}
