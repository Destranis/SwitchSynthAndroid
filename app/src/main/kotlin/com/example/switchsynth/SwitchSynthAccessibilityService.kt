package com.example.switchsynth

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class SwitchSynthAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Only stop on clicks and long clicks to avoid "random stopping" during exploration
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                stopTts()
            }
        }
    }

    private fun stopTts() {
        val intent = Intent(this, SwitchSynthService::class.java)
        intent.action = "com.example.switchsynth.ACTION_STOP"
        startService(intent)
    }

    override fun onInterrupt() {
        stopTts()
    }
}
