package app.postmortem.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.postmortem.core.analysis.Eval
import app.postmortem.core.line.Lines
import app.postmortem.core.pgn.Game
import app.postmortem.core.store.GameLibrary
import app.postmortem.core.store.GameStore
import app.postmortem.core.store.GameSummary
import app.postmortem.core.store.NewGame
import app.postmortem.core.store.SavedGame

/** The name of the saved games' file, in the app's own storage. */
const val DATABASE_NAME = "postmortem.sqlite"

/**
 * The saved games, opened on first use so that starting the app does no disk work; every call comes from a background thread.
 * The calls are serialised: SQLite has one writer, and the order in which changes were made is the order they are saved in.
 */
class LazyGameLibrary(
    private val context: Context,
) : GameLibrary {
    private val store: GameStore by lazy {
        val file = context.getDatabasePath(DATABASE_NAME)
        file.parentFile?.mkdirs()
        GameStore(AndroidSqlDb(SQLiteDatabase.openOrCreateDatabase(file, null)))
    }

    @Synchronized
    override fun findSaved(game: Game): SavedGame? = store.findSaved(game)

    @Synchronized
    override fun save(new: NewGame): Long = store.save(new)

    @Synchronized
    override fun saveLines(
        id: Long,
        lines: Lines,
        lineEvals: Map<String, Eval>,
    ) = store.saveLines(id, lines, lineEvals)

    @Synchronized
    override fun setOpening(
        id: Long,
        opening: String?,
    ) = store.setOpening(id, opening)

    @Synchronized
    override fun load(id: Long): SavedGame? = store.load(id)

    @Synchronized
    override fun list(): List<GameSummary> = store.list()

    @Synchronized
    override fun delete(id: Long) = store.delete(id)

    @Synchronized
    override fun restore(saved: SavedGame) = store.restore(saved)
}
