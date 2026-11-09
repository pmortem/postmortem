package app.postmortem.core.pgn

/** What a share brought in: games to queue, a game's link instead of its PGN, or nothing that is a game. */
sealed interface Shared {
    /** The PGN of each game, at most [SharedPgn.MAX_GAMES]; [more] counts the games left out over that. */
    data class Games(
        val pgns: List<String>,
        val more: Int = 0,
    ) : Shared

    /** A link to a game on Lichess or chess.com: the app has no internet, so it needs the PGN instead. */
    data object Link : Shared

    data object NotAGame : Shared
}

/** Reading what another app shares with Postmortem: one PGN, several games in one PGN, a link, or something else. */
object SharedPgn {
    /** More text than this is not read at all, so a huge share cannot hang the app. */
    const val MAX_CHARS = 1_000_000

    /** At most this many games are queued from one share. */
    const val MAX_GAMES = 20

    private val link = Regex("""https?://(www\.)?(lichess\.org|chess\.com)/\S*""", RegexOption.IGNORE_CASE)
    private val tag = Regex("""^\s*\[\w+\s+".*"\s*]\s*$""")

    fun classify(text: String): Shared {
        if (text.length > MAX_CHARS) return Shared.NotAGame
        val trimmed = text.trim()
        if ('\n' !in trimmed && link.matches(trimmed)) return Shared.Link
        val games = split(trimmed).filter { PgnParser.parse(it) is PgnResult.Success }
        if (games.isEmpty()) return Shared.NotAGame
        return Shared.Games(games.take(MAX_GAMES), (games.size - MAX_GAMES).coerceAtLeast(0))
    }

    /** The games of a PGN with one or more games in it: a new game starts at a tag line that comes after moves. */
    fun split(text: String): List<String> {
        val games = ArrayList<String>()
        val current = StringBuilder()
        var hasMoves = false
        for (line in text.lines()) {
            val isTag = tag.matches(line)
            if (isTag && hasMoves) {
                games += current.toString().trim()
                current.clear()
                hasMoves = false
            }
            if (!isTag && line.isNotBlank()) hasMoves = true
            current.append(line).append('\n')
        }
        if (current.isNotBlank()) games += current.toString().trim()
        return games
    }
}
