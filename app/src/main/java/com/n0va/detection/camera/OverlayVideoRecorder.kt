package com.n0va.detection.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.n0va.detection.detection.DetectionResult
import java.io.ByteArrayOutputStream
import java.io.File

class OverlayVideoRecorder(
    private val outputFile: File,
    private val frameW: Int,
    private val frameH: Int,
    private val drawBoxes: Boolean = true,
    private val bitRate: Int = 1_500_000,
    private val frameRate: Int = 20
) {
    companion object {
        private const val TAG = "OverlayRecorder"
        private const val TIMEOUT_US = 10_000L
    }

    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var frameIndex = 0
    private var presentationTimeUs = 0L

    private var detections: List<DetectionResult> = emptyList()
    private val jpegBuffer = ByteArrayOutputStream(1024 * 1024)

    fun setDetections(dets: List<DetectionResult>, rot: Int) {
        detections = dets
    }

    fun start(): Boolean {
        return try {
            val w = if (frameW > 0) frameW else 640
            val h = if (frameH > 0) frameH else 480
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, w, h
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            outputFile.parentFile?.mkdirs()
            mediaMuxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            presentationTimeUs = 0L; frameIndex = 0
            Log.i(TAG, "编码器已启动: ${w}x$h")
            true
        } catch (e: Exception) {
            Log.e(TAG, "编码器启动失败", e); release(); false
        }
    }

    fun submitFrame(nv21: ByteArray, w: Int, h: Int, rot: Int, timestamp: Long) {
        val codec = mediaCodec ?: return
        try {
            val yuv = YuvImage(nv21, android.graphics.ImageFormat.NV21, w, h, null)
            jpegBuffer.reset()
            yuv.compressToJpeg(Rect(0, 0, w, h), 80, jpegBuffer)
            var bitmap = BitmapFactory.decodeByteArray(jpegBuffer.toByteArray(), 0, jpegBuffer.size()) ?: return

            val ew = if (frameW > 0) frameW else w
            val eh = if (frameH > 0) frameH else h
            if (bitmap.width != ew || bitmap.height != eh) {
                bitmap = Bitmap.createScaledBitmap(bitmap, ew, eh, true)
            }

            if (rot != 0) {
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
            }

            if (drawBoxes && detections.isNotEmpty()) {
                val canvas = Canvas(bitmap)
                val boxPaint = Paint().apply {
                    color = Color.GREEN; strokeWidth = 5f; style = Paint.Style.STROKE
                }
                val labelPaint = Paint().apply {
                    color = Color.GREEN; textSize = 36f; style = Paint.Style.FILL
                }
                for (det in detections) {
                    val left = (det.cx - det.w / 2f) * bitmap.width
                    val top = (det.cy - det.h / 2f) * bitmap.height
                    val right = (det.cx + det.w / 2f) * bitmap.width
                    val bottom = (det.cy + det.h / 2f) * bitmap.height
                    canvas.drawRect(left, top, right, bottom, boxPaint)
                    val label = "${det.className} ${"%.0f".format(det.confidence * 100)}%"
                    canvas.drawText(label, left, top - 8f, labelPaint)
                }
                canvas.setBitmap(null)
            }

            val ew2 = bitmap.width; val eh2 = bitmap.height
            val yuvData = bitmapToI420(bitmap, ew2, eh2)
            bitmap.recycle()

            val pts = if (timestamp > 0) timestamp / 1000 else {
                presentationTimeUs += 1_000_000L / frameRate
                presentationTimeUs
            }
            presentationTimeUs = pts

            val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inIdx >= 0) {
                val inBuf = codec.getInputBuffer(inIdx) ?: return
                inBuf.clear(); inBuf.put(yuvData)
                codec.queueInputBuffer(inIdx, 0, yuvData.size, presentationTimeUs, 0)
                frameIndex++
            }
            drainEncoder(codec, false)
        } catch (e: Exception) {
            Log.w(TAG, "帧编码错误: ${e.message}")
        }
    }

    private fun bitmapToI420(bitmap: Bitmap, dstW: Int, dstH: Int): ByteArray {
        val argb = IntArray(dstW * dstH)
        bitmap.getPixels(argb, 0, dstW, 0, 0, dstW, dstH)
        val ySize = dstW * dstH
        val uvSize = (dstW / 2) * (dstH / 2)
        val yuv = ByteArray(ySize + uvSize * 2)
        var uIdx = ySize; var vIdx = ySize + uvSize
        for (j in 0 until dstH) {
            for (i in 0 until dstW) {
                val pixel = argb[j * dstW + i]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[j * dstW + i] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    yuv[uIdx++] = u.coerceIn(0, 255).toByte()
                    yuv[vIdx++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
        return yuv
    }

    private fun drainEncoder(codec: MediaCodec, endOfStream: Boolean) {
        if (endOfStream) codec.signalEndOfInputStream()
        var count = 0
        while (count < 20) {
            count++
            val outInfo = MediaCodec.BufferInfo()
            val outIdx = codec.dequeueOutputBuffer(outInfo, TIMEOUT_US)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (!endOfStream) break }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = mediaMuxer?.addTrack(codec.outputFormat) ?: -1
                        if (trackIndex >= 0) { mediaMuxer?.start(); muxerStarted = true }
                    }
                }
                outIdx >= 0 -> {
                    if (muxerStarted && (outInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val outBuf = codec.getOutputBuffer(outIdx) ?: continue
                        outInfo.presentationTimeUs = presentationTimeUs
                        mediaMuxer?.writeSampleData(trackIndex, outBuf, outInfo)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((outInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        }
    }

    fun stop() {
        try { mediaCodec?.let { drainEncoder(it, true) } } catch (_: Exception) {}
        Log.i(TAG, "录像完成: ${outputFile.absolutePath}, 共 $frameIndex 帧")
        release()
    }

    private fun release() {
        try { mediaCodec?.stop() } catch (_: Exception) {}
        try { mediaCodec?.release() } catch (_: Exception) {}
        mediaCodec = null
        try { if (muxerStarted) mediaMuxer?.stop() } catch (_: Exception) {}
        try { mediaMuxer?.release() } catch (_: Exception) {}
        mediaMuxer = null; muxerStarted = false
    }
}
