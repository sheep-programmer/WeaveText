package com.weavetext.ime.link

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.net.wifi.WifiManager

/**
 * 互联开启期间的前台服务：保持进程，持有组播锁（局域网发现需要），显示「已连接」通知。
 * Foreground service while WeaveLink is on: keeps the process alive, holds the multicast lock needed for LAN
 * discovery and shows the "connected" notification.
 */
class LinkService : Service() {
    private var multicast: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        multicast = runCatching {
            getSystemService(WifiManager::class.java)?.createMulticastLock("weavelink")?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = LinkNotifications.ongoingNotification(this, 0, null)
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(LinkNotifications.ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(LinkNotifications.ONGOING_ID, n)
        }
        LinkManager.get(this).ensureRunning()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { multicast?.release() }
        multicast = null
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            val i = Intent(ctx, LinkService::class.java)
            // 后台不允许启动前台服务时（系统限制），内核照样在进程里运行，只是没有常驻通知。
            // If the system forbids starting it from the background, the core still runs in-process, without the notification.
            runCatching { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, LinkService::class.java)) }
        }
    }
}
