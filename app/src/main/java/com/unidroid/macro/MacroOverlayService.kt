package com.unidroid.macro

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MacroOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    // UI overlays
    private var floatingBubble: View? = null
    private var menuContainer: View? = null
    private var clickOverlayView: View? = null
    private var cropOverlayContainer: FrameLayout? = null

    // Macro Logic
    private var macroEngine: MacroEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        getScreenMetrics()
        createNotificationChannel()
        startForegroundService()
        showFloatingBubble()
    }

    private fun getScreenMetrics() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Macro Overlay Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("UniDroid Macro Overlay")
            .setContentText("Overlay Controller is active")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingBubble() {
        val bubble = FrameLayout(this).apply {
            // Circle Background with Text 'M'
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#6200EE"))
                setStroke(4, Color.WHITE)
            }
            background = bg
            addView(TextView(context).apply {
                text = "M"
                setTextColor(Color.WHITE)
                textSize = 20f
                gravity = Gravity.CENTER
            })
        }

        val params = WindowManager.LayoutParams(
            130, 130,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - 160
            y = screenHeight / 3
        }

        bubble.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val diffX = Math.abs(event.rawX - initialTouchX)
                        val diffY = Math.abs(event.rawY - initialTouchY)
                        if (diffX < 10 && diffY < 10) {
                            showMenu()
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(bubble, params)
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(bubble, params)
        floatingBubble = bubble
    }

    private fun showMenu() {
        // Hide floating bubble while showing menu
        floatingBubble?.visibility = View.GONE

        val menuLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EAEAEA"))
            setPadding(20, 20, 20, 20)
            
            // Corner radius
            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#EAEAEA"))
                cornerRadius = 24f
                setStroke(2, Color.LTGRAY)
            }
            background = bg

            addView(TextView(context).apply {
                text = "Macro Control Panel"
                setTextColor(Color.BLACK)
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, 10, 0, 20)
            })

            // Click coords button
            addView(Button(context).apply {
                text = if (clickX > 0f) "Click Point: (${clickX.toInt()}, ${clickY.toInt()})" else "Set Click Point"
                setOnClickListener {
                    hideMenu()
                    enterClickSelectionMode()
                }
            })

            // Crop area button
            addView(Button(context).apply {
                text = if (cropRect.width() > 0) "Area: ${cropRect.width()}x${cropRect.height()}" else "Set Crop Area"
                setOnClickListener {
                    hideMenu()
                    enterCropSelectionMode()
                }
            })

            // Start / Stop button
            addView(Button(context).apply {
                text = if (isRunning) "STOP MACRO" else "START MACRO"
                setBackgroundColor(if (isRunning) Color.RED else Color.parseColor("#4CAF50"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    toggleMacro()
                    text = if (isRunning) "STOP MACRO" else "START MACRO"
                    setBackgroundColor(if (isRunning) Color.RED else Color.parseColor("#4CAF50"))
                }
            })

            // Close button
            addView(Button(context).apply {
                text = "Minimize Menu"
                setOnClickListener {
                    hideMenu()
                    floatingBubble?.visibility = View.VISIBLE
                }
            })
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        windowManager.addView(menuLayout, params)
        menuContainer = menuLayout
    }

    private fun hideMenu() {
        menuContainer?.let {
            windowManager.removeView(it)
            menuContainer = null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enterClickSelectionMode() {
        Toast.makeText(this, "Tap the screen to set click coordinate", Toast.LENGTH_SHORT).show()

        val selectionOverlay = View(this).apply {
            setBackgroundColor(Color.argb(50, 0, 0, 0)) // 20% transparent black
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        selectionOverlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                clickX = event.rawX
                clickY = event.rawY
                Toast.makeText(this, "Saved Coordinate: (${clickX.toInt()}, ${clickY.toInt()})", Toast.LENGTH_SHORT).show()
                
                // Tear down click overlay
                windowManager.removeView(selectionOverlay)
                clickOverlayView = null

                // Restore bubble
                floatingBubble?.visibility = View.VISIBLE
            }
            true
        }

        windowManager.addView(selectionOverlay, params)
        clickOverlayView = selectionOverlay
    }

    private fun enterCropSelectionMode() {
        Toast.makeText(this, "Adjust green box to select screen section", Toast.LENGTH_LONG).show()

        val rootFrame = FrameLayout(this)
        val cropper = CropOverlayView(this)

        val btnSave = Button(this).apply {
            text = "SAVE CROP REGION"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                cropRect.set(cropper.cropRect)
                Toast.makeText(context, "Crop bounds saved: ${cropRect.width()}x${cropRect.height()}", Toast.LENGTH_SHORT).show()
                windowManager.removeView(rootFrame)
                cropOverlayContainer = null
                floatingBubble?.visibility = View.VISIBLE
            }
        }

        val btnParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 80
        }

        rootFrame.addView(cropper)
        rootFrame.addView(btnSave, btnParams)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(rootFrame, params)
        cropOverlayContainer = rootFrame
    }

    private fun toggleMacro() {
        if (isRunning) {
            stopMacroEngine()
            Toast.makeText(this, "Macro Stopped", Toast.LENGTH_SHORT).show()
        } else {
            startMacroEngine()
        }
    }

    private fun startMacroEngine() {
        val projectionIntent = mediaProjectionResultIntent
        if (projectionIntent == null) {
            Toast.makeText(this, "Media Projection configuration missing!", Toast.LENGTH_LONG).show()
            return
        }

        if (isClickEnabled && !MacroAccessibilityService.isRunning) {
            Toast.makeText(this, "Accessibility Service is not enabled!", Toast.LENGTH_LONG).show()
            return
        }

        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            // Obtain MediaProjection from result
            val projection = projectionManager.getMediaProjection(android.app.Activity.RESULT_OK, projectionIntent)
            
            macroEngine = MacroEngine(
                context = this,
                mediaProjection = projection,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                screenDensity = screenDensity
            ).apply {
                this.intervalMs = clickIntervalMs
                this.isClickEnabled = Companion.isClickEnabled
                this.clickX = Companion.clickX
                this.clickY = Companion.clickY
                this.isImageCompareEnabled = Companion.isImageCompareEnabled
                this.cropRect = Companion.cropRect
                this.alertType = Companion.alertType
                this.start()
            }
            isRunning = true
            Toast.makeText(this, "Macro Started successfully!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MacroOverlayService", "Failed starting engine", e)
            Toast.makeText(this, "Failed starting: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopMacroEngine() {
        macroEngine?.stop()
        macroEngine = null
        isRunning = false
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMacroEngine()
        floatingBubble?.let { windowManager.removeView(it) }
        menuContainer?.let { windowManager.removeView(it) }
        clickOverlayView?.let { windowManager.removeView(it) }
        cropOverlayContainer?.let { windowManager.removeView(it) }
    }

    // Custom view drawn within WindowManager overlays
    inner class CropOverlayView(context: Context) : View(context) {
        val cropRect = Rect(screenWidth / 4, screenHeight / 3, screenWidth * 3 / 4, screenHeight * 2 / 3)
        private val strokePaint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        private val handlePaint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.FILL
        }
        private val dimPaint = Paint().apply {
            color = Color.argb(128, 0, 0, 0)
        }

        private var isDragging = false
        private var isResizing = false
        private var startX = 0f
        private var startY = 0f
        private val handleSize = 60f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // Screen masking outside cropRect
            canvas.drawRect(0f, 0f, width.toFloat(), cropRect.top.toFloat(), dimPaint)
            canvas.drawRect(0f, cropRect.top.toFloat(), cropRect.left.toFloat(), cropRect.bottom.toFloat(), dimPaint)
            canvas.drawRect(cropRect.right.toFloat(), cropRect.top.toFloat(), width.toFloat(), cropRect.bottom.toFloat(), dimPaint)
            canvas.drawRect(0f, cropRect.bottom.toFloat(), width.toFloat(), height.toFloat(), dimPaint)

            // Crop boundary box
            canvas.drawRect(cropRect, strokePaint)

            // Resizing corner handle (bottom-right)
            canvas.drawRect(
                cropRect.right.toFloat() - handleSize,
                cropRect.bottom.toFloat() - handleSize,
                cropRect.right.toFloat(),
                cropRect.bottom.toFloat(),
                handlePaint
            )
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x
            val y = event.y

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Check bottom-right corner grip resizing activation
                    if (x in (cropRect.right - handleSize * 2)..(cropRect.right + handleSize) &&
                        y in (cropRect.bottom - handleSize * 2)..(cropRect.bottom + handleSize)) {
                        isResizing = true
                    } else if (cropRect.contains(x.toInt(), y.toInt())) {
                        isDragging = true
                        startX = x
                        startY = y
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isResizing) {
                        cropRect.right = x.toInt().coerceAtLeast(cropRect.left + 150)
                        cropRect.bottom = y.toInt().coerceAtLeast(cropRect.top + 150)
                        invalidate()
                    } else if (isDragging) {
                        val dx = (x - startX).toInt()
                        val dy = (y - startY).toInt()
                        cropRect.offset(dx, dy)
                        // Confine coordinates within screen frame
                        if (cropRect.left < 0) cropRect.offset(-cropRect.left, 0)
                        if (cropRect.top < 0) cropRect.offset(0, -cropRect.top)
                        if (cropRect.right > width) cropRect.offset(width - cropRect.right, 0)
                        if (cropRect.bottom > height) cropRect.offset(0, height - cropRect.bottom)
                        startX = x
                        startY = y
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    isDragging = false
                    isResizing = false
                }
            }
            return true
        }
    }

    companion object {
        const val CHANNEL_ID = "macro_overlay_channel"
        const val NOTIFICATION_ID = 2026

        // Shared static configuration parameters
        var mediaProjectionResultIntent: Intent? = null
        var clickIntervalMs: Long = 1000L
        var isClickEnabled = false
        var isImageCompareEnabled = false
        var alertType = "BOTH" // NONE, VIBRATE, SOUND, BOTH
        var clickX = 0f
        var clickY = 0f
        val cropRect = Rect()
        var isRunning = false
    }
}
