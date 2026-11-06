package app.postmortem.data

import app.postmortem.core.store.MergeResult
import java.io.File
import java.io.InputStream

/** A database file in the app's database folder: its name, size and, when it can be read, the number of games in it. */
data class DbFileInfo(
    val fileName: String,
    val sizeBytes: Long,
    val games: Int?,
    val active: Boolean,
)

/** Why a database action did not happen. Nothing was changed when it says so. */
enum class DbProblem {
    EMPTY_NAME,
    BAD_CHARACTERS,
    TOO_LONG,
    EXISTS,
    NOT_FOUND,
    NOT_SQLITE,
    NOT_OURS,
    NEWER,
    FAILED,
}

sealed interface DbOutcome {
    data class Renamed(
        val fileName: String,
    ) : DbOutcome

    data class Opened(
        val fileName: String,
    ) : DbOutcome

    /** A complete copy of the active database, in the cache folder, ready to share. */
    data class Exported(
        val file: File,
    ) : DbOutcome

    data class Imported(
        val result: MergeResult,
    ) : DbOutcome

    data class Failed(
        val problem: DbProblem,
    ) : DbOutcome
}

/**
 * The database files: which one is active, renaming it, switching to another, exporting it, opening or merging a file the user
 * picked. Every action either happens completely or changes nothing, and a file that is not ours is never opened for writing.
 */
interface DatabaseControl {
    fun activeName(): String

    fun files(): List<DbFileInfo>

    /** Gives the active database the name [input] (`.sqlite` is added). Refused when that name is taken or not allowed. */
    fun rename(input: String): DbOutcome

    /** Makes the database [fileName] in the folder the active one: the Library and the saves use it from now on. */
    fun open(fileName: String): DbOutcome

    /** A complete copy of the active database to share. */
    fun export(): DbOutcome

    /** Copies a picked file into the folder (as [displayName], made unique), checks it, and makes it the active database. */
    fun openPicked(
        displayName: String?,
        source: () -> InputStream?,
    ): DbOutcome

    /** Merges the games of a picked file into the active database; the picked file is not kept. */
    fun importPicked(source: () -> InputStream?): DbOutcome
}
