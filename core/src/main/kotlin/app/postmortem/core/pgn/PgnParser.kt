package app.postmortem.core.pgn

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType

/**
 * A tolerant PGN reader. It takes the first game of the text, keeps the main line only, and replays every
 * move with chesslib, so a game that parses is a game of legal moves.
 */
object PgnParser {
    private val headerLine = Regex("""^\s*\[(\w+)\s+"((?:[^"\\]|\\.)*)"\s*]\s*$""")
    private val resultTokens = setOf("1-0", "0-1", "1/2-1/2", "*")
    private val moveNumber = Regex("""^\d+\.+""")
    private val annotationSuffix = Regex("""[!?]+$""")
    private const val STANDARD_VARIANT = "Standard"
    private val standardVariants = setOf(STANDARD_VARIANT, "From Position")

    fun parse(text: String): PgnResult {
        val lines =
            text
                .removePrefix("")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .lines()
        if (lines.all { it.isBlank() }) return PgnResult.Failure(PgnError.Empty)

        val headers = LinkedHashMap<String, String>()
        var next = 0
        while (next < lines.size && (lines[next].isBlank() || headerLine.matches(lines[next]))) {
            headerLine.matchEntire(lines[next])?.let { headers[it.groupValues[1]] = it.groupValues[2].replace("\\\"", "\"") }
            next++
        }

        headers["Variant"]?.let { if (it !in standardVariants) return PgnResult.Failure(PgnError.UnsupportedVariant(it)) }

        val board = Board()
        val startFen = headers["FEN"]?.takeIf { it.isNotBlank() }
        if (startFen != null) {
            try {
                board.loadFromFen(startFen)
            } catch (e: Exception) {
                return PgnResult.Failure(PgnError.BadFen(startFen))
            }
        }
        val start = board.fen

        val plies = ArrayList<Ply>()
        for (token in mainLineTokens(lines.drop(next))) {
            val san = token.replace("0-0-0", "O-O-O").replace("0-0", "O-O")
            val ply = plies.size + 1
            val ok =
                try {
                    board.doMove(san)
                } catch (e: Exception) {
                    false
                }
            if (!ok) return PgnResult.Failure(PgnError.IllegalMove(ply, token))
            plies += Ply(san, uciOf(board), board.fen)
        }
        if (plies.isEmpty()) return PgnResult.Failure(PgnError.NoMoves)
        return PgnResult.Success(Game(start, plies, headers))
    }

    private fun uciOf(board: Board): String {
        val move = board.backup.last.move
        val promotion = move.promotion
        val suffix = if (promotion == Piece.NONE) "" else promotionLetter(promotion.pieceType)
        return move.from.value().lowercase() + move.to.value().lowercase() + suffix
    }

    private fun promotionLetter(type: PieceType) =
        when (type) {
            PieceType.QUEEN -> "q"
            PieceType.ROOK -> "r"
            PieceType.BISHOP -> "b"
            else -> "n"
        }

    /** The SAN tokens of the first game's main line, without comments, variations, NAGs and move numbers. */
    private fun mainLineTokens(lines: List<String>): List<String> {
        val tokens = ArrayList<String>()
        val word = StringBuilder()
        var depth = 0
        var inBrace = false
        var finished = false

        fun flush() {
            if (word.isEmpty()) return
            val raw = word.toString()
            word.setLength(0)
            if (depth > 0) return
            if (raw in resultTokens) {
                finished = true
                return
            }
            if (raw.startsWith("$")) return
            val san = raw.replace(moveNumber, "").replace(annotationSuffix, "")
            if (san.isNotEmpty()) tokens += san
        }

        for (line in lines) {
            if (finished) break
            // A tag line outside a comment after the moves began is the next game.
            if (!inBrace && tokens.isNotEmpty() && headerLine.matches(line)) break
            var i = 0
            while (i < line.length && !finished) {
                val c = line[i]
                when {
                    inBrace -> {
                        if (c == '}') inBrace = false
                    }

                    c == '{' -> {
                        flush()
                        inBrace = true
                    }

                    c == ';' -> {
                        flush()
                        i = line.length
                    }

                    c == '(' -> {
                        flush()
                        depth++
                    }

                    c == ')' -> {
                        flush()
                        if (depth > 0) depth--
                    }

                    c.isWhitespace() -> {
                        flush()
                    }

                    else -> {
                        word.append(c)
                    }
                }
                i++
            }
            flush()
        }
        flush()
        return tokens
    }
}
