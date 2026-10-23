package com.weavetext.ime.ui

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weavetext.ime.core.DataInstaller
import com.weavetext.ime.core.NativeEngine
import com.weavetext.ime.debug.SmokeActivity
import com.weavetext.ime.ime.InputController
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.ui.keyboard.ImeWindowHost
import com.weavetext.ime.ui.keyboard.VoicePanel
import com.weavetext.ime.ui.keyboard.VoiceSession
import com.weavetext.ime.ui.keyboard.WeaveKeyboard
import com.weavetext.ime.voice.MultiEngineResults
import com.weavetext.ime.voice.VoiceAutoDownload
import com.weavetext.ime.voice.VoiceEngines
import com.weavetext.ime.voice.VoiceListener
import com.weavetext.ime.voice.VoicePlugin
import com.weavetext.ime.voice.VoiceRecognizer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Production cards in a real window; all gestures enter through screen touch injection. */
@RunWith(AndroidJUnit4::class)
class VoiceResultsDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    // Original local fixtures: distinct endings make an incorrect selection visible in the editor.
    private val transcripts = listOf(
        "今天沿着河边慢慢走，记下桥下的水声和傍晚的灯光。回家后把路线画在纸上，明天带朋友一起再走一次。",
        "周末准备整理阳台，先把花盆移到阳光下面，再给每一株植物贴上日期。最后留一张空椅子，坐下来读完那本书。",
        "会议结束前，请确认三件事：窗边的桌子留给读书活动，门口放一张路线图，下午四点一起检查照明。确认后把钥匙交给值班同事。",
    )
    private val ids = listOf("fixture-river", "fixture-garden", "fixture-meeting")
    private val qualities = listOf(0.61f, 0.96f, 0.82f)
    private val plugins get() = ids.mapIndexed { i, id ->
        VoicePlugin(id, "测试引擎${i + 1}", "本地文字样本", "1", null, emptyList(), quality = qualities[i])
    }

    private fun results(): MultiEngineResults = MultiEngineResults(
        plugins.map { it.id to it.name }, ids[0],
    ).apply {
        ids.forEachIndexed { i, id ->
            final(id, transcripts[i])
            score(id, qualities[i])
        }
        stop(0L)
        ids.forEachIndexed { i, id -> end(id, 120L + i * 30L) }
    }

    @Test fun threeRankedCardsPreviewSwipeConfirmAndCancelThroughTheScreen() {
        val app = instrumentation.targetContext
        val namespace = "voice-results-${UUID.randomUUID()}"
        val root = File(app.cacheDir, namespace).apply { mkdirs() }
        val prefNames = mutableSetOf<String>()
        val owner = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(key: String, mode: Int) =
                app.getSharedPreferences("$namespace-$key", mode).also { prefNames += "$namespace-$key" }
        }
        val savedEngines = VoiceAccess.enginesProvider
        val savedRecognizer = VoiceAccess.recognizerProvider
        val savedEnsure = VoiceAutoDownload.ensureOverride
        val engines = object : VoiceEngines {
            override var activeId: String? = ids[0]
            override var extraIds: Set<String> = ids.drop(1).toSet()
            override fun list() = plugins
            override fun getConfig(id: String, key: String): String? = null
            override fun setConfig(id: String, key: String, value: String) {}
            override fun install(xipkPath: String) = Result.failure<VoicePlugin>(UnsupportedOperationException())
            override fun uninstall(id: String) = Result.failure<Unit>(UnsupportedOperationException())
        }
        var captureStarts = 0
        val recognizer = object : VoiceRecognizer {
            override val isRunning = false
            override fun start(listener: VoiceListener): Boolean { captureStarts++; return false }
            override fun stop() {}
            override fun cancel() {}
            override fun warmUp() {}
        }
        var engine: NativeEngine? = null
        val needsAudioPermission = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        var adoptedAudioPermission = false
        try {
            // Borrow shell permission only for this test; dropping it avoids revoking our running app's UID.
            if (needsAudioPermission) {
                automation.adoptShellPermissionIdentity(Manifest.permission.RECORD_AUDIO)
                adoptedAudioPermission = true
            }
            assertEquals(PackageManager.PERMISSION_GRANTED, app.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
            instrumentation.runOnMainSync {
                VoiceAccess.enginesProvider = { engines }
                VoiceAccess.recognizerProvider = { recognizer }
                VoiceAutoDownload.ensureOverride = { false }
            }
            val prefs = WeavePrefs.of(owner)
            assertTrue(prefs.edit().putString(WeavePrefs.THEME, "light")
                .putBoolean(WeavePrefs.CLOUD_WORDS, false)
                .putBoolean(WeavePrefs.FLOATING, false).putInt(WeavePrefs.ONE_HAND, 0)
                .putString(WeavePrefs.KEYBOARDS, "pinyin,english")
                .putString(WeavePrefs.ACTIVE_KEYBOARD, "pinyin").commit())
            val native = requireNotNull(NativeEngine.createFromSpec(
                DataInstaller.sourceSpec(owner), File(root, "user").path, DataInstaller.cacheKb(owner),
            ))
            engine = native
            ActivityScenario.launch(SmokeActivity::class.java).use { scenario ->
                var keyboard: WeaveKeyboard? = null
                var input: InputController? = null
                lateinit var panel: VoicePanel
                lateinit var editor: EditText
                val prefix = "记录："
                try {
                    scenario.onActivity { activity ->
                        val context = object : ContextWrapper(activity) {
                            override fun getApplicationContext(): Context = owner
                            override fun getFilesDir() = owner.filesDir
                            override fun getCacheDir() = owner.cacheDir
                            override fun getSharedPreferences(key: String, mode: Int) = owner.getSharedPreferences(key, mode)
                        }
                        editor = activity.findViewById(android.R.id.edit)
                        editor.showSoftInputOnFocus = false
                        activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                        fun hideSystemIme() {
                            activity.getSystemService(InputMethodManager::class.java)
                                .hideSoftInputFromWindow(editor.windowToken, 0)
                            if (Build.VERSION.SDK_INT >= 30) activity.window.insetsController?.hide(WindowInsets.Type.ime())
                        }
                        hideSystemIme()
                        activity.window.decorView.post { hideSystemIme() }
                        editor.setText(prefix)
                        editor.setSelection(editor.length())
                        val info = EditorInfo().apply {
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                            packageName = activity.packageName
                            fieldId = android.R.id.edit
                        }
                        val controller = InputController { editor.onCreateInputConnection(info) }
                        input = controller
                        controller.onStartInput(info, false)
                        controller.attachEngine(native)
                        controller.voiceBegin()
                        val kb = WeaveKeyboard(context, controller, object : ImeWindowHost {
                            override fun hideKeyboard() {}
                            override val window get() = activity.window
                        })
                        keyboard = kb
                        activity.addContentView(kb.view, FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.BOTTOM })
                        kb.onShown()
                        kb.showPanel("voice")
                        panel = kb.panel as VoicePanel
                        panel.session.previewResults(results())
                        kb.flushRender()
                    }
                    val kb = requireNotNull(keyboard)
                    await(scenario, "three cards drawn and system IME hidden") { activity ->
                        val view = panel.view
                        val imeHidden = Build.VERSION.SDK_INT < 30 ||
                            activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == false
                        imeHidden && view.isShown && view.width > 0 && view.height > 0 &&
                            view.alpha >= 0.99f && kotlin.math.abs(view.translationY) < 1f &&
                            !view.isLayoutRequested && panel.view.resultCardBounds().size == 3
                    }
                    scenario.onActivity { activity ->
                        val cards = panel.view.resultCardBounds()
                        assertEquals(listOf(ids[1], ids[2], ids[0]), panel.session.results!!.rows().map { it.id })
                        assertEquals(listOf(ids[1], ids[2], ids[0]), cards.map { it.first })
                        assertEquals(ids[1], panel.view.selectedResultId)
                        assertNull(panel.view.resultPreviewId)
                        assertEquals(VoiceSession.State.CHOOSING, panel.session.state)
                        assertEquals(prefix, editor.text.toString())
                        assertEquals("", panel.session.committed.toString())
                        val visible = Rect()
                        assertTrue(panel.view.getLocalVisibleRect(visible))
                        val origin = IntArray(2).also(panel.view::getLocationOnScreen)
                        visible.offset(origin[0], origin[1])
                        val footerTop = panel.view.height - kb.metrics.dp(48f)
                        cards.forEachIndexed { i, (_, bounds) ->
                            assertTrue("Card must have usable transcript space", bounds.width() > kb.metrics.dp(40f) && bounds.height() > kb.metrics.dp(46f))
                            assertTrue(bounds.left >= 0f && bounds.top >= kb.metrics.topBar)
                            assertTrue(bounds.right <= panel.view.width && bounds.bottom <= footerTop)
                            assertEquals(cards[0].second.width(), bounds.width(), 1f)
                            assertEquals(cards[0].second.top, bounds.top, 1f)
                            assertEquals(cards[0].second.bottom, bounds.bottom, 1f)
                            val screen = RectF(bounds).apply { offset(origin[0].toFloat(), origin[1].toFloat()) }
                            assertTrue("Whole card must be visible on screen: $screen", RectF(visible).contains(screen))
                            cards.drop(i + 1).forEach { assertFalse(RectF.intersects(bounds, it.second)) }
                        }
                        val location = IntArray(2).also(kb.view::getLocationOnScreen)
                        val content = activity.findViewById<android.view.View>(android.R.id.content)
                        val contentLocation = IntArray(2).also(content::getLocationOnScreen)
                        assertEquals("Keyboard must be mounted at the content bottom",
                            contentLocation[1] + content.height, location[1] + kb.view.height)
                    }
                    screenshot("voice-cards-device.png")

                    fun assertChoosing(selected: String, preview: String?, expectedEditor: String = prefix) {
                        scenario.onActivity {
                            assertEquals(VoiceSession.State.CHOOSING, panel.session.state)
                            assertEquals(selected, panel.view.selectedResultId)
                            assertEquals(preview, panel.view.resultPreviewId)
                            assertEquals(expectedEditor, editor.text.toString())
                            assertEquals("", panel.session.committed.toString())
                        }
                    }
                    fun card(id: String): PointF {
                        lateinit var point: PointF
                        scenario.onActivity {
                            val bounds = panel.view.resultCardBounds().first { it.first == id }.second
                            point = screenPoint(panel, bounds.centerX(), bounds.centerY())
                        }
                        return point
                    }
                    fun footer(confirm: Boolean): PointF {
                        lateinit var point: PointF
                        scenario.onActivity {
                            val x = if (confirm) panel.view.width - kb.metrics.dp(56f) else kb.metrics.dp(56f)
                            point = screenPoint(panel, x, panel.view.height - kb.metrics.dp(28f))
                        }
                        return point
                    }
                    fun swipe(left: Boolean) {
                        lateinit var start: PointF
                        lateinit var end: PointF
                        scenario.onActivity {
                            val cards = panel.view.resultCardBounds()
                            val bounds = RectF(cards.first().second).apply { union(cards.last().second) }
                            val x1 = bounds.left + bounds.width() * if (left) 0.85f else 0.15f
                            val x2 = bounds.left + bounds.width() * if (left) 0.15f else 0.85f
                            assertTrue("Swipe must exceed production threshold", kotlin.math.abs(x2 - x1) > kb.metrics.dp(48f))
                            start = screenPoint(panel, x1, bounds.centerY())
                            end = screenPoint(panel, x2, bounds.centerY())
                        }
                        gesture(start, end)
                    }
                    swipe(left = true)
                    assertChoosing(ids[2], null)
                    swipe(left = false)
                    assertChoosing(ids[1], null)
                    gesture(card(ids[2]))
                    assertChoosing(ids[2], ids[2])
                    screenshot("voice-preview-device.png")
                    swipe(left = true)
                    assertChoosing(ids[0], ids[0])
                    swipe(left = false)
                    assertChoosing(ids[2], ids[2])
                    gesture(footer(confirm = true))
                    val committedText = prefix + transcripts[2]
                    scenario.onActivity {
                        assertEquals(committedText, editor.text.toString())
                        assertEquals(transcripts[2], panel.session.committed.toString())
                        assertEquals(VoiceSession.State.IDLE, panel.session.state)
                        assertNull(panel.session.results)
                    }

                    // A second set verifies that preview Back preserves results and Cancel discards them.
                    scenario.onActivity { panel.session.previewResults(results()) }
                    await(scenario, "fresh results drawn") { panel.view.selectedResultId == ids[1] && panel.view.resultPreviewId == null }
                    gesture(card(ids[1]))
                    scenario.onActivity {
                        assertEquals(ids[1], panel.view.resultPreviewId)
                        assertEquals(committedText, editor.text.toString())
                    }
                    gesture(footer(confirm = false))
                    scenario.onActivity {
                        assertNull(panel.view.resultPreviewId)
                        assertNotNull(panel.session.results)
                        assertEquals(VoiceSession.State.CHOOSING, panel.session.state)
                        assertEquals(committedText, editor.text.toString())
                    }
                    swipe(left = true)
                    scenario.onActivity { assertEquals(ids[2], panel.view.selectedResultId) }
                    gesture(footer(confirm = false))
                    scenario.onActivity {
                        assertEquals(VoiceSession.State.IDLE, panel.session.state)
                        assertNull(panel.session.results)
                        assertEquals(committedText, editor.text.toString())
                        assertEquals(transcripts[2], panel.session.committed.toString())
                        assertEquals("Preview acceptance must never start capture", 0, captureStarts)
                    }
                } finally {
                    scenario.onActivity {
                        try { keyboard?.dispose() } finally { input?.onFinishInput() }
                    }
                }
            }
        } finally {
            try {
                instrumentation.runOnMainSync {
                    VoiceAccess.enginesProvider = savedEngines
                    VoiceAccess.recognizerProvider = savedRecognizer
                    VoiceAutoDownload.ensureOverride = savedEnsure
                }
            } finally {
                try {
                    engine?.close()
                    root.deleteRecursively()
                    prefNames.forEach(app::deleteSharedPreferences)
                } finally {
                    if (adoptedAudioPermission) automation.dropShellPermissionIdentity()
                }
            }
        }
    }

    private fun screenPoint(panel: VoicePanel, x: Float, y: Float): PointF {
        val origin = IntArray(2).also(panel.view::getLocationOnScreen)
        return PointF(origin[0] + x, origin[1] + y)
    }

    private fun gesture(start: PointF, end: PointF = start) {
        val downTime = SystemClock.uptimeMillis()
        fun emit(action: Int, point: PointF) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue("Screen touch injection failed", automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        emit(MotionEvent.ACTION_DOWN, start)
        if (start != end) {
            for (step in 1..12) {
                SystemClock.sleep(16)
                val fraction = step / 12f
                emit(MotionEvent.ACTION_MOVE, PointF(start.x + (end.x - start.x) * fraction, start.y + (end.y - start.y) * fraction))
            }
        } else SystemClock.sleep(40)
        emit(MotionEvent.ACTION_UP, end)
        instrumentation.waitForIdleSync()
    }

    private fun await(scenario: ActivityScenario<SmokeActivity>, label: String, ready: (SmokeActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            instrumentation.waitForIdleSync()
            var done = false
            scenario.onActivity { done = ready(it) }
            if (done) return
            SystemClock.sleep(30)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("Timed out waiting for $label")
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        // Selection changes animate for 220 ms; let a displayed frame settle before capturing the screen.
        SystemClock.sleep(300)
        fun capture(): Bitmap? {
            repeat(3) { attempt ->
                automation.takeScreenshot()?.let { return it }
                if (attempt < 2) SystemClock.sleep(250)
            }
            // Read shell stdout in our process: shell cannot write directly into the app's private cache.
            return ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap -p")).use {
                BitmapFactory.decodeStream(it)
            }
        }
        val bitmap = requireNotNull(capture()) { "Full-screen capture unavailable from screenshot and screencap" }
        try {
            assertTrue("Full-screen capture has no pixels", bitmap.width > 0 && bitmap.height > 0)
            File(instrumentation.targetContext.cacheDir, name).outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
}
