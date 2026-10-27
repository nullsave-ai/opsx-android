package com.oneplus.opsx

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var cameraRecorder: CameraRecorder
    private lateinit var opsxPlayer: OpsxPlayer
    private var previewView: PreviewView? = null
    private var playbackSurfaceView: SurfaceView? = null

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (!granted) {
            updateLog("تنبيه: يجب منح صلاحيات الكاميرا والصوت لتشغيل البث والتسجيل.")
        }
    }

    private val logState = mutableStateOf("جاري تهيئة منظومة OPSX...\n")

    private fun updateLog(msg: String) {
        logState.value += "$msg\n"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraRecorder = CameraRecorder(this)
        opsxPlayer = OpsxPlayer()

        checkPermissions()

        val isRustReady = OpsxNativeBridge.isNativeReady()
        updateLog(if (isRustReady) "نواة Rust الأصلية متصلة وجاهزة (JNI Native Acceleration)" else "تعمل عبر النواة المدمجة (Kotlin Core Engine)")

        setContent {
            OpsxTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF121212)
                ) {
                    OpsxMainScreen()
                }
            }
        }
    }

    private fun checkPermissions() {
        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissionsLauncher.launch(missing.toTypedArray())
        }
    }

    @Composable
    fun OpsxMainScreen() {
        var isRecording by remember { mutableStateOf(false) }
        var isPlaying by remember { mutableStateOf(false) }
        val logs by remember { logState }
        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            // العنوان
            Text(
                text = "منظومة OPSF / OPSX - كوتلن خالص 100% مع Rust Core",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 8.dp)
            )

            // مساحة العرض (كاميرا / مشغل)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black)
            ) {
                if (!isPlaying) {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).also { previewView = it }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceView(ctx).also { playbackSurfaceView = it }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // سجل العمليات والقياسات اللحظية
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .padding(top = 8.dp)
                    .background(Color(0xFF1E1E1E))
                    .padding(8.dp)
                    .verticalScroll(scrollState)
            ) {
                Text(
                    text = logs,
                    color = Color(0xFF00E676),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // أزرار التحكم
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val targetFile = File(getExternalFilesDir(null), "recorded_camera.opsx")
                        if (!isRecording) {
                            previewView?.let { pv ->
                                isRecording = true
                                updateLog("بدء التسجيل العتادي عبر MediaCodec إلى: recorded_camera.opsx")
                                cameraRecorder.startRecording(targetFile, pv, this@MainActivity) { status ->
                                    updateLog(status)
                                }
                            }
                        } else {
                            isRecording = false
                            cameraRecorder.stopRecording {
                                updateLog("تم حفظ مقطع OPSX بنجاح بحجم ${targetFile.length() / 1024} KB.")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) Color.Red else Color(0xFF0D47A1)
                    )
                ) {
                    Text(if (isRecording) "إيقاف التسجيل" else "تسجيل OPSX عتادي")
                }

                Button(
                    onClick = {
                        val targetFile = File(getExternalFilesDir(null), "recorded_camera.opsx")
                        if (!isPlaying) {
                            isPlaying = true
                            lifecycleScope.launch {
                                val holder = playbackSurfaceView?.holder
                                if (holder != null) {
                                    opsxPlayer.play(targetFile, holder.surface) { status ->
                                        updateLog(status)
                                    }
                                    isPlaying = false
                                } else {
                                    updateLog("جاري تهيئة شاشة العرض، اضغط مجدداً...")
                                }
                            }
                        } else {
                            opsxPlayer.stop()
                            isPlaying = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00796B))
                ) {
                    Text(if (isPlaying) "إيقاف المشغل" else "تشغيل مقطع OPSX")
                }
            }
        }
    }

    @Composable
    fun OpsxTheme(content: @Composable () -> Unit) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Color(0xFF0D47A1),
                background = Color(0xFF121212)
            ),
            content = content
        )
    }
}
