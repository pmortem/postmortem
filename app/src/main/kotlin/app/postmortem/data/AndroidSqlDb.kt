package app.postmortem.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.postmortem.core.store.SqlDb

/** [SqlDb] on Android's own SQLite, so the saved games are a standard SQLite file any desktop tool opens. */
class AndroidSqlDb(
    private val db: SQLiteDatabase,
) : SqlDb,
    AutoCloseable {
    private var depth = 0

    override fun execute(
        sql: String,
        args: List<Any?>,
    ) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args.toTypedArray())
    }

    override fun query(
        sql: String,
        args: List<Any?>,
    ): List<Map<String, Any?>> =
        db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until cursor.columnCount).associate { cursor.getColumnName(it) to cursor.value(it) })
                }
            }
        }

    private fun Cursor.value(column: Int): Any? =
        when (getType(column)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> getLong(column)
            Cursor.FIELD_TYPE_FLOAT -> getDouble(column)
            Cursor.FIELD_TYPE_STRING -> getString(column)
            else -> error("unsupported column type in column $column")
        }

    /** A call inside another joins it: SQLite has no nested transactions, so only the outermost commits or rolls back. */
    override fun <T> transaction(block: () -> T): T {
        if (depth > 0) return block()
        depth++
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
            depth--
        }
    }

    override fun close() = db.close()
}
