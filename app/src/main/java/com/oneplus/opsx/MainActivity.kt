package com.oneplus.opsx

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var cameraRecorder: CameraRecorder
    private lateinit var opsxPlayer: OpsxPlayer
    private lateinit var yacineEngine: YacineOpsxEngine

    private var previewView: PreviewView? = null
    private var playbackSurfaceView: SurfaceView? = null
    private var activePlaybackJob: Job? = null

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (!granted) {
            updateLog("تنبيه: يرجى منح أذونات الكاميرا والصوت للتمكن من التسجيل.")
        }
    }

    private val logState = mutableStateOf("بدء تهيئة منظومة OPSF / OPSX...\n")

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
        updateLog(if (isRustReady) "نواة Rust الأصلية (libopsx_core.so) متصلة وجاهزة للعمل" else "تعمل عبر النواة المدمجة (Kotlin Engine)")

        setContent {
            OpsxTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0B0E14)
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

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun OpsxMainScreen() {
        var inputUrl by remember { mutableStateOf("1080|1") }
        var isRecording by remember { mutableStateOf(false) }
        var isLiveWatching by remember { mutableStateOf(false) }
        var isServerOnlyRunning by remember { mutableStateOf(false) }
        var isLocalPlaying by remember { mutableStateOf(false) }
        var currentLocalUrl by remember { mutableStateOf("") }
        var copyNotification by remember { mutableStateOf("") }

        val logs by remember { logState }
        val scrollState = rememberScrollState()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        // التمرير التلقائي لأسفل السجل عند وصول أسطر جديدة
        LaunchedEffect(logs) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }

        // إخفاء إشعار النسخ بعد مهلة قصيرة
        LaunchedEffect(copyNotification) {
            if (copyNotification.isNotEmpty()) {
                delay(2500)
                copyNotification = ""
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // شريط العنوان العلوي
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "منظومة OPSF / OPSX للبث الفائق",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "كوتلن خالص 100% + معالجة Rust الأصلية بدون وسيط",
                        color = Color(0xFF90A4AE),
                        fontSize = 11.sp
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (OpsxNativeBridge.isNativeReady()) Color(0xFF1565C0) else Color(0xFFE65100),
                    modifier = Modifier.padding(start = 6.dp)
                ) {
                    Text(
                        text = if (OpsxNativeBridge.isNativeReady()) "Rust JNI: متصل" else "Kotlin Fallback",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // شاشة العرض (مشغل البث أو الكاميرا)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF141824))
                    .border(1.dp, Color(0xFF1F263B), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (isRecording) {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).also { previewView = it }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (isLiveWatching || isLocalPlaying) {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceView(ctx).also { playbackSurfaceView = it }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "شاشة العرض المباشر (MediaCodec Hardware)",
                            color = Color(0xFFB0BEC5),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "اختر قناة واضغط [مشاهدة القناة داخل التطبيق] للبدء الفوري",
                            color = Color(0xFF78909C),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // بطاقة إدخال الرابط والقنوات السريعة
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF1F263B), RoundedCornerShape(10.dp))
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = "رابط البث أو معرف القناة:",
                        color = Color(0xFFCFD8DC),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    OutlinedTextField(
                        value = inputUrl,
                        onValueChange = { inputUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color(0xFFECEFF1),
                            focusedContainerColor = Color(0xFF0F121C),
                            unfocusedContainerColor = Color(0xFF0F121C),
                            focusedBorderColor = Color(0xFF2979FF),
                            unfocusedBorderColor = Color(0xFF263238),
                            cursorColor = Color(0xFF00B0FF)
                        )
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // أزرار الحافظة (لصق / نسخ / مسح)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = {
                                val clip = clipboard.primaryClip
                                if (clip != null && clip.itemCount > 0) {
                                    val text = clip.getItemAt(0).text?.toString() ?: ""
                                    if (text.isNotBlank()) {
                                        inputUrl = text
                                        copyNotification = "تم لصق الرابط بنجاح"
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2838))
                        ) {
                            Text("لصق الرابط", fontSize = 11.sp, color = Color(0xFF90CAF9))
                        }

                        Button(
                            onClick = {
                                val clip = ClipData.newPlainText("stream_url", inputUrl)
                                clipboard.setPrimaryClip(clip)
                                copyNotification = "تم نسخ رابط القناة إلى الحافظة"
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2838))
                        ) {
                            Text("نسخ الرابط", fontSize = 11.sp, color = Color(0xFF90CAF9))
                        }

                        Button(
                            onClick = { inputUrl = "" },
                            modifier = Modifier.weight(0.7f),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2026))
                        ) {
                            Text("مسح", fontSize = 11.sp, color = Color(0xFFFF8A80))
                        }
                    }

                    // قنوات سريعة جاهزة
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val channels = listOf(
                            "beIN 1" to "1080|1",
                            "beIN 2" to "1080|2",
                            "beIN 3" to "1080|3",
                            "SSC 1" to "1080|4"
                        )
                        channels.forEach { (name, id) ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (inputUrl == id) Color(0xFF2979FF) else Color(0xFF1E2433),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { inputUrl = id }
                            ) {
                                Text(
                                    text = name,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .padding(vertical = 5.dp)
                                        .align(Alignment.CenterHorizontally),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // أزرار التحكم الرئيسية
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // زر مشاهدة القناة مباشرة داخل التطبيق
                Button(
                    onClick = {
                        if (!isLiveWatching) {
                            isLiveWatching = true
                            isLocalPlaying = false
                            updateLog("جاري بدء فك دفق [$inputUrl] وتشغيله داخل التطبيق...")

                            yacineEngine.startStream(
                                targetUrl = inputUrl,
                                onUrlReady = { localUrl ->
                                    currentLocalUrl = localUrl
                                    updateLog("رابط OPSX المحلي: $localUrl")

                                    // بدء تشغيل المشغل العتادي على الشاشة مباشرة
                                    activePlaybackJob?.cancel()
                                    activePlaybackJob = lifecycleScope.launch {
                                        delay(1200) // إتاحة وقت كافٍ لبدء تدفق الحزم الأولية
                                        val holder = playbackSurfaceView?.holder
                                        if (holder != null) {
                                            opsxPlayer.playUrl(localUrl, holder.surface) { status ->
                                                updateLog(status)
                                            }
                                        } else {
                                            updateLog("تنبيه: السطح الرسومي غير جاهز بعد")
                                        }
                                    }
                                },
                                onStatus = { status ->
                                    updateLog(status)
                                }
                            )
                        } else {
                            yacineEngine.stop()
                            opsxPlayer.stop()
                            activePlaybackJob?.cancel()
                            isLiveWatching = false
                            updateLog("تم إيقاف المشغل والبث المباشر.")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isLiveWatching) Color(0xFFD32F2F) else Color(0xFF1565C0)
                    )
                ) {
                    Text(
                        text = if (isLiveWatching) "إيقاف مشاهدة البث" else "مشاهدة وتشغيل القناة داخل التطبيق (Live In-App)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                // صف أزرار: خادم البث فقط + تسجيل الكاميرا
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = {
                            if (!isServerOnlyRunning) {
                                isServerOnlyRunning = true
                                updateLog("تشغيل خادم تحويل الدفق إلى OPSX في الخلفية...")
                                yacineEngine.startStream(
                                    targetUrl = inputUrl,
                                    onUrlReady = { localUrl ->
                                        currentLocalUrl = localUrl
                                        updateLog("رابط OPSX الخارجي: $localUrl")
                                    },
                                    onStatus = { status -> updateLog(status) }
                                )
                            } else {
                                yacineEngine.stop()
                                isServerOnlyRunning = false
                                updateLog("تم إيقاف خادم OPSX.")
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isServerOnlyRunning) Color(0xFFC62828) else Color(0xFF263238)
                        )
                    ) {
                        Text(
                            text = if (isServerOnlyRunning) "إيقاف الخادم" else "بدء خادم OPSX",
                            fontSize = 11.sp,
                            color = Color.White
                        )
                    }

                    Button(
                        onClick = {
                            val targetFile = File(getExternalFilesDir(null), "recorded_camera.opsx")
                            if (!isRecording) {
                                previewView?.let { pv ->
                                    isRecording = true
                                    isLiveWatching = false
                                    updateLog("بدء تسجيل كاميرا الهاتف بصيغة OPSX عتادياً...")
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
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording) Color(0xFFC62828) else Color(0xFF37474F)
                        )
                    ) {
                        Text(
                            text = if (isRecording) "إيقاف التسجيل" else "تسجيل كاميرا OPSX",
                            fontSize = 11.sp,
                            color = Color.White
                        )
                    }

                    Button(
                        onClick = {
                            val targetFile = File(getExternalFilesDir(null), "recorded_camera.opsx")
                            if (!isLocalPlaying) {
                                isLocalPlaying = true
                                isLiveWatching = false
                                activePlaybackJob?.cancel()
                                activePlaybackJob = lifecycleScope.launch {
                                    val holder = playbackSurfaceView?.holder
                                    if (holder != null) {
                                        opsxPlayer.playFile(targetFile, holder.surface) { status ->
                                            updateLog(status)
                                        }
                                        isLocalPlaying = false
                                    } else {
                                        updateLog("شاشة العرض غير مهيأة بعد.")
                                    }
                                }
                            } else {
                                opsxPlayer.stop()
                                activePlaybackJob?.cancel()
                                isLocalPlaying = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isLocalPlaying) Color(0xFFC62828) else Color(0xFF455A64)
                        )
                    ) {
                        Text(
                            text = if (isLocalPlaying) "إيقاف المشغل" else "تشغيل ملف محلي",
                            fontSize = 11.sp,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // شريط إشعار الحافظة عند النسخ
            if (copyNotification.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF0D47A1),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                ) {
                    Text(
                        text = copyNotification,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // نافذة السجل والتفاصيل (بدون أي لون أخضر نهائياً)
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF10131B)),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .border(1.dp, Color(0xFF1C2233), RoundedCornerShape(10.dp))
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "تفاصيل وسجل العمليات الحية (نواة Rust + فك MediaCodec):",
                            color = Color(0xFFB0BEC5),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFF1E2838),
                                modifier = Modifier.clickable {
                                    val clip = ClipData.newPlainText("opsx_logs", logs)
                                    clipboard.setPrimaryClip(clip)
                                    copyNotification = "تم نسخ سجل العمليات كاملاً"
                                }
                            ) {
                                Text(
                                    text = "نسخ السجل",
                                    color = Color(0xFF80D8FF),
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFF261D24),
                                modifier = Modifier.clickable {
                                    logState.value = "تم تفريغ السجل.\n"
                                }
                            ) {
                                Text(
                                    text = "مسح",
                                    color = Color(0xFFFF8A80),
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF090B10))
                            .padding(6.dp)
                            .verticalScroll(scrollState)
                    ) {
                        Text(
                            text = logs,
                            color = Color(0xFF80D8FF), // نص سماوي ثلجي أنيق - خالٍ تماماً من اللون الأخضر
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun OpsxTheme(content: @Composable () -> Unit) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Color(0xFF2979FF),
                secondary = Color(0xFF00B0FF),
                background = Color(0xFF0B0E14),
                surface = Color(0xFF141824)
            ),
            content = content
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        yacineEngine.stop()
        opsxPlayer.stop()
        activePlaybackJob?.cancel()
    }
}
