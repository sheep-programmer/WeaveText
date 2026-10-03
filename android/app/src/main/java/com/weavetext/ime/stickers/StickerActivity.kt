package com.weavetext.ime.stickers

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import android.widget.*

/** Manager and image-only Android share target. */
class StickerActivity:ComponentActivity() {
    private lateinit var shelf:StickerShelfView
    private val repository by lazy {StickerRepository.get(this)}
    private var waitingOverlay=false
    private val pictures=registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){collect(it)}
    private val photos=registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)){collect(it)}
    private val archive=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri -> if(uri!=null)StickerRepository.io.execute {
        val result=runCatching {contentResolver.openInputStream(uri)?.let {repository.store.importArchive(it)} ?: error("无法打开备份")}
        repository.changed();runOnUiThread {message(result.fold({"已导入 ${it.first} 张，重复 ${it.second} 张"},{it.message ?: "导入失败"}))}
    }}
    private val export=registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri ->if(uri!=null)StickerRepository.io.execute {
        val result=runCatching {val output=contentResolver.openOutputStream(uri) ?: error("无法写入备份");repository.store.export(output)}
        runOnUiThread {message(if(result.isSuccess)"表情备份已导出"else result.exceptionOrNull()?.message ?: "导出失败")}
    }}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val dark=(resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(if(dark)android.graphics.Color.rgb(20,22,28) else android.graphics.Color.rgb(246,248,252));setPadding(dp(10),dp(8),dp(10),0)}
        val toolbar=LinearLayout(this).apply {gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(dp(6),dp(3),dp(6),dp(8))}
        toolbar.addView(ImageButton(this).apply {setImageResource(com.weavetext.ime.R.drawable.ic_arrow_back);contentDescription="返回";background=null;setOnClickListener {finish()}},LinearLayout.LayoutParams(dp(44),dp(44)))
        val titleBlock=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(dp(8),0,dp(8),0)}
        titleBlock.addView(TextView(this).apply {text="表情收纳袋";textSize=21f;setTextColor(if(dark)android.graphics.Color.rgb(239,241,246) else android.graphics.Color.rgb(28,34,48));setTypeface(null,android.graphics.Typeface.BOLD)},LinearLayout.LayoutParams(-1,dp(27)))
        titleBlock.addView(TextView(this).apply {text="收藏、整理并快速发送你的图片";textSize=12f;setTextColor(if(dark)android.graphics.Color.rgb(164,169,181) else android.graphics.Color.rgb(105,113,130))},LinearLayout.LayoutParams(-1,dp(19)))
        toolbar.addView(titleBlock,LinearLayout.LayoutParams(0,dp(48),1f))
        toolbar.addView(Button(this).apply {text="备份";setOnClickListener {export.launch("织文表情备份.zip")};setPadding(dp(12),0,dp(12),0)})
        root.addView(toolbar)
        shelf=StickerShelfView(this,false,{item->StickerSending.share(this,item)}, {},::chooseImport,::startOverlay,::message)
        root.addView(shelf,LinearLayout.LayoutParams(-1,0,1f))
        root.setOnApplyWindowInsetsListener {_,insets ->
            @Suppress("DEPRECATION") root.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom)
            insets
        }
        setContentView(root);root.requestApplyInsets();collectShare(intent)
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);collectShare(intent)}
    private fun chooseImport(){android.app.AlertDialog.Builder(this).setTitle("收纳表情").setItems(arrayOf("从相册选择","从图片文件导入","从织文表情备份导入","收纳已复制的图片")){_,which->when(which){
        0->photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        1->pictures.launch(arrayOf("image/*","application/octet-stream"))
        2->archive.launch(arrayOf("application/zip","application/octet-stream"))
        3->{val clip=getSystemService(android.content.ClipboardManager::class.java).primaryClip
            val uris=(0 until (clip?.itemCount ?: 0)).mapNotNull {clip?.getItemAt(it)?.uri}
            if(uris.isEmpty())message("剪贴板没有可读取的图片，请从原应用分享或保存后导入")else collect(uris)}
    }}.show()}
    private fun collectShare(intent:Intent?) {
        if(intent?.action !in listOf(Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE))return
        @Suppress("DEPRECATION") val uris=when(intent?.action){
            Intent.ACTION_SEND->listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE->intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else->emptyList()
        }.ifEmpty {(0 until (intent?.clipData?.itemCount ?: 0)).mapNotNull {intent?.clipData?.getItemAt(it)?.uri}}
        if(uris.isEmpty())message("没有收到图片文件，分享链接不能直接收纳为表情")else collect(uris)
        intent?.action=null // Configuration restore must not collect the same share twice.
    }
    private fun collect(uris:List<Uri>){if(uris.isNotEmpty())repository.import(uris,::message)}
    private fun startOverlay(){if(!android.provider.Settings.canDrawOverlays(this)) {
        waitingOverlay=true
        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
        message("允许悬浮窗后可拖入图片；不支持拖出的表情仍可用分享收纳")
    } else StickerOverlayService.start(this)}
    override fun onResume(){super.onResume();if(waitingOverlay){waitingOverlay=false;if(android.provider.Settings.canDrawOverlays(this))StickerOverlayService.start(this)}}
    private fun message(text:String){Toast.makeText(this,text,Toast.LENGTH_LONG).show()}
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
}
