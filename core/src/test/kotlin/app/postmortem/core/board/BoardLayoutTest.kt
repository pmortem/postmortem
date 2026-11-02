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

    @Test
    fun coordinatesRunAToHAndEightToOne() {
        assertEquals("abcdefgh", (0 until BoardLayout.SIZE).map(BoardLayout::fileName).joinToString(""))
        assertEquals("87654321", (0 until BoardLayout.SIZE).map(BoardLayout::rankName).joinToString(""))
    }

    @Test
    fun coordinatesAgreeWithWherePiecesAreDrawn() {
        // In the start position the white king is on e1: the bottom row, and the column named e.
        val king =
            BoardLayout
                .cells("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
                .single { it.piece == BoardPiece(PieceColor.WHITE, PieceKind.KING) }
        assertEquals("e1", "${BoardLayout.fileName(king.col)}${BoardLayout.rankName(king.row)}")
    }

    @Test
    fun coordinatesOutsideTheBoardAreRefused() {
        assertFailsWith<IllegalArgumentException> { BoardLayout.fileName(8) }
        assertFailsWith<IllegalArgumentException> { BoardLayout.rankName(-1) }
    }

    @Test
    fun flippedCoordinatesReadHToAAndOneToEight() {
        assertEquals("hgfedcba", (0 until BoardLayout.SIZE).map { BoardLayout.fileName(it, flipped = true) }.joinToString(""))
        assertEquals("12345678", (0 until BoardLayout.SIZE).map { BoardLayout.rankName(it, flipped = true) }.joinToString(""))
    }

    private val square = 100f

    private fun label(
        labels: List<BoardLabel>,
        text: Char,
        isRank: Boolean,
    ) = labels.single { it.text == text && it.isRank == isRank }

    @Test
    fun thereAreSixteenLabelsOnTheLeftFileAndTheBottomRank() {
        val labels = BoardLabels.all()
        assertEquals(16, labels.size)
        assertTrue(labels.filter { it.isRank }.all { it.col == 0 })
        assertTrue(labels.filterNot { it.isRank }.all { it.row == BoardLayout.SIZE - 1 })
        assertEquals("87654321", labels.filter { it.isRank }.map { it.text }.joinToString(""))
        assertEquals("abcdefgh", labels.filterNot { it.isRank }.map { it.text }.joinToString(""))
    }

    @Test
    fun theLabelsOnA1AreInOppositeCornersOfTheirSquares() {
        val labels = BoardLabels.all()
        // "1" is in the a1 square (row 7, col 0), top-left: x 6 px in, baseline 22 px below the square's top.
        val one = label(labels, '1', isRank = true)
        assertEquals(7 to 0, one.row to one.col)
        assertEquals(6f, one.x(square), 1e-4f)
        assertEquals(7 * square + 22f, one.y(square), 1e-4f)
        // "a" is in the same square, bottom-right: starts 20 px left of the right edge, baseline 7 px above the bottom.
        val a = label(labels, 'a', isRank = false)
        assertEquals(7 to 0, a.row to a.col)
        assertEquals(square - 20f, a.x(square), 1e-4f)
        assertEquals(8 * square - 7f, a.y(square), 1e-4f)
    }

    @Test
    fun theLabelsOnH8AreInTheTopRightSquare() {
        val labels = BoardLabels.all()
        val eight = label(labels, '8', isRank = true)
        assertEquals(0 to 0, eight.row to eight.col)
        assertEquals(22f, eight.y(square), 1e-4f)
        val h = label(labels, 'h', isRank = false)
        assertEquals(7 to 7, h.row to h.col)
        assertEquals(8 * square - 20f, h.x(square), 1e-4f)
    }

    @Test
    fun flippedLabelsKeepTheirCornersAndReverseTheirNames() {
        val flipped = BoardLabels.all(flipped = true)
        assertEquals("12345678", flipped.filter { it.isRank }.map { it.text }.joinToString(""))
        assertEquals("hgfedcba", flipped.filterNot { it.isRank }.map { it.text }.joinToString(""))
        // The first rank label is still on the top row of the left file; it now reads 1.
        assertEquals(0 to 0, flipped.first { it.isRank }.let { it.row to it.col })
        assertEquals('1', flipped.first { it.isRank }.text)
        // The bottom-right square is a1 when flipped,.
        val a = label(flipped, 'a', isRank = false)
        assertEquals(7 to 7, a.row to a.col)
    }

    @Test
    fun aFlippedBoardTurnsHalfAround() {
        val start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        assertEquals(32, BoardLayout.cells(start, flipped = true).size)
        // Every piece is on the opposite square, none lost or doubled.
        assertEquals(
            BoardLayout.cells(start).map { it.piece to (7 - it.row to 7 - it.col) }.toSet(),
            BoardLayout.cells(start, flipped = true).map { it.piece to (it.row to it.col) }.toSet(),
        )
    }
}
