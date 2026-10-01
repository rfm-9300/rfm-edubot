package com.rfm.edubot.agents.ai

import com.rfm.edubot.agents.AgentsModule
import com.rfm.edubot.agents.actions.AgentActions
import com.rfm.edubot.agents.actions.AiSteps
import com.rfm.edubot.agents.model.AgentDefinition
import com.rfm.edubot.agents.model.AgentKind
import com.rfm.edubot.agents.model.AgentPolicy
import com.rfm.edubot.agents.model.AgentStatus
import com.rfm.edubot.agents.model.AgentVoice
import com.rfm.edubot.agents.model.Autonomy
import com.rfm.edubot.agents.model.StepSpec
import com.rfm.edubot.agents.model.TriggerSpec
import com.rfm.edubot.agents.registry.AgentRegistry
import com.rfm.edubot.agents.registry.TriggerTypes
import com.rfm.edubot.agents.runtime.AgentServices
import com.rfm.edubot.ai.AiClient
import com.rfm.edubot.ai.AiResponse
import com.rfm.edubot.ai.ChatMessage
import com.rfm.edubot.ai.TenantUsageRepository
import com.rfm.edubot.ai.UsageInfo
import com.rfm.edubot.ai.UsageSources
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.testing.TestMongo
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentDrafterTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("agent_drafter")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private val now = Instant.parse("2026-10-01T10:00:00Z")
    private val registry = AgentRegistry(AgentActions.builtIn, TriggerTypes.all)

    /** Answers each call in turn and keeps what it was sent. */
    private class Model(vararg answers: AiResponse) {
        val queue = ArrayDeque(answers.toList())
        val seen: MutableList<List<ChatMessage>> = Collections.synchronizedList(mutableListOf())
        val client: AiClient = mockk<AiClient>().also { ai ->
            coEvery { ai.complete(any(), any(), any(), any()) } answers {
                seen += firstArg<List<ChatMessage>>().toList()
                synchronized(queue) { queue.removeFirst() }
            }
        }
    }

    private fun text(content: String) = AiResponse.Text(content = content, usage = UsageInfo(prompt_tokens = 900, completion_tokens = 100), responseId = "r")

    private fun tenant(budget: Long = 2_000_000L) = Tenant(
        slug = "t-${ObjectId().toHexString().takeLast(8)}",
        name = "Obras Silva",
        channels = emptyList(),
        enabledModules = listOf(DashboardModules.CLIENTS, DashboardModules.INVOICES, DashboardModules.AGENTS),
        monthlyTokenBudget = budget,
        createdAt = now,
        updatedAt = now,
    )

    private fun usage(tenant: Tenant) = TenantUsageRepository(mongo, tenant.id) { now }

    private fun module(ai: AiClient?) = AgentsModule(mongo, registry, AgentServices(mongo, aiClient = ai, usage = { usage(it) }, clock = { now }))

    private val validDefinition = """
        "triggers": [{"type": "event", "config": {"event": "invoice.created"}}],
        "steps": [{"action": "team.notify", "input": {"message": "Nova fatura {{invoice.number}}"}}]
    """

    private fun answer(definition: String, name: String = "Avisar faturas") =
        """{"name": "$name", "description": "Avisa a equipa de cada fatura nova.", "icon": "invoice", "kind": "WORKFLOW", "definition": {$definition}}"""

    @Test
    fun `a request becomes a validated draft within the company's autonomy, with its tokens metered as agents`(): Unit = runBlocking {
        val tenant = tenant()
        val reply = """
            Here it is:
            ```json
            {"name": "Lembrete de faturas", "description": "Avisa a equipa de cada fatura nova.", "icon": "invoice", "kind": "WORKFLOW",
             "note": "Ligue o WhatsApp para avisar também os clientes.",
             "definition": {
               "triggers": [{"type": "event", "config": {"event": "invoice.created"}}],
               "steps": [
                 {"action": "team.notify", "input": {"message": "Nova fatura {{invoice.number}}"}, "autonomy": "AUTO"},
                 {"id": "s1", "action": "team.notify", "input": {"message": "Confirme {{invoice.number}}"}, "autonomy": "DRAFT"}
               ],
               "policy": {"autonomy": "AUTO", "maxRunsPerDay": 5000, "cooldownHours": 99999},
               "voice": {"tone": "sarcastic", "language": "fr"}
             }}
            ```
        """.trimIndent()
        val model = Model(text(reply))
        val module = module(model.client)

        val outcome = AgentDrafter(module).draft(tenant, "Avisa a equipa quando sai uma fatura", createdBy = "user-1")

        val drafted = assertIs<AgentDrafter.Outcome.Drafted>(outcome)
        assertEquals(emptyList(), drafted.problems)
        assertEquals("Ligue o WhatsApp para avisar também os clientes.", drafted.note)
        val saved = module.agents.findById(tenant.id, drafted.agent.id)!!
        assertEquals(AgentStatus.DRAFT, saved.status, "a draft is never activated")
        assertEquals("Lembrete de faturas", saved.name)
        assertEquals("invoice", saved.icon)
        assertEquals(AgentKind.WORKFLOW, saved.kind)
        assertEquals("user-1", saved.createdBy)
        val definition = saved.definition
        assertEquals(listOf("t1"), definition.triggers.map { it.id })
        assertEquals(listOf("s2", "s1"), definition.steps.map { it.id }, "missing ids are filled without clashing")
        assertNull(definition.steps[0].autonomy, "a step can't act more freely than the company allows")
        assertEquals(Autonomy.DRAFT, definition.steps[1].autonomy, "a stricter step stays stricter")
        assertEquals(AgentPolicy(autonomy = Autonomy.APPROVE, cooldownHours = 24 * 365), definition.policy)
        assertEquals(AgentVoice(), definition.voice, "an unknown tone and language fall back to the defaults")

        assertEquals(1, model.seen.size)
        val prompt = model.seen.single().first().content.orEmpty()
        assertTrue("team.notify" in prompt && "invoice.created" in prompt, "the model gets the catalog")
        assertTrue("EXAMPLE definition" in prompt, "and a template as a sample")
        assertEquals("Avisa a equipa quando sai uma fatura", model.seen.single().last().content)
        assertEquals(1_000L, usage(tenant).tokensBySourceThisMonth()[UsageSources.AGENTS])
    }

    @Test
    fun `problems go back to the model once, and a fixed answer is saved without them`(): Unit = runBlocking {
        val tenant = tenant()
        val broken = answer(
            """
            "triggers": [{"type": "event", "config": {"event": "invoice.created"}}],
            "steps": [{"action": "team.shout", "input": {"message": "Nova fatura"}}]
            """,
        )
        val model = Model(text(broken), text(answer(validDefinition)))
        val module = module(model.client)

        val drafted = assertIs<AgentDrafter.Outcome.Drafted>(AgentDrafter(module).draft(tenant, "Avisa a equipa das faturas", createdBy = null))

        assertEquals(emptyList(), drafted.problems)
        assertEquals("team.notify", drafted.agent.definition.steps.single().action)
        assertEquals(2, model.seen.size)
        val retry = model.seen[1]
        assertEquals(broken, retry[retry.size - 2].content, "the model sees its own answer")
        val feedback = retry.last().content.orEmpty()
        assertTrue("steps[0]: unknown_action (team.shout)" in feedback, feedback)
        assertEquals(2_000L, usage(tenant).tokensBySourceThisMonth()[UsageSources.AGENTS])
    }

    @Test
    fun `after a second answer with problems the one with fewer is saved, problems and all`(): Unit = runBlocking {
        val tenant = tenant()
        val worse = answer(""""steps": [{"action": "team.shout", "input": {}}]""")
        val better = answer(
            """
            "triggers": [{"type": "event", "config": {"event": "invoice.created"}}],
            "steps": [{"action": "team.shout", "input": {}}]
            """,
            name = "Melhor",
        )
        val module = module(Model(text(worse), text(better)).client)

        val drafted = assertIs<AgentDrafter.Outcome.Drafted>(AgentDrafter(module).draft(tenant, "Avisa a equipa", createdBy = null))

        assertEquals("Melhor", drafted.agent.name)
        assertEquals(listOf("unknown_action"), drafted.problems.map { it.code })
        assertEquals(AgentStatus.DRAFT, module.agents.findById(tenant.id, drafted.agent.id)!!.status)
    }

    @Test
    fun `no AI, a spent budget, an error or no usable answer save nothing`(): Unit = runBlocking {
        val noAi = tenant()
        assertEquals(AgentDrafter.Outcome.Failed(AiSteps.UNAVAILABLE), AgentDrafter(module(null)).draft(noAi, "Avisa a equipa", createdBy = null))

        val spent = tenant(budget = 1_000L)
        usage(spent).recordUsage(1_000L, UsageSources.PIPELINE)
        val idle = Model()
        assertEquals(AgentDrafter.Outcome.Failed(AiSteps.TOKEN_BUDGET), AgentDrafter(module(idle.client)).draft(spent, "Avisa a equipa", createdBy = null))
        assertEquals(0, idle.seen.size, "the model isn't called once the budget is spent")

        val chatty = tenant()
        val prose = Model(text("Claro! Posso ajudar com isso."), text("Não sei fazer isso."))
        val module = module(prose.client)
        assertEquals(AgentDrafter.Outcome.Failed(AiSteps.NO_RESULT), AgentDrafter(module).draft(chatty, "Avisa a equipa", createdBy = null))
        assertEquals(2, prose.seen.size)
        assertTrue("only the JSON object" in prose.seen[1].last().content.orEmpty())
        assertEquals(2_000L, usage(chatty).tokensBySourceThisMonth()[UsageSources.AGENTS], "tokens spent on a failed draft still count")

        val failing = tenant()
        val broken = mockk<AiClient>().also { coEvery { it.complete(any(), any(), any(), any()) } throws IllegalStateException("boom") }
        assertEquals(AgentDrafter.Outcome.Failed(AiSteps.UNAVAILABLE), AgentDrafter(module(broken)).draft(failing, "Avisa a equipa", createdBy = null))

        listOf(noAi, spent, chatty, failing).forEach { assertEquals(emptyList(), module.agents.list(it.id)) }
    }

    @Test
    fun `the answer is the JSON object in the reply, fences and prose around it allowed`() {
        assertEquals(buildJsonObject { put("a", 1) }, AgentDrafter.parseAnswer("Sure!\n```json\n{\"a\": 1}\n```\nDone."))
        assertNull(AgentDrafter.parseAnswer("No agent today."))
        assertNull(AgentDrafter.parseAnswer("{\"a\": "))
        assertNull(AgentDrafter.parseAnswer("[1, 2] }"))
    }

    @Test
    fun `missing trigger and step ids get the next free ones`() {
        val definition = buildJsonObject {
            put("triggers", kotlinx.serialization.json.Json.parseToJsonElement("""[{"type": "event"}, {"id": "t1", "type": "manual"}]"""))
            put("steps", kotlinx.serialization.json.Json.parseToJsonElement("""[{"id": "s2", "action": "a"}, {"action": "b"}, {"action": "c"}, "junk"]"""))
        }
        val filled = AgentDrafter.withIds(definition)
        assertEquals(listOf("t2", "t1"), filled["triggers"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        val steps = filled["steps"]!!.jsonArray
        assertEquals(listOf("s2", "s1", "s3"), steps.take(3).map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(JsonPrimitive("junk"), steps[3], "what isn't an object is left for the decoder to reject")
        assertEquals(JsonObject(emptyMap()), AgentDrafter.withIds(JsonObject(emptyMap())))
    }

    @Test
    fun `sanitizing bounds sizes and never loosens a step past the company's autonomy`() {
        val definition = AgentDefinition(
            triggers = (1..7).map { TriggerSpec("t$it", TriggerTypes.MANUAL) },
            steps = (1..30).map { StepSpec("s$it", "team.notify", autonomy = if (it % 2 == 0) Autonomy.APPROVE else Autonomy.AUTO, label = "  Passo $it  ") },
            policy = AgentPolicy(autonomy = Autonomy.AUTO, businessDaysOnly = true, cooldownHours = -5, maxRunsPerDay = 9_999),
            voice = AgentVoice(tone = "formal", language = "es", signature = "  Equipa  ", instructions = " ", emoji = true),
        )

        val strict = AgentDrafter.sanitize(definition, Autonomy.DRAFT)
        assertEquals(5, strict.triggers.size)
        assertEquals(25, strict.steps.size)
        assertTrue(strict.steps.all { it.autonomy == null }, "under Draft only, no step may ask first or act")
        assertEquals("Passo 1", strict.steps.first().label)
        assertEquals(AgentPolicy(autonomy = Autonomy.DRAFT, businessDaysOnly = true, cooldownHours = 0), strict.policy)
        assertEquals(AgentVoice(tone = "formal", language = "es", signature = "Equipa", instructions = null, emoji = true), strict.voice)

        val open = AgentDrafter.sanitize(definition, Autonomy.AUTO)
        assertEquals(listOf(null, Autonomy.APPROVE), open.steps.take(2).map { it.autonomy }, "asking first is stricter than acting")
        assertEquals(Autonomy.AUTO, open.policy.autonomy)
    }
}
