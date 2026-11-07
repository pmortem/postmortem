package app.postmortem

import androidx.compose.material3.SnackbarHostState
import app.postmortem.ui.showOpenedNotice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OpenedNoticeTest {
    @Test
    fun theBarIsShownFirstAndTheFlagIsClearedOnlyWhenItIsGone() =
        runTest {
            val snackbars = SnackbarHostState()
            var cleared = false
            val job = launch { showOpenedNotice(snackbars, "Opened from your library") { cleared = true } }
            advanceUntilIdle()
            assertEquals("Opened from your library", snackbars.currentSnackbarData?.visuals?.message)
            assertFalse(cleared, "clearing the flag first would cancel the bar before it shows")
            snackbars.currentSnackbarData?.dismiss()
            advanceUntilIdle()
            assertTrue(cleared)
            job.join()
        }
}
