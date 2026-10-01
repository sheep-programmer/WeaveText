package com.weavetext.ime.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.weavetext.ime.R
import com.weavetext.ime.settings.SettingsActivity

/** 互联的通知：常驻状态、收到文件、收到文字。 WeaveLink notifications: ongoing status, received files and text. */
object LinkNotifications {
    const val ONGOING_ID = 7301
    private const val CH_ONGOING = "link"
    private const val CH_RECEIVED = "link_received"
    private var nextId = 7400

    private fun channels(ctx: Context): NotificationManager? {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return null
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH_ONGOING) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_ONGOING, "互联状态", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
            nm.createNotificationChannel(NotificationChannel(CH_RECEIVED, "收到的文件与文字", NotificationManager.IMPORTANCE_DEFAULT))
        }
        return nm
    }

    private fun builder(ctx: Context, channel: String): Notification.Builder =
        (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, channel) else @Suppress("DEPRECATION") Notification.Builder(ctx))
            .setSmallIcon(R.drawable.ic_logo)

    private fun openSettings(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(Intent.ACTION_VIEW, Uri.parse("weavetext://settings/link"), ctx, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun ongoingNotification(ctx: Context, count: Int, name: String?): Notification {
        channels(ctx)
        val text = when {
            count == 0 -> "等待电脑连接"
            count == 1 -> "已连接 ${name.orEmpty()}"
            else -> "已连接 $count 台设备"
        }
        return builder(ctx, CH_ONGOING).setContentTitle("织文互联").setContentText(text).setOngoing(true)
            .setContentIntent(openSettings(ctx)).build()
    }

    fun ongoing(ctx: Context, count: Int, name: String?) {
        runCatching { channels(ctx)?.notify(ONGOING_ID, ongoingNotification(ctx, count, name)) }
    }

    fun received(ctx: Context, name: String, mime: String, from: String, uri: Uri) {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, nextId, view, PendingIntent.FLAG_IMMUTABLE)
        val n = builder(ctx, CH_RECEIVED).setContentTitle("收到文件：$name").setContentText(if (from.isEmpty()) "已保存到接收目录" else "来自 $from · 已保存到接收目录")
            .setAutoCancel(true).setContentIntent(pi).build()
        runCatching { channels(ctx)?.notify(nextId++, n) }
    }

    fun text(ctx: Context, from: String, text: String) {
        val n = builder(ctx, CH_RECEIVED).setContentTitle(if (from.isEmpty()) "收到文字" else "来自 $from 的文字").setContentText("已复制：" + text.take(80))
            .setStyle(Notification.BigTextStyle().bigText(text.take(1000))).setAutoCancel(true).build()
        runCatching { channels(ctx)?.notify(nextId++, n) }
    }
}
