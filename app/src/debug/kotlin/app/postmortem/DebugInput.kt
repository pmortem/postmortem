package app.postmortem

import android.content.Context
import android.content.Intent
import android.util.Base64

/** Debug builds only: the smoke check hands a game over as a base64 intent extra, and starts from no saved games. */
object DebugInput {
    private const val EXTRA_PGN_B64 = "smoke_pgn_b64"

    fun pgn(intent: Intent): String? =
        intent.getStringExtra(EXTRA_PGN_B64)?.let { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) }

    /** A smoke run starts from an empty library, so it analyses the game again every time. */
    fun resetData(
        context: Context,
        databaseName: String,
    ) {
        context.deleteDatabase(databaseName)
    }
}
