package com.example.gxcloud

import java.util.concurrent.Executor

// The supplied executor must run tasks serially. Reads form barriers between writes.
internal class CoalescingSerialQueue(private val executor: Executor) {
    private class PendingWrite(val key: String, var operation: (() -> Unit)?)
    private val lock = Any()
    private var replaceableWrite: PendingWrite? = null

    fun write(key: String, operation: () -> Unit) {
        synchronized(lock) {
            val previous = replaceableWrite
            if (previous != null && previous.key == key) {
                previous.operation = operation
                return
            }
            val task = PendingWrite(key, operation)
            replaceableWrite = task
            executor.execute {
                val work = synchronized(lock) {
                    if (replaceableWrite === task) replaceableWrite = null
                    requireNotNull(task.operation).also { task.operation = null }
                }
                work()
            }
        }
    }

    fun read(operation: () -> Unit) {
        synchronized(lock) {
            // A replacement Activity must load the state saved before this barrier.
            replaceableWrite = null
            executor.execute { operation() }
        }
    }
}
