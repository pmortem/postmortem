package app.postmortem.core.pgn

import com.github.bhlangonijr.chesslib.Board
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PgnParserTest {
    private val clockMarker = "[%clk"
    private val withMoves = Fixtures.names.filter { it != "chesscom-no-moves" }

    private fun placement(fen: String) = fen.substringBefore(' ')
    private fun pieceCount(fen: String) = placement(fen).count { it.isLetter() }
    private fun failure(text: String) = assertIs<PgnResult.Failure>(PgnParser.parse(text)).error
    private fun success(text: String) = assertIs<PgnResult.Success>(PgnParser.parse(text)).game

    /** The movetext of a game rebuilt from its plies, so tests can add noise around real moves. */
    private fun movetext(plies: List<Ply>, firstPly: Int = 1, annotate: (Int, String) -> String = { _, s -> s }): String =
        plies.mapIndexed { i, p ->
            val ply = firstPly + i
            val number = when {
                ply % 2 == 1 -> "${(ply + 1) / 2}. "
                i == 0 -> "${ply / 2}... "
                else -> ""
            }
            number + annotate(ply, p.san)
        }.joinToString(" ")

    @Test
    fun everyClockedFixtureHasOnePlyPerClock() {
        for (name in withMoves) {
            val text = Fixtures.text(name)
            assertEquals(text.split(clockMarker).size - 1, Fixtures.game(name).plies.size, name)
        }
    }

    @Test
    fun chessComFinalPositionMatchesItsOwnCurrentPositionTag() {
        for (name in withMoves.filter { it.startsWith("chesscom") }) {
            val game = Fixtures.game(name)
            val expected = game.headers.getValue("CurrentPosition")
            val last = game.plies.last().fen
            assertEquals(placement(expected), placement(last), name)
            assertEquals(expected.split(' ')[1], last.split(' ')[1], "$name side to move")
        }
    }

    @Test
    fun everyCaptureRemovesExactlyOnePieceAndEverythingElseNone() {
        for (name in withMoves) {
            val game = Fixtures.game(name)
            var before = game.startFen
            game.plies.forEachIndexed { i, ply ->
                val promotion = '=' in ply.san
                val removed = pieceCount(before) - pieceCount(ply.fen)
                assertEquals(if ('x' in ply.san) 1 else 0, removed, "$name ply ${i + 1} ${ply.san}")
                assertEquals(promotion, ply.uci.length == 5, "$name ply ${i + 1}")
                before = ply.fen
            }
        }
    }

    @Test
    fun promotionLetterInUciMatchesTheSan() {
        val promotions = Fixtures.game("lichess-promotion-long-castle").plies.filter { '=' in it.san }
        assertTrue(promotions.isNotEmpty())
        for (ply in promotions) assertEquals(ply.san.substringAfter('=').first().lowercaseChar(), ply.uci.last(), ply.san)
    }

    @Test
    fun castlingIsWrittenAsKingMoves() {
        val game = Fixtures.game("lichess-mate-castle")
        val castle = game.plies.withIndex().first { it.value.san == "O-O" }
        assertEquals(if (castle.index % 2 == 0) "e1g1" else "e8g8", castle.value.uci)
        val long = Fixtures.game("lichess-promotion-long-castle").plies.withIndex().first { it.value.san == "O-O-O" }
        assertEquals(if (long.index % 2 == 0) "e1c1" else "e8c8", long.value.uci)
    }

    @Test
    fun aMatingFixtureEndsInMate() {
        val last = Fixtures.game("lichess-mate-castle").plies.last()
        assertTrue(last.san.endsWith("#"))
        assertTrue(Board().apply { loadFromFen(last.fen) }.isMated)
    }

    @Test
    fun headersAndStartPositionAreRead() {
        val game = Fixtures.game("chesscom-short-resign")
        assertEquals("Chess.com", game.headers["Site"])
        assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", game.startFen)
        assertEquals(game.plies.size + 1, game.positionCount)
        assertEquals(game.startFen, game.fenAt(0))
        assertEquals(game.plies[4].fen, game.fenAt(5))
    }

    @Test
    fun aGameWithoutMovesIsRejected() {
        assertEquals(PgnError.NoMoves, failure(Fixtures.text("chesscom-no-moves")))
    }

    @Test
    fun blankTextIsRejected() {
        assertEquals(PgnError.Empty, failure(""))
        assertEquals(PgnError.Empty, failure(" \n\r\n\t"))
    }

    @Test
    fun byteOrderMarkAndWindowsLineEndingsChangeNothing() {
        val text = Fixtures.text("lichess-draw")
        val plain = success(text)
        val noisy = success("﻿" + text.replace("\n", "\r\n"))
        assertEquals(plain.plies, noisy.plies)
        assertEquals(plain.headers, noisy.headers)
    }

    @Test
    fun onlyTheFirstGameIsRead() {
        val first = Fixtures.text("chesscom-short-resign")
        val second = Fixtures.text("lichess-short-resign")
        val game = success(first + "\n\n" + second)
        assertEquals(Fixtures.game("chesscom-short-resign").plies, game.plies)
    }

    @Test
    fun commentsVariationsAnnotationsAndNagsAreSkipped() {
        val plies = Fixtures.game("lichess-short-resign").plies
        val noisy = movetext(plies) { ply, san ->
            when (ply) {
                1 -> "$san!? \$1 { a comment with (parens) and 2. d4 } (1. d4 d5 (1... Nf6 2. c4 { nested }) 2. c4) ?!"
                2 -> "$san ; rest of the line 3. Qh5\n"
                3 -> "$san \$14 \$15 {[%eval 0.3] [%clk 0:03:00]}"
                else -> san
            }
        }
        assertEquals(plies, success("[Event \"x\"]\n\n$noisy 1-0").plies)
    }

    @Test
    fun movesWithoutSpacesAfterTheNumberAreRead() {
        val plies = Fixtures.game("lichess-short-resign").plies
        val glued = plies.take(4).mapIndexed { i, p -> if (i % 2 == 0) "${i / 2 + 1}.${p.san}" else p.san }.joinToString(" ")
        assertEquals(plies.take(4), success(glued).plies)
    }

    @Test
    fun zeroCastlingIsAccepted() {
        val game = Fixtures.game("lichess-short-resign")
        val text = movetext(game.plies) { _, san -> san.replace("O-O-O", "0-0-0").replace("O-O", "0-0") }
        assertEquals(game.plies, success(text).plies)
    }

    @Test
    fun aGameStartingFromAFenIsReplayedFromThere() {
        val full = Fixtures.game("chesscom-promotion-long")
        val cut = 40
        val tail = full.plies.drop(cut)
        val text = "[SetUp \"1\"]\n[FEN \"${full.plies[cut - 1].fen}\"]\n\n${movetext(tail, firstPly = cut + 1)}"
        val game = success(text)
        assertEquals(full.plies[cut - 1].fen, game.startFen)
        assertEquals(tail, game.plies)
    }

    @Test
    fun anIllegalMoveNamesItsPly() {
        val plies = Fixtures.game("lichess-short-resign").plies
        val text = movetext(plies) { ply, san -> if (ply == 5) "Kh5" else san }
        assertEquals(PgnError.IllegalMove(5, "Kh5"), failure(text))
    }

    @Test
    fun textThatIsNotMovesIsAnIllegalMoveAtTheFirstPly() {
        assertEquals(PgnError.IllegalMove(1, "hello"), failure("hello world"))
    }

    @Test
    fun anUnknownVariantIsRejected() {
        assertEquals(PgnError.UnsupportedVariant("Chess960"), failure("[Variant \"Chess960\"]\n\n1. e4 e5"))
    }

    @Test
    fun aBrokenFenIsRejected() {
        assertEquals(PgnError.BadFen("not a fen"), failure("[FEN \"not a fen\"]\n\n1. e4 e5"))
    }
}
