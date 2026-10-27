# تطبيق OPSX لمنظومة البث المباشر (OnePlus Streaming Format)

مشروع أندرويد متكامل مبني بالمعمارية الهجينة الحديثة:
- واجهات المستخدم وإدارة الكاميرا: كوتلن خالص 100% عبر Jetpack Compose بدون أي ملفات تخطيط XML.
- الترميز وفك الترميز العتادي: Android MediaCodec (H.264 / HEVC).
- نواة المعالجة السريعة: Rust Core (libopsx_core.so) لحسابات CRC32، وتشفير ChaCha20، وتصحيح الأخطاء FEC عبر JNI.

---

## 1. المعمارية الهندسية

1. **MainActivity.kt:** واجهة تفاعلية كاملة معروضة بـ Jetpack Compose الخالص.
2. **OpsxNativeBridge.kt:** جسر JNI يربط كوتلن بمكتبة Rust المترجمة لجميع معماريات أندرويد (arm64-v8a, armeabi-v7a, x86_64).
3. **CameraRecorder.kt:** يسحب إطارات الكاميرا عبر CameraX ويمررها إلى مسرّع MediaCodec العتادي لتغليفها مباشرة في دفق .opsx.
4. **OpsxPlayer.kt:** يقرأ دفق .opsx، يتحقق من سلامة البايتات عبر Rust CRC32، ويمرر الإطارات إلى MediaCodec Decoder للعرض الفوري على الشاشة.
5. **app/src/main/rust/:** كود Rust الأصلي لمعالجة الحزم والعمليات الثقيلة.

---

## 2. البناء الآلي على GitHub Actions (إنتاج ملف APK)

يحتوي المشروع على خط أنابيب أتمتة متكامل في المسار:
`.github/workflows/build-apk.yml`

### خطوات البناء والتحميل عبر GitHub:
1. ارفع مجلد `opsx-android` إلى مستودعك على GitHub:
   ```bash
   cd /storage/emulated/0/Oneplusapp/opsx-android
   git init
   git add .
   git commit -m "Build OPSX Hybrid Android App with Kotlin Compose and Rust Core"
   git branch -M main
   git remote add origin https://github.com/YOUR_USERNAME/opsx-android.git
   git push -u origin main
   ```
2. فور الرفع، سيبدأ GitHub Actions تلقائياً بالخطوات التالية:
   - إعداد JDK 17 و Android NDK r26b.
   - تجميع كود Rust لكافة معماريات هواتف أندرويد عبر cargo-ndk.
   - تجميع كود Kotlin Compose عبر Gradle.
   - إنشاء ملف الـ APK النهائي.
3. توجه إلى تبويب **Actions** في صفحة مستودعك على GitHub.
4. ادخل على آخر تشغيل (Run)، وستجد ملف الـ APK الجاهز للتحميل في قسم **Artifacts** باسم:
   `OPSX-Player-Debug`
5. حمّل الملف وثبته مباشرة على هاتفك لتجربة التسجيل والتشغيل.
