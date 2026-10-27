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
        var selectedTab by remember { mutableStateOf(1) } // البداية في صفحة المشغل مباشرة

        var inputSourceUrl by remember { mutableStateOf("1080|1") }
        var generatedOpsxUrl by remember { mutableStateOf("") }
        var isServerRunning by remember { mutableStateOf(false) }

        var testPlayerUrl by remember { mutableStateOf("1080|1") }
        var isPlayerRunning by remember { mutableStateOf(false) }
        var isRecording by remember { mutableStateOf(false) }
        var isLocalPlaying by remember { mutableStateOf(false) }
        var copyNotification by remember { mutableStateOf("") }

        var isFullscreen by remember { mutableStateOf(false) }
        var isLogoOverlayActive by remember { mutableStateOf(true) }

        val context = this@MainActivity
        val overlayNativeBitmap = remember {
            try {
                context.assets.open("oneplus_exact.png").use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                try {
                    context.assets.open("oneplus_logo.png").use { BitmapFactory.decodeStream(it) }
                } catch (e2: Exception) {
                    try {
                        context.assets.open("logo_overlay.png").use { BitmapFactory.decodeStream(it) }
                    } catch (e3: Exception) {
                        null
                    }
                }
            }
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isFullscreen) 0.dp else 10.dp)
        ) {
            // شريط العنوان وأزرار التبويبات (تختفي فقط في وضع ملء الشاشة)
            if (!isFullscreen) {
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
            }

            // ============================================
            // شاشة عارض الفيديو الثابتة (لا يتم تدميرها نهائياً عند ملء الشاشة)
            // ============================================
            if (selectedTab == 1 || isFullscreen) {
                Box(
                    modifier = if (isFullscreen) {
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .height(205.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF141824))
                            .border(1.dp, Color(0xFF1F263B), RoundedCornerShape(10.dp))
                    },
                    contentAlignment = Alignment.Center
                ) {
                    if (isRecording) {
                        AndroidView(
                            factory = { ctx -> PreviewView(ctx).also { previewView = it } },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // حاوية الفيديو والأغطية العتادية (TextureView + FrameLayout)
                        AndroidView(
                            factory = { ctx ->
                                FrameLayout(ctx).apply {
                                    layoutParams = FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.MATCH_PARENT
                                    )

                                    // 1. عارض الفيديو TextureView
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

                                    // 2. غطاء شعار one+ في الركن العلوي الأيمن فوق بين سبورت بدقة تامة وبخلفية تحجب شعار بين
                                    val logoContainer = FrameLayout(ctx).apply {
                                        tag = "logo_overlay_container"
                                        background = GradientDrawable().apply {
                                            shape = GradientDrawable.RECTANGLE
                                            cornerRadius = dpToPx(ctx, 4).toFloat()
                                            setColor(AndroidColor.parseColor("#E60A0D14"))
                                        }
                                        setPadding(dpToPx(ctx, 3), dpToPx(ctx, 2), dpToPx(ctx, 3), dpToPx(ctx, 2))

                                        val iv = ImageView(ctx).apply {
                                            tag = "logo_image"
                                            scaleType = ImageView.ScaleType.FIT_CENTER
                                            if (overlayNativeBitmap != null) {
                                                setImageBitmap(overlayNativeBitmap)
                                            }
                                        }
                                        addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                                    }
                                    addView(logoContainer)
                                }
                            },
                            update = { container ->
                                val ctx = container.context

                                // تحديث موضع وحجم غطاء one+ فوق بين سبورت
                                val logoContainer = container.findViewWithTag<FrameLayout>("logo_overlay_container")
                                if (logoContainer != null) {
                                    val iv = logoContainer.findViewWithTag<ImageView>("logo_image")
                                    if (iv != null && overlayNativeBitmap != null && iv.drawable == null) {
                                        iv.setImageBitmap(overlayNativeBitmap)
                                    }
                                    val w = if (isFullscreen) dpToPx(ctx, 135) else dpToPx(ctx, 92)
                                    val h = if (isFullscreen) dpToPx(ctx, 40) else dpToPx(ctx, 28)
                                    val topM = if (isFullscreen) dpToPx(ctx, 12) else dpToPx(ctx, 6)
                                    val endM = if (isFullscreen) dpToPx(ctx, 16) else dpToPx(ctx, 8)

                                    val lp = FrameLayout.LayoutParams(w, h).apply {
                                        gravity = Gravity.TOP or Gravity.END
                                        setMargins(0, topM, endM, 0)
                                    }
                                    logoContainer.layoutParams = lp
                                    logoContainer.visibility = if (isLogoOverlayActive && (isPlayerRunning || isLocalPlaying)) View.VISIBLE else View.GONE
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // زر التحكم العائم لملء الشاشة أو تصغيرها
                        if (isPlayerRunning || isLocalPlaying) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xCC0D47A1),
                                modifier = Modifier
                                    .align(if (isFullscreen) Alignment.BottomStart else Alignment.BottomEnd)
                                    .padding(if (isFullscreen) 16.dp else 6.dp)
                                    .clickable { isFullscreen = !isFullscreen }
                            ) {
                                Text(
                                    text = if (isFullscreen) "تصغير الشاشة والعودة" else "ملء الشاشة",
                                    color = Color.White,
                                    fontSize = if (isFullscreen) 12.sp else 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
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
                                    text = "اضغط [تشغيل واختبار البث] لبدء المشاهدة الحية",
                                    color = Color(0xFF78909C),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                if (!isFullscreen) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // ============================================
            // عناصر التحكم والسجلات (تظهر فقط خارج وضع ملء الشاشة)
            // ============================================
            if (!isFullscreen) {
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

                            Button(
                                onClick = {
                                    if (!isServerRunning) {
                                        isServerRunning = true
                                        updateLog("بدء تشغيل خادم OPSX وتحويل القناة [$inputSourceUrl]...")
                                        yacineEngine.startStream(
                                            targetUrl = inputSourceUrl,
                                            onUrlReady = { url ->
                                                generatedOpsxUrl = url
                                                testPlayerUrl = inputSourceUrl
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
                        }
                    }
                } else {
                    // التبويب 2: التحكم في المشغل
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFF1F263B), RoundedCornerShape(10.dp))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "رابط أو معرف القناة (1080|1 أو 1080|2 أو M3U8):",
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
                                                updateLog("تحويل وتوجيه المصدر [$trimmedUrl] إلى دفق OPSX الحي...")
                                                yacineEngine.startStream(
                                                    targetUrl = trimmedUrl,
                                                    onUrlReady = { u -> generatedOpsxUrl = u },
                                                    onStatus = { s -> updateLog(s) }
                                                )
                                                delay(1200)
                                                finalPlayUrl = "http://127.0.0.1:8989/live.opsx"
                                            } else {
                                                finalPlayUrl = trimmedUrl
                                            }

                                            updateLog("بدء الاتصال وتشغيل دفق OPSX المباشر...")

                                            var waitAttempts = 0
                                            while (activePlaybackSurface == null && waitAttempts < 20) {
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

                            // أزرار التحكم في وضع ملء الشاشة وتغطية الشعار
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
                                    Text("عرض ملء الشاشة", fontSize = 11.sp, color = Color.White)
                                }

                                Button(
                                    onClick = {
                                        isLogoOverlayActive = !isLogoOverlayActive
                                        copyNotification = if (isLogoOverlayActive) "تم تفعيل غطاء one+" else "تم تعطيل غطاء one+"
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isLogoOverlayActive) Color(0xFF1565C0) else Color(0xFF263238)
                                    )
                                ) {
                                    Text(
                                        text = if (isLogoOverlayActive) "شعار one+: مفعل" else "شعار one+: معطل",
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
