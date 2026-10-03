package com.weavetext.ime.ui.keyboard

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.weavetext.ime.stickers.*

class StickerPanel(kb:WeaveKeyboard):KbPanel(kb) {
    override val toolIndex=com.weavetext.ime.style.ToolIds.STICKERS
    private fun manage(){kb.ctx.startActivity(Intent(kb.ctx,StickerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    private fun openFloating(){
        if(!Settings.canDrawOverlays(kb.ctx)){
            kb.ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:${kb.ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            kb.topBar.showAction("允许悬浮窗权限后，再点一次“悬浮”即可打开",null,3500,null)
        }else StickerOverlayService.start(kb.ctx)
    }
    override val view=StickerShelfView(kb.ctx,true,{item ->
        if(kb.controller.isSensitiveField)kb.topBar.showAction("此输入框不支持图片，请到收纳袋管理页分享",null,2500,null)
        else {
            val repository=StickerRepository.get(kb.ctx)
            if(runCatching {kb.controller.onContent(repository.uri(item),item.mime,item.name)}.getOrDefault(false)){
                repository.used(item.id);kb.topBar.showAction("已插入，请在应用中确认发送",null,2000,null)
            } else StickerSending.share(kb.ctx,item)
        }
    },::manage,::manage,::openFloating,{message->kb.topBar.showAction(message,null,3000,null)},{!kb.controller.isSensitiveField})
    override fun onShow(){view.dark=kb.palette.dark;view.reload()}
    override fun applyTheme(){view.dark=kb.palette.dark}
}
