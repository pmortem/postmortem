package app.postmortem.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import app.postmortem.core.input.HoldTiming
import app.postmortem.core.input.holdSteps
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * A button that steps once on a tap, and keeps stepping while it is held (see [HoldTiming]). Releasing, or dragging the finger off
 * the button, stops at once. Each repeated step gives a light haptic tick, which needs no permission. Stepping ends by itself when
 * [enabled] turns false (the end of the game or line). Screen readers still get one step from a click.
 */
@Composable
fun Modifier.repeatingClick(
    enabled: Boolean,
    timing: HoldTiming = HoldTiming(),
    onStep: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    val step = rememberUpdatedState(onStep)
    val stillEnabled = rememberUpdatedState(enabled)
    return this
        .indication(source, ripple())
        .pointerInput(enabled, timing) {
            if (!enabled) return@pointerInput
            coroutineScope {
                val scope = this
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val press = PressInteraction.Press(down.position)
                    source.tryEmit(press)
                    var repeated = false
                    val hold =
                        scope.launch {
                            holdSteps(timing) {
                                repeated = true
                                step.value()
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                stillEnabled.value
                            }
                        }
                    val up = waitForUpOrCancellation()
                    hold.cancel()
                    source.tryEmit(if (up != null) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                    // a press that ended before the repeat began is a tap: one step
                    if (up != null && !repeated) step.value()
                }
            }
        }.semantics {
            role = Role.Button
            if (enabled) {
                onClick {
                    step.value()
                    true
                }
            } else {
                disabled()
            }
        }
}
