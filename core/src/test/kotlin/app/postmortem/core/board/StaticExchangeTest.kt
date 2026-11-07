package app.postmortem.core.board

import app.postmortem.core.pgn.Fixtures
import app.postmortem.core.pgn.STANDARD_START_FEN
import kotlin.test.Test
import kotlin.test.assertEquals

class StaticExchangeTest {
    @Test
    fun theStartPositionHasNothingToTake() {
        assertEquals(0, StaticExchange.bestGain(STANDARD_START_FEN))
    }

    @Test
    fun aPieceLeftHangingIsWorthItsValue() {
        // the queen on h5 takes the undefended bishop on b5
        assertEquals(3, StaticExchange.bestGain("4k3/8/8/1B5q/8/8/8/4K3 b - - 0 1"))
    }

    @Test
    fun aDefendedPieceIsNotWonBecauseTheCapturerWouldBeTaken() {
        // the pawn on a4 guards the bishop, so Qxb5 axb5 loses the queen: nothing to win
        assertEquals(0, StaticExchange.bestGain("4k3/8/8/1B5q/P7/8/8/4K3 b - - 0 1"))
    }

    @Test
    fun anExchangeSequenceIsPlayedOutWithTheLeastValuablePieceFirst() {
        // Nxd5 Nxd5 Rxd5: Black ends a pawn up, and cannot do better than that
        assertEquals(1, StaticExchange.bestGain("3r2k1/2n5/8/3P4/5N2/8/8/4K3 b - - 0 1"))
    }

    @Test
    fun aPinnedDefenderCannotRecapture() {
        // the knight on c3 guards the bishop on b5 but is pinned to the king by the bishop on a5 (and guarded by the pawn on b2)
        assertEquals(3, StaticExchange.bestGain("6k1/8/8/bB5q/8/2N5/1P6/4K3 b - - 0 1"))
        // without the pin the knight takes back the queen
        assertEquals(0, StaticExchange.bestGain("6k1/8/8/1B5q/8/2N5/1P6/4K3 b - - 0 1"))
    }

    @Test
    fun enPassantTakesAPawn() {
        val fen = "4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1"
        assertEquals(1, StaticExchange.bestGain(fen))
        assertEquals(1, StaticExchange.captured(fen, "e5d6"))
    }

    @Test
    fun aCapturingPromotionIsPlayedAsAQueen() {
        val fen = "r3k3/1P6/8/8/8/8/8/4K3 w - - 0 1"
        assertEquals(5, StaticExchange.bestGain(fen))
        assertEquals(5, StaticExchange.captured(fen, "b7a8q"))
        assertEquals(5, StaticExchange.captured(fen, "b7a8n"))
    }

    @Test
    fun capturedIsZeroForAQuietOrIllegalMove() {
        assertEquals(0, StaticExchange.captured(STANDARD_START_FEN, "e2e4"))
        assertEquals(0, StaticExchange.captured(STANDARD_START_FEN, "e2e5"))
        assertEquals(3, StaticExchange.captured("4k3/8/8/1B5q/8/8/8/4K3 b - - 0 1", "h5b5"))
    }

    @Test
    fun onEveryFixtureGameACaptureIsExactlyAMoveThatTakesSomethingAndNoPositionWinsANegativeAmount() {
        for (name in Fixtures.names.filter { it != "chesscom-no-moves" }) {
            val game = Fixtures.game(name)
            game.plies.forEachIndexed { index, ply ->
                val fen = game.fenAt(index)
                assertEquals("x" in ply.san, StaticExchange.captured(fen, ply.uci) > 0, "$name ply ${index + 1} ${ply.san}")
                assert(StaticExchange.bestGain(fen) >= 0)
            }
        }
    }
}
