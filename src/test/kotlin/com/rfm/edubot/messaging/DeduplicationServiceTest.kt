package com.rfm.edubot.messaging

import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.Platform
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

// This is the collection behind the "at-least-once delivery" guarantee described in
// docs/architecture.md: every inbound webhook, from every tenant and every channel,
// goes through isDuplicate() before it's ever enqueued. It had zero test coverage.
@Testcontainers
class DeduplicationServiceTest {

    companion object {
        @Container
        @JvmStatic
        val mongo = MongoDBContainer("mongo:7")

        private lateinit var mongoModule: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo.start()
            mongoModule = MongoModule(AppConfig.MongoConfig(uri = mongo.replicaSetUrl, database = "test"))
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongoModule.shutdown()
            mongo.stop()
        }
    }

    private fun freshService(): DeduplicationService {
        // Each test gets its own collection state cleared, since the collection name
        // ("webhook_events") is fixed by DeduplicationService itself.
        runBlocking { mongoModule.database.getCollection<Document>("webhook_events").drop() }
        return DeduplicationService(mongoModule)
    }

    @Test
    fun `first sighting of an eventId is not a duplicate`() = runBlocking {
        val service = freshService()
        assertFalse(service.isDuplicate("evt-1", "{}"))
    }

    @Test
    fun `second sighting of the same eventId is a duplicate`() = runBlocking {
        val service = freshService()
        assertFalse(service.isDuplicate("evt-1", "{}"))
        assertTrue(service.isDuplicate("evt-1", "{}"))
    }

    @Test
    fun `concurrent duplicate webhook deliveries are only accepted once`() {
        // Meta retries webhook delivery on slow 200s; two deliveries of the same eventId
        // can race each other into isDuplicate concurrently. Exactly one must win.
        // Uses real JVM threads (not just coroutine interleaving) to actually exercise the
        // Mutex + Mongo unique-upsert race protection under genuine concurrent access.
        val service = freshService()
        val notDuplicateCount = java.util.concurrent.atomic.AtomicInteger(0)
        val latch = java.util.concurrent.CountDownLatch(20)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(20)
        try {
            repeat(20) {
                executor.submit {
                    try {
                        if (!runBlocking { service.isDuplicate("evt-race", "{}") }) {
                            notDuplicateCount.incrementAndGet()
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            executor.shutdown()
        }

        assertEquals(1, notDuplicateCount.get(), "exactly one caller should see isDuplicate == false")
    }

    @Test
    fun `different eventIds are independent`() = runBlocking {
        val service = freshService()
        assertFalse(service.isDuplicate("evt-a", "{}"))
        assertFalse(service.isDuplicate("evt-b", "{}"))
    }

    @Test
    fun `markProcessed updates status without affecting duplicate detection`() = runBlocking {
        val service = freshService()
        assertFalse(service.isDuplicate("evt-1", "{}"))
        service.markProcessed("evt-1")

        val doc = mongoModule.database.getCollection<Document>("webhook_events")
            .find(com.mongodb.client.model.Filters.eq("eventId", "evt-1")).firstOrNull()
        assertEquals("processed", doc?.getString("status"))
        // Still a duplicate afterwards - processed events must not be retried.
        assertTrue(service.isDuplicate("evt-1", "{}"))
    }

    private fun queued(eventId: String) = InboundMessage(
        tenantId = ObjectId(),
        phoneNumberId = "ig-account-1",
        platform = Platform.INSTAGRAM,
        channelExternalId = "ig-account-1",
        waId = "igsid-1",
        waMessageId = eventId,
        profileName = "Ana",
        messageText = "Olá, fazem orçamentos?",
        timestamp = "1700000000",
        eventId = eventId,
        registerOnly = true,
    )

    @Test
    fun `an accepted message that was never finished reads back exactly as it was enqueued`() = runBlocking {
        val service = freshService()
        val message = queued("evt-unfinished")
        assertFalse(service.isDuplicate(message.eventId, "{}", message.tenantId, message))

        val now = Clock.System.now()
        assertEquals(listOf(message), service.unprocessedInbound(now - 1.minutes, now + 1.minutes))
    }

    @Test
    fun `a queued photo keeps its media after a restart`() = runBlocking {
        val service = freshService()
        val photo = queued("evt-photo").copy(
            platform = Platform.WHATSAPP,
            registerOnly = false,
            messageText = "A mancha no sofá",
            media = InboundMedia("image", "media-1", "image/jpeg", null),
        )
        service.isDuplicate(photo.eventId, "{}", photo.tenantId, photo)

        val now = Clock.System.now()
        assertEquals(listOf(photo), service.unprocessedInbound(now - 1.minutes, now + 1.minutes))
    }

    @Test
    fun `finished events, events with no queued message, and events outside the window are not re-queued`() = runBlocking {
        val service = freshService()
        val processed = queued("evt-processed")
        service.isDuplicate(processed.eventId, "{}", processed.tenantId, processed)
        service.markProcessed(processed.eventId)
        val failed = queued("evt-failed")
        service.isDuplicate(failed.eventId, "{}", failed.tenantId, failed)
        service.markFailed(failed.eventId)
        service.isDuplicate("igc:comment-1", "{}")
        val unfinished = queued("evt-unfinished")
        service.isDuplicate(unfinished.eventId, "{}", unfinished.tenantId, unfinished)

        val now = Clock.System.now()
        assertEquals(listOf(unfinished), service.unprocessedInbound(now - 1.minutes, now + 1.minutes))
        assertEquals(emptyList(), service.unprocessedInbound(now + 1.minutes, now + 2.minutes))
    }

    @Test
    fun `markFailed updates status so a failed event can be distinguished from a processed one`() = runBlocking {
        val service = freshService()
        assertFalse(service.isDuplicate("evt-1", "{}"))
        service.markFailed("evt-1")

        val doc = mongoModule.database.getCollection<Document>("webhook_events")
            .find(com.mongodb.client.model.Filters.eq("eventId", "evt-1")).firstOrNull()
        assertEquals("failed", doc?.getString("status"))
    }
}
