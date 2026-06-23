package com.unidroid.macro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.RingtoneManager
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import java.nio.ByteBuffer

class MacroEngine(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val screenDensity: Int
) {
    private val handler = Handler(Looper.getMainLooper())
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var isRunning = false

    // Configurable Settings
    var intervalMs: Long = 1000L
    var isClickEnabled: Boolean = false
    var clickX: Float = 0f
    var clickY: Float = 0f

    var isImageCompareEnabled: Boolean = false
    var cropRect: Rect? = null
    var targetBitmap: Bitmap? = null
    var similarityThreshold: Float = 0.80f // 80% matches required
    var alertType: String = "BOTH" // NONE, VIBRATE, SOUND, BOTH

    private val macroRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return

            try {
                executeMacroStep()
            } catch (e: Exception) {
                Log.e(TAG, "Error executing macro step", e)
            }

            handler.postDelayed(this, intervalMs)
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        setupVirtualDisplay()
        handler.post(macroRunnable)
        Log.d(TAG, "MacroEngine started")
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        handler.removeCallbacks(macroRunnable)
        releaseVirtualDisplay()
        targetBitmap = null
        Log.d(TAG, "MacroEngine stopped")
    }

    private fun setupVirtualDisplay() {
        // Create an ImageReader to capture screen pixels
        // We use RGBA_8888 format
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "MacroCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_KEYGUARD_SHOW,
            imageReader?.surface,
            null,
            null
        )
    }

    private fun releaseVirtualDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
    }

    private fun executeMacroStep() {
        // Step 1: Perform Click if enabled
        if (isClickEnabled) {
            Log.d(TAG, "Dispatching click at ($clickX, $clickY)")
            MacroAccessibilityService.clickAt(clickX, clickY)
        }

        // Step 2: Compare screen region if enabled
        if (isImageCompareEnabled) {
            val rect = cropRect
            if (rect != null && rect.width() > 0 && rect.height() > 0) {
                captureAndCompare(rect)
            } else {
                Log.w(TAG, "Comparison enabled but crop coordinates are invalid: $rect")
            }
        }
    }

    private fun captureAndCompare(rect: Rect) {
        val reader = imageReader ?: return
        val image = reader.acquireLatestImage() ?: return

        try {
            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            // Create full screen bitmap from buffer
            val fullBitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            fullBitmap.copyPixelsFromBuffer(buffer)

            // Crop bitmap within bounds check
            val cropX = rect.left.coerceIn(0, screenWidth - 1)
            val cropY = rect.top.coerceIn(0, screenHeight - 1)
            val cropW = rect.width().coerceAtMost(screenWidth - cropX)
            val cropH = rect.height().coerceAtMost(screenHeight - cropY)

            if (cropW > 0 && cropH > 0) {
                val currentCropped = Bitmap.createBitmap(fullBitmap, cropX, cropY, cropW, cropH)

                val target = targetBitmap
                if (target == null) {
                    // Set the first capture as target reference image
                    targetBitmap = currentCropped
                    Log.d(TAG, "Target reference image captured. Dimensions: ${cropW}x${cropH}")
                } else {
                    // Compare with target reference
                    val isMatch = compareBitmaps(target, currentCropped, similarityThreshold)
                    if (!isMatch) {
                        Log.w(TAG, "TARGET IMAGE MISSING IN DESIGNATED AREA!")
                        triggerAlarm()
                    } else {
                        Log.d(TAG, "Target image match OK.")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed capturing screen slice", e)
        } finally {
            image.close()
        }
    }

    private fun compareBitmaps(target: Bitmap, current: Bitmap, threshold: Float): Boolean {
        if (target.width != current.width || target.height != current.height) {
            return false
        }
        val w = target.width
        val h = target.height
        val tPixels = IntArray(w * h)
        val cPixels = IntArray(w * h)
        target.getPixels(tPixels, 0, w, 0, 0, w, h)
        current.getPixels(cPixels, 0, w, 0, 0, w, h)

        var matchCount = 0
        val total = w * h

        for (i in 0 until total) {
            val p1 = tPixels[i]
            val p2 = cPixels[i]

            val r1 = (p1 shr 16) and 0xFF
            val g1 = (p1 shr 8) and 0xFF
            val b1 = p1 and 0xFF

            val r2 = (p2 shr 16) and 0xFF
            val g2 = (p2 shr 8) and 0xFF
            val b2 = p2 and 0xFF

            // Absolute difference between color components
            val diff = Math.abs(r1 - r2) + Math.abs(g1 - g2) + Math.abs(b1 - b2)
            // Color components within RGB sum diff <= 45 considered a pixel match
            if (diff <= 45) {
                matchCount++
            }
        }

        val similarity = matchCount.toFloat() / total.toFloat()
        Log.d(TAG, "Similarity score: ${(similarity * 100).toInt()}% (Threshold: ${(threshold * 100).toInt()}%)")
        return similarity >= threshold
    }

    private fun triggerAlarm() {
        if (alertType == "NONE") return

        if (alertType == "VIBRATE" || alertType == "BOTH") {
            try {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vibratorManager.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0, 300, 150, 300), -1)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to vibrate", e)
            }
        }

        if (alertType == "SOUND" || alertType == "BOTH") {
            try {
                val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val ringtone = RingtoneManager.getRingtone(context, notificationUri)
                ringtone?.let {
                    if (!it.isPlaying) {
                        it.play()
                        // Automatically stop after 1.5 seconds so it alerts but doesn't lock up audio
                        handler.postDelayed({
                            if (it.isPlaying) {
                                it.stop()
                            }
                        }, 1500)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play sound alarm", e)
            }
        }
    }

    companion object {
        private const val TAG = "MacroEngine"
    }
}
