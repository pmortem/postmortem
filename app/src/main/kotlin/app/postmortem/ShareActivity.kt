package app.postmortem

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import app.postmortem.core.pgn.PgnParser
import app.postmortem.core.pgn.PgnResult
import app.postmortem.core.pgn.Shared
import app.postmortem.core.pgn.SharedPgn
import app.postmortem.core.pgn.overview
import app.postmortem.core.queue.Enqueued
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where a game shared from Lichess or chess.com lands: no screen of its own, it reads the PGN (text, or a `.pgn` file, read through
 * Android without any storage permission), puts the games in the background queue, says so in a toast, and is gone, so the user is
 * back in the chess app at once. It never opens the main screen. A link is refused, the app has no internet, and so is anything else.
 */
class ShareActivity : ComponentActivity() {
    private val app: PostmortemApp get() = application as PostmortemApp

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { sharedText(intent) }.getOrDefault("") }
            toast(take(SharedPgn.classify(text)))
            if (app.takeNotificationQuestion() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                finish()
            }
        }
    }

    /** Queues the games and says what happened; a game saved already is not queued again. */
    private suspend fun take(shared: Shared): String =
        when (shared) {
            Shared.Link -> {
                getString(R.string.share_link)
            }

            Shared.NotAGame -> {
                getString(R.string.share_not_a_game)
            }

            is Shared.Games -> {
                app.startQueue()
                val before =
                    app.queue.progress.value
                        .map { it.id }
                        .toSet()
                val outcomes = shared.pgns.map { app.queue.enqueue(it) }
                val added = outcomes.filterIsInstance<Enqueued.Queued>().filter { it.id !in before }
                val waiting = (app.queue.progress.value.size - 1).coerceAtLeast(0)
                when {
                    added.size > 1 -> {
                        getString(R.string.share_queued_many, added.size)
                    }

                    added.size == 1 -> {
                        val game = (PgnParser.parse(shared.pgns[outcomes.indexOf(added.single())]) as? PgnResult.Success)?.game
                        val white = game?.overview?.white ?: getString(R.string.summary_white)
                        val black = game?.overview?.black ?: getString(R.string.summary_black)
                        getString(R.string.share_queued_one, getString(R.string.notify_players, white, black), waiting)
                    }

                    outcomes.any { it is Enqueued.Queued } -> {
                        getString(R.string.share_already_queued)
                    }

                    outcomes.any { it is Enqueued.AlreadySaved } -> {
                        getString(R.string.share_already_saved)
                    }

                    else -> {
                        getString(R.string.share_not_a_game)
                    }
                }
            }
        }

    /** The text of the share: the text itself, or what the shared files hold (all of them for several), up to the size cap. */
    private fun sharedText(intent: Intent): String {
        intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.let { return it.toString().take(SharedPgn.MAX_CHARS + 1) }
        val uris =
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
                }

                Intent.ACTION_SEND_MULTIPLE -> {
                    IntentCompat
                        .getParcelableArrayListExtra(
                            intent,
                            Intent.EXTRA_STREAM,
                            Uri::class.java,
                        ).orEmpty()
                }

                else -> {
                    emptyList()
                }
            }
        val text = StringBuilder()
        for (uri in uris) {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                val buffer = CharArray(BUFFER)
                while (text.length <= SharedPgn.MAX_CHARS) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    text.append(buffer, 0, read)
                }
            }
            text.append("\n\n")
            if (text.length > SharedPgn.MAX_CHARS) break
        }
        return text.toString()
    }

    private fun toast(message: String) = Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val BUFFER = 8192
    }
}
