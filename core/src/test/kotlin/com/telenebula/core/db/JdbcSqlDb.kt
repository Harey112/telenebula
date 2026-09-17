package com.telenebula.core.db

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * [SqlDb] over a plain JDBC connection, so the store's SQL is exercised against a real SQLite in
 * the unit tests. The statements it runs are the ones the device runs; only the driver differs,
 * which is why every one of them stays inside what Android's own SQLite understands.
 */
internal class JdbcSqlDb private constructor(private val connection: Connection) : SqlDb {
    override fun execute(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    override fun insert(sql: String, args: List<Any?>): Boolean = prepared(sql, args).use { it.executeUpdate() > 0 }

    override fun update(sql: String, args: List<Any?>): Int = prepared(sql, args).use { it.executeUpdate() }

    override fun <T> query(sql: String, args: List<Any?>, map: (SqlRow) -> T): List<T> = prepared(sql, args).use { statement ->
        statement.executeQuery().use { results ->
            val row = ResultSetRow(results)
            val out = ArrayList<T>()
            while (results.next()) out += map(row)
            out
        }
    }

    override fun <T> transaction(block: () -> T): T {
        // the store never nests its own transactions, but the schema migration runs inside open()
        if (!connection.autoCommit) return block()
        connection.autoCommit = false
        try {
            val result = block()
            connection.commit()
            return result
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    override fun close() = connection.close()

    private fun prepared(sql: String, args: List<Any?>): PreparedStatement {
        val statement = connection.prepareStatement(sql)
        args.forEachIndexed { index, value ->
            val at = index + 1
            when (value) {
                null -> statement.setNull(at, java.sql.Types.NULL)
                is String -> statement.setString(at, value)
                is Long -> statement.setLong(at, value)
                is Int -> statement.setInt(at, value)
                is Boolean -> statement.setLong(at, if (value) 1 else 0)
                is Double -> statement.setDouble(at, value)
                is ByteArray -> statement.setBytes(at, value)
                else -> statement.setString(at, value.toString())
            }
        }
        return statement
    }

    private class ResultSetRow(private val results: ResultSet) : SqlRow {
        override fun isNull(index: Int): Boolean {
            results.getObject(index + 1)
            return results.wasNull()
        }

        override fun stringOrNull(index: Int): String? = results.getString(index + 1)

        override fun longOrNull(index: Int): Long? {
            val value = results.getLong(index + 1)
            return if (results.wasNull()) null else value
        }

        override fun value(index: Int): Any? = results.getObject(index + 1)
    }

    companion object {
        fun open(path: String): SqlDb {
            val connection = DriverManager.getConnection("jdbc:sqlite:$path")
            return JdbcSqlDb(connection)
        }
    }
}
