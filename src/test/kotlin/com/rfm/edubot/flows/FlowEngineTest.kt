package com.rfm.edubot.flows

import com.rfm.edubot.tenant.model.Platform
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlowEngineTest {
    /** Tuesday 17 November 2026, 10:00 in Lisbon (UTC+0 in winter). */
    private val tuesday = Instant.parse("2026-11-17T10:00:00Z")
    private val sunday = Instant.parse("2026-11-22T10:00:00Z")
    private val zone = TimeZone.of("Europe/Lisbon")

    private class Actions(var known: Boolean = false, var failBooking: Boolean = false) : FlowActions {
        val clients = mutableListOf<FlowClient>()
        val bookings = mutableListOf<FlowBooking>()
        val notes = mutableListOf<FlowTeamNote>()
        override suspend fun isKnownClient(phone: String?) = known
        override suspend fun createClient(client: FlowClient) = FlowActionResult(true, "created").also { clients += client }
        override suspend fun createBooking(booking: FlowBooking) =
            if (failBooking) FlowActionResult(false, "slot taken") else FlowActionResult(true, "booked").also { bookings += booking }
        override suspend fun notifyTeam(note: FlowTeamNote) = FlowActionResult(true, "told").also { notes += note }
    }

    private fun step(id: String, text: String = "", vararg buttons: FlowButton, build: (FlowStep) -> FlowStep = { it }) = build(FlowStep(id, text, buttons.toList()))
    private fun flow(vararg steps: FlowStep, aiReads: Boolean = false, afterMisses: AfterMisses = AfterMisses.HAND_OVER, handoverText: String? = null) = ChatFlow(
        id = "f", name = "Marcações", channels = setOf(Platform.WHATSAPP, Platform.INSTAGRAM, Platform.WEB), keywords = listOf("olá", "menu"),
        steps = steps.toList(), aiReads = aiReads, afterMisses = afterMisses, handoverText = handoverText,
    )

    private fun ctx(now: Instant = tuesday, platform: Platform = Platform.WHATSAPP, aiAnswers: Boolean = true, aiAllowed: Boolean = true) =
        FlowContext(platform, "351912345678", "Maria", ObjectId(), now, zone, aiAnswers, aiAllowed)

    /** One customer in one chat: each message goes through the engine with the session the last one left. */
    private inner class Chat(val flows: ChatFlows, val actions: Actions = Actions(), interpreter: FlowInterpreter? = null, val platform: Platform = Platform.WHATSAPP) {
        val engine = FlowEngine(actions, interpreter)
        var session: FlowSession? = null
        var now = tuesday
        var aiAnswers = true
        var aiAllowed = true

        fun say(text: String, payload: String? = null): FlowResult = runBlocking {
            engine.handle(flows, session, FlowInput(platform, text, payload), ctx(now, platform, aiAnswers, aiAllowed)).also { session = it.session }
        }
    }

    private val menu = flow(
        step("menu", "O que deseja?", FlowButton("Unhas", "unhas", listOf("manicure")), FlowButton("Pestanas", "pestanas")),
        step("unhas", "Unhas: 20 €. Mais alguma coisa?"),
        step("pestanas", "Pestanas: 30 €."),
    )

    @Test
    fun `a keyword starts the flow and leaves the customer waiting at the options`() {
        val chat = Chat(ChatFlows(listOf(menu)))
        val result = chat.say("Olá!")

        assertTrue(result.handled)
        assertEquals("Marcações", result.flowName)
        assertEquals(listOf("O que deseja?"), result.messages.map { it.text })
        assertEquals(listOf("flow:f:unhas:0", "flow:f:pestanas:1"), result.messages.single().options.map { it.payload })
        assertEquals("flow:f:menu", result.messages.single().origin)
        assertEquals("menu", result.session?.stepId)
    }

    private val kept = flow(
        step("area", "Em que área trabalha?", FlowButton("Unhas", "dor", listOf("manicure")), FlowButton("Cabeleireiro", "dor")) { it.copy(key = "business_type") },
        step("dor", "Diga-me mais.", FlowButton("Tempo", "fim")) { it.copy(key = "pain_point") },
        step("fim", "Anotado: {business_type}.") { it.copy(next = "tell") },
        step("tell") { it.copy(kind = StepKind.ACTION, action = ActionKind.NOTIFY_TEAM) },
    )

    @Test
    fun `a message that keeps its choice keeps the option's title, however the customer chose it`() {
        val typed = Chat(ChatFlows(listOf(kept)))
        typed.say("olá")
        assertEquals(mapOf("business_type" to "Unhas"), typed.say("manicure").session?.vars, "typed words choose it")

        val tapped = Chat(ChatFlows(listOf(kept)))
        tapped.say("olá")
        assertEquals(mapOf("business_type" to "Cabeleireiro"), tapped.say("Cabeleireiro", "flow:f:dor:1").session?.vars, "a tap")

        val numbered = Chat(ChatFlows(listOf(kept)))
        numbered.say("olá")
        numbered.say("1")
        val done = numbered.say("1")
        assertEquals(mapOf("business_type" to "Unhas", "pain_point" to "Tempo"), numbered.actions.notes.single().answers)
        assertEquals("Anotado: Unhas.", done.messages.single().text)
    }

    @Test
    fun `a tap on an old message keeps nothing, and a message without a key keeps nothing`() {
        val chat = Chat(ChatFlows(listOf(kept)))
        chat.say("olá"); chat.say("1")
        val stale = chat.say("Cabeleireiro", "flow:f:dor:1")
        assertEquals(mapOf("business_type" to "Unhas"), stale.session?.vars, "the customer is past that message: what they chose there stays")

        val plain = Chat(ChatFlows(listOf(menu)))
        plain.say("olá")
        assertEquals(emptyMap(), plain.say("Unhas", "flow:f:unhas:0").session?.vars ?: emptyMap<String, String>())
    }

    @Test
    fun `the AI choosing an option keeps it like a typed choice`() {
        val chat = Chat(ChatFlows(listOf(kept.copy(aiReads = true))), interpreter = FlowInterpreter { FlowDecision.Option(1) })
        chat.say("olá")
        assertEquals(mapOf("business_type" to "Cabeleireiro"), chat.say("trabalho com cabelos").session?.vars)
    }

    @Test
    fun `no keyword and no session means no flow answers`() {
        val chat = Chat(ChatFlows(listOf(menu)))
        assertFalse(chat.say("Têm vaga amanhã?").handled)
        assertFalse(chat.engine.wouldAnswer(ChatFlows(listOf(menu)), null, FlowInput(Platform.WHATSAPP, "Têm vaga amanhã?", null), tuesday))
        assertTrue(chat.engine.wouldAnswer(ChatFlows(listOf(menu)), null, FlowInput(Platform.WHATSAPP, "olá", null), tuesday))
    }

    @Test
    fun `a typed number, title or extra word chooses an option, and a message with none ends the flow`() {
        for (reply in listOf("1", "Unhas", "queria manicure")) {
            val chat = Chat(ChatFlows(listOf(menu)))
            chat.say("olá")
            val result = chat.say(reply)
            assertEquals(listOf("Unhas: 20 €. Mais alguma coisa?"), result.messages.map { it.text }, reply)
            assertNull(result.session, "$reply: the flow ended")
        }
    }

    @Test
    fun `a tapped option goes straight to its step, with no session needed`() {
        val chat = Chat(ChatFlows(listOf(menu)))
        val result = chat.say("Pestanas", payload = "flow:f:pestanas:1")
        assertEquals(listOf("Pestanas: 30 €."), result.messages.map { it.text })
    }

    @Test
    fun `a reply nothing can read repeats the options, then hands over after the second miss`() {
        val chat = Chat(ChatFlows(listOf(menu.copy(handoverText = "Vamos pedir a uma colega que lhe responda.", steps = menu.steps.map { if (it.id == "menu") it.copy(retryText = "Não percebi.") else it }))))
        chat.say("olá")

        val first = chat.say("hmm")
        assertEquals(listOf("Não percebi.\n\nO que deseja?"), first.messages.map { it.text })
        assertEquals(1, first.session?.misses)

        val second = chat.say("hmmm")
        assertEquals(listOf("Vamos pedir a uma colega que lhe responda."), second.messages.map { it.text })
        assertNotNull(second.handOver)
        assertTrue("Marcações" in second.handOver!!.reason)
        assertNull(second.session)
    }

    @Test
    fun `after the misses a flow can leave the chat to the AI, or to the team where the AI is off`() {
        val flows = ChatFlows(listOf(menu.copy(afterMisses = AfterMisses.LET_AI)))
        val chat = Chat(flows)
        chat.say("olá"); chat.say("hmm")
        val gaveUp = chat.say("hmm")
        assertFalse(gaveUp.handled, "no flow answers it: the AI does, or the team where the AI is off")
        assertNull(gaveUp.session)
        assertNull(gaveUp.handOver, "it isn't a hand-over: nothing is paused")
    }

    @Test
    fun `how many times a step asks again is the flow's choice, and a flow made before it never asks`() {
        fun misses(retries: Int, afterMisses: AfterMisses = AfterMisses.LET_AI): List<Boolean> {
            val chat = Chat(ChatFlows(listOf(menu.copy(retries = retries, afterMisses = afterMisses))))
            chat.say("olá")
            return List(4) { chat.say("hmm").handled }
        }
        assertEquals(listOf(false, false, false, false), misses(0), "an unreadable reply leaves the flow at once, as menus always did")
        assertEquals(listOf(true, false, false, false), misses(1))
        assertEquals(listOf(true, true, false, false), misses(2))

        val handsOver = Chat(ChatFlows(listOf(menu.copy(retries = 0))))
        handsOver.say("olá")
        assertNotNull(handsOver.say("hmm").handOver, "with no retries and a hand-over, the first unreadable reply goes to a person")
    }

    @Test
    fun `a flow keyword typed anywhere starts it over, and a number out of range is a miss`() {
        val chat = Chat(ChatFlows(listOf(menu)))
        chat.say("olá")
        assertEquals("menu", chat.say("menu").session?.stepId)
        assertEquals(1, chat.say("9").session?.misses)
    }

    @Test
    fun `questions keep their answers, check them, and fill them into later messages`() {
        val ask = flow(
            step("name", "Como se chama?") { it.copy(kind = StepKind.ASK, key = "name", expect = Expect.NAME, next = "phone") },
            step("phone", "E o seu telemóvel?") { it.copy(kind = StepKind.ASK, key = "phone", expect = Expect.PHONE, next = "done", retryText = "Preciso de um número.") },
            step("done", "Obrigada, {name}! Ligamos para {phone}."),
        )
        val chat = Chat(ChatFlows(listOf(ask.copy(keywords = listOf("marcar")))))

        assertEquals(listOf("Como se chama?"), chat.say("quero marcar").messages.map { it.text })
        assertEquals(listOf("E o seu telemóvel?"), chat.say("Sou a Ana Costa").messages.map { it.text })
        val retry = chat.say("não tenho")
        assertEquals(listOf("Preciso de um número.\n\nE o seu telemóvel?"), retry.messages.map { it.text })
        assertEquals(mapOf("name" to "Ana Costa"), retry.session?.vars)
        val done = chat.say("912 345 678")
        assertEquals(listOf("Obrigada, Ana Costa! Ligamos para 912345678."), done.messages.map { it.text })
        assertNull(done.session)
    }

    @Test
    fun `a flow keyword inside an answer is the answer, not a restart`() {
        val ask = flow(step("name", "Como se chama?") { it.copy(kind = StepKind.ASK, key = "name", next = "done") }, step("done", "Olá {name}"))
        val chat = Chat(ChatFlows(listOf(ask)))
        chat.say("olá")
        assertEquals(listOf("Olá Menu Silva"), chat.say("Menu Silva").messages.map { it.text })
    }

    @Test
    fun `a message with no options carries on to its next step, so messages come in order`() {
        val chain = flow(
            step("a", "Primeiro.") { it.copy(next = "b") },
            step("b", "Segundo.") { it.copy(next = "c") },
            step("c", "O que quer?", FlowButton("Nada", "d")),
            step("d", "Fim."),
        )
        val result = Chat(ChatFlows(listOf(chain))).say("olá")
        assertEquals(listOf("Primeiro.", "Segundo.", "O que quer?"), result.messages.map { it.text })
        assertEquals("c", result.session?.stepId)
    }

    @Test
    fun `a condition on opening hours picks a branch by the company's time`() {
        val hours = (1..5).map { HoursWindow(it, "09:00", "18:00") }
        val branch = flow(
            step("gate") { it.copy(kind = StepKind.CONDITION, check = Check.HOURS, hours = hours, whenTrue = "open", whenFalse = "closed") },
            step("open", "Estamos abertos."),
            step("closed", "Estamos fechados."),
        )
        val chat = Chat(ChatFlows(listOf(branch)))
        assertEquals(listOf("Estamos abertos."), chat.say("olá").messages.map { it.text })
        chat.now = sunday
        assertEquals(listOf("Estamos fechados."), chat.say("olá").messages.map { it.text })
        chat.now = Instant.parse("2026-11-17T18:00:00Z")
        assertEquals(listOf("Estamos fechados."), chat.say("olá").messages.map { it.text }, "closing time is closed")
    }

    @Test
    fun `a condition can ask whether the customer is already a client, or look at an earlier answer`() {
        val known = flow(
            step("who") { it.copy(kind = StepKind.CONDITION, check = Check.KNOWN_CLIENT, whenTrue = "back", whenFalse = "new") },
            step("back", "Bem-vinda de volta."),
            step("new", "Prazer!"),
        )
        val actions = Actions(known = true)
        assertEquals(listOf("Bem-vinda de volta."), Chat(ChatFlows(listOf(known)), actions).say("olá").messages.map { it.text })
        actions.known = false
        assertEquals(listOf("Prazer!"), Chat(ChatFlows(listOf(known)), actions).say("olá").messages.map { it.text })

        val answers = flow(
            step("ask", "Já cá veio?") { it.copy(kind = StepKind.ASK, key = "visited", expect = Expect.YES_NO, next = "check") },
            step("check") { it.copy(kind = StepKind.CONDITION, check = Check.ANSWER, answerKey = "visited", answerIs = "yes", whenTrue = "again", whenFalse = "first") },
            step("again", "Outra vez!"),
            step("first", "A primeira!"),
        )
        val chat = Chat(ChatFlows(listOf(answers)))
        chat.say("olá")
        assertEquals(listOf("Outra vez!"), chat.say("Sim").messages.map { it.text })
    }

    @Test
    fun `actions create the client and the booking from the answers, tell the team, and say so`() {
        val book = flow(
            step("name", "Nome?") { it.copy(kind = StepKind.ASK, key = "name", next = "service") },
            step("service", "Que serviço?") { it.copy(kind = StepKind.ASK, key = "service", next = "date") },
            step("date", "Que dia?") { it.copy(kind = StepKind.ASK, key = "date", expect = Expect.DATE, next = "time") },
            step("time", "A que horas?") { it.copy(kind = StepKind.ASK, key = "time", expect = Expect.TIME, next = "client") },
            step("client") { it.copy(kind = StepKind.ACTION, action = ActionKind.CREATE_CLIENT, next = "booking") },
            step("booking", "Marcado: {service}, {date} às {time}.") { it.copy(kind = StepKind.ACTION, action = ActionKind.CREATE_BOOKING, key = "service", next = "tell", failNext = "sorry") },
            step("tell") { it.copy(kind = StepKind.ACTION, action = ActionKind.NOTIFY_TEAM, next = "end") },
            step("end", "Até lá!"),
            step("sorry", "Esse horário já não está livre."),
        )
        val actions = Actions()
        val chat = Chat(ChatFlows(listOf(book)), actions)
        chat.say("olá"); chat.say("Ana"); chat.say("Limpeza"); chat.say("2026-11-20")
        val done = chat.say("15h30")

        assertEquals(listOf("Marcado: Limpeza, 2026-11-20 às 15:30.", "Até lá!"), done.messages.map { it.text })
        assertEquals(listOf(FlowClient("Ana", "351912345678", null, "service: Limpeza\ndate: 2026-11-20\ntime: 15:30")), actions.clients, "the phone is WhatsApp's own number")
        val booking = actions.bookings.single()
        assertEquals(Triple("Limpeza", Instant.parse("2026-11-20T15:30:00Z"), "Ana"), Triple(booking.serviceName, booking.startAt, booking.name))
        assertEquals(Platform.WHATSAPP, booking.platform)
        assertEquals("Ana", actions.notes.single().answers["name"])
        assertEquals(listOf(ActionKind.CREATE_CLIENT, ActionKind.CREATE_BOOKING, ActionKind.NOTIFY_TEAM), done.effects.map { it.action })
        assertTrue(done.effects.all { it.ok })
        assertNull(done.handOver)

        actions.failBooking = true
        val again = Chat(ChatFlows(listOf(book)), actions)
        again.say("olá"); again.say("Ana"); again.say("Limpeza"); again.say("2026-11-20")
        val failed = again.say("15h30")
        assertEquals(listOf("Esse horário já não está livre."), failed.messages.map { it.text }, "the fail path is taken, and the booking message isn't sent")
        assertEquals(listOf(true, false), failed.effects.map { it.ok })
    }

    @Test
    fun `an action that fails with nowhere to go hands the chat to a person, and a client needs a phone`() {
        val noFail = flow(step("book") { it.copy(kind = StepKind.ACTION, action = ActionKind.CREATE_BOOKING, serviceId = "svc", next = "ok") }, step("ok", "Marcado."))
        val result = Chat(ChatFlows(listOf(noFail))).say("olá")
        assertNotNull(result.handOver, "no date or time: a person finishes it")
        assertEquals(listOf(false), result.effects.map { it.ok })

        val client = flow(step("c") { it.copy(kind = StepKind.ACTION, action = ActionKind.CREATE_CLIENT, next = "ok", failNext = "no") }, step("ok", "Guardado."), step("no", "Preciso do seu telefone."))
        val chat = Chat(ChatFlows(listOf(client)), platform = Platform.INSTAGRAM)
        assertEquals(listOf("Preciso do seu telefone."), chat.say("olá").messages.map { it.text }, "Instagram has no phone unless the flow asked for it")
    }

    @Test
    fun `a hand-over step says its words, passes the chat on with what was answered, and ends the flow`() {
        val handover = flow(
            step("name", "Nome?") { it.copy(kind = StepKind.ASK, key = "name", next = "person") },
            step("person", "Uma pessoa já lhe responde, {name}.") { it.copy(kind = StepKind.HANDOFF) },
        )
        val chat = Chat(ChatFlows(listOf(handover)))
        chat.say("olá")
        val result = chat.say("Rita")
        assertEquals(listOf("Uma pessoa já lhe responde, Rita."), result.messages.map { it.text })
        assertEquals("“Marcações” · name: Rita", result.handOver?.reason)
        assertNull(result.session)
    }

    @Test
    fun `a session idle for twelve hours is a new beginning`() {
        val chat = Chat(ChatFlows(listOf(menu)))
        chat.say("olá")
        chat.now = Instant.parse("2026-11-18T10:00:01Z")
        assertFalse(chat.say("1").handled, "the options are long forgotten")
        assertEquals("menu", chat.say("olá").session?.stepId)
    }

    @Test
    fun `a disabled flow or one on another channel leaves its customer alone`() {
        val chat = Chat(ChatFlows(listOf(menu)))
        chat.say("olá")
        val off = Chat(ChatFlows(listOf(menu.copy(enabled = false)))).also { it.session = chat.session }
        assertFalse(off.say("1").handled)
        val web = Chat(ChatFlows(listOf(menu.copy(channels = setOf(Platform.INSTAGRAM))))).also { it.session = chat.session }
        assertFalse(web.say("1").handled)
    }

    @Test
    fun `when the AI reads, it picks the option or the value, and a hand-over, but only after the usual ways fail`() {
        val calls = mutableListOf<FlowReading>()
        var decision: FlowDecision = FlowDecision.Option(1)
        val interpreter = FlowInterpreter { reading -> calls += reading; decision }
        val chat = Chat(ChatFlows(listOf(menu.copy(aiReads = true))), interpreter = interpreter)
        chat.say("olá")

        assertEquals(listOf("Unhas: 20 €. Mais alguma coisa?"), chat.say("1").messages.map { it.text })
        assertEquals(0, calls.size, "a reply the usual ways read never reaches the AI")

        chat.say("olá")
        assertEquals(listOf("Pestanas: 30 €."), chat.say("tipo as pestas").messages.map { it.text })
        assertEquals(FlowReading("O que deseja?", listOf("Unhas", "Pestanas"), null, "tipo as pestas", kotlinx.datetime.LocalDate(2026, 11, 17)), calls.single())

        chat.say("olá")
        decision = FlowDecision.HandOver
        assertNotNull(chat.say("quero falar com a dona").handOver)

        chat.say("olá")
        decision = FlowDecision.Option(7)
        assertEquals(1, chat.say("sei lá").session?.misses, "an option that doesn't exist is a miss")
    }

    @Test
    fun `a value from the AI must pass the same reading as a typed one`() {
        val ask = flow(step("day", "Para que dia?") { it.copy(kind = StepKind.ASK, key = "date", expect = Expect.DATE, next = "done") }, step("done", "Dia {date}."), aiReads = true)
        var value = "2026-11-24"
        val chat = Chat(ChatFlows(listOf(ask)), interpreter = { FlowDecision.Value(value) })
        chat.say("olá")
        value = "terça"
        assertEquals(1, chat.say("terça da semana que vem").session?.misses, "not a date, so ask again")
        value = "2026-11-24"
        assertEquals(listOf("Dia 2026-11-24."), chat.say("terça da semana que vem").messages.map { it.text })
    }

    @Test
    fun `the AI is not asked when the company is out of tokens, when it fails, or when the flow doesn't use it`() {
        var asked = 0
        val interpreter = FlowInterpreter { asked++; FlowDecision.Option(0) }
        val noBudget = Chat(ChatFlows(listOf(menu.copy(aiReads = true))), interpreter = interpreter).also { it.aiAllowed = false }
        noBudget.say("olá"); noBudget.say("sei lá")
        val notUsed = Chat(ChatFlows(listOf(menu)), interpreter = interpreter)
        notUsed.say("olá"); notUsed.say("sei lá")
        assertEquals(0, asked)

        val broken = Chat(ChatFlows(listOf(menu.copy(aiReads = true))), interpreter = { error("model down") })
        broken.say("olá")
        assertEquals(1, broken.say("sei lá").session?.misses, "a failing model is a miss, not a crash")
    }

    @Test
    fun `a flow whose silent steps lead back to themselves is a problem, and one that waits is not`() {
        val loop = flow(step("a", "a") { it.copy(next = "b") }, step("b", "b") { it.copy(next = "a") })
        assertTrue("loop" in loop.problems())
        val waits = flow(step("a", "a") { it.copy(next = "b") }, step("b", "b?", FlowButton("again", "a")))
        assertFalse("loop" in waits.problems())
    }

    @Test
    fun `steps are checked by kind`() {
        fun problems(vararg steps: FlowStep) = flow(*steps).problems()
        val end = step("end", "Fim.")

        assertEquals(emptyList(), problems(step("q", "Nome?") { it.copy(kind = StepKind.ASK, key = "name", next = "end") }, end))
        assertEquals(listOf("key:q"), problems(step("q", "Nome?") { it.copy(kind = StepKind.ASK, key = "Nome!", next = "end") }, end))
        assertEquals(listOf("next:q"), problems(step("q", "Nome?") { it.copy(kind = StepKind.ASK, key = "name", next = "gone") }, end))
        assertEquals(listOf("hours:c"), problems(step("c") { it.copy(kind = StepKind.CONDITION, check = Check.HOURS, whenTrue = "end", whenFalse = "end") }, end))
        assertEquals(listOf("hours:c"), problems(step("c") { it.copy(kind = StepKind.CONDITION, check = Check.HOURS, hours = listOf(HoursWindow(1, "18:00", "09:00")), whenTrue = "end", whenFalse = "end") }, end))
        assertEquals(listOf("branch:c"), problems(step("c") { it.copy(kind = StepKind.CONDITION, check = Check.KNOWN_CLIENT, whenTrue = "end") }, end))
        assertEquals(listOf("answer:c"), problems(step("c") { it.copy(kind = StepKind.CONDITION, check = Check.ANSWER, whenTrue = "end", whenFalse = "end") }, end))
        assertEquals(listOf("service:b"), problems(step("b") { it.copy(kind = StepKind.ACTION, action = ActionKind.CREATE_BOOKING, next = "end") }, end))
        assertEquals(emptyList(), problems(step("h", "Já vamos.") { it.copy(kind = StepKind.HANDOFF) }, end))
        assertEquals(listOf("words:m"), problems(step("m", "Qual?", FlowButton("A", "end", List(11) { "w$it" })), end))
        assertEquals(listOf("list_label:m"), problems(step("m", "Qual?") { it.copy(listLabel = "x".repeat(21)) }, end))
    }
}
