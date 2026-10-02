package com.weavetext.ime.stickers

import android.app.Activity
import android.os.Build
import android.view.DragEvent
import android.view.View

object StickerDrop {
    fun bind(view:View,repository:StickerRepository,result:(String)->Unit) {
        if(Build.VERSION.SDK_INT>=31) {
            view.setOnReceiveContentListener(arrayOf("image/*")) {_,payload ->
                val uris=(0 until payload.clip.itemCount).mapNotNull {payload.clip.getItemAt(it).uri}
                if(uris.isEmpty()){result("原应用没有提供图片文件，请通过分享或相册导入");payload}
                else {repository.import(uris,result);null}
            }
            return
        }
        view.setOnDragListener {_,event ->when(event.action) {
            DragEvent.ACTION_DRAG_STARTED->event.clipDescription?.hasMimeType("image/*")==true
            DragEvent.ACTION_DROP->{
                val activity=findActivity(view.context)
                val grant=activity?.requestDragAndDropPermissions(event)
                val uris=(0 until (event.clipData?.itemCount ?: 0)).mapNotNull {event.clipData?.getItemAt(it)?.uri}
                if(uris.isEmpty())result("没有收到图片文件，请从原应用分享后收纳")
                else repository.import(uris) {message->grant?.release();result(message)}
                true
            }
            else->true
        }}
    }
    private fun findActivity(context:android.content.Context):Activity? {
        var current=context
        while(current is android.content.ContextWrapper){if(current is Activity)return current;val next=current.baseContext;if(next===current)break;current=next}
        return null
    }
}
