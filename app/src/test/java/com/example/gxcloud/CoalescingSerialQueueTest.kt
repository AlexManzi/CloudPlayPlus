package com.example.gxcloud

import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class CoalescingSerialQueueTest {
    private class ManualExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runNext() { tasks.removeFirst().run() }
        fun drain() { while (tasks.isNotEmpty()) runNext() }
    }

    @Test fun aSlowWorkerKeepsOnlyTheNewestAdjacentSnapshot() {
        val executor = ManualExecutor()
        val queue = CoalescingSerialQueue(executor)
        val writes = mutableListOf<Int>()
        val saves = SaveRevisionTracker()
        repeat(1_000) { value ->
            saves.changed()
            val revision = saves.begin()!!
            queue.write("notes") { writes.add(value); saves.complete(revision, true) }
        }
        assertEquals(1, executor.tasks.size)
        executor.drain()
        assertEquals(listOf(999), writes)
        assertNull(saves.begin())
    }

    @Test fun aRunningWriteIsImmutableWhileItsSuccessorsCoalesce() {
        val executor = ManualExecutor()
        val queue = CoalescingSerialQueue(executor)
        val events = mutableListOf<String>()
        queue.write("notes") {
            events.add("running-start")
            queue.write("notes") { events.add("obsolete") }
            queue.write("notes") { events.add("newest") }
            events.add("running-finish")
        }
        executor.runNext()
        assertEquals(listOf("running-start", "running-finish"), events)
        assertEquals(1, executor.tasks.size)
        executor.drain()
        assertEquals(listOf("running-start", "running-finish", "newest"), events)
    }

    @Test fun replacementActivityLoadSeesEarlierSaveBeforeLaterEdits() {
        val executor = ManualExecutor()
        val queue = CoalescingSerialQueue(executor)
        var disk = "original"
        var loaded = ""
        queue.write("notes") { disk = "obsolete old Activity save" }
        queue.write("notes") { disk = "final old Activity save" }
        queue.read { loaded = disk }
        queue.write("notes") { disk = "obsolete later edit" }
        queue.write("notes") { disk = "newest later edit" }
        assertEquals(3, executor.tasks.size)
        executor.drain()
        assertEquals("final old Activity save", loaded)
        assertEquals("newest later edit", disk)
    }

    @Test fun writesToOtherFilesAlsoPreserveOrdering() {
        val executor = ManualExecutor()
        val queue = CoalescingSerialQueue(executor)
        val writes = mutableListOf<String>()
        queue.write("a") { writes.add("a1") }
        queue.write("b") { writes.add("b1") }
        queue.write("a") { writes.add("a2") }
        executor.drain()
        assertEquals(listOf("a1", "b1", "a2"), writes)
    }

    @Test fun failureOfTheNewestCoalescedRevisionRemainsRetryable() {
        val executor = ManualExecutor()
        val queue = CoalescingSerialQueue(executor)
        val saves = SaveRevisionTracker()
        saves.changed()
        val old = saves.begin()!!
        queue.write("notes") { fail("Obsolete snapshot must not be written") }
        saves.changed()
        val latest = saves.begin()!!
        assertTrue(latest > old)
        queue.write("notes") { saves.complete(latest, false) }
        executor.drain()
        assertEquals(latest, saves.begin())
        queue.write("notes") { saves.complete(latest, true) }
        executor.drain()
        assertNull(saves.begin())
    }
}
