package com.weavetext.ime.stickers

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

class StickerRepository private constructor(context:Context) {
    private val ctx=context.applicationContext
    val store=StickerStore(File(ctx.filesDir,"stickers")) {file ->
        val options=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeFile(file.path,options);options.outWidth to options.outHeight
    }
    private val main=Handler(Looper.getMainLooper())
    private val listeners=mutableSetOf<()->Unit>()
    fun observe(callback:()->Unit){synchronized(listeners){listeners+=callback}}
    fun unobserve(callback:()->Unit){synchronized(listeners){listeners-=callback}}
    fun changed(){main.post {synchronized(listeners){listeners.toList()}.forEach {it()}}}
    fun uri(sticker:Sticker):Uri {
        val file=store.file(sticker);check(file.isFile){"表情原文件丢失，请重新导入"}
        return FileProvider.getUriForFile(ctx,ctx.packageName+".files",file)
    }
    /**
     * 拖放：授权只在回调期间有效，必须当场打开数据流。
     * Drag and drop: the grant only lasts for the callback, so streams are opened right here.
     */
    fun import(uris:List<Uri>,result:(String)->Unit)=importOpened(uris.take(100).map(::open),result)
    /**
     * 选图器与分享：授权跟随 Activity，查询和打开都放到后台，云端图片下载时界面不卡。
     * Pickers and shares: the grant lives with the Activity, so querying and opening run in the background and
     * cloud-backed photos do not freeze the screen while they download.
     */
    fun importInBackground(uris:List<Uri>,result:(String)->Unit) {
        val list=uris.take(100);io.execute {importOpened(list.map(::open),result)}
    }
    private fun open(uri:Uri)=runCatching {
        require(uri.scheme=="content" || uri.scheme=="file"){"收到的是链接，请从原应用分享图片或保存后导入"}
        val name=com.weavetext.ime.link.LinkContent.describe(ctx,uri).first
        val input=ctx.contentResolver.openInputStream(uri) ?: error("原应用没有开放图片读取权限")
        name to input
    }
    private fun importOpened(inputs:List<Result<Pair<String,InputStream>>>,result:(String)->Unit) {
        io.execute {
            var added=0;var duplicate=0;var failed=0;var lastError=""
            for(input in inputs) {
                runCatching {val (name,stream)=input.getOrThrow();store.import(stream,name)}
                    .fold({if(it.second)added++ else duplicate++},{failed++;lastError=it.message ?: "无法读取图片"})
            }
            changed();main.post {result("已收纳 $added 张"+(if(duplicate>0)"，重复 $duplicate 张"else "")+
                if(failed>0)"，失败 $failed 张：$lastError"else "")}
        }
    }
    fun edit(id:String,name:String,group:String,tags:List<String>,favorite:Boolean,done:((String)->Unit)?=null) {
        io.execute {val result=runCatching {store.edit(id,name,group,tags,favorite)};changed();main.post {done?.invoke(if(result.isSuccess)"已保存"else result.exceptionOrNull()?.message ?: "保存失败")}}
    }
    fun delete(ids:Set<String>,done:(String)->Unit){io.execute {val result=runCatching {store.delete(ids)};changed();main.post {done(if(result.isSuccess)"已删除 ${ids.size} 张"else "删除失败")}}}
    fun used(id:String){io.execute {runCatching {store.used(id)};changed()}}
    companion object {
        val io=Executors.newSingleThreadExecutor {r->Thread(r,"weave-stickers").apply {isDaemon=true}}
        @Volatile private var instance:StickerRepository?=null
        fun get(ctx:Context)=instance ?: synchronized(this){instance ?: StickerRepository(ctx).also {instance=it}}
        internal fun reset(){instance=null}
    }
}

object StickerSending {
    private var receiver:java.lang.ref.WeakReference<com.weavetext.ime.ime.InputController>?=null
    fun activate(controller:com.weavetext.ime.ime.InputController){receiver=java.lang.ref.WeakReference(controller)}
    fun deactivate(controller:com.weavetext.ime.ime.InputController){if(receiver?.get()===controller)receiver=null}
    fun insert(ctx:Context,item:Sticker):Boolean {
        val repository=StickerRepository.get(ctx)
        val sent=runCatching {receiver?.get()?.onContent(repository.uri(item),item.mime,item.name)==true}.getOrDefault(false)
        if(sent)repository.used(item.id)
        return sent
    }
    fun share(ctx:Context,item:Sticker) {
        try {
        val repository=StickerRepository.get(ctx);val uri=repository.uri(item)
        val intent=android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type=item.mime;putExtra(android.content.Intent.EXTRA_STREAM,uri)
            clipData=android.content.ClipData.newUri(ctx.contentResolver,item.name,uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(android.content.Intent.createChooser(intent,"分享表情 · ${item.name}").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        repository.used(item.id)
        }catch(e:Exception){android.widget.Toast.makeText(ctx,e.message ?: "无法分享表情，请重新导入",android.widget.Toast.LENGTH_LONG).show()}
    }
}
