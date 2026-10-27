package com.oneplus.opsx

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
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
import androidx.compose.ui.text.style.TextAlign
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
    private var activePlaybackSurface: Surface? = null
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

    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun OpsxMainScreen() {
        var selectedTab by remember { mutableStateOf(0) } // 0 = الخادم والتوليد, 1 = المشغل والاختبار

        var inputSourceUrl by remember { mutableStateOf("1080|1") }
        var generatedOpsxUrl by remember { mutableStateOf("") }
        var isServerRunning by remember { mutableStateOf(false) }

        var testPlayerUrl by remember { mutableStateOf("http://127.0.0.1:8989/live.opsx") }
        var isPlayerRunning by remember { mutableStateOf(false) }
        var isRecording by remember { mutableStateOf(false) }
        var isLocalPlaying by remember { mutableStateOf(false) }
        var copyNotification by remember { mutableStateOf("") }

        var isFullscreen by remember { mutableStateOf(false) }
        var isLogoOverlayActive by remember { mutableStateOf(true) }
        var isBlurOverlayActive by remember { mutableStateOf(true) }

        val context = this@MainActivity
        val overlayNativeBitmap = remember {
            val externalFile = File("/storage/emulated/0/Oneplusapp/opsf_live_demo/file_00000000720881f489949e3529ed9509.png")
            val bmp = if (externalFile.exists()) {
                BitmapFactory.decodeFile(externalFile.absolutePath)
            } else {
                try {
                    context.assets.open("logo_overlay.png").use { BitmapFactory.decodeStream(it) }
                } catch (ignored: Exception) {
                    null
                }
            }
            bmp
        }

        val logs by remember { logState }
        val scrollState = rememberScrollState()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        LaunchedEffect(logs) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }

        LaunchedEffect(copyNotification) {
            if (copyNotification.isNotEmpty()) {
                delay(2500)
                copyNotification = ""
            }
        }

        // تفعيل الغطاء تلقائياً لقنوات ياسين الأولى والثانية
        val isChannel1or2 = testPlayerUrl.contains("1080|1") || testPlayerUrl.contains("1080|2") ||
                            inputSourceUrl.contains("1080|1") || inputSourceUrl.contains("1080|2")

        if (isFullscreen) {
            // ============================================
            // وضع ملء الشاشة الكامل (Fullscreen Viewport)
            // ============================================
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )

                            // 1. عارض الفيديو عتادياً بتقنية TextureView
                            val tv = TextureView(ctx).apply {
                                layoutParams = FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT
                                )
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                        activePlaybackSurface = Surface(st)
                                    }
                                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                        activePlaybackSurface?.release()
                                        activePlaybackSurface = null
                                        return true
                                    }
                                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                                }
                            }
                            addView(tv)

                            // 2. غطاء شعار بين سبورت في الركن العلوي الأيمن بدقة تامة
                            val logoOverlayView = ImageView(ctx).apply {
                                val w = dpToPx(ctx, 150)
                                val h = dpToPx(ctx, 48)
                                val topM = dpToPx(ctx, 16)
                                val endM = dpToPx(ctx, 22)
                                layoutParams = FrameLayout.LayoutParams(w, h).apply {
                                    gravity = Gravity.TOP or Gravity.END
                                    setMargins(0, topM, endM, 0)
                                }
                                scaleType = ImageView.ScaleType.FIT_XY
                                if (overlayNativeBitmap != null) {
                                    setImageBitmap(overlayNativeBitmap)
                                }
                                visibility = if (isLogoOverlayActive && isChannel1or2) View.VISIBLE else View.GONE
                            }
                            addView(logoOverlayView)

                            // 3. طبقة تمويه وتشويش نص ياسين تيفي في أقصى اليسار العلوي
                            val yacineBlurView = View(ctx).apply {
                                val w = dpToPx(ctx, 210)
                                val h = dpToPx(ctx, 44)
                                val topM = dpToPx(ctx, 16)
                                val startM = dpToPx(ctx, 18)
                                layoutParams = FrameLayout.LayoutParams(w, h).apply {
                                    gravity = Gravity.TOP or Gravity.START
                                    setMargins(startM, topM, 0, 0)
                                }
                                background = GradientDrawable().apply {
                                    shape = GradientDrawable.RECTANGLE
                                    cornerRadius = dpToPx(ctx, 6).toFloat()
                                    orientation = GradientDrawable.Orientation.LEFT_RIGHT
                                    colors = intArrayOf(
                                        AndroidColor.parseColor("#E60B0E14"),
                                        AndroidColor.parseColor("#D9141824"),
                                        AndroidColor.parseColor("#B30B0E14")
                                    )
                                    setStroke(dpToPx(ctx, 1), AndroidColor.parseColor("#402979FF"))
                                }
                                visibility = if (isBlurOverlayActive && isChannel1or2) View.VISIBLE else View.GONE
                            }
                            addView(yacineBlurView)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // زر الخروج من وضع ملء الشاشة
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xCC0D47A1),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp)
                        .clickable { isFullscreen = false }
                ) {
                    Text(
                        text = "تصغير الشاشة والعودة للتحكم",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        } else {
            // ============================================
            // الواجهة العادية المتكاملة
            // ============================================
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // شريط العنوان
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "منظومة OPSF / OPSX للبث الفائق",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "كوتلن خالص 100% + معالجة Rust الأصلية بدون وسيط",
                            color = Color(0xFF90A4AE),
                            fontSize = 10.sp
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (OpsxNativeBridge.isNativeReady()) Color(0xFF1565C0) else Color(0xFFE65100)
                    ) {
                        Text(
                            text = if (OpsxNativeBridge.isNativeReady()) "Rust JNI: متصل" else "Kotlin Fallback",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                // شريط التبويبات
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color(0xFF141824),
                    contentColor = Color(0xFF2979FF),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Text(
                                text = "1. الخادم وتوليد الرابط",
                                fontSize = 12.sp,
                                fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTab == 0) Color(0xFF00B0FF) else Color(0xFF90A4AE)
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Text(
                                text = "2. المشغل واختبار البث",
                                fontSize = 12.sp,
                                fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTab == 1) Color(0xFF00B0FF) else Color(0xFF90A4AE)
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // محتوى التبويب المختار
                if (selectedTab == 0) {
                    // التبويب 1: الخادم وتوليد رابط البث
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFF1F263B), RoundedCornerShape(10.dp))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "مصدر البث (معرف قناة ياسين أو رابط M3U8/TS مخصص):",
                                color = Color(0xFFCFD8DC),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            OutlinedTextField(
                                value = inputSourceUrl,
                                onValueChange = { inputSourceUrl = it },
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

                            // أزرار الحافظة للمصدر
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
                                                inputSourceUrl = text
                                                copyNotification = "تم لصق الرابط في حقل المصدر"
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2838))
                                ) {
                                    Text("لصق", fontSize = 11.sp, color = Color(0xFF90CAF9))
                                }

                                Button(
                                    onClick = {
                                        val clip = ClipData.newPlainText("stream_source", inputSourceUrl)
                                        clipboard.setPrimaryClip(clip)
                                        copyNotification = "تم نسخ رابط المصدر"
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2838))
                                ) {
                                    Text("نسخ", fontSize = 11.sp, color = Color(0xFF90CAF9))
                                }

                                Button(
                                    onClick = { inputSourceUrl = "" },
                                    modifier = Modifier.weight(0.7f),
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2026))
                                ) {
                                    Text("مسح", fontSize = 11.sp, color = Color(0xFFFF8A80))
                                }
                            }

                            // أزرار القنوات السريعة
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val channels = listOf(
                                    "beIN 1" to "1080|1",
                                    "beIN 2" to "1080|2",
                                    "beIN 3" to "1080|3",
                                    "beIN Xtra" to "https://bein-xtra-bein.amagi.tv/playlist.m3u8",
                                    "Alkass 1" to "https://liveeu-gcps.alkassdigital.net/alkass1-p/main.m3u8"
                                )
                                channels.forEach { (name, id) ->
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (inputSourceUrl == id) Color(0xFF2979FF) else Color(0xFF1E2433),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { inputSourceUrl = id }
                                    ) {
                                        Text(
                                            text = name,
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 5.dp),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // زر تشغيل أو إيقاف خادم التوليد
                            Button(
                                onClick = {
                                    if (!isServerRunning) {
                                        isServerRunning = true
                                        updateLog("بدء تشغيل خادم OPSX وتحويل القناة [$inputSourceUrl]...")
                                        yacineEngine.startStream(
                                            targetUrl = inputSourceUrl,
                                            onUrlReady = { url ->
                                                generatedOpsxUrl = url
                                                testPlayerUrl = url
                                                updateLog("تم توليد رابط بث OPSX بنجاح: $url")
                                            },
                                            onStatus = { status -> updateLog(status) }
                                        )
                                    } else {
                                        yacineEngine.stop()
                                        isServerRunning = false
                                        updateLog("تم إيقاف خادم البث.")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isServerRunning) Color(0xFFC62828) else Color(0xFF1565C0)
                                )
                            ) {
                                Text(
                                    text = if (isServerRunning) "إيقاف خادم البث" else "بدء خادم البث وتوليد رابط OPSX",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }

                            // صندوق عرض الرابط الناتج
                            if (generatedOpsxUrl.isNotEmpty() || isServerRunning) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Card(
                                    shape = RoundedCornerShape(8.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1420)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, Color(0xFF2979FF), RoundedCornerShape(8.dp))
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Text(
                                            text = "رابط البث الناتج بنظام OPSX:",
                                            color = Color(0xFF80D8FF),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = generatedOpsxUrl.ifEmpty { "جاري استقرار وتوليد الرابط..." },
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Button(
                                                onClick = {
                                                    val clip = ClipData.newPlainText("opsx_live_url", generatedOpsxUrl)
                                                    clipboard.setPrimaryClip(clip)
                                                    copyNotification = "تم نسخ رابط OPSX! انتقل الآن لتبويب المشغل لتجربته"
                                                },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(6.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D47A1))
                                            ) {
                                                Text("نسخ رابط البث الناتج", fontSize = 11.sp, color = Color.White)
                                            }

                                            Button(
                                                onClick = {
                                                    testPlayerUrl = generatedOpsxUrl
                                                    selectedTab = 1
                                                },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(6.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D47A1))
                                            ) {
                                                Text("الانتقال لصفحة المشغل", fontSize = 11.sp, color = Color.White)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // التبويب 2: المشغل واختبار البث
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(195.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF141824))
                            .border(1.dp, Color(0xFF1F263B), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isRecording) {
                            AndroidView(
                                factory = { ctx ->
                                    PreviewView(ctx).also { previewView = it }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else if (isPlayerRunning || isLocalPlaying) {
                            AndroidView(
                                factory = { ctx ->
                                    FrameLayout(ctx).apply {
                                        layoutParams = FrameLayout.LayoutParams(
                                            FrameLayout.LayoutParams.MATCH_PARENT,
                                            FrameLayout.LayoutParams.MATCH_PARENT
                                        )

                                        val tv = TextureView(ctx).apply {
                                            layoutParams = FrameLayout.LayoutParams(
                                                FrameLayout.LayoutParams.MATCH_PARENT,
                                                FrameLayout.LayoutParams.MATCH_PARENT
                                            )
                                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                                    activePlaybackSurface = Surface(st)
                                                }
                                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                                    activePlaybackSurface?.release()
                                                    activePlaybackSurface = null
                                                    return true
                                                }
                                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                                            }
                                        }
                                        addView(tv)

                                        // غطاء شعار بين سبورت في الركن العلوي الأيمن
                                        val logoOverlayView = ImageView(ctx).apply {
                                            val w = dpToPx(ctx, 110)
                                            val h = dpToPx(ctx, 35)
                                            val topM = dpToPx(ctx, 6)
                                            val endM = dpToPx(ctx, 10)
                                            layoutParams = FrameLayout.LayoutParams(w, h).apply {
                                                gravity = Gravity.TOP or Gravity.END
                                                setMargins(0, topM, endM, 0)
                                            }
                                            scaleType = ImageView.ScaleType.FIT_XY
                                            if (overlayNativeBitmap != null) {
                                                setImageBitmap(overlayNativeBitmap)
                                            }
                                            visibility = if (isLogoOverlayActive && isChannel1or2) View.VISIBLE else View.GONE
                                        }
                                        addView(logoOverlayView)

                                        // طبقة تمويه نص ياسين في أقصى اليسار
                                        val yacineBlurView = View(ctx).apply {
                                            val w = dpToPx(ctx, 145)
                                            val h = dpToPx(ctx, 32)
                                            val topM = dpToPx(ctx, 6)
                                            val startM = dpToPx(ctx, 8)
                                            layoutParams = FrameLayout.LayoutParams(w, h).apply {
                                                gravity = Gravity.TOP or Gravity.START
                                                setMargins(startM, topM, 0, 0)
                                            }
                                            background = GradientDrawable().apply {
                                                shape = GradientDrawable.RECTANGLE
                                                cornerRadius = dpToPx(ctx, 6).toFloat()
                                                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                                                colors = intArrayOf(
                                                    AndroidColor.parseColor("#E60B0E14"),
                                                    AndroidColor.parseColor("#D9141824"),
                                                    AndroidColor.parseColor("#B30B0E14")
                                                )
                                                setStroke(dpToPx(ctx, 1), AndroidColor.parseColor("#402979FF"))
                                            }
                                            visibility = if (isBlurOverlayActive && isChannel1or2) View.VISIBLE else View.GONE
                                        }
                                        addView(yacineBlurView)
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )

                            // زر ملء الشاشة العائم
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xCC0D47A1),
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(6.dp)
                                    .clickable { isFullscreen = true }
                            ) {
                                Text(
                                    text = "ملء الشاشة",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "شاشة اختبار البث (Low-Latency Hardware + AAC Audio)",
                                    color = Color(0xFFB0BEC5),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "ضع رابط البث واضغط [تشغيل واختبار البث بالصوت والصورة]",
                                    color = Color(0xFF78909C),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // حقل إدخال رابط الاختبار
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFF1F263B), RoundedCornerShape(10.dp))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "رابط البث المراد تشغيله واختباره:",
                                color = Color(0xFFCFD8DC),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            OutlinedTextField(
                                value = testPlayerUrl,
                                onValueChange = { testPlayerUrl = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color(0xFFECEFF1),
                                    focusedContainerColor = Color(0xFF0F121C),
                                    unfocusedContainerColor = Color(0xFF0F121C),
                                    focusedBorderColor = Color(0xFF00B0FF),
                                    unfocusedBorderColor = Color(0xFF263238),
                                    cursorColor = Color(0xFF00B0FF)
                                )
                            )

                            Spacer(modifier = Modifier.height(6.dp))

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
                                                testPlayerUrl = text
                                                copyNotification = "تم لصق الرابط في المشغل"
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
                                        if (generatedOpsxUrl.isNotEmpty()) {
                                            testPlayerUrl = generatedOpsxUrl
                                            copyNotification = "تم جلب رابط الخادم الحالي"
                                        } else {
                                            testPlayerUrl = "http://127.0.0.1:8989/live.opsx"
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2838))
                                ) {
                                    Text("جلب رابط الخادم", fontSize = 11.sp, color = Color(0xFF90CAF9))
                                }

                                Button(
                                    onClick = { testPlayerUrl = "" },
                                    modifier = Modifier.weight(0.7f),
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2026))
                                ) {
                                    Text("مسح", fontSize = 11.sp, color = Color(0xFFFF8A80))
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // أزرار تشغيل المشغل
                            Button(
                                onClick = {
                                    if (!isPlayerRunning) {
                                        isPlayerRunning = true
                                        isLocalPlaying = false

                                        activePlaybackJob?.cancel()
                                        activePlaybackJob = lifecycleScope.launch {
                                            val trimmedUrl = testPlayerUrl.trim()
                                            val finalPlayUrl: String

                                            if (trimmedUrl.contains(".m3u8") || trimmedUrl.contains("|") || trimmedUrl.contains("ch=")) {
                                                updateLog("تحويل وتوجيه المصدر [$trimmedUrl] إلى OPSX...")
                                                yacineEngine.startStream(
                                                    targetUrl = trimmedUrl,
                                                    onUrlReady = { u -> generatedOpsxUrl = u },
                                                    onStatus = { s -> updateLog(s) }
                                                )
                                                delay(1500)
                                                finalPlayUrl = "http://127.0.0.1:8989/live.opsx"
                                            } else {
                                                finalPlayUrl = trimmedUrl
                                            }

                                            updateLog("بدء تشغيل واختبار الدفق من: $finalPlayUrl (مزامنة عتادية متناسقة 1.0x)")
                                            
                                            // انتظار جاهزية السطح العتادي
                                            var waitAttempts = 0
                                            while (activePlaybackSurface == null && waitAttempts < 15) {
                                                delay(100)
                                                waitAttempts++
                                            }

                                            val surf = activePlaybackSurface
                                            if (surf != null) {
                                                opsxPlayer.playUrl(finalPlayUrl, surf) { status ->
                                                    updateLog(status)
                                                }
                                                isPlayerRunning = false
                                            } else {
                                                updateLog("تنبيه: شاشة العرض العتادية غير جاهزة بعد")
                                                isPlayerRunning = false
                                            }
                                        }
                                    } else {
                                        opsxPlayer.stop()
                                        activePlaybackJob?.cancel()
                                        isPlayerRunning = false
                                        updateLog("تم إيقاف المشغل.")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isPlayerRunning) Color(0xFFD32F2F) else Color(0xFF1565C0)
                                )
                            ) {
                                Text(
                                    text = if (isPlayerRunning) "إيقاف المشغل" else "تشغيل واختبار البث (صورة طبيعية 1.0x + صوت)",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // أزرار التحكم في وضع ملء الشاشة وتغطية الشعار والتمويه
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(
                                    onClick = { isFullscreen = true },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D47A1))
                                ) {
                                    Text("عرض ملء الشاشة", fontSize = 10.sp, color = Color.White)
                                }

                                Button(
                                    onClick = {
                                        isLogoOverlayActive = !isLogoOverlayActive
                                        copyNotification = if (isLogoOverlayActive) "تم تفعيل تغطية شعار beIN" else "تم تعطيل تغطية الشعار"
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isLogoOverlayActive) Color(0xFF1565C0) else Color(0xFF263238)
                                    )
                                ) {
                                    Text(
                                        text = if (isLogoOverlayActive) "غطاء beIN: مفعل" else "غطاء beIN: معطل",
                                        fontSize = 10.sp,
                                        color = Color.White
                                    )
                                }

                                Button(
                                    onClick = {
                                        isBlurOverlayActive = !isBlurOverlayActive
                                        copyNotification = if (isBlurOverlayActive) "تم تفعيل تمويه نص ياسين" else "تم تعطيل تمويه النص"
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isBlurOverlayActive) Color(0xFF1565C0) else Color(0xFF263238)
                                    )
                                ) {
                                    Text(
                                        text = if (isBlurOverlayActive) "تمويه ياسين: مفعل" else "تمويه ياسين: معطل",
                                        fontSize = 10.sp,
                                        color = Color.White
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // أزرار الكاميرا والتسجيل المحلي
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
                                                isPlayerRunning = false
                                                updateLog("بدء تسجيل كاميرا OPSX عتادياً...")
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
                                    shape = RoundedCornerShape(6.dp),
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
                                            isPlayerRunning = false
                                            activePlaybackJob?.cancel()
                                            activePlaybackJob = lifecycleScope.launch {
                                                var waitAttempts = 0
                                                while (activePlaybackSurface == null && waitAttempts < 15) {
                                                    delay(100)
                                                    waitAttempts++
                                                }
                                                val surf = activePlaybackSurface
                                                if (surf != null) {
                                                    opsxPlayer.playFile(targetFile, surf) { status ->
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
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isLocalPlaying) Color(0xFFC62828) else Color(0xFF455A64)
                                    )
                                ) {
                                    Text(
                                        text = if (isLocalPlaying) "إيقاف الملف" else "تشغيل ملف محلي",
                                        fontSize = 11.sp,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // شريط الإشعار
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

                // صندوق السجل والتفاصيل الحية
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
                                text = "سجل العمليات والتفاصيل الحية (صوت وفيديو + Rust Core):",
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
                                color = Color(0xFF80D8FF),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 15.sp
                            )
                        }
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
        activePlaybackSurface?.release()
        activePlaybackSurface = null
    }
}
