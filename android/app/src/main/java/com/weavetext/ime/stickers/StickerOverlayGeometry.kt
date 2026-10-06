package com.weavetext.ime.stickers

/** All coordinates are display coordinates, including the keyboard's measured top edge. */
internal object StickerOverlayGeometry {
    data class Box(val x:Int,val y:Int,val width:Int,val height:Int)
    enum class Corner(val horizontal:Int,val vertical:Int,val label:String) {
        TOP_LEFT(-1,-1,"左上角"), TOP_RIGHT(1,-1,"右上角"), BOTTOM_LEFT(-1,1,"左下角"), BOTTOM_RIGHT(1,1,"右下角")
    }
    /** The opposite corner stays anchored; constraints come from the current safe display area. */
    fun resize(start:Box,corner:Corner,dx:Int,dy:Int,left:Int,right:Int,top:Int,bottom:Int,minWidth:Int,minHeight:Int):Box {
        val safeWidth=start.width.coerceAtMost((right-left).coerceAtLeast(1))
        val safeHeight=start.height.coerceAtMost((bottom-top).coerceAtLeast(1))
        val safeX=start.x.coerceIn(left,maxOf(left,right-safeWidth))
        val safeY=start.y.coerceIn(top,maxOf(top,bottom-safeHeight))
        val anchorX=if(corner.horizontal<0)safeX+safeWidth else safeX
        val anchorY=if(corner.vertical<0)safeY+safeHeight else safeY
        val maxWidth=(if(corner.horizontal<0)anchorX-left else right-anchorX).coerceAtLeast(1)
        val maxHeight=(if(corner.vertical<0)anchorY-top else bottom-anchorY).coerceAtLeast(1)
        val width=(safeWidth+corner.horizontal*dx).coerceIn(minWidth.coerceAtMost(maxWidth),maxWidth)
        val height=(safeHeight+corner.vertical*dy).coerceIn(minHeight.coerceAtMost(maxHeight),maxHeight)
        return Box(if(corner.horizontal<0)anchorX-width else anchorX,
            if(corner.vertical<0)anchorY-height else anchorY,width,height)
    }
    fun place(screenWidth:Int,screenHeight:Int,left:Int,right:Int,top:Int,bottom:Int,keyboardTop:Int?,
              desiredWidth:Int,desiredHeight:Int,x:Int,y:Int,gap:Int):Box {
        val safeBottom=minOf(screenHeight-bottom,keyboardTop ?: screenHeight)-gap
        val width=desiredWidth.coerceAtMost((screenWidth-left-right).coerceAtLeast(1))
        val height=desiredHeight.coerceAtMost((safeBottom-top-gap).coerceAtLeast(1))
        return Box(x.coerceIn(left,maxOf(left,screenWidth-right-width)),
            y.coerceIn(top+gap,maxOf(top+gap,safeBottom-height)),width,height)
    }
}
