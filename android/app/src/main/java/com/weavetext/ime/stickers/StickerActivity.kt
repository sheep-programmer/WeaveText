package com.weavetext.ime.stickers

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.weavetext.ime.settings.WeaveSettingsTheme
import android.widget.Toast

/** 管理页与「收纳到织文」分享目标。 Manager and the image-only share target. */
class StickerActivity : ComponentActivity() {
    private val repository by lazy { StickerRepository.get(this) }
    /** 待收纳的分享图片；null 表示没有。 Pending shared images to collect, or null. */
    private val pending = mutableStateOf<List<Uri>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        setContent {
            WeaveSettingsTheme(dark = night) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val uris = pending.value
                    LaunchedEffect(uris) {
                        if (uris != null) {
                            repository.import(uris) { message ->
                                Toast.makeText(this@StickerActivity, message, Toast.LENGTH_LONG).show()
                            }
                            pending.value = null
                        }
                    }
                    StickerManagerScreen(onBack = { finish() })
                }
            }
        }
        collectShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        collectShare(intent)
    }

    private fun collectShare(intent: Intent?) {
        if (intent?.action !in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
        @Suppress("DEPRECATION")
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }.ifEmpty { (0 until (intent?.clipData?.itemCount ?: 0)).mapNotNull { intent?.clipData?.getItemAt(it)?.uri } }
        if (uris.isEmpty()) {
            Toast.makeText(this, "没有收到图片文件，分享链接不能直接收纳为表情", Toast.LENGTH_LONG).show()
        } else {
            // 先取到数据流再清 action：配置重建时不能重复收纳同一张。 Consume before clearing the action.
            pending.value = uris
        }
        intent?.action = null
    }
}
