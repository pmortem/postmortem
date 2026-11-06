package app.postmortem.core.store

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement

/** [SqlDb] on a real SQLite through JDBC, for the tests; the phone uses Android's SQLite behind the same interface. */
class JdbcSqlDb(
    private val connection: Connection,
) : SqlDb,
    AutoCloseable {
    private var depth = 0

    private fun PreparedStatement.bind(args: List<Any?>): PreparedStatement {
        args.forEachIndexed { index, value ->
            when (value) {
                null -> setObject(index + 1, null)
                is Int -> setLong(index + 1, value.toLong())
                is Long -> setLong(index + 1, value)
                is Double -> setDouble(index + 1, value)
                is String -> setString(index + 1, value)
                else -> error("unsupported value $value")
            }
        }
        return this
    }

    override fun execute(
        sql: String,
        args: List<Any?>,
    ) {
        connection.prepareStatement(sql).use { it.bind(args).execute() }
    }

    override fun query(
        sql: String,
        args: List<Any?>,
    ): List<Map<String, Any?>> =
        connection.prepareStatement(sql).use { statement ->
            statement.bind(args).executeQuery().use { rows ->
                val meta = rows.metaData
                buildList {
                    while (rows.next()) {
                        add(
                            (1..meta.columnCount).associate { column ->
                                meta.getColumnLabel(column) to
                                    when (val value = rows.getObject(column)) {
                                        is Int -> value.toLong()
                                        else -> value
                                    }
                            },
                        )
                    }
                }
            }
        }

    /** Nested calls join the outer transaction, as on the phone: only the outermost one commits or rolls back. */
    override fun <T> transaction(block: () -> T): T {
        if (depth > 0) return block()
        depth++
        connection.autoCommit = false
        try {
            val result = block()
            connection.commit()
            return result
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            depth--
            connection.autoCommit = true
        }
    }

    override fun close() = connection.close()

    companion object {
        fun open(path: String = ":memory:") = JdbcSqlDb(DriverManager.getConnection("jdbc:sqlite:$path"))
    }
}
