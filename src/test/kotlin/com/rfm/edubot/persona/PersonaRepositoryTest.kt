package com.rfm.edubot.persona

import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.testing.TestMongo
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How a persona, its sources and its history are stored, against a real MongoDB. */
class PersonaRepositoryTest {
    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("persona_repo")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() = mongo.shutdown()
    }

    private val repo get() = PersonaRepository(mongo)

    @Test
    fun `every change is a new version, kept in history with what changed and who changed it`(): Unit = runBlocking {
        val tenant = ObjectId()
        val v1 = repo.saveInstructions(tenant, "Somos a Clínica Sorriso.", PersonaChange.MANUAL, "ana@clinica.pt")!!
        val v2 = repo.saveBehavior(tenant, PersonaBehavior(botName = "Sofia", tone = PersonaTone.FRIENDLY), "rui@clinica.pt")
        val v3 = repo.saveInstructions(tenant, "Somos a Clínica Sorriso, em Lisboa.", PersonaChange.SYNTHESIS, null, sourceCount = 2)!!

        assertEquals(listOf(1, 2, 3), listOf(v1.version, v2.version, v3.version))
        assertEquals("Somos a Clínica Sorriso, em Lisboa.", v3.compiledInstructions)
        assertEquals("Sofia", v3.behavior.botName, "a change to the instructions keeps the settings")
        assertEquals(PersonaStatus.READY, v3.status)
        assertTrue(v3.tokenEstimate > PersonaPrompt.estimateTokens("Somos a Clínica Sorriso, em Lisboa."), "the estimate covers the settings too")

        val history = repo.listVersions(tenant)
        assertEquals(listOf(3, 2, 1), history.map { it.version })
        assertEquals(listOf(PersonaChange.SYNTHESIS, PersonaChange.SETTINGS, PersonaChange.MANUAL), history.map { it.change })
        assertEquals(listOf(null, "rui@clinica.pt", "ana@clinica.pt"), history.map { it.author })
        assertEquals(2, history.first().sourceCount)
        assertEquals("Somos a Clínica Sorriso, em Lisboa.".length, history.first().chars)

        val snapshot = repo.findVersion(tenant, 1)!!
        assertEquals("Somos a Clínica Sorriso.", snapshot.compiledInstructions)
        assertEquals(PersonaBehavior(), snapshot.behavior)
        assertEquals(PersonaBehavior(botName = "Sofia", tone = PersonaTone.FRIENDLY), repo.findVersion(tenant, 2)!!.behavior)
    }

    @Test
    fun `restoring brings back both the instructions and the settings as a new version`(): Unit = runBlocking {
        val tenant = ObjectId()
        repo.saveInstructions(tenant, "Versão boa.", PersonaChange.MANUAL, "ana@x.pt")
        repo.saveBehavior(tenant, PersonaBehavior(botName = "Sofia"), "ana@x.pt")
        repo.saveInstructions(tenant, "Versão má.", PersonaChange.SYNTHESIS, null)
        repo.saveBehavior(tenant, PersonaBehavior(botName = "Errado"), "ana@x.pt")

        val restored = repo.restore(tenant, 2, "rui@x.pt")!!
        assertEquals(5, restored.version)
        assertEquals("Versão boa.", restored.compiledInstructions)
        assertEquals("Sofia", restored.behavior.botName)
        val entry = repo.listVersions(tenant).first()
        assertEquals(PersonaChange.RESTORE, entry.change)
        assertEquals(2, entry.restoredFrom)
        assertEquals("rui@x.pt", entry.author)
        assertNull(repo.restore(tenant, 99, "rui@x.pt"))
        assertEquals(5, repo.findByTenant(tenant)!!.version)
    }

    @Test
    fun `a persona written before history was kept is recorded once, so its first edit can be undone`(): Unit = runBlocking {
        val tenant = ObjectId()
        mongo.database.getCollection<Document>("tenant_persona").insertOne(
            Document("_id", ObjectId()).append("tenantId", tenant).append("compiledInstructions", "Texto antigo.")
                .append("version", 7).append("tokenEstimate", 4).append("status", "READY").append("updatedAt", Date()),
        )
        repo.saveInstructions(tenant, "Texto novo.", PersonaChange.MANUAL, "ana@x.pt")
        repo.saveInstructions(tenant, "Texto mais novo.", PersonaChange.MANUAL, "ana@x.pt")

        val history = repo.listVersions(tenant)
        assertEquals(listOf(9, 8, 7), history.map { it.version })
        assertEquals(PersonaChange.BASELINE, history.last().change)
        assertEquals("Texto antigo.", repo.restore(tenant, 7, "ana@x.pt")!!.compiledInstructions)
    }

    @Test
    fun `text starting with a dollar sign is stored as written, not read as a field`(): Unit = runBlocking {
        val tenant = ObjectId()
        repo.saveInstructions(tenant, "\$100 de desconto na primeira visita.", PersonaChange.MANUAL, null)
        repo.saveBehavior(tenant, PersonaBehavior(botName = "\$ofia", rules = listOf("\$5 por km."), greeting = "\$audações!"), null)

        val saved = repo.findByTenant(tenant)!!
        assertEquals("\$100 de desconto na primeira visita.", saved.compiledInstructions)
        assertEquals("\$ofia", saved.behavior.botName)
        assertEquals(listOf("\$5 por km."), saved.behavior.rules)
        assertEquals("\$audações!", saved.behavior.greeting)
    }

    @Test
    fun `a write that expects an older version changes nothing`(): Unit = runBlocking {
        val tenant = ObjectId()
        repo.saveInstructions(tenant, "Editado à mão.", PersonaChange.MANUAL, "ana@x.pt")

        assertNull(repo.saveInstructions(tenant, "Da síntese.", PersonaChange.SYNTHESIS, null, expectedVersion = 0))
        assertEquals("Editado à mão.", repo.findByTenant(tenant)!!.compiledInstructions)
        assertEquals(1, repo.listVersions(tenant).size)
        assertNotNull(repo.saveInstructions(tenant, "Da síntese.", PersonaChange.SYNTHESIS, null, expectedVersion = 1))
    }

    @Test
    fun `the first write of a synthesis lands on a persona that only had a status`(): Unit = runBlocking {
        val tenant = ObjectId()
        repo.setStatus(tenant, PersonaStatus.COMPILING)
        val saved = repo.saveInstructions(tenant, "Primeira versão.", PersonaChange.SYNTHESIS, null, expectedVersion = 0)!!
        assertEquals(1, saved.version)
        assertEquals(PersonaStatus.READY, saved.status)
    }

    @Test
    fun `a hand edit keeps a running or failed synthesis on show, and the synthesis settles it`(): Unit = runBlocking {
        val tenant = ObjectId()
        repo.saveInstructions(tenant, "Base.", PersonaChange.MANUAL, null)
        repo.setStatus(tenant, PersonaStatus.COMPILING)
        assertEquals(PersonaStatus.COMPILING, repo.saveBehavior(tenant, PersonaBehavior(botName = "Ana"), null).status)
        assertEquals(PersonaStatus.COMPILING, repo.saveInstructions(tenant, "Base editada.", PersonaChange.MANUAL, null)!!.status)

        repo.setStatus(tenant, PersonaStatus.ERROR, PersonaErrors.AI_UNAVAILABLE)
        val edited = repo.saveInstructions(tenant, "Outra edição.", PersonaChange.MANUAL, null)!!
        assertEquals(PersonaStatus.ERROR, edited.status)
        assertEquals(PersonaErrors.AI_UNAVAILABLE, edited.lastError)

        val synthesized = repo.saveInstructions(tenant, "Da síntese.", PersonaChange.SYNTHESIS, null)!!
        assertEquals(PersonaStatus.READY, synthesized.status)
        assertNull(synthesized.lastError)
    }

    @Test
    fun `clearing everything leaves an empty persona`(): Unit = runBlocking {
        val tenant = ObjectId()
        repo.saveInstructions(tenant, "Algo.", PersonaChange.MANUAL, null)
        val cleared = repo.saveInstructions(tenant, "", PersonaChange.MANUAL, null)!!
        assertEquals(PersonaStatus.EMPTY, cleared.status)
        assertEquals(0, cleared.tokenEstimate)
        assertEquals(PersonaStatus.READY, repo.saveBehavior(tenant, PersonaBehavior(rules = listOf("Sem descontos.")), null).status)
        assertEquals(PersonaStatus.EMPTY, repo.saveBehavior(tenant, PersonaBehavior(), null).status)
    }

    @Test
    fun `a stale persona is cleared by a hand edit or a rebuild, not by a synthesis`(): Unit = runBlocking {
        val tenant = ObjectId()
        repo.saveInstructions(tenant, "Base.", PersonaChange.MANUAL, null)
        repo.markStale(tenant)
        assertTrue(repo.saveInstructions(tenant, "Base + nota.", PersonaChange.SYNTHESIS, null)!!.stale)
        assertTrue(repo.saveBehavior(tenant, PersonaBehavior(botName = "Ana"), null).stale)
        assertFalse(repo.saveInstructions(tenant, "Reescrito.", PersonaChange.REBUILD, null)!!.stale)
        repo.markStale(tenant)
        assertFalse(repo.saveInstructions(tenant, "À mão.", PersonaChange.MANUAL, null)!!.stale)
    }

    @Test
    fun `history keeps the latest versions only`(): Unit = runBlocking {
        val tenant = ObjectId()
        repeat(PersonaLimits.KEPT_VERSIONS + 5) { repo.saveInstructions(tenant, "Versão $it", PersonaChange.MANUAL, null) }
        val history = repo.listVersions(tenant)
        assertEquals(PersonaLimits.KEPT_VERSIONS, history.size)
        assertEquals(PersonaLimits.KEPT_VERSIONS + 5, history.first().version)
        assertEquals(6, history.last().version)
        assertNull(repo.findVersion(tenant, 5))
    }

    @Test
    fun `sources list without their text, newest first, with their size`(): Unit = runBlocking {
        val tenant = ObjectId()
        repo.addSource(tenant, SourceKind.TEXT_NOTE, "Abrimos às 9h.", "Abrimos às 9h.", "ana@x.pt")
        delay(5)
        repo.addSource(tenant, SourceKind.FILE, "a".repeat(500), "menu.pdf", "rui@x.pt", truncated = true)
        mongo.database.getCollection<Document>("persona_sources").insertOne(
            Document("_id", ObjectId()).append("tenantId", tenant).append("kind", "TEXT_NOTE").append("content", "Nota antiga")
                .append("label", "Nota antiga").append("compiledIntoVersion", 3).append("createdAt", Date(0)),
        )

        val listed = repo.listSources(tenant)
        assertEquals(listOf("menu.pdf", "Abrimos às 9h.", "Nota antiga"), listed.map { it.label })
        assertEquals(listOf(500, 14, 11), listed.map { it.chars })
        assertEquals(listOf(true, false, false), listed.map { it.truncated })
        assertEquals(listOf("rui@x.pt", "ana@x.pt", null), listed.map { it.addedBy })
        assertEquals(525L, repo.totalSourceChars(tenant))
        assertEquals(3L, repo.countSources(tenant))
        assertEquals(listOf("Abrimos às 9h.", "a".repeat(500)), repo.pendingSources(tenant).map { it.content })
    }

    @Test
    fun `removing a source hands it back, and only within its own company`(): Unit = runBlocking {
        val mine = ObjectId()
        val theirs = ObjectId()
        val source = repo.addSource(mine, SourceKind.TEXT_NOTE, "Nota", "Nota")
        assertNull(repo.deleteSource(theirs, source.id))
        assertNull(repo.findSource(theirs, source.id))
        assertEquals("Nota", repo.deleteSource(mine, source.id)!!.content)
        assertNull(repo.deleteSource(mine, source.id))
    }

    @Test
    fun `history and personas never cross companies`(): Unit = runBlocking {
        val a = ObjectId()
        val b = ObjectId()
        repo.saveInstructions(a, "A", PersonaChange.MANUAL, null)
        repo.saveInstructions(b, "B", PersonaChange.MANUAL, null)
        assertEquals("A", repo.findByTenant(a)!!.compiledInstructions)
        assertEquals(listOf(1), repo.listVersions(b).map { it.version })
        assertEquals("B", repo.findVersion(b, 1)!!.compiledInstructions)
        assertNull(repo.restore(b, 2, null))
    }

    @Test
    fun `companies left mid-synthesis or with pending sources are found for the boot recovery`(): Unit = runBlocking {
        val compiling = ObjectId()
        val pending = ObjectId()
        val settled = ObjectId()
        repo.setStatus(compiling, PersonaStatus.COMPILING)
        repo.addSource(pending, SourceKind.TEXT_NOTE, "Nota", "Nota")
        val done = repo.addSource(settled, SourceKind.TEXT_NOTE, "Nota", "Nota")
        repo.markSourcesCompiled(settled, listOf(done.id), 1)

        val found = repo.tenantsToCompile()
        assertTrue(compiling in found)
        assertTrue(pending in found)
        assertFalse(settled in found)
    }
}
