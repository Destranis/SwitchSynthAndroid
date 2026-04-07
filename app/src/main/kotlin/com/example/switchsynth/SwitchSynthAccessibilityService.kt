package com.example.switchsynth

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class SwitchSynthAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                SwitchSynthService.stopSpeech()
            }
        }
    }

    override fun onInterrupt() {
        SwitchSynthService.stopSpeech()
    }
}
