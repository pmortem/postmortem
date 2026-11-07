package app.postmortem

import app.postmortem.core.analysis.Eval
import app.postmortem.core.analysis.PositionEvals
import app.postmortem.core.board.PieceColor
import app.postmortem.core.line.Lines
import app.postmortem.core.pgn.Game
import app.postmortem.core.pgn.PgnParser
import app.postmortem.core.pgn.PgnResult
import app.postmortem.core.report.DrillItem
import app.postmortem.core.report.DrillKind
import app.postmortem.core.report.Outcome
import app.postmortem.core.report.PlayerGame
import app.postmortem.core.store.GameLibrary
import app.postmortem.core.store.GameSummary
import app.postmortem.core.store.NewGame
import app.postmortem.core.store.SavedGame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DrillControllerTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val game: Game =
        (PgnParser.parse(checkNotNull(javaClass.getResource("/pgn/scholars-mate.pgn")).readText()) as PgnResult.Success).game

    /** A library with one game; best moves are what [bests] holds (by position), and every try is noted. */
    private class OneGameLibrary(
        private val game: Game,
        val bests: MutableMap<Int, String> = HashMap(),
    ) : GameLibrary {
        val tries = ArrayList<Triple<Long, Int, Boolean>>()
        val written = ArrayList<Triple<Long, Int, String>>()
        var hasGame = true

        override fun findSaved(game: Game): SavedGame? = null

        override fun save(new: NewGame): Long = 0

        override fun saveLines(
            id: Long,
            lines: Lines,
            lineEvals: Map<String, Eval>,
        ) = Unit

        override fun setOpening(
            id: Long,
            opening: String?,
        ) = Unit

        override fun load(id: Long): SavedGame? =
            if (hasGame) {
                SavedGame(id, "k", "pgn", game, List(game.positionCount) { Eval.Cp(0) }, "t", Lines(), emptyMap(), null, null, null, 1, 1)
            } else {
                null
            }

        override fun list(): List<GameSummary> = emptyList()

        override fun delete(id: Long) = Unit

        override fun restore(saved: SavedGame) = Unit

        override fun bestMove(
            gameId: Long,
            ply: Int,
        ): String? = bests[ply]

        override fun setBestMove(
            gameId: Long,
            ply: Int,
            uci: String,
        ) {
            written += Triple(gameId, ply, uci)
            bests[ply] = uci
        }

        override fun recordDrill(
            gameId: Long,
            position: Int,
            solved: Boolean,
        ) {
            tries += Triple(gameId, position, solved)
        }
    }

    private class FakeEngine(
        var after: Eval = Eval.Cp(0),
        var best: String? = null,
    ) : PositionEvals {
        val asked = ArrayList<String>()
        var released = 0
        var fail = false

        override fun cached(fen: String): Eval? = null

        override suspend fun evaluate(fen: String): Eval {
            asked += fen
            if (fail) error("engine broke")
            return after
        }

        override suspend fun bestMove(fen: String): String? = best

        override fun seed(
            game: Game,
            evals: List<Eval>,
        ) = Unit

        override suspend fun release() {
            released++
        }
    }

    private fun item(
        position: Int,
        color: PieceColor = PieceColor.WHITE,
        before: Eval = Eval.Cp(30),
        after: Eval = Eval.Cp(-300),
    ) = DrillItem(
        PlayerGame(1, color, "Anna", Outcome.LOSS, null, 80.0, "2025.01.01"),
        position,
        DrillKind.BLUNDER,
        game.plies[position].san,
        before,
        after,
        null,
    )

    private fun controller(
        library: GameLibrary?,
        engine: PositionEvals? = FakeEngine(),
    ): DrillController {
        val scope = CoroutineScope(dispatcher)
        val report = ReportController(scope, dispatcher, library, InMemoryReportPlayerSetting())
        return DrillController(scope, dispatcher, library, engine, report)
    }

    private fun DrillController.move(
        from: String,
        to: String,
    ) {
        tapSquare(from)
        tapSquare(to)
    }

    // Position 2 of the game: after 1.e4 e5, White to move; the engine's best move there is 2.Nf3.
    private val library get() = OneGameLibrary(game, mutableMapOf(2 to "g1f3"))

    @Test
    fun anEmptyQueueStartsNothing() {
        val drill = controller(library)
        drill.start(emptyList())
        assertFalse(drill.ui.active)
    }

    @Test
    fun startingAsksTheFirstPositionFromThePlayersSideWithTheMoveBeforeItMarked() {
        val drill = controller(library)
        drill.start(listOf(item(2), item(3, PieceColor.BLACK)))
        assertTrue(drill.ui.active)
        assertEquals(DrillPhase.ASKING, drill.ui.phase)
        assertEquals(game.fenAt(2), drill.ui.fen)
        assertEquals(game.plies[1].uci, drill.ui.lastMove, "the opponent's last move is highlighted")
        assertFalse(drill.ui.flipped)
        assertEquals(2, drill.ui.total)
        assertNull(drill.ui.best, "no hint before the answer")
    }

    @Test
    fun theBestMoveIsRightAtOnceWithoutAskingTheEngineAndIsRecordedAsSolved() {
        val lib = library
        val engine = FakeEngine()
        val drill = controller(lib, engine)
        drill.start(listOf(item(2)))
        drill.move("g1", "f3")
        assertEquals(DrillPhase.RIGHT, drill.ui.phase)
        assertEquals(1, drill.ui.solved)
        assertTrue(engine.asked.isEmpty())
        assertEquals(listOf(Triple(1L, 2, true)), lib.tries)
        assertEquals(game.fenAt(2).let { drill.ui.fen != it }, true, "the board shows the move played")
    }

    @Test
    fun aMoveNearlyAsGoodIsGoodWithAnArrowForTheBestMoveAndIsSolved() {
        val lib = library
        val engine = FakeEngine(after = Eval.Cp(10))
        val drill = controller(lib, engine)
        drill.start(listOf(item(2)))
        drill.move("b1", "c3")
        assertEquals(DrillPhase.GOOD, drill.ui.phase)
        assertEquals("g1f3", drill.ui.best)
        assertEquals(1, engine.asked.size)
        assertEquals(listOf(Triple(1L, 2, true)), lib.tries)
    }

    @Test
    fun aWrongMoveIsTakenBackAndCanBeTriedAgainOrShown() {
        val lib = library
        val engine = FakeEngine(after = Eval.Cp(-400))
        val drill = controller(lib, engine)
        drill.start(listOf(item(2)))
        drill.move("a2", "a3")
        assertEquals(DrillPhase.WRONG, drill.ui.phase)
        assertEquals(game.fenAt(2), drill.ui.fen, "the move was taken back")
        assertTrue(lib.tries.isEmpty(), "a retry is not an attempt yet")
        drill.tryAgain()
        assertEquals(DrillPhase.ASKING, drill.ui.phase)
        drill.move("a2", "a3")
        drill.show()
        assertEquals(DrillPhase.SHOWN, drill.ui.phase)
        assertEquals("g1f3", drill.ui.best)
        assertEquals("Nf3", drill.ui.bestSan)
        assertEquals(listOf(Triple(1L, 2, false)), lib.tries)
        assertEquals(1, drill.ui.failed)
    }

    @Test
    fun aPositionWithNoStoredBestMoveGetsOneFromTheEngineAndItIsWrittenBack() {
        val lib = OneGameLibrary(game)
        val engine = FakeEngine(best = "g1f3")
        val drill = controller(lib, engine)
        drill.start(listOf(item(2)))
        assertEquals(DrillPhase.ASKING, drill.ui.phase)
        assertEquals(listOf(Triple(1L, 2, "g1f3")), lib.written)
        drill.move("g1", "f3")
        assertEquals(DrillPhase.RIGHT, drill.ui.phase)
    }

    @Test
    fun anEngineThatBreaksLeavesTheQuestionAskedWithAMessage() {
        val engine = FakeEngine().apply { fail = true }
        val drill = controller(library, engine)
        drill.start(listOf(item(2)))
        drill.move("a2", "a3")
        assertEquals(DrillPhase.ASKING, drill.ui.phase)
        assertTrue(drill.ui.engineFailed)
    }

    @Test
    fun skippingGoesOnAndCountsAsFailedAndTheEndOffersTheFailedOnesAgain() {
        val lib = library
        val drill = controller(lib)
        drill.start(listOf(item(2), item(2)))
        drill.skip()
        assertEquals(1, drill.ui.position)
        drill.move("g1", "f3")
        drill.next()
        assertEquals(DrillPhase.DONE, drill.ui.phase)
        assertEquals(1, drill.ui.solved)
        assertEquals(1, drill.ui.failed)
        assertEquals(listOf(Triple(1L, 2, false), Triple(1L, 2, true)), lib.tries)
        drill.startOver()
        assertEquals(DrillPhase.ASKING, drill.ui.phase)
        assertEquals(1, drill.ui.total, "only the skipped one comes back")
        assertEquals(0, drill.ui.solved)
    }

    @Test
    fun aGameDeletedMeanwhileIsPassedOver() {
        val lib = library.also { it.hasGame = false }
        val drill = controller(lib)
        drill.start(listOf(item(2)))
        assertEquals(DrillPhase.DONE, drill.ui.phase)
    }

    @Test
    fun closingEndsThePracticeAndReleasesTheEngine() {
        val engine = FakeEngine()
        val drill = controller(library, engine)
        drill.start(listOf(item(2)))
        drill.close()
        assertFalse(drill.ui.active)
        assertEquals(1, engine.released)
    }

    @Test
    fun otherMovesAreIgnoredWhileCheckingOrAfterTheVerdict() {
        val drill = controller(library)
        drill.start(listOf(item(2)))
        drill.move("g1", "f3")
        val shown = drill.ui.fen
        drill.move("b1", "c3")
        assertEquals(shown, drill.ui.fen)
        assertEquals(DrillPhase.RIGHT, drill.ui.phase)
    }

    @Test
    fun backClosesThePracticeBeforeAnythingElse() {
        val lib = library
        val vm = MainViewModel({ kotlinx.coroutines.flow.flow { } }, dispatcher, library = lib, positions = FakeEngine())
        vm.selectStartTab(StartTab.Report)
        vm.drill.start(listOf(item(2)))
        assertTrue(vm.drill.ui.active)
        assertTrue(vm.back())
        assertFalse(vm.drill.ui.active)
        assertEquals(StartTab.Report, vm.startTab, "the first Back only left the practice")
    }
}
