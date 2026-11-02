package app.postmortem.core.analysis

import app.postmortem.core.pgn.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The game of #64, analysed by Lichess (`[%eval]` after every move): its evals go through our accuracy, ACPL and judgement
 * code, which must give what Lichess's own algorithm gives for the same inputs. The expected numbers are a direct port of
 * lila's `AccuracyPercent.gameAccuracy`, `AccuracyCP` and `Advice` (scratch script, run on this fixture), and Lichess's own
 * annotations for the counts (it judged White's 7.Be2 an inaccuracy and 12.Bxh5 a blunder, nothing else).
 */
class LichessAccuracyTest {
    private val name = "lichess-64-annotated"
    private val lichess = LichessAnnotated(Fixtures.text(name))
    private val report = GameReport.of(lichess.evals())

    @Test
    fun accuracyIsWhatLilasAlgorithmGivesForTheSameEvals() {
        assertEquals(80.8186, report.white.accuracy!!, 0.01)
        assertEquals(98.4347, report.black.accuracy!!, 0.01)
    }

    @Test
    fun acplIsWhatLichessShows() {
        assertEquals(28, report.white.acpl)
        assertEquals(4, report.black.acpl)
    }

    @Test
    fun countsAreLichessOwn() {
        assertEquals(1, report.white.inaccuracies)
        assertEquals(0, report.white.mistakes)
        assertEquals(1, report.white.blunders)
        assertEquals(0, report.black.inaccuracies + report.black.mistakes + report.black.blunders)
    }

    @Test
    fun everyJudgementMatchesLichessAnnotationOnTheSamePly() {
        assertEquals(lichess.plies.size, report.judgements.size)
        lichess.plies.forEachIndexed { index, ply ->
            assertEquals(ply.judgement, report.judgements[index], "ply ${index + 1} ${ply.san}")
        }
    }
}
