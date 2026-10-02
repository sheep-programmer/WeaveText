package com.weavetext.ime.stickers

/** All coordinates are display coordinates, including the keyboard's measured top edge. */
internal object StickerOverlayGeometry {
    data class Box(val x:Int,val y:Int,val width:Int,val height:Int)
    fun place(screenWidth:Int,screenHeight:Int,left:Int,right:Int,top:Int,bottom:Int,keyboardTop:Int?,
              desiredWidth:Int,desiredHeight:Int,x:Int,y:Int,gap:Int):Box {
        val safeBottom=minOf(screenHeight-bottom,keyboardTop ?: screenHeight)-gap
        val width=desiredWidth.coerceAtMost((screenWidth-left-right).coerceAtLeast(1))
        val height=desiredHeight.coerceAtMost((safeBottom-top-gap).coerceAtLeast(1))
        return Box(x.coerceIn(left,maxOf(left,screenWidth-right-width)),
            y.coerceIn(top+gap,maxOf(top+gap,safeBottom-height)),width,height)
    }
}
