package app.postmortem.core.store

import app.postmortem.core.pgn.Game
import java.security.MessageDigest

/**
 * The key two copies of the same game share: a hash of the start position, the moves of the main line (as UCI) and the PGN tags. A
 * changed tag, a different move or another start gives another key; the order the tags were written in, the move numbers, comments
 * and clock tags in the movetext do not matter, because only what the parser made of them goes in.
 */
fun gameKey(game: Game): String {
    val text =
        buildString {
            append(game.startFen).append('\n')
            append(game.plies.joinToString(" ") { it.uci }).append('\n')
            game.headers.toSortedMap().forEach { (name, value) -> append(name).append('=').append(value).append('\n') }
        }
    return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
