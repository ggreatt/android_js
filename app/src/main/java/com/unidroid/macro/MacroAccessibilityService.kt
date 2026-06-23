package com.unidroid.macro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class MacroAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service Connected")
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No action needed for events, we only use this service to dispatch click gestures
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service Interrupted")
        instance = null
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "Accessibility Service Unbound")
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    /**
     * Dispatches a tap gesture programmatically at the specified (x, y) coordinates.
     */
    fun performClick(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val builder = GestureDescription.Builder()
        // Tap starting at 0ms and lasting for 50ms
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        builder.addStroke(stroke)
        
        dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "Gesture completed successfully at ($x, $y)")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.e(TAG, "Gesture cancelled at ($x, $y)")
            }
        }, null)
    }

    companion object {
        private const val TAG = "MacroAccessibility"
        
        @Volatile
        private var instance: MacroAccessibilityService? = null

        val isRunning: Boolean
            get() = instance != null

        fun clickAt(x: Float, y: Float): Boolean {
            val service = instance
            return if (service != null) {
                service.performClick(x, y)
                true
            } else {
                Log.e(TAG, "Accessibility Service is not running!")
                false
            }
        }
    }
}
