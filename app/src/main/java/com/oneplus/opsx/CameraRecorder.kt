package com.oneplus.opsx

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class CameraRecorder(private val context: Context) {

    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var isRecording = AtomicBoolean(false)
    private var muxer: OpsxMuxer? = null
    private var recordThread: Thread? = null

    fun startRecording(
        outputFile: File,
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
        onStatus: (String) -> Unit
    ) {
        val width = 1280
        val height = 720
        val bitRate = 2_000_000 // 2 Mbps
        val frameRate = 30

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2) // GOP = 2s
        }

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = mediaCodec?.createInputSurface()
        mediaCodec?.start()

        val fos = FileOutputStream(outputFile)
        muxer = OpsxMuxer(fos)
        muxer?.writeHeader(timescale = 90000, trackCount = 1)
        muxer?.writeTrackDescriptor(trackId = 1, trackType = 1, codecId = 0x0001, width = width, height = height)

        isRecording.set(true)

        // سحب الإطارات من MediaCodec وحفظها في OPSX
        recordThread = Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            var startPts = -1L

            while (isRecording.get()) {
                val outputBufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
                if (outputBufferIndex >= 0) {
                    val outputBuffer = mediaCodec?.getOutputBuffer(outputBufferIndex) ?: continue
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size > 0) {
                        if (startPts == -1L) startPts = bufferInfo.presentationTimeUs
                        val relativePts = (bufferInfo.presentationTimeUs - startPts) / 1000 // ms
                        val isKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                        val payload = ByteArray(bufferInfo.size)
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.get(payload, 0, bufferInfo.size)

                        muxer?.writeChunk(trackId = 1, isKeyframe = isKey, pts = relativePts, payload = payload)
                    }

                    mediaCodec?.releaseOutputBuffer(outputBufferIndex, false)
                }
            }
        }.apply { start() }

        // ربط الكاميرا مع مدخل الـ MediaCodec السطحي
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().setTargetResolution(Size(width, height)).build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                onStatus("جاري التسجيل العتادي عبر MediaCodec و CameraX...")
            } catch (e: Exception) {
                onStatus("خطأ في تشغيل الكاميرا: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stopRecording(onFinished: (File) -> Unit) {
        isRecording.set(false)
        try {
            recordThread?.join(1000)
            mediaCodec?.stop()
            mediaCodec?.release()
            inputSurface?.release()
            muxer?.close(finalPts = 30000)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
