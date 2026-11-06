package app.postmortem.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import app.postmortem.core.input.HoldTiming
import app.postmortem.core.input.holdSteps
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * A button that steps once on a tap, and keeps stepping while it is held (see [HoldTiming]). The tap, the ripple and the screen
 * reader's click are the ordinary `clickable`; on top of it, a press held past the first delay starts the repeat, and the click that
 * ends such a press makes no extra step. Releasing, or dragging the finger off the button, stops the repeat at once. Each repeated
 * step gives a light haptic tick, which needs no permission. The repeat ends by itself when [enabled] turns false (the end of the
 * game or line), and a click then does nothing.
 */
@Composable
fun Modifier.repeatingClick(
    enabled: Boolean,
    timing: HoldTiming = HoldTiming(),
    onStep: () -> Unit,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val step = rememberUpdatedState(onStep)
    val stillEnabled = rememberUpdatedState(enabled)
    // whether the press in progress (or the last one) has started repeating, so that its click is not a step too
    val repeated = remember { BooleanArray(1) }
    // The click stays enabled even at the end of the line: turning it off in the middle of a press leaves its ripple stuck on the
    // button. At the end it does nothing, and the button looks and reads as disabled.
    return this
        .clickable(role = Role.Button) {
            if (stillEnabled.value && !repeated[0]) step.value()
        }.semantics { if (!enabled) disabled() }
        .pointerInput(timing) {
            coroutineScope {
                val scope = this
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    repeated[0] = false
                    val hold =
                        scope.launch {
                            holdSteps(timing) {
                                if (stillEnabled.value) {
                                    repeated[0] = true
                                    step.value()
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                }
                                stillEnabled.value
                            }
                        }
                    waitForUpOrCancellation()
                    hold.cancel()
                }
            }
        }
}
