package app.postmortem

import app.postmortem.core.analysis.Eval
import app.postmortem.core.analysis.PositionEvals
import app.postmortem.core.line.Lines
import app.postmortem.core.pgn.Game
import app.postmortem.core.pgn.PgnParser
import app.postmortem.core.pgn.PgnResult
import app.postmortem.core.store.GameLibrary
import app.postmortem.core.store.GameSummary
import app.postmortem.core.store.NewGame
import app.postmortem.core.store.SavedGame

/** A library that holds nothing and does nothing; a test overrides only what it looks at. */
open class StubLibrary : GameLibrary {
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

    override fun load(id: Long): SavedGame? = null

    override fun list(): List<GameSummary> = emptyList()

    override fun delete(id: Long) = Unit

    override fun restore(saved: SavedGame) = Unit
}

/** Position evals that know nothing and give 0.00; a test overrides only what it looks at. */
open class StubPositions : PositionEvals {
    override fun cached(fen: String): Eval? = null

    override suspend fun evaluate(fen: String): Eval = Eval.Cp(0)

    override fun seed(
        game: Game,
        evals: List<Eval>,
    ) = Unit

    override suspend fun release() = Unit
}

/** The real games under `src/test/resources/pgn`, as text and parsed. */
object TestPgn {
    fun text(name: String): String = checkNotNull(TestPgn::class.java.getResource("/pgn/$name.pgn")) { "no fixture $name" }.readText()

    fun game(name: String): Game = (PgnParser.parse(text(name)) as PgnResult.Success).game
}
