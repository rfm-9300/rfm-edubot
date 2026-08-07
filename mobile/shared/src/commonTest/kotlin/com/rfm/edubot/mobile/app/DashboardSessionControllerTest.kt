package com.rfm.edubot.mobile.app

import com.rfm.edubot.mobile.data.ChannelAsset
import com.rfm.edubot.mobile.data.CatalogItem
import com.rfm.edubot.mobile.data.CrmClient
import com.rfm.edubot.mobile.data.CreateInvoice
import com.rfm.edubot.mobile.data.CreateQuote
import com.rfm.edubot.mobile.data.AssistantThread
import com.rfm.edubot.mobile.data.AssistantThreadDetail
import com.rfm.edubot.mobile.data.AssistantMessage
import com.rfm.edubot.mobile.data.Contact
import com.rfm.edubot.mobile.data.Conversation
import com.rfm.edubot.mobile.data.DashboardApi
import com.rfm.edubot.mobile.data.DashboardIdentity
import com.rfm.edubot.mobile.data.DashboardUser
import com.rfm.edubot.mobile.data.InMemoryTokenStore
import com.rfm.edubot.mobile.data.Overview
import com.rfm.edubot.mobile.data.Invoice
import com.rfm.edubot.mobile.data.Quote
import com.rfm.edubot.mobile.data.Persona
import com.rfm.edubot.mobile.data.WebWidget
import com.rfm.edubot.mobile.data.Session
import com.rfm.edubot.mobile.data.Tenant
import com.rfm.edubot.mobile.data.ThreadMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DashboardSessionControllerTest {
    @Test
    fun `restores the stored session and loads overview`() = runBlocking {
        val store = InMemoryTokenStore().also { it.write("stored-token") }
        val controller = DashboardSessionController(FakeDashboardApi(), store)

        controller.restore()
        waitForSignedIn(controller)

        val state = assertIs<DashboardSessionState.SignedIn>(controller.state.value)
        assertEquals("Acme", state.identity.tenant.name)
        assertEquals(9, state.overview?.messages)
    }

    @Test
    fun `rejects blank login credentials without calling api`() = runBlocking {
        val controller = DashboardSessionController(FakeDashboardApi(), InMemoryTokenStore())

        controller.login("", "")
        waitForSignedOut(controller)

        val state = assertIs<DashboardSessionState.SignedOut>(controller.state.value)
        assertEquals(SessionError.MISSING_CREDENTIALS, state.error)
    }

    @Test
    fun `sign out clears stored token`() = runBlocking {
        val store = InMemoryTokenStore().also { it.write("stored-token") }
        val controller = DashboardSessionController(FakeDashboardApi(), store)

        controller.signOut()
        waitForSignedOut(controller)

        assertEquals(null, store.read())
    }

    @Test
    fun `applies locale to the active session immediately`() = runBlocking {
        val controller = DashboardSessionController(FakeDashboardApi(), InMemoryTokenStore().also { it.write("stored-token") })
        controller.restore()
        waitForSignedIn(controller)

        controller.applyLocale("pt-PT")

        val state = assertIs<DashboardSessionState.SignedIn>(controller.state.value)
        assertEquals("pt-PT", state.identity.tenant.locale)
    }

    private suspend fun waitForSignedIn(controller: DashboardSessionController) {
        repeat(100) {
            if (controller.state.value is DashboardSessionState.SignedIn) return
            delay(10)
        }
        error("Controller did not become signed in")
    }

    private suspend fun waitForSignedOut(controller: DashboardSessionController) {
        repeat(100) {
            if (controller.state.value is DashboardSessionState.SignedOut) return
            delay(10)
        }
        error("Controller did not become signed out")
    }
}

class SettingsControllerTest {
    @Test
    fun `selects locale immediately and keeps server response`() = runBlocking {
        val appliedLocales = mutableListOf<String>()
        val controller = SettingsController(FakeDashboardApi(localeUpdateDelay = 100), "token", "en", appliedLocales::add)

        controller.updateLocale("pt-PT")

        waitUntil { controller.state.value.selectedLocale == "pt-PT" }
        assertEquals("pt-PT", controller.state.value.selectedLocale)
        assertEquals(true, controller.state.value.updatingLocale)
        assertEquals("pt-PT", appliedLocales.first())
        waitUntil { !controller.state.value.updatingLocale }
        assertEquals("pt-PT", controller.state.value.selectedLocale)
        assertEquals("pt-PT", appliedLocales.last())
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            delay(10)
        }
        error("Condition was not met")
    }
}

