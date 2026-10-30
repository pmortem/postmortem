package app.postmortem

import android.content.Intent

/** Release builds read no intent extras. */
object DebugInput {
    @Suppress("UNUSED_PARAMETER")
    fun pgn(intent: Intent): String? = null
}
