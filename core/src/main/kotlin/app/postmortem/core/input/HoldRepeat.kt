package app.postmortem.core.input

import kotlinx.coroutines.delay

/**
 * How a held button repeats, like a key held on a keyboard: nothing for [firstDelayMs] (a shorter press is a tap), then a step every
 * [intervalMs], and after the button has been held [fastAfterMs] a step every [fastIntervalMs], so a long game can be crossed quickly.
 */
data class HoldTiming(
    val firstDelayMs: Long = 400,
    val intervalMs: Long = 120,
    val fastAfterMs: Long = 2000,
    val fastIntervalMs: Long = 60,
)

/**
 * Calls [step] for as long as the coroutine runs: the first time [HoldTiming.firstDelayMs] after it started, then at the repeat
 * rate. [step] returns false when there is nothing more to step to (the end of the game or line), which ends the repeat. Cancelling
 * the coroutine, as releasing the button does, stops it at once and leaves no step owed.
 */
suspend fun holdSteps(
    timing: HoldTiming = HoldTiming(),
    step: () -> Boolean,
) {
    delay(timing.firstDelayMs)
    var heldMs = timing.firstDelayMs
    while (step()) {
        val gap = if (heldMs >= timing.fastAfterMs) timing.fastIntervalMs else timing.intervalMs
        delay(gap)
        heldMs += gap
    }
}
