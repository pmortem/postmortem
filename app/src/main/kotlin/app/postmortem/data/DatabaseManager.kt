package app.postmortem.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.postmortem.core.analysis.Eval
import app.postmortem.core.line.Lines
import app.postmortem.core.pgn.Game
import app.postmortem.core.store.DatabaseCheck
import app.postmortem.core.store.DatabaseName
import app.postmortem.core.store.GameLibrary
import app.postmortem.core.store.GameStore
import app.postmortem.core.store.GameSummary
import app.postmortem.core.store.NewGame
import app.postmortem.core.store.ReportRow
import app.postmortem.core.store.SavedGame
import app.postmortem.core.store.Schema
import java.io.File
import java.io.InputStream

/**
 * The saved games and the database files, in one place: it keeps one database open (the active one, opened on first use), and
 * every action that changes files closes it first so that what is copied or renamed is complete, then lets it reopen.
 * The calls are serialised: SQLite has one writer, and the order of the changes is the order they are saved in.
 */
class DatabaseManager(
    private val context: Context,
    private val active: ActiveDatabaseName,
) : GameLibrary,
    DatabaseControl {
    private var open: OpenDatabase? = null

    private class OpenDatabase(
        val fileName: String,
        val db: AndroidSqlDb,
        val store: GameStore,
    )

    private val folder: File get() = context.getDatabasePath(DatabaseName.DEFAULT).parentFile ?: error("no database folder")

    private fun fileOf(fileName: String) = File(folder, fileName)

    private fun store(): GameStore {
        val name = active.get()
        open?.takeIf { it.fileName == name }?.let { return it.store }
        closeActive()
        folder.mkdirs()
        val db = AndroidSqlDb(SQLiteDatabase.openOrCreateDatabase(fileOf(name), null))
        return GameStore(db).also { open = OpenDatabase(name, db, it) }
    }

    /** Ends the open database, so its file is whole and nothing holds it. The next call reopens it. */
    private fun closeActive() {
        open?.db?.close()
        open = null
    }

    @Synchronized
    override fun findSaved(game: Game): SavedGame? = store().findSaved(game)

    @Synchronized
    override fun save(new: NewGame): Long = store().save(new)

    @Synchronized
    override fun saveLines(
        id: Long,
        lines: Lines,
        lineEvals: Map<String, Eval>,
    ) = store().saveLines(id, lines, lineEvals)

    @Synchronized
    override fun setOpening(
        id: Long,
        opening: String?,
    ) = store().setOpening(id, opening)

    @Synchronized
    override fun load(id: Long): SavedGame? = store().load(id)

    @Synchronized
    override fun list(): List<GameSummary> = store().list()

    override fun reportRows(): List<ReportRow> = store().reportRows()

    @Synchronized
    override fun delete(id: Long) = store().delete(id)

    @Synchronized
    override fun restore(saved: SavedGame) = store().restore(saved)

    @Synchronized
    override fun activeName(): String = active.get()

    @Synchronized
    override fun files(): List<DbFileInfo> {
        val name = active.get()
        return folder
            .listFiles { file -> file.isFile && file.name.endsWith(DatabaseName.EXTENSION) }
            .orEmpty()
            .sortedBy { it.name.lowercase() }
            .map { DbFileInfo(it.name, it.length(), gamesIn(it), it.name == name) }
    }

    /** The number of games in [file], or null when it cannot be read as one of ours. Opened read-only. */
    private fun gamesIn(file: File): Int? =
        runCatching {
            AndroidSqlDb(SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)).use {
                (DatabaseCheck.inspect(it) as? DatabaseCheck.Ok)?.games
            }
        }.getOrNull()

    @Synchronized
    override fun rename(input: String): DbOutcome {
        val name =
            when (val parsed = DatabaseName.parse(input)) {
                is DatabaseName.Valid -> parsed.fileName
                is DatabaseName.Invalid -> return DbOutcome.Failed(parsed.reason.toProblem())
            }
        val current = active.get()
        if (name == current) return DbOutcome.Renamed(name)
        if (fileOf(name).exists()) return DbOutcome.Failed(DbProblem.EXISTS)
        closeActive()
        return runCatching {
            // the file and its journal files move together, or the rename is undone
            val moved = ArrayList<Pair<File, File>>()
            try {
                for (suffix in SIDE_FILES) {
                    val from = fileOf(current + suffix)
                    if (from.exists()) {
                        val to = fileOf(name + suffix)
                        check(from.renameTo(to)) { "could not rename $from" }
                        moved += from to to
                    }
                }
            } catch (e: Exception) {
                moved.forEach { (from, to) -> to.renameTo(from) }
                throw e
            }
            active.set(name)
            DbOutcome.Renamed(name)
        }.getOrElse { DbOutcome.Failed(DbProblem.FAILED) }
    }

    @Synchronized
    override fun open(fileName: String): DbOutcome {
        val file = fileOf(fileName)
        if (!file.isFile) return DbOutcome.Failed(DbProblem.NOT_FOUND)
        return switchTo(file)
    }

    /** Checks [file] without changing it and makes it the active database; nothing changes when it is not one of ours. */
    private fun switchTo(file: File): DbOutcome {
        checkFile(file)?.let { return DbOutcome.Failed(it) }
        closeActive()
        active.set(file.name)
        return DbOutcome.Opened(file.name)
    }

    /** Why [file] cannot be used as a database, or null when it can (an older schema is migrated when it is opened). */
    private fun checkFile(file: File): DbProblem? {
        val header =
            runCatching {
                file.inputStream().use { input ->
                    val bytes = ByteArray(HEADER_BYTES)
                    bytes.copyOf(maxOf(input.read(bytes), 0))
                }
            }.getOrDefault(ByteArray(0))
        if (!DatabaseCheck.hasSqliteHeader(header)) return DbProblem.NOT_SQLITE
        return runCatching {
            AndroidSqlDb(SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)).use {
                when (DatabaseCheck.inspect(it)) {
                    DatabaseCheck.NotOurs -> DbProblem.NOT_OURS
                    is DatabaseCheck.Newer -> DbProblem.NEWER
                    else -> null
                }
            }
        }.getOrElse { DbProblem.NOT_SQLITE }
    }

    @Synchronized
    override fun export(): DbOutcome {
        closeActive()
        return runCatching {
            val name = active.get()
            store() // makes sure the file exists, then closes it again so that the copy is whole
            closeActive()
            val copy = File(File(context.cacheDir, EXPORT_FOLDER).apply { mkdirs() }, name)
            fileOf(name).copyTo(copy, overwrite = true)
            DbOutcome.Exported(copy)
        }.getOrElse { DbOutcome.Failed(DbProblem.FAILED) }
    }

    @Synchronized
    override fun openPicked(
        displayName: String?,
        source: () -> InputStream?,
    ): DbOutcome {
        folder.mkdirs()
        val target = uniqueFile(displayName)
        return runCatching {
            val input = source() ?: return DbOutcome.Failed(DbProblem.FAILED)
            input.use { stream -> target.outputStream().use { stream.copyTo(it) } }
            checkFile(target)?.let {
                target.delete()
                return DbOutcome.Failed(it)
            }
            switchTo(target)
        }.getOrElse {
            target.delete()
            DbOutcome.Failed(DbProblem.FAILED)
        }
    }

    /** A free file name in the folder for a picked file: its own name made safe, with a number added when it is taken. */
    private fun uniqueFile(displayName: String?): File {
        val stem =
            (displayName ?: DatabaseName.DEFAULT)
                .substringBeforeLast('.')
                .filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ' }
                .trim()
                .take(DatabaseName.MAX_LENGTH)
                .ifEmpty { "imported" }
        var candidate = fileOf(stem + DatabaseName.EXTENSION)
        var number = 2
        while (candidate.exists()) candidate = fileOf("$stem $number${DatabaseName.EXTENSION}").also { number++ }
        return candidate
    }

    @Synchronized
    override fun importPicked(source: () -> InputStream?): DbOutcome {
        val temp = File.createTempFile("import", DatabaseName.EXTENSION, context.cacheDir)
        return try {
            val input = source() ?: return DbOutcome.Failed(DbProblem.FAILED)
            input.use { stream -> temp.outputStream().use { stream.copyTo(it) } }
            checkFile(temp)?.let { return DbOutcome.Failed(it) }
            // the picked file may be of an older schema: it is read through a store, which migrates this copy, never the original
            val picked = AndroidSqlDb(SQLiteDatabase.openDatabase(temp.path, null, SQLiteDatabase.OPEN_READWRITE))
            picked.use {
                Schema.migrate(it)
                DbOutcome.Imported(store().mergeFrom(GameStore(it)))
            }
        } catch (e: Exception) {
            DbOutcome.Failed(DbProblem.FAILED)
        } finally {
            temp.delete()
            File(temp.path + "-journal").delete()
        }
    }

    private fun DatabaseName.Reason.toProblem() =
        when (this) {
            DatabaseName.Reason.EMPTY -> DbProblem.EMPTY_NAME
            DatabaseName.Reason.BAD_CHARACTERS -> DbProblem.BAD_CHARACTERS
            DatabaseName.Reason.TOO_LONG -> DbProblem.TOO_LONG
        }

    private companion object {
        const val HEADER_BYTES = 16
        const val EXPORT_FOLDER = "export"
        val SIDE_FILES = listOf("", "-wal", "-shm", "-journal")
    }
}
