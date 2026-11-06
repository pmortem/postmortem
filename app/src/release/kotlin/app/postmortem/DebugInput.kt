package app.postmortem

import android.content.Context
import android.content.Intent

/** Release builds read no intent extras. */
object DebugInput {
    @Suppress("UNUSED_PARAMETER")
    fun pgn(intent: Intent): String? = null

    @Suppress("UNUSED_PARAMETER")
    fun resetData(context: Context) = Unit
}
