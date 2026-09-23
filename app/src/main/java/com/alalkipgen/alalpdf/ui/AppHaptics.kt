package com.alalkipgen.alalpdf.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Semantic haptics for the app. View.performHapticFeedback respects the
 * device's touch-feedback setting and lets Android choose the best effect for
 * the hardware instead of forcing a custom vibration pattern.
 */
@Stable
class AppHaptics internal constructor(private val view: View) {
    fun tick() = perform(
        if (Build.VERSION.SDK_INT >= 34) {
            HapticFeedbackConstants.SEGMENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
    )

    fun confirm() = perform(
        if (Build.VERSION.SDK_INT >= 30) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
    )

    fun gestureStart() = perform(
        if (Build.VERSION.SDK_INT >= 30) {
            HapticFeedbackConstants.GESTURE_START
        } else {
            HapticFeedbackConstants.CONTEXT_CLICK
        }
    )

    fun gestureEnd() = perform(
        if (Build.VERSION.SDK_INT >= 30) {
            HapticFeedbackConstants.GESTURE_END
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
    )

    fun longPress() = perform(HapticFeedbackConstants.LONG_PRESS)

    fun selectionChanged() = perform(
        if (Build.VERSION.SDK_INT >= 27) {
            HapticFeedbackConstants.TEXT_HANDLE_MOVE
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
    )

    private fun perform(effect: Int) {
        view.performHapticFeedback(effect)
    }
}

@Composable
fun rememberAppHaptics(): AppHaptics {
    val view = LocalView.current
    return remember(view) { AppHaptics(view) }
}