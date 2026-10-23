package com.sabbir.rumi

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class RumiControlService : AccessibilityService() {

    companion object {
        var instance: RumiControlService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    // নিচে স্ক্রল করার ফাংশন (Swipe Up gesture to scroll down)
    fun scrollDown() {
        val path = Path()
        path.moveTo(500f, 1500f)
        path.lineTo(500f, 400f)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 450))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // উপরে স্ক্রল করার ফাংশন (Swipe Down gesture to scroll up)
    fun scrollUp() {
        val path = Path()
        path.moveTo(500f, 400f)
        path.lineTo(500f, 1500f)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 450))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // হোম স্ক্রিনে যাওয়ার ফাংশন
    fun goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    // ব্যাক বাটনের কাজ
    fun goBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
}