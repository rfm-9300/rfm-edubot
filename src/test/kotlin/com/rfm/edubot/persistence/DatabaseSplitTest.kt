package com.rfm.edubot.persistence

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.dashboard.DashboardUserRepository
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.testing.TestMongo
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterEach
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Moving the platform's collections and the runtime state out of the one database that held everything. */
class DatabaseSplitTest {
    private val started = mutableListOf<MongoModule>()

    @AfterEach
    fun closeClients() = started.forEach { it.shutdown() }

    private fun start(config: AppConfig.MongoConfig): MongoModule = MongoModule(config).also { started += it; it.initialize() }

    private fun unstarted(config: AppConfig.MongoConfig): MongoModule = MongoModule(config).also { started += it }

    private suspend fun MongoModule.names(db: String, collection: String): List<String> {
        val database = when (db) { "platform" -> platform; "runtime" -> runtime; else -> database }
        return database.getCollection<Document>(collection).find().toList().map { it.getString("name") }.sorted()
    }

    private suspend fun MongoModule.seedBeforeSplit() {
        database.getCollection<Document>("tenants").insertMany(listOf(tenant("acme"), tenant("bolt")))
        database.getCollection<Document>("dashboard_users").insertOne(
            Document("name", "owner").append("email", "owner@acme.test").append("tenantId", ObjectId())
                .append("role", "TENANT_ADMIN").append("createdAt", Date()),
        )
        database.getCollection<Document>("admin_emails").insertOne(Document("name", "ops").append("email", "ops@lab.test").append("addedAt", Date()))
        database.getCollection<Document>("platform_settings").insertOne(Document("_id", "global").append("name", "settings").append("overrides", Document()))
        database.getCollection<Document>("tenant_usage").insertOne(Document("name", "usage").append("tenantId", ObjectId()).append("period", "2026-10"))
        database.getCollection<Document>("webhook_events").insertOne(Document("name", "evt-1").append("eventId", "evt-1").append("receivedAt", Date()))
        database.getCollection<Document>("scheduler_leases").insertOne(Document("_id", "agents").append("name", "agents"))
        database.getCollection<Document>("crm.clients").insertOne(Document("name", "client").append("tenantId", ObjectId()))
    }

    private fun tenant(slug: String) = Document("name", slug).append("slug", slug).append("status", "ACTIVE")
        .append("channels", emptyList<Document>()).append("createdAt", Date()).append("updatedAt", Date())

    @Test
    fun `the first start copies the platform's collections and runtime state, and leaves the originals`(): Unit = runBlocking {
        val config = TestMongo.config("split_first")
        unstarted(config).seedBeforeSplit()

        val mongo = start(config)

        assertEquals(listOf("acme", "bolt"), mongo.names("platform", "tenants"))
        assertEquals(listOf("acme", "bolt"), TenantRepository(mongo).findAll().map { it.slug }.sorted())
        assertNotNull(DashboardUserRepository(mongo).findByEmail("owner@acme.test"))
        assertEquals(listOf("ops"), mongo.names("platform", "admin_emails"))
        assertEquals(listOf("settings"), mongo.names("platform", "platform_settings"))
        assertEquals(listOf("usage"), mongo.names("platform", "tenant_usage"))
        assertEquals(listOf("evt-1"), mongo.names("runtime", "webhook_events"))
        assertEquals(listOf("agents"), mongo.names("runtime", "scheduler_leases"))

        assertEquals(listOf("acme", "bolt"), mongo.names("data", "tenants"), "the originals stay for a rollback")
        assertEquals(listOf("client"), mongo.names("data", "crm.clients"))
        assertEquals(emptyList(), mongo.names("platform", "crm.clients"))

        val emailIndex = mongo.platform.getCollection<Document>("dashboard_users").listIndexes().toList().first { it.getString("name") == "email_1" }
        assertEquals(true, emailIndex.getBoolean("unique"))
    }

