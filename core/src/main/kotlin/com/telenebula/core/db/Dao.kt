package com.telenebula.core.db

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One aggregate of the store. Every DAO shares the store's single lock, and the lock is reentrant
 * on purpose: an aggregate that reads another under the lock must not deadlock on itself.
 */
internal abstract class Dao(protected val db: SqlDb, private val lock: ReentrantLock) {
    protected fun <T> locked(block: () -> T): T = lock.withLock(block)

    /** Several statements that must land together, or not at all. */
    protected fun <T> lockedTransaction(block: () -> T): T = lock.withLock { db.transaction(block) }

    protected fun now(): Long = System.currentTimeMillis()
}