class AssistantControllerTest {
    @Test
    fun `voice transcript remains editable and is not sent automatically`() = runBlocking {
        val thread = AssistantThread("thread-1", "Voice", "now", "now")
        val api = FakeDashboardApi(AssistantThreadDetail(thread, emptyList()))
        val voiceInput = FakeVoiceInput()
        val controller = AssistantController(api, "token", "pt-PT", voiceInput)

        controller.load()
        waitUntil { controller.state.value.detail != null }
        controller.updateDraft("Create")
        controller.toggleVoice()
        assertEquals("pt-PT", voiceInput.startedLocale)

        voiceInput.emit(VoiceInputState.Listening("a quote"))
        waitUntil { controller.state.value.draft == "Create a quote" }
        voiceInput.emit(VoiceInputState.Finished("a quote for Acme"))
        waitUntil { controller.state.value.draft == "Create a quote for Acme" }

        assertEquals(emptyList(), api.sentAssistantMessages)
        controller.updateDraft("Create a quote for Acme tomorrow")
        controller.send()
        waitUntil { api.sentAssistantMessages.isNotEmpty() && !controller.state.value.busy }

        assertEquals(listOf("Create a quote for Acme tomorrow"), api.sentAssistantMessages)
        assertEquals("", controller.state.value.draft)
        controller.close()
    }

    @Test
    fun `voice permission failure is exposed to the UI`() = runBlocking {
        val voiceInput = FakeVoiceInput()
        val controller = AssistantController(FakeDashboardApi(), "token", "en", voiceInput)

        voiceInput.emit(VoiceInputState.Failed(VoiceInputError.PERMISSION_DENIED))
        waitUntil { controller.state.value.voiceError != null }

        assertEquals(VoiceInputError.PERMISSION_DENIED, controller.state.value.voiceError)
        controller.close()
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            delay(10)
        }
        error("Condition was not met")
    }
}

private class FakeVoiceInput : VoiceInput {
    private val mutableState = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
    override val state: StateFlow<VoiceInputState> = mutableState
    var startedLocale: String? = null

    override fun start(locale: String) {
        startedLocale = locale
        mutableState.value = VoiceInputState.Listening()
    }

    override fun stop() {
        val transcript = (mutableState.value as? VoiceInputState.Listening)?.transcript.orEmpty()
        mutableState.value = VoiceInputState.Finished(transcript)
    }

    override fun cancel() {
        mutableState.value = VoiceInputState.Idle
    }

    fun emit(state: VoiceInputState) {
        mutableState.value = state
    }
}

private class FakeDashboardApi(
    private val assistantDetail: AssistantThreadDetail? = null,
    private val localeUpdateDelay: Long = 0,
) : DashboardApi {
    val sentAssistantMessages = mutableListOf<String>()
    override suspend fun login(email: String, password: String): Session = Session("new-token")

    override suspend fun me(token: String): DashboardIdentity = DashboardIdentity(
        tenant = Tenant("tenant-1", "acme", "Acme", "en", listOf(ChannelAsset("WHATSAPP", "asset-1"))),
        user = DashboardUser("user-1", "user@acme.test", "TENANT_ADMIN", "ACTIVE"),
        modules = listOf("overview", "conversations"),
        principalType = "tenant",
    )

    override suspend fun overview(token: String): Overview = Overview(
        users = 2,
        conversations = 3,
        messages = 9,
        messagesToday = 1,
        quotes = 0,
        invoices = 0,
    )

    override suspend fun contacts(token: String): List<Contact> = emptyList()

    override suspend fun updateContactStatus(token: String, contactId: String, status: String): Contact =
        error("not used")

    override suspend fun conversations(token: String): List<Conversation> = emptyList()

    override suspend fun messages(token: String, conversationId: String): List<ThreadMessage> = emptyList()

    override suspend fun sendMessage(token: String, conversationId: String, text: String, assetExternalId: String): ThreadMessage =
        error("not used")

    override suspend fun assistantThreads(token: String): List<AssistantThread> = assistantDetail?.let { listOf(it.thread) }.orEmpty()

    override suspend fun createAssistantThread(token: String, title: String): AssistantThread = error("not used")

    override suspend fun assistantThread(token: String, threadId: String): AssistantThreadDetail = requireNotNull(assistantDetail)

    override suspend fun sendAssistantMessage(token: String, threadId: String, content: String): AssistantThreadDetail {
        sentAssistantMessages += content
        val detail = requireNotNull(assistantDetail)
        return detail.copy(messages = detail.messages + AssistantMessage("message-1", "user", content, "now"))
    }

    override suspend fun decideAssistantAction(token: String, threadId: String, actionId: String, decision: String): AssistantThreadDetail =
        error("not used")

    override suspend fun clients(token: String): List<CrmClient> = emptyList()

    override suspend fun createClient(token: String, name: String, phone: String, address: String?): CrmClient = error("not used")

    override suspend fun quotes(token: String): List<Quote> = emptyList()

    override suspend fun invoices(token: String): List<Invoice> = emptyList()

    override suspend fun catalog(token: String): List<CatalogItem> = emptyList()

    override suspend fun createCatalogItem(token: String, item: CatalogItem): CatalogItem = error("not used")

    override suspend fun createQuote(token: String, request: CreateQuote): Quote = error("not used")

    override suspend fun createInvoice(token: String, request: CreateInvoice): Invoice = error("not used")

    override suspend fun persona(token: String): Persona = error("not used")

    override suspend fun updatePersona(token: String, compiledInstructions: String): Persona = error("not used")

    override suspend fun webWidget(token: String): WebWidget = error("not used")

    override suspend fun updateLocale(token: String, locale: String): String {
        delay(localeUpdateDelay)
        return locale
    }
}
