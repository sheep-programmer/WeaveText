package com.weavetext.ime.link

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.settings.WeaveSettingsTheme

/** The camera runs only in this explicitly opened screen, and stops on pause or a successful scan. */
class LinkScanActivity : ComponentActivity() {
    private lateinit var preview: BarcodeView
    private var allowed by mutableStateOf(false)
    private var message by mutableStateOf("对准电脑上的织文配对二维码或跨网连接二维码")
    private var cameraError by mutableStateOf(false)
    private var requested = false
    private var foreground = false
    private var completed = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
        if (granted) resumeCamera()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requested = savedInstanceState?.getBoolean("cameraRequested") ?: false
        allowed = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        preview = BarcodeView(this).apply {
            decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            addStateListener(object : CameraPreview.StateListener {
                override fun previewSized() {}
                override fun previewStarted() {}
                override fun previewStopped() {}
                override fun cameraClosed() {}
                override fun cameraError(error: Exception) {
                    cameraError = true
                    message = "无法打开相机，请关闭其他使用相机的应用后重试"
                    pause()
                }
            })
            decodeContinuous(object : BarcodeCallback {
                override fun barcodeResult(result: BarcodeResult) {
                    if (completed || !this@LinkScanActivity.foreground) return
                    if (LinkQrPayload.parse(result.text) == null) {
                        message = "二维码无效或已过期，请扫描织文中的配对二维码"
                        return
                    }
                    completed = true
                    pause()
                    setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_TEXT, result.text))
                    finish()
                }
                override fun possibleResultPoints(points: MutableList<ResultPoint>?) {}
            })
        }
        setContent {
            val dark = when (WeavePrefs.theme(WeavePrefs.of(this))) {
                "dark" -> true; "light" -> false; else -> isSystemInDarkTheme()
            }
            WeaveSettingsTheme(dark) {
                Scaffold(topBar = {
                    TopAppBar(title = { Text("扫描二维码") }, navigationIcon = { TextButton(onClick = { finish() }) { Text("返回") } })
                }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (allowed && !cameraError) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
                                Box(Modifier.size(240.dp).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)))
                            }
                            Text(message, Modifier.padding(24.dp))
                        } else {
                            Column(Modifier.weight(1f).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (cameraError) message else "需要相机权限才能扫描二维码")
                                Button(onClick = {
                                    if (cameraError) { cameraError = false; preview.pause(); resumeCamera() }
                                    else if (requested && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                                    } else { requested = true; permission.launch(Manifest.permission.CAMERA) }
                                }, modifier = Modifier.padding(top = 16.dp)) { Text(if (cameraError) "重试" else "允许使用相机") }
                            }
                        }
                    }
                }
            }
        }
        if (!allowed && !requested) { requested = true; permission.launch(Manifest.permission.CAMERA) }
    }

    private fun resumeCamera() { if (foreground && allowed && !completed && !cameraError) preview.resume() }

    override fun onResume() {
        super.onResume()
        foreground = true
        allowed = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        resumeCamera()
    }

    override fun onPause() {
        foreground = false
        preview.pause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("cameraRequested", requested)
        super.onSaveInstanceState(outState)
    }

    companion object { const val RESULT_TEXT = "weavelink.qr.text" }
}
