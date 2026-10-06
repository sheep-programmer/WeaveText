package com.weavetext.translate.google

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import com.weavetext.translation.contract.ITranslationCallback
import com.weavetext.translation.contract.ITranslationPlugin
import com.weavetext.translation.contract.TranslationPluginContract

class TranslationPluginService : Service() {
    private lateinit var engine: Engine
    private lateinit var broker: RequestBroker
    private lateinit var policy: CallerPolicy

    override fun onCreate() {
        super.onCreate()
        engine = Engine()
        broker = RequestBroker { json, listener -> engine.request(json, listener) }
        policy = CallerPolicy(
            permitted = { uid -> uid == applicationInfo.uid || checkPermission(TranslationPluginContract.PERMISSION, -1, uid) == PackageManager.PERMISSION_GRANTED },
            sameSignature = { uid -> packageManager.checkSignatures(uid, applicationInfo.uid) == PackageManager.SIGNATURE_MATCH },
        )
    }

    private fun caller(): Int = Binder.getCallingUid().also { policy.requireTrusted(it) }
    private val binder = object : ITranslationPlugin.Stub() {
        override fun protocolVersion(): Int { caller(); return TranslationPluginContract.VERSION }
        override fun request(requestId: String?, json: String?, callback: ITranslationCallback?) {
            val uid = caller()
            if (callback == null) return
            broker.request(uid, requestId.orEmpty(), json.orEmpty(), callback)
        }
        override fun cancel(requestId: String?) { broker.cancel(caller(), requestId.orEmpty()) }
    }

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onUnbind(intent: Intent?): Boolean { broker.cancelAll(); return false }
    override fun onDestroy() {
        broker.close()
        engine.close()
        super.onDestroy()
    }
}
