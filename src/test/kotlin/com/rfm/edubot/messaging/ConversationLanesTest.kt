package com.rfm.edubot.messaging

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationLanesTest {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun `messages from one conversation run one at a time in arrival order`() = runBlocking {
        val lanes = ConversationLanes(scope, laneCount = 4)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondDone = CompletableDeferred<Unit>()

        lanes.submit("tenant:WHATSAPP:351900000001") {
            events += "hi:start"
            firstStarted.complete(Unit)
            releaseFirst.await()
            events += "hi:end"
        }
        lanes.submit("tenant:WHATSAPP:351900000001") {
            events += "quote:start"
            secondDone.complete(Unit)
        }

        withTimeout(2_000) { firstStarted.await() }
        delay(100)
        assertEquals(listOf("hi:start"), events.toList(), "the second message must wait for the first")

        releaseFirst.complete(Unit)
        withTimeout(2_000) { secondDone.await() }
        assertEquals(listOf("hi:start", "hi:end", "quote:start"), events.toList())
    }

    @Test
    fun `conversations on different lanes do not wait for each other`() = runBlocking {
        val lanes = ConversationLanes(scope, laneCount = 2)
        val slowKey = "tenant:WHATSAPP:slow"
        val fastKey = generateSequence(0) { it + 1 }.map { "tenant:WHATSAPP:fast-$it" }.first { lanes.laneOf(it) != lanes.laneOf(slowKey) }
        val fastDone = CompletableDeferred<Unit>()

        lanes.submit(slowKey) { awaitCancellation() }
        lanes.submit(fastKey) { fastDone.complete(Unit) }

        withTimeout(2_000) { fastDone.await() }
    }

    @Test
    fun `no more jobs run at once than there are lanes`() = runBlocking {
        val lanes = ConversationLanes(scope, laneCount = 2)
        val running = AtomicInteger(0)
        val maxRunning = AtomicInteger(0)
        val release = CompletableDeferred<Unit>()
        val finished = AtomicInteger(0)
        val allDone = CompletableDeferred<Unit>()

        repeat(8) { i ->
            lanes.submit("tenant:WHATSAPP:customer-$i") {
                maxRunning.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                release.await()
                running.decrementAndGet()
                if (finished.incrementAndGet() == 8) allDone.complete(Unit)
            }
        }

        delay(200)
        release.complete(Unit)
        withTimeout(2_000) { allDone.await() }
        assertTrue(maxRunning.get() <= 2, "at most 2 jobs may run at once, saw ${maxRunning.get()}")
    }

    @Test
    fun `a failing job does not stop later jobs on its lane`() = runBlocking {
        val lanes = ConversationLanes(scope, laneCount = 1)
        val laterRan = CompletableDeferred<Unit>()

        lanes.submit("tenant:WHATSAPP:a") { error("pipeline blew up") }
        lanes.submit("tenant:WHATSAPP:a") { laterRan.complete(Unit) }

        withTimeout(2_000) { laterRan.await() }
    }
}
