/*
 * Copyright 2016-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kotlinx.coroutines.experimental.internal

import kotlinx.coroutines.experimental.TestBase
import org.junit.Assert.*
import org.junit.Test
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * This stress test has 4 threads adding randomly to the list and them immediately undoing
 * this addition by remove, and 4 threads trying to remove nodes from two lists simultaneously (atomically).
 */
class LockFreeLinkedListAtomicStressTest : TestBase() {
    data class IntNode(val i: Int) : LockFreeLinkedListNode()

    val TEST_DURATION = 5000L * stressTestMultiplier

    val threads = mutableListOf<Thread>()
    val nLists = 4
    val nAdderThreads = 4
    val nRemoverThreads = 4
    val completedAdder = AtomicInteger()
    val completedRemover = AtomicInteger()

    val lists = Array(nLists) { LockFreeLinkedListHead() }

    val undone = AtomicLong()
    val missed = AtomicLong()
    val removed = AtomicLong()
    val error = AtomicReference<Throwable>()

    @Volatile
    var stop = false

    @Test
    fun testStress() {
        println("--- LockFreeLinkedListAtomicStressTest")
        repeat(nAdderThreads) { threadId ->
            threads += thread(start = false, name = "adder-$threadId") {
                val rnd = Random()
                while (!stop) {
                    when (rnd.nextInt(4)) {
                        0 -> {
                            val list = lists[rnd.nextInt(nLists)]
                            val node = IntNode(threadId)
                            list.addLast(node)
                            burnTime(rnd)
                            tryRemove(node)
                        }
                        1 -> {
                            // just to test conditional add
                            val list = lists[rnd.nextInt(nLists)]
                            val node = IntNode(threadId)
                            assertTrue(list.addLastIf(node, { true }))
                            burnTime(rnd)
                            tryRemove(node)
                        }
                        2 -> {
                            // just to test failed conditional add and burn some time
                            val list = lists[rnd.nextInt(nLists)]
                            val node = IntNode(threadId)
                            assertFalse(list.addLastIf(node, { false }))
                            burnTime(rnd)
                        }
                        3 -> {
                            // add two atomically
                            val idx1 = rnd.nextInt(nLists - 1)
                            val idx2 = idx1 + 1 + rnd.nextInt(nLists - idx1 - 1)
                            check(idx1 < idx2) // that is our global order
                            val list1 = lists[idx1]
                            val list2 = lists[idx2]
                            val node1 = IntNode(threadId)
                            val node2 = IntNode(-threadId - 1)
                            val add1 = list1.describeAddLast(node1)
                            val add2 = list2.describeAddLast(node2)
                            val op = object : AtomicOp<Any?>() {
                                override fun prepare(affected: Any?): Any? =
                                    add1.prepare(this) ?:
                                    add2.prepare(this)
                                override fun complete(affected: Any?, failure: Any?) {
                                    add1.complete(this, failure)
                                    add2.complete(this, failure)
                                }
                            }
                            assertTrue(op.perform(null) == null)
                            burnTime(rnd)
                            tryRemove(node1)
                            tryRemove(node2)
                        }
                        else -> error("Cannot happen")
                    }
                }
                completedAdder.incrementAndGet()
            }
        }
        repeat(nRemoverThreads) { threadId ->
            threads += thread(start = false, name = "remover-$threadId") {
                val rnd = Random()
                while (!stop) {
                    val idx1 = rnd.nextInt(nLists - 1)
                    val idx2 = idx1 + 1 + rnd.nextInt(nLists - idx1 - 1)
                    check(idx1 < idx2) // that is our global order
                    val list1 = lists[idx1]
                    val list2 = lists[idx2]
                    val remove1 = list1.describeRemoveFirst()
                    val remove2 = list2.describeRemoveFirst()
                    val op = object : AtomicOp<Any?>() {
                        override fun prepare(affected: Any?): Any? =
                            remove1.prepare(this) ?:
                            remove2.prepare(this)
                        override fun complete(affected: Any?, failure: Any?) {
                            remove1.complete(this, failure)
                            remove2.complete(this, failure)
                        }
                    }
                    val success = op.perform(null) == null
                    if (success) removed.addAndGet(2)

                }
                completedRemover.incrementAndGet()
            }
        }
        threads.forEach { it.setUncaughtExceptionHandler { t, e ->
            println("Exception in thread $t")
            e.printStackTrace(System.out)
            error.compareAndSet(null, e)
        }}
        val startTime = System.currentTimeMillis()
        val deadline = startTime + TEST_DURATION
        threads.forEach(Thread::start)
        var nextPrintTime = startTime
        var prevProgress = -1L
        waitLoop@ while (error.get() == null) {
            val now = System.currentTimeMillis()
            if (now >= deadline) break
            while (now >= nextPrintTime) {
                val progress = progressStats()
                if (progress == prevProgress) {
                    println("!!! Stalled")
                    dumpTraces(threads)
                    error.compareAndSet(null, Error("Stalled"))
                    break@waitLoop
                }
                prevProgress = progress
                println("---")
                nextPrintTime += 1000L
            }
            Thread.sleep(nextPrintTime - now)
        }
        stop = true
        threads.forEach(Thread::join)
        println("Completed successfully ${completedAdder.get()} adder threads")
        println("Completed successfully ${completedRemover.get()} remover threads")
        progressStats()
        error.get()?.let { throw it }
        assertEquals(nAdderThreads, completedAdder.get())
        assertEquals(nRemoverThreads, completedRemover.get())
        assertEquals(missed.get(), removed.get())
        assertTrue(undone.get() > 0)
        assertTrue(missed.get() > 0)
        lists.forEach { it.validate() }
    }

    private fun dumpTraces(threads: List<Thread>) {
        for (thread in threads) {
            println("=== Thread $thread")
            val trace = thread.stackTrace
            for (t in trace) {
                println("\tat ${t.className}.${t.methodName}(${t.fileName}:${t.lineNumber})")
            }
        }
    }

    private fun progressStats(): Long {
        val _undone = undone.get()
        val _missed = missed.get()
        val _removed = removed.get()
        println("  Adders undone $_undone node additions")
        println("  Adders missed $_missed nodes")
        println("Remover removed $_removed nodes")
        return _undone + _missed + _removed
    }

    private val sink = IntArray(1024)

    private fun burnTime(rnd: Random) {
        if (rnd.nextInt(100) < 95) return // be quick, no wait 95% of time
        do {
            val x = rnd.nextInt(100)
            val i = rnd.nextInt(sink.size)
            repeat(x) { sink[i] += it }
        } while (x >= 90)
    }

    private fun tryRemove(node: IntNode) {
        if (node.remove())
            undone.incrementAndGet()
        else
            missed.incrementAndGet()
    }
}