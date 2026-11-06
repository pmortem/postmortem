package app.postmortem.core.input

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class HoldRepeatTest {
    private val timing = HoldTiming()

    @Test
    fun holdingForFirstDelayPlusFiveIntervalsStepsSixTimes() =
        runTest {
            var steps = 0
            val hold = launch { holdSteps(timing) { steps++ >= 0 } }
            advanceTimeBy(timing.firstDelayMs + 5 * timing.intervalMs + 1)
            runCurrent()
            hold.cancel()
            assertEquals(6, steps)
        }

    @Test
    fun aPressShorterThanTheFirstDelayStepsNothingHere() =
        runTest {
            var steps = 0
            val hold = launch { holdSteps(timing) { steps++ >= 0 } }
            advanceTimeBy(timing.firstDelayMs - 1)
            runCurrent()
            hold.cancel()
            assertEquals(0, steps, "a tap is one step on release, made by the button, not by the repeat")
        }

    @Test
    fun releasingStopsAtOnceAndNoStepIsOwed() =
        runTest {
            var steps = 0
            val hold = launch { holdSteps(timing) { steps++ >= 0 } }
            advanceTimeBy(timing.firstDelayMs + 2 * timing.intervalMs + 1)
            runCurrent()
            hold.cancel()
            val atRelease = steps
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(3, atRelease)
            assertEquals(atRelease, steps)
        }

    @Test
    fun theRepeatSpeedsUpAfterTheButtonWasHeldForTwoSeconds() =
        runTest {
            val times = ArrayList<Long>()
            val hold =
                launch {
                    holdSteps(timing) {
                        times += testScheduler.currentTime
                        true
                    }
                }
            advanceTimeBy(3000)
            runCurrent()
            hold.cancel()
            assertEquals(timing.firstDelayMs, times.first())
            val gaps = times.zipWithNext { a, b -> b - a }
            assertEquals(timing.intervalMs, gaps.first())
            assertEquals(timing.fastIntervalMs, gaps.last())
            assertEquals(setOf(timing.intervalMs, timing.fastIntervalMs), gaps.toSet())
            val firstFast = times[gaps.indexOfFirst { it == timing.fastIntervalMs }]
            assertEquals(true, firstFast >= timing.fastAfterMs - timing.intervalMs)
        }

    @Test
    fun theRepeatEndsByItselfWhenThereIsNothingMoreToStepTo() =
        runTest {
            var position = 0
            val last = 4
            val hold =
                launch {
                    holdSteps(timing) {
                        if (position < last) position++
                        position < last
                    }
                }
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(last, position)
            assertEquals(true, hold.isCompleted, "the loop ended without being cancelled")
        }
}
