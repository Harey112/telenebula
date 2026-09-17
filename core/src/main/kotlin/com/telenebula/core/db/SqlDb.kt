package com.telenebula.core.db

/** One row of a result set, read by column index. */
internal interface SqlRow {
    fun isNull(index: Int): Boolean

    fun stringOrNull(index: Int): String?

    fun longOrNull(index: Int): Long?

    fun string(index: Int): String = stringOrNull(index).orEmpty()

    fun long(index: Int): Long = longOrNull(index) ?: 0

    fun int(index: Int): Int = long(index).toInt()

    fun intOrNull(index: Int): Int? = longOrNull(index)?.toInt()

    fun boolean(index: Int): Boolean = longOrNull(index) == 1L

    /** The column as SQLite stored it, for the schema-agnostic copy a backup merge does. */
    fun value(index: Int): Any?
}

/**
 * The little of SQLite the store needs, so the same SQL runs against the framework database on a
 * device and against a plain JDBC connection in the unit tests.
 *
 * Every statement here must work on the SQLite that ships with the oldest supported Android
 * (3.18 on API 26): no UPSERT (`ON CONFLICT … DO UPDATE`), no `VACUUM INTO`, no window functions.
 */
internal interface SqlDb : AutoCloseable {
    /**
     * A single statement with no arguments and no result: DDL. A `PRAGMA` goes through [query]
     * even when its result is thrown away — the framework database rejects anything that can
     * return a row here.
     */
    fun execute(sql: String)

    /** INSERT; true when a row was actually written (false for an ignored conflict). */
    fun insert(sql: String, args: List<Any?> = emptyList()): Boolean

    /** UPDATE or DELETE; returns the number of rows changed. */
    fun update(sql: String, args: List<Any?> = emptyList()): Int

    fun <T> query(sql: String, args: List<Any?> = emptyList(), map: (SqlRow) -> T): List<T>

    fun <T> transaction(block: () -> T): T
}

internal fun <T> SqlDb.queryFirst(sql: String, args: List<Any?> = emptyList(), map: (SqlRow) -> T): T? =
    query(sql, args, map).firstOrNull()

internal fun SqlDb.count(sql: String, args: List<Any?> = emptyList()): Long =
    queryFirst(sql, args) { it.long(0) } ?: 0

/** `?,?,?` for an IN clause of [size] values. */
internal fun placeholders(size: Int): String = List(size) { "?" }.joinToString(",")
