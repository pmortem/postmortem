package app.postmortem

import app.postmortem.core.analysis.AnalysisProgress
import app.postmortem.core.analysis.Eval
import app.postmortem.core.analysis.GameReport
import app.postmortem.core.line.Cursor
import app.postmortem.core.pgn.Game
import app.postmortem.core.pgn.PgnParser
import app.postmortem.core.pgn.PgnResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** The game's report and the line reports are worked out once and shared by the copies a result makes as the user steps and plays. */
@OptIn(ExperimentalCoroutinesApi::class)
class ResultCacheTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val game: Game = TestPgn.game("scholars-mate")
    private val evals = List(game.positionCount) { Eval.Cp(it * 40 - 100) }

    @Test
    fun theReportIsTheSameObjectInEveryCopyAndEqualsAFreshOne() {
        val result = UiState.Result("pgn", game, evals)
        val report = result.report
        val stepped = result.copy(cursor = Cursor.game(3), tab = ResultTab.Chart, flipped = true)
        assertSame(report, stepped.report, "a copy does not work the report out again")
        assertEquals(
            GameReport.of(evals, game.whiteMovesFirst, startEval = Eval.Cp(GameReport.LICHESS_START_CP), moves = null).judgements,
            report.judgements,
        )
        assertEquals(UiState.Result("pgn", game, evals).report, report, "the same numbers as a result made from scratch")
    }

    @Test
    fun otherEvalsGetTheirOwnReportEvenThroughACopy() {
        val result = UiState.Result("pgn", game, evals)
        val report = result.report
        val other = result.copy(evals = List(game.positionCount) { Eval.Cp(0) })
        assertNotSame(report, other.report)
        assertEquals(UiState.Result("pgn", game, other.evals).report, other.report)
    }

    @Test
    fun resultsAreEqualWhateverTheirCachesHold() {
        val a = UiState.Result("pgn", game, evals)
        val b = UiState.Result("pgn", game, evals)
        a.report
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    private fun canned(game: Game): Flow<AnalysisProgress> = flow { emit(AnalysisProgress(game.positionCount, game.positionCount, evals)) }

    @Test
    fun aLineReportIsWorkedOutOnceForTheSameLineAndEvalsAndAgainWhenAnEvalArrives() {
        val vm = MainViewModel(::canned, dispatcher)
        vm.onPgnChange(TestPgn.text("scholars-mate"))
        vm.analyse()
        vm.selectPly(4)
        vm.playMove("d2", "d4")
        val before = assertIs<UiState.Result>(vm.state)
        val line = before.lines.all.single()
        assertEquals(emptyList(), before.judgementsOf(line), "no eval yet")
        val withEval =
            before.copy(
                lineEvals =
                    mapOf(
                        app.postmortem.core.analysis
                            .positionKey(line.plies[0].fen) to Eval.Cp(-900),
                    ),
            )
        val marks = withEval.judgementsOf(line)
        assertEquals(1, marks.size)
        val stepped = withEval.copy(cursor = Cursor.game(2))
        assertSame(withEval.cache, stepped.cache)
        assertEquals(marks, stepped.judgementsOf(line))
        assertEquals(listOf(false), stepped.brilliantOf(line))
    }
}
