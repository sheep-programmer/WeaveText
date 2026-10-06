package com.weavetext.ime.ui

import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.ui.keyboard.ExpressionCatalog
import com.weavetext.ime.ui.keyboard.ScrollGridView
import com.weavetext.ime.ui.keyboard.SymbolData
import com.weavetext.ime.ui.keyboard.SymbolPanel
import com.weavetext.ime.ui.keyboard.SymbolUsage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.io.File
import com.github.takahirom.roborazzi.captureRoboImage

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class ExpressionTooltipTest : KeyboardSnapshotSupport() {
    override fun snap(name:String) {idle();kb!!.view.captureRoboImage(File("/tmp/weavetext-expression-android-ui","$name.png").path)}
    private fun grid(v:View):ScrollGridView? {
        if(v.javaClass.name.endsWith("SymbolPanel\$Grid")) return v as ScrollGridView
        if(v is ViewGroup) for(i in 0 until v.childCount) grid(v.getChildAt(i))?.let{return it}
        return null
    }
    @Test fun catalogsHaveNamedEmojiKaomojiAndValidComplexSkinToneSequences() {
        val catalog=ExpressionCatalog.load(app)!!
        assertEquals(1898,catalog.emoji.size);assertEquals(132,catalog.kaomoji.size)
        assertTrue((catalog.emoji+catalog.kaomoji).all{it.name.isNotBlank() && it.text.isNotBlank()})
        assertEquals("嘿嘿",catalog.name("😀"));assertEquals("开心笑",catalog.name("(＾▽＾)"))
        assertEquals("⛹🏻‍♂️",SymbolData.withTone("⛹️‍♂️",1,catalog.skinToneBases))
        assertTrue(catalog.name("👍🏽")!!.contains("中等肤色"))
        assertTrue(catalog.supportedEmoji.all{android.graphics.Paint().hasGlyph(it.text)})
        val common=SymbolData.categories(listOf("🫠"),catalog).first().items
        if(!catalog.displayable("🫠"))assertFalse(common.contains("🫠"))
    }
    @Test fun selectingCommonSymbolsLearnsImmediatelyAndLaterChoicesCanLead() {
        val (keyboard,_)=keyboard(false)
        keyboard.prefs.edit().putString(WeavePrefs.SYMBOL_RECENT,"α\u0001β").putBoolean(WeavePrefs.SYMBOL_LOCK,true).commit()
        keyboard.showPanel("symbol");val panel=keyboard.panelNamed("symbol") as SymbolPanel;panel.selectTab(SymbolData.TAB_COMMON)
        snap("common_before_learning")
        val grid=grid(keyboard.view)!!
        grid.onItemTap(1)
        assertEquals("β",SymbolUsage.order(keyboard.prefs).first())
        assertTrue(grid.accessibilityNodeProvider.createAccessibilityNodeInfo(0)!!.contentDescription.toString().contains("β"))
        repeat(2){grid.onItemTap(0)}
        assertEquals("β",SymbolUsage.order(keyboard.prefs).first())
        grid.onItemTap(1)
        assertEquals("α",SymbolUsage.order(keyboard.prefs).first())
        snap("common_after_learning")
    }
    @Test fun holdingShowsTheNameWithoutTypingAndOrdinaryTapStillSelects() {
        for(dark in listOf(false,true)) {
            val (keyboard,_)=keyboard(dark);keyboard.showPanel("symbol")
            val recentBefore=keyboard.prefs.getString(WeavePrefs.SYMBOL_RECENT,"")
            val panel=keyboard.panelNamed("symbol") as SymbolPanel;panel.selectEmoji();snap("emoji_${if(dark)"dark" else "light"}")
            val grid=grid(keyboard.view)!!
            val node=grid.accessibilityNodeProvider.createAccessibilityNodeInfo(0)!!
            val rect=Rect().also{node.getBoundsInParent(it)}
            val x=rect.exactCenterX();val y=rect.exactCenterY()
            val time=SystemClock.uptimeMillis()
            fun event(action:Int) {val e=MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,x,y,0);try{grid.dispatchTouchEvent(e)}finally{e.recycle()}}
            event(MotionEvent.ACTION_DOWN)
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(450))
            assertEquals("嘿嘿",keyboard.overlay!!.infoLabel)
            assertEquals(recentBefore,keyboard.prefs.getString(WeavePrefs.SYMBOL_RECENT,""))
            snap("emoji_name_${if(dark)"dark" else "light"}")
            event(MotionEvent.ACTION_UP);assertEquals("",keyboard.overlay!!.infoLabel)
            assertEquals(recentBefore,keyboard.prefs.getString(WeavePrefs.SYMBOL_RECENT,""))
            event(MotionEvent.ACTION_DOWN);event(MotionEvent.ACTION_UP)
            assertTrue(keyboard.prefs.getString(WeavePrefs.SYMBOL_RECENT,"")!!.contains("😀"))
            panel.selectTab(SymbolData.TAB_KAOMOJI);snap("kaomoji_${if(dark)"dark" else "light"}")
            assertTrue(grid.onItemLong(0));assertEquals("开心笑",keyboard.overlay!!.infoLabel)
            grid.onLongUp(true);assertEquals("",keyboard.overlay!!.infoLabel)
            keyboard.dispose()
        }
    }
    @Test fun skinToneChoiceKeepsTheNameAndClosingThePanelClearsBothPopups() {
        val (keyboard,_)=keyboard(false);keyboard.showPanel("symbol")
        val panel=keyboard.panelNamed("symbol") as SymbolPanel
        val catalog=ExpressionCatalog.load(app)!!
        val cats=SymbolData.categories(emptyList(),catalog);val category=cats.indexOfFirst{it.name=="人物"}
        panel.selectTab(category);snap("people")
        val grid=grid(keyboard.view)!!;val index=cats[category].items.indexOf("👋")
        assertTrue(grid.onItemLong(index));assertTrue(keyboard.overlay!!.altShown)
        assertTrue(keyboard.overlay!!.infoLabel.isNotBlank());snap("skin_tone_name")
        val before=keyboard.prefs.getString(WeavePrefs.SYMBOL_RECENT,"")
        grid.onLongUp(false);assertEquals(before,keyboard.prefs.getString(WeavePrefs.SYMBOL_RECENT,""))
        assertTrue(grid.onItemLong(index))
        val box=RectF();keyboard.overlay!!.alternativeBounds(box)
        val overlayLoc=IntArray(2);val gridLoc=IntArray(2)
        keyboard.overlay!!.getLocationInWindow(overlayLoc);grid.getLocationInWindow(gridLoc)
        grid.onLongMove(box.left+box.width()*3.5f/6+overlayLoc[0]-gridLoc[0],box.centerY()+overlayLoc[1]-gridLoc[1])
        assertEquals(3,keyboard.overlay!!.altSelected)
        grid.onLongUp(false);assertTrue(keyboard.prefs.getString(WeavePrefs.SYMBOL_RECENT,"")!!.contains("👋🏽"))
        assertTrue(grid.onItemLong(index))
        keyboard.closePanel();assertFalse(keyboard.overlay!!.altShown);assertEquals("",keyboard.overlay!!.infoLabel)
    }
}