    @Test
    fun `a restart copies nothing over what the platform database got since`(): Unit = runBlocking {
        val config = TestMongo.config("split_restart")
        unstarted(config).seedBeforeSplit()
        val first = start(config)
        first.platform.getCollection<Document>("tenants").updateOne(Filters.eq("slug", "acme"), Updates.set("name", "acme renamed"))
        first.platform.getCollection<Document>("admin_emails").deleteMany(Document())

        val second = start(config)

        assertEquals(listOf("acme renamed", "bolt"), second.names("platform", "tenants"))
        assertEquals(emptyList(), second.names("platform", "admin_emails"))
    }

    @Test
    fun `what a release from before the split wrote to the originals is copied again at the next start`(): Unit = runBlocking {
        val config = TestMongo.config("split_rollback")
        unstarted(config).seedBeforeSplit()
        val first = start(config)
        first.platform.getCollection<Document>("tenants").updateOne(Filters.eq("slug", "acme"), Updates.set("name", "lost on rollback"))
        // The rolled-back release only knows the data database.
        first.database.getCollection<Document>("tenants").insertOne(tenant("made-after-rollback"))

        val second = start(config)

        assertEquals(listOf("acme", "bolt", "made-after-rollback"), second.names("platform", "tenants"))
        assertEquals(listOf("owner@acme.test"), second.platform.getCollection<Document>("dashboard_users").find().toList().map { it.getString("email") })
        val marker = second.platform.getCollection<Document>(DatabaseSplit.MARKERS).find(Filters.eq("_id", DatabaseSplit.MARKER_ID)).firstOrNull()
        assertNotNull(marker)
        assertTrue(marker.getDate("updatedAt") >= marker.getDate("splitAt"))
    }

    @Test
    fun `an emptied or dropped original is never copied`(): Unit = runBlocking {
        val config = TestMongo.config("split_emptied")
        unstarted(config).seedBeforeSplit()
        val first = start(config)
        first.database.getCollection<Document>("admin_emails").deleteMany(Document())
        first.database.getCollection<Document>("dashboard_users").drop()

        val second = start(config)

        assertEquals(listOf("ops"), second.names("platform", "admin_emails"))
        assertEquals(listOf("owner"), second.names("platform", "dashboard_users"))
    }

    @Test
    fun `runtime state is copied once only`(): Unit = runBlocking {
        val config = TestMongo.config("split_runtime")
        unstarted(config).seedBeforeSplit()
        val first = start(config)
        first.database.getCollection<Document>("webhook_events").insertOne(Document("name", "evt-2").append("eventId", "evt-2").append("receivedAt", Date()))

        val second = start(config)

        assertEquals(listOf("evt-1"), second.names("runtime", "webhook_events"))
    }

    @Test
    fun `without a recorded split a platform database that already has documents keeps them`(): Unit = runBlocking {
        val config = TestMongo.config("split_seeded")
        val mongo = unstarted(config)
        mongo.platform.getCollection<Document>("tenants").insertOne(tenant("seeded-in-platform"))
        mongo.database.getCollection<Document>("tenants").insertOne(tenant("old-layout"))

        val started = start(config)

        assertEquals(listOf("seeded-in-platform"), started.names("platform", "tenants"))
    }

    @Test
    fun `a fresh install copies nothing and records the split`(): Unit = runBlocking {
        val mongo = start(TestMongo.config("split_fresh"))

        assertEquals(emptyList(), mongo.names("platform", "tenants"))
        assertNotNull(mongo.platform.getCollection<Document>(DatabaseSplit.MARKERS).find(Filters.eq("_id", DatabaseSplit.MARKER_ID)).firstOrNull())
        assertEquals(emptyList(), mongo.database.listCollectionNames().toList().filter { it in MongoModule.PLATFORM_COLLECTIONS + MongoModule.RUNTIME_COLLECTIONS })
    }
}
