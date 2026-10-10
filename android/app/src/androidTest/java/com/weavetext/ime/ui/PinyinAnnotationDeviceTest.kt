package com.weavetext.ime.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
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
import com.weavetext.ime.ui.keyboard.WeaveKeyboard
import com.weavetext.ime.ui.keyboard.ScrollGridView
import com.weavetext.ime.ui.keyboard.SymbolPanel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PinyinAnnotationDeviceTest {
    @Test fun realDictionaryAnnotationsRefreshAndTappingTheirRowCommitsOnlyTheWord() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext
        val name="pinyin-device-"+UUID.randomUUID()
        val root=File(app.cacheDir,name).apply{mkdirs()}
        val prefsNames=mutableSetOf<String>()
        val owner=object:ContextWrapper(app) {
            override fun getFilesDir()=File(root,"files").apply{mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply{mkdirs()}
            override fun getSharedPreferences(key:String,mode:Int)=app.getSharedPreferences("$name-$key",mode).also{prefsNames+="$name-$key"}
        }
        val engine=NativeEngine.createFromSpec(DataInstaller.sourceSpec(owner),File(root,"user").path,DataInstaller.cacheKb(owner))!!
        try {
            ActivityScenario.launch(SmokeActivity::class.java).use {scenario->scenario.onActivity {activity->
                val ctx=object:ContextWrapper(activity) {
                    override fun getFilesDir()=owner.filesDir
                    override fun getCacheDir()=owner.cacheDir
                    override fun getSharedPreferences(key:String,mode:Int)=owner.getSharedPreferences(key,mode)
                }
                val editor=activity.findViewById<android.widget.EditText>(android.R.id.edit)
                editor.showSoftInputOnFocus=false;editor.setText("")
                val info=EditorInfo().apply{inputType=InputType.TYPE_CLASS_TEXT}
                val controller=InputController {editor.onCreateInputConnection(info)}
                controller.onStartInput(info,false);controller.attachEngine(engine)
                val kb=WeaveKeyboard(ctx,controller,object:ImeWindowHost {
                    override fun hideKeyboard() {}
                    override val window get()=activity.window
                })
                try {
                    activity.addContentView(kb.view,FrameLayout.LayoutParams(-1,-2).apply{gravity=android.view.Gravity.BOTTOM})
                    WeavePrefs.of(ctx).edit().putInt(WeavePrefs.PINYIN_HINT,1).commit()
                    kb.onShown()
                    "yinhang".forEach{controller.onChar(it.code)};kb.flushRender()
                    assertEquals("银行",controller.state.candidates.first().text)
                    assertEquals("yín háng",controller.state.candidates.first().pinyin)
                    assertEquals("yín háng",kb.topBar.candidatePinyinAt(0))
                    val enabledHeight=kb.metrics.kbHeight
                    WeavePrefs.of(ctx).edit().putInt(WeavePrefs.PINYIN_HINT,0).commit();kb.flushRender()
                    assertTrue(controller.state.candidates.all{it.pinyin.isEmpty()})
                    assertEquals("",kb.topBar.candidatePinyinAt(0));assertEquals(enabledHeight,kb.metrics.kbHeight,0.01f)
                    WeavePrefs.of(ctx).edit().putInt(WeavePrefs.PINYIN_HINT,1).commit();kb.flushRender()
                    assertEquals("yín háng",kb.topBar.candidatePinyinAt(0));assertEquals(enabledHeight,kb.metrics.kbHeight,0.01f)
                    val width=activity.resources.displayMetrics.widthPixels
                    kb.view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2000,View.MeasureSpec.AT_MOST))
                    kb.view.layout(0,0,width,kb.view.measuredHeight)
                    val bitmap=Bitmap.createBitmap(width,kb.view.measuredHeight,Bitmap.Config.ARGB_8888)
                    kb.view.draw(Canvas(bitmap))
                    File(app.cacheDir,"pinyin-annotation-device.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
                    val x=kb.metrics.dp(20f)
                    val y=kb.metrics.dp(24f)
                    for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                        val event=MotionEvent.obtain(0,1,action,x,y,0)
                        try {kb.topBar.dispatchTouchEvent(event)}finally{event.recycle()}
                    }
                    assertEquals("银行",editor.text.toString())
                    controller.onFinishInput()
                    engine.setSchema("wubi86");engine.inputChar('i'.code)
                    assertTrue(engine.snapshot().candidates.any{it.comment.isNotEmpty() && it.pinyin.isNotEmpty()})
                    kb.showPanel("symbol");(kb.panelNamed("symbol") as SymbolPanel).selectEmoji()
                    kb.view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2000,View.MeasureSpec.AT_MOST))
                    kb.view.layout(0,0,width,kb.view.measuredHeight)
                    fun grid(v:View):ScrollGridView? {
                        if(v.javaClass.name.endsWith("SymbolPanel\$Grid"))return v as ScrollGridView
                        if(v is ViewGroup)for(i in 0 until v.childCount)grid(v.getChildAt(i))?.let{return it}
                        return null
                    }
                    val emojis=grid(kb.view)!!
                    assertTrue(emojis.onItemLong(0));assertEquals("嘿嘿",kb.overlay!!.infoLabel)
                    val preview=Bitmap.createBitmap(width,kb.view.measuredHeight,Bitmap.Config.ARGB_8888)
                    kb.view.draw(Canvas(preview))
                    File(app.cacheDir,"emoji-name-device.png").outputStream().use{preview.compress(Bitmap.CompressFormat.PNG,100,it)};preview.recycle()
                    emojis.onLongUp(false);assertEquals("",kb.overlay!!.infoLabel)
                    assertEquals("银行",editor.text.toString())
                }finally{kb.dispose()}
            }}
        }finally{engine.close();root.deleteRecursively();prefsNames.forEach{app.deleteSharedPreferences(it)}}
    }
}
