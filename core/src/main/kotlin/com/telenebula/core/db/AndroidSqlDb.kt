package com.telenebula.core.db

import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteProgram
import com.telenebula.core.CoreException
import java.io.File

/**
 * [SqlDb] over the framework database. Arguments are bound through a cursor factory rather than
 * `rawQuery`'s string array, so an integer reaches SQLite as an integer — the same way Room does
 * it, and the only way the framework offers typed binding for a query.
 */
internal class AndroidSqlDb private constructor(private val db: SQLiteDatabase) : SqlDb {
    override fun execute(sql: String) = wrap(sql) { db.execSQL(sql) }

    override fun insert(sql: String, args: List<Any?>): Boolean = wrap(sql) {
        db.compileStatement(sql).use { statement ->
            statement.bindAll(args)
            statement.executeInsert() != -1L
        }
    }

    override fun update(sql: String, args: List<Any?>): Int = wrap(sql) {
        db.compileStatement(sql).use { statement ->
            statement.bindAll(args)
            statement.executeUpdateDelete()
        }
    }

    override fun <T> query(sql: String, args: List<Any?>, map: (SqlRow) -> T): List<T> = wrap(sql) {
        val cursor = db.rawQueryWithFactory(
            { _, driver, editTable, query ->
                query.bindAll(args)
                SQLiteCursor(driver, editTable, query)
            },
            sql,
            EMPTY_ARGS,
            // the cursor's "edit table" is only used by long-removed update APIs
            "",
        )
        cursor.use {
            val out = ArrayList<T>(it.count.coerceAtMost(PREALLOC))
            val row = CursorRow(it)
            while (it.moveToNext()) out += map(row)
            out
        }
    }

    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    override fun close() = db.close()

    private inline fun <T> wrap(sql: String, block: () -> T): T = try {
        block()
    } catch (e: android.database.SQLException) {
        throw CoreException.db("${e.message} (${sql.take(SQL_IN_ERROR)})", e)
    }

    private class CursorRow(private val cursor: Cursor) : SqlRow {
        override fun isNull(index: Int): Boolean = cursor.isNull(index)

        override fun stringOrNull(index: Int): String? = if (cursor.isNull(index)) null else cursor.getString(index)

        override fun longOrNull(index: Int): Long? = if (cursor.isNull(index)) null else cursor.getLong(index)

        override fun value(index: Int): Any? = when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index)
            else -> cursor.getString(index)
        }
    }

    companion object {
        private val EMPTY_ARGS = emptyArray<String>()
        private const val PREALLOC = 256
        private const val SQL_IN_ERROR = 120

        fun open(path: String): SqlDb {
            File(path).parentFile?.mkdirs()
            val db = SQLiteDatabase.openOrCreateDatabase(path, null)
            db.enableWriteAheadLogging()
            return AndroidSqlDb(db)
        }

        private fun SQLiteProgram.bindAll(args: List<Any?>) {
            args.forEachIndexed { index, value ->
                val at = index + 1
                when (value) {
                    null -> bindNull(at)
                    is String -> bindString(at, value)
                    is Long -> bindLong(at, value)
                    is Int -> bindLong(at, value.toLong())
                    is Boolean -> bindLong(at, if (value) 1 else 0)
                    is Double -> bindDouble(at, value)
                    is ByteArray -> bindBlob(at, value)
                    else -> bindString(at, value.toString())
                }
            }
        }
    }
}
