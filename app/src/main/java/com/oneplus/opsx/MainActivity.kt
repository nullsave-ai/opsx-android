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
    private lateinit var yacineEngine: YacineOpsxEngine

    private var previewView: PreviewView? = null
    private var playbackSurfaceView: SurfaceView? = null

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (!granted) {
            updateLog("تنبيه: يرجى منح صلاحيات الكاميرا لتشغيل البث والتسجيل.")
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
        yacineEngine = YacineOpsxEngine()

        checkPermissions()

        val isRustReady = OpsxNativeBridge.isNativeReady()
        updateLog(if (isRustReady) "نواة Rust الأصلية متصلة وجاهزة (JNI Native)" else "تعمل عبر النواة المدمجة (Kotlin Engine)")

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
        var isYacineRunning by remember { mutableStateOf(false) }
        var currentOpsxUrl by remember { mutableStateOf("") }
        var transcodeToHevc by remember { mutableStateOf(false) }

        val logs by remember { logState }
        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Text(
                text = "منظومة OPSF / OPSX - كوتلن خالص 100% بدون وسيط",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 6.dp)
            )

            // إعدادات وضع الترميز
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (transcodeToHevc) "وضع الترميز: HEVC (H.265) فائق التوفير" else "وضع الترميز: Direct Remux سريع",
                    color = if (transcodeToHevc) Color(0xFF00E676) else Color(0xFFFFB300),
                    fontSize = 12.sp
                )
                Switch(
                    checked = transcodeToHevc,
                    onCheckedChange = { transcodeToHevc = it }
                )
            }

            // شاشة الكاميرا أو العرض
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black)
            ) {
                if (!isPlaying && !isYacineRunning) {
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

            // سجل العمليات
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .padding(top = 6.dp)
                    .background(Color(0xFF1E1E1E))
                    .padding(8.dp)
                    .verticalScroll(scrollState)
            ) {
                Text(
                    text = logs,
                    color = Color(0xFF00E676),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // أزرار التحكم
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = {
                            val targetFile = File(getExternalFilesDir(null), "recorded_camera.opsx")
                            if (!isRecording) {
                                previewView?.let { pv ->
                                    isRecording = true
                                    updateLog("بدء تسجيل OPSX عتادي: recorded_camera.opsx")
                                    cameraRecorder.startRecording(targetFile, pv, this@MainActivity) { status ->
                                        updateLog(status)
                                    }
                                }
                            } else {
                                isRecording = false
                                cameraRecorder.stopRecording {
                                    updateLog("اكتمل حفظ مقطع OPSX بحجم ${targetFile.length() / 1024} KB.")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording) Color.Red else Color(0xFF0D47A1)
                        )
                    ) {
                        Text(if (isRecording) "إيقاف التسجيل" else "تسجيل كاميرا OPSX")
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
                                        updateLog("شاشة العرض غير مهيأة بعد.")
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
                        Text(if (isPlaying) "إيقاف المشغل" else "تشغيل ملف OPSX")
                    }
                }

                // زر تشغيل قنوات ياسين المباشرة محلياً بدون وسيط
                Button(
                    onClick = {
                        if (!isYacineRunning) {
                            isYacineRunning = true
                            updateLog("بدء تشغيل محرك فك ياسين المباشر...")
                            yacineEngine.startNativeGateway(
                                channelQuery = "1080|1",
                                onUrlReady = { url ->
                                    currentOpsxUrl = url
                                    updateLog("رابط بث قناتك بصيغة OPSX: $url")
                                },
                                onStatus = { status ->
                                    updateLog(status)
                                }
                            )
                        } else {
                            yacineEngine.stop()
                            isYacineRunning = false
                            updateLog("تم إيقاف بث القناة.")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isYacineRunning) Color(0xFFD32F2F) else Color(0xFFE65100)
                    )
                ) {
                    Text(if (isYacineRunning) "إيقاف بث قناة ياسين" else "فك وبث قناة ياسين (beIN 1) بصيغة OPSX")
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

    override fun onDestroy() {
        super.onDestroy()
        yacineEngine.stop()
    }
}
