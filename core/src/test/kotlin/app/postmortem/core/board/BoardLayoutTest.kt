package app.postmortem.core.board

import app.postmortem.core.pgn.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardLayoutTest {
    private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    private fun List<BoardCell>.at(square: String): BoardPiece? =
        BoardLayout.position(square).let { (row, col) -> firstOrNull { it.row == row && it.col == col }?.piece }

    private fun piece(letter: Char) =
        BoardPiece(
            if (letter.isUpperCase()) PieceColor.WHITE else PieceColor.BLACK,
            when (letter.lowercaseChar()) {
                'k' -> PieceKind.KING
                'q' -> PieceKind.QUEEN
                'r' -> PieceKind.ROOK
                'b' -> PieceKind.BISHOP
                'n' -> PieceKind.KNIGHT
                else -> PieceKind.PAWN
            },
        )

    @Test
    fun theStartPositionHasWhiteAtTheBottom() {
        val cells = BoardLayout.cells(startFen)
        assertEquals(32, cells.size)
        assertEquals(piece('R'), cells.at("a1"))
        assertEquals(piece('K'), cells.at("e1"))
        assertEquals(piece('Q'), cells.at("d1"))
        assertEquals(piece('k'), cells.at("e8"))
        assertEquals(piece('p'), cells.at("h7"))
        assertEquals(BoardCell(7, 0, piece('R')), cells.single { it.row == 7 && it.col == 0 })
        assertEquals(BoardCell(0, 4, piece('k')), cells.single { it.row == 0 && it.col == 4 })
        assertNull(cells.at("e4"))
    }

    @Test
    fun squaresMapToRowsAndColumns() {
        assertEquals(7 to 0, BoardLayout.position("a1"))
        assertEquals(0 to 7, BoardLayout.position("h8"))
        assertEquals(4 to 4, BoardLayout.position("e4"))
    }

    @Test
    fun everyMoveOfEveryFixtureLandsOnItsSquare() {
        // Covers castling, promotion and en passant from real games: the UCI move says where the piece must be.
        for (name in Fixtures.names.filter { it != "chesscom-no-moves" }) {
            Fixtures.game(name).plies.forEachIndexed { i, ply ->
                val cells = BoardLayout.cells(ply.fen)
                val label = "$name ply ${i + 1} ${ply.san}"
                val landed = checkNotNull(cells.at(ply.uci.substring(2, 4))) { label }
                assertEquals(if (i % 2 == 0) PieceColor.WHITE else PieceColor.BLACK, landed.color, label)
                if (ply.uci.length == 5) assertEquals(piece(ply.uci[4]).kind, landed.kind, label)
                // Castling is the one move after which the king's start square can hold the rook's neighbour, not the king.
                if (!ply.san.startsWith("O-O")) assertNull(cells.at(ply.uci.substring(0, 2)), label)
            }
        }
    }

    @Test
    fun castlingMovesKingAndRook() {
        val game = Fixtures.game("lichess-mate-castle")
        val index = game.plies.indexOfFirst { it.san == "O-O" }
        val white = index % 2 == 0
        val cells = BoardLayout.cells(game.plies[index].fen)
        val rank = if (white) "1" else "8"
        assertEquals(piece(if (white) 'K' else 'k'), cells.at("g$rank"))
        assertEquals(piece(if (white) 'R' else 'r'), cells.at("f$rank"))
        assertNull(cells.at("e$rank"))
        assertNull(cells.at("h$rank"))
    }

    @Test
    fun aPromotionPutsTheNewPieceOnTheBackRank() {
        val game = Fixtures.game("lichess-promotion-long-castle")
        val index = game.plies.indexOfFirst { it.uci.length == 5 }
        val ply = game.plies[index]
        val letter = ply.uci[4].let { if (index % 2 == 0) it.uppercaseChar() else it }
        assertEquals(piece(letter), BoardLayout.cells(ply.fen).at(ply.uci.substring(2, 4)))
    }

    @Test
    fun finalPositionsMatchChessComsCurrentPositionTag() {
        for (name in Fixtures.names.filter { it.startsWith("chesscom") && it != "chesscom-no-moves" }) {
            val game = Fixtures.game(name)
            val expected = BoardLayout.cells(game.headers.getValue("CurrentPosition"))
            assertEquals(expected.toSet(), BoardLayout.cells(game.plies.last().fen).toSet(), name)
        }
    }

    @Test
    fun enPassantRemovesTheCapturedPawn() {
        val game = Fixtures.game("chesscom-en-passant")
        var before = BoardLayout.cells(game.startFen)
        var found = false
        game.plies.forEachIndexed { i, ply ->
            val after = BoardLayout.cells(ply.fen)
            val to = ply.uci.substring(2, 4)
            val pawnCapture = ply.san.contains('x') && ply.san[0].isLowerCase()
            if (pawnCapture && before.at(to) == null) {
                found = true
                val behind = to[0] + (if (i % 2 == 0) (to[1] - 1) else (to[1] + 1)).toString()
                assertEquals(piece(if (i % 2 == 0) 'p' else 'P'), before.at(behind))
                assertNull(after.at(behind))
            }
            before = after
        }
        assertTrue(found, "the fixture has no en passant capture")
    }

    @Test
    fun malformedFensAreRefused() {
        assertFailsWith<IllegalArgumentException> { BoardLayout.cells("8/8/8 w") }
        assertFailsWith<IllegalArgumentException> { BoardLayout.cells("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBN w") }
        assertFailsWith<IllegalArgumentException> { BoardLayout.cells("xnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w") }
    }
}
