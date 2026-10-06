package com.weavetext.ime.ui

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.weavetext.ime.ime.InputController
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.testing.FakeInputConnection
import com.weavetext.ime.translate.*
import com.weavetext.ime.ui.keyboard.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranslationPanelTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun button(view: View, title: String): Button? {
        if (view is Button && view.text.toString() == title) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) button(view.getChildAt(i), title)?.let { return it }
        return null
    }
    @Test fun selectedTextTranslatesAndRequiresExplicitWriteback() {
        WeavePrefs.of(app).edit().clear()
            .putBoolean(com.weavetext.ime.settings.TranslationSettings.GOOGLE_WEB_MIGRATION, true)
            .putString(com.weavetext.ime.settings.TranslationSettings.PROTOCOL, com.weavetext.ime.translate.TranslationProtocol.GOOGLE_DEVICE.id)
            .putString(com.weavetext.ime.settings.TranslationSettings.SOURCE_LANGUAGE, "en")
            .putString(com.weavetext.ime.settings.TranslationSettings.TARGET_LANGUAGE, "zh").commit()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val frame = FrameLayout(activity)
        val ic = FakeInputConnection(frame)
        ic.commitText("hello", 1); ic.setSelection(0, 5)
        val controller = InputController { ic }
        controller.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; initialSelStart = 0; initialSelEnd = 5 }, false)
        val kb = WeaveKeyboard(activity, controller, object : ImeWindowHost {
            override fun hideKeyboard() {}
            override val window: android.view.Window? get() = null
        })
        try {
            frame.addView(kb.view, FrameLayout.LayoutParams(-1, -2))
            activity.setContentView(frame); kb.onShown()
            var request: TranslationRequest? = null
            val panel = TranslationPanel(kb, serviceProvider = { object : TranslationService {
                override fun translate(r: TranslationRequest, callback: (TranslationResult) -> Unit): TranslationCall {
                    request = r; callback(TranslationResult.Success("你好")); return TranslationCall {}
                }
            } })
            frame.addView(panel.view, FrameLayout.LayoutParams(-1, 900))
            panel.applyTheme(); panel.onShow(); ShadowLooper.idleMainLooper()
            button(panel.view, "使用 Google Translate 翻译")!!.performClick(); ShadowLooper.idleMainLooper()
            assertEquals("hello", request!!.text)
            assertEquals("en", request!!.sourceLanguage)
            assertEquals("zh", request!!.targetLanguage)
            assertEquals("hello", ic.text)
            val dir = File(System.getProperty("weave.snapshotDir") ?: "build/snapshots")
            panel.view.captureRoboImage(File(dir, "keyboard_translation_light.png").path)
            button(panel.view, "复制")!!.performClick()
            assertEquals("你好", (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!.getItemAt(0).text.toString())
            button(panel.view, "替换选区")!!.performClick()
            assertEquals("你好", ic.text)
        } finally { kb.dispose() }
    }
}
