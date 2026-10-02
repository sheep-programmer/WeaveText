package com.weavetext.ime.stickers

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat

/** Optional, user-started, bounded window. It never intercepts touches outside its own rectangle. */
class StickerOverlayService:Service() {
    private lateinit var wm:WindowManager
    private var window:View?=null
    private var parameters:WindowManager.LayoutParams?=null
    private var expanded=false
    private var keyboardTop:Int?=latestKeyboardTop
    private var navBottom=0
    private var statusTop=0
    private var navLeft=0
    private var navRight=0
    override fun onCreate(){super.onCreate();wm=getSystemService(WindowManager::class.java)}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==STOP){stopSelf();return START_NOT_STICKY}
        if(!android.provider.Settings.canDrawOverlays(this)){stopSelf();return START_NOT_STICKY}
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"表情收纳窗",NotificationManager.IMPORTANCE_LOW))
        val close=PendingIntent.getService(this,0,Intent(this,StickerOverlayService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(com.weavetext.ime.R.drawable.ic_sticker_bag)
            .setContentTitle("表情收纳窗已打开").setContentText("拖入可分享的图片收纳；点击表情插入或转发")
            .setOngoing(true).addAction(0,"关闭",close).build()
        if(Build.VERSION.SDK_INT>=34)startForeground(480,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)else startForeground(480,notification)
        active=java.lang.ref.WeakReference(this)
        if(window==null)show(false)
        return START_NOT_STICKY
    }
    private fun show(requestedOpen:Boolean) {
        safeEdges()
        val open=requestedOpen && safeBottom()-statusTop>=dp(190)
        window?.let {runCatching {wm.removeView(it)}};expanded=open
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(5),dp(5),dp(5),dp(5))}
        val color=if(resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK==android.content.res.Configuration.UI_MODE_NIGHT_YES)Color.rgb(31,34,41)else Color.rgb(245,248,255)
        root.background=GradientDrawable().apply {setColor(color);cornerRadius=dp(if(open)16 else 24).toFloat()}
        val header=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL}
        val handle=TextView(this).apply {text=if(open)"拖这里移动收纳袋"else"▣";textSize=if(open)13f else 24f;gravity=Gravity.CENTER;setTextColor(if(color==Color.rgb(31,34,41))Color.WHITE else Color.rgb(40,73,136));contentDescription="表情收纳袋，拖动移动，点击展开"}
        header.addView(handle,LinearLayout.LayoutParams(0,dp(38),1f))
        if(open){header.addView(action("收起"){show(false)});header.addView(action("关闭"){stopSelf()})}
        root.addView(header)
        if(open){
            val shelf=StickerShelfView(this,true,{item ->
                if(StickerSending.insert(this,item))toast("已插入，请在聊天应用中确认发送")else StickerSending.share(this,item)
            },::manage,::manage, {show(false)},::toast)
            root.addView(shelf,LinearLayout.LayoutParams(-1,0,1f))
        }
        StickerDrop.bind(root,StickerRepository.get(this)){toast(it)}
        val old=parameters
        val layout=WindowManager.LayoutParams(dp(if(open)330 else 48),dp(if(open)360 else 48),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,android.graphics.PixelFormat.TRANSLUCENT).apply {
            // Our geometry already includes system bars and the IME. Do not add their offsets a second time.
            if(Build.VERSION.SDK_INT>=30)setFitInsetsTypes(0)
            gravity=Gravity.TOP or Gravity.LEFT
            x=old?.x ?: (screenWidth()-width-dp(10));y=old?.y ?: (screenHeight()*0.4f).toInt()
        }
        parameters=layout;window=root
        var downX=0f;var downY=0f;var startX=0;var startY=0;var moved=false
        handle.setOnTouchListener {_,event ->when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{downX=event.rawX;downY=event.rawY;startX=layout.x;startY=layout.y;moved=false;true}
            MotionEvent.ACTION_MOVE->{val dx=event.rawX-downX;val dy=event.rawY-downY;if(kotlin.math.abs(dx)+kotlin.math.abs(dy)>dp(6))moved=true
                layout.x=startX+dx.toInt();layout.y=startY+dy.toInt();clamp();runCatching {wm.updateViewLayout(root,layout)};true}
            MotionEvent.ACTION_UP->{if(!moved && !open)show(true);true}
            else->true
        }}
        root.setOnApplyWindowInsetsListener {_,insets ->
        if(Build.VERSION.SDK_INT>=30){val safe=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout());statusTop=safe.top;navBottom=safe.bottom
                val ime=insets.getInsets(WindowInsets.Type.ime()).bottom;keyboardTop=latestKeyboardTop ?: if(ime>0)screenHeight()-ime else null
            } else {
                @Suppress("DEPRECATION") val bottom=insets.systemWindowInsetBottom
                @Suppress("DEPRECATION") val top=insets.systemWindowInsetTop
                navBottom=bottom;statusTop=top
            }
            clamp();runCatching {wm.updateViewLayout(root,layout)};insets
        }
        try {clamp();wm.addView(root,layout);root.requestApplyInsets()}catch(e:Exception){toast("无法打开收纳窗，请检查悬浮窗权限");stopSelf()}
    }
    private fun clamp() {
        val p=parameters ?: return
        safeEdges()
        val box=StickerOverlayGeometry.place(screenWidth(),screenHeight(),navLeft,navRight,statusTop,navBottom,keyboardTop,
            dp(if(expanded)330 else 48),dp(if(expanded)360 else 48),p.x,p.y,dp(8))
        p.width=box.width;p.height=box.height;p.x=box.x;p.y=box.y
        if(expanded && p.height<dp(190))window?.post {if(expanded)show(false)}
    }
    private fun safeBottom()=minOf(screenHeight()-navBottom,keyboardTop ?: screenHeight())-dp(8)
    private fun safeEdges() {
        if(Build.VERSION.SDK_INT>=30){
            val safe=wm.maximumWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            statusTop=safe.top;navBottom=safe.bottom;navLeft=safe.left;navRight=safe.right
        }
    }
    override fun onConfigurationChanged(config:android.content.res.Configuration){super.onConfigurationChanged(config);show(expanded)}
    private fun manage(){startActivity(Intent(this,StickerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    private fun action(label:String,onClick:()->Unit)=TextView(this).apply {text=label;textSize=12f;setTextColor(Color.rgb(80,125,230));setPadding(dp(8),dp(8),dp(8),dp(8));setOnClickListener {onClick()}}
    private fun screenSize():android.graphics.Point {
        if(Build.VERSION.SDK_INT>=30){val b=wm.maximumWindowMetrics.bounds;return android.graphics.Point(b.width(),b.height())}
        val size=android.graphics.Point();@Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(size);return size
    }
    private fun screenWidth()=screenSize().x
    private fun screenHeight()=screenSize().y
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun toast(text:String){Toast.makeText(this,text,Toast.LENGTH_LONG).show()}
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onDestroy(){window?.let {runCatching {wm.removeView(it)}};window=null;if(active?.get()===this)active=null;super.onDestroy()}
    companion object {
        private const val CHANNEL="weave-sticker-collection"
        private const val STOP="weave.stickers.stop"
        private var active:java.lang.ref.WeakReference<StickerOverlayService>?=null
        private var latestKeyboardTop:Int?=null
        fun start(ctx:Context){runCatching {ctx.startForegroundService(Intent(ctx,StickerOverlayService::class.java))}.onFailure {Toast.makeText(ctx,"请从表情收纳袋页面开启悬浮窗",Toast.LENGTH_LONG).show()}}
        fun avoidKeyboard(top:Int?){latestKeyboardTop=top;active?.get()?.let {it.keyboardTop=top;it.clamp();val v=it.window;val p=it.parameters;if(v!=null && p!=null)runCatching {it.wm.updateViewLayout(v,p)}}}
    }
}
