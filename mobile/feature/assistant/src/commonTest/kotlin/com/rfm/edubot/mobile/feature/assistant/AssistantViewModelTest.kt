package com.rfm.edubot.mobile.feature.assistant

import com.rfm.edubot.mobile.core.common.VoiceInputError
import com.rfm.edubot.mobile.core.common.VoiceInputState
import com.rfm.edubot.mobile.core.model.AssistantThread
import com.rfm.edubot.mobile.core.model.AssistantThreadDetail
import com.rfm.edubot.mobile.core.testing.FakeDashboardApi
import com.rfm.edubot.mobile.core.testing.FakeVoiceInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AssistantViewModelTest {
    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun `voice transcript remains editable and is not sent automatically`() = runBlocking {
        val thread = AssistantThread("thread-1", "Voice", "now", "now")
        val api = FakeDashboardApi(AssistantThreadDetail(thread, emptyList()))
        val voiceInput = FakeVoiceInput()
        val scope = testScope()
        val controller = AssistantViewModel(api, "token", "pt-PT", voiceInput, scopeOverride = scope)

        controller.load()
        controller.state.awaitFirst { it.detail != null }
        controller.updateDraft("Create")
        controller.toggleVoice()
        assertEquals("pt-PT", voiceInput.startedLocale)

        voiceInput.emit(VoiceInputState.Listening("a quote"))
        controller.state.awaitFirst { it.draft == "Create a quote" }
        voiceInput.emit(VoiceInputState.Finished("a quote for Acme"))
        controller.state.awaitFirst { it.draft == "Create a quote for Acme" }

        assertEquals(emptyList(), api.sentAssistantMessages)
        controller.updateDraft("Create a quote for Acme tomorrow")
        controller.send()
        controller.state.awaitFirst { state ->
            !state.busy && state.detail?.messages?.any { it.content == "Create a quote for Acme tomorrow" } == true
        }

        assertEquals(listOf("Create a quote for Acme tomorrow"), api.sentAssistantMessages)
        assertEquals("", controller.state.value.draft)
        scope.cancel()
    }

    @Test
    fun `user message appears in chat before the assistant reply arrives`() = runBlocking {
        val thread = AssistantThread("thread-1", "Chat", "now", "now")
        val api = FakeDashboardApi(
            assistantDetail = AssistantThreadDetail(thread, emptyList()),
            assistantSendDelay = 150,
        )
        val scope = testScope()
        val controller = AssistantViewModel(api, "token", "en", FakeVoiceInput(), scopeOverride = scope)

        controller.load()
        controller.state.awaitFirst { it.detail != null }
        controller.updateDraft("Hello assistant")
        controller.send()

        val optimistic = controller.state.awaitFirst { state ->
            state.busy &&
                state.draft.isEmpty() &&
                state.detail?.messages?.singleOrNull()?.let { it.role == "user" && it.content == "Hello assistant" } == true
        }
        assertEquals(1, optimistic.detail!!.messages.size)
        assertTrue(optimistic.detail!!.messages.first().id.startsWith("local-"))

        controller.state.awaitFirst { state ->
            !state.busy &&
                state.detail?.messages?.any { it.role == "assistant" && it.content == "ok" } == true
        }
        assertEquals(2, controller.state.value.detail!!.messages.size)
        assertEquals(listOf("Hello assistant"), api.sentAssistantMessages)
        scope.cancel()
    }

    @Test
    fun `failed send restores draft and rolls back optimistic message`() = runBlocking {
        val thread = AssistantThread("thread-1", "Chat", "now", "now")
        val api = FakeDashboardApi(
            assistantDetail = AssistantThreadDetail(thread, emptyList()),
            assistantSendError = true,
        )
        val scope = testScope()
        val controller = AssistantViewModel(api, "token", "en", FakeVoiceInput(), scopeOverride = scope)

        controller.load()
        controller.state.awaitFirst { it.detail != null }
        controller.updateDraft("Retry me")
        controller.send()

        controller.state.awaitFirst { state -> state.error && !state.busy }
        assertEquals("Retry me", controller.state.value.draft)
        assertEquals(emptyList(), controller.state.value.detail!!.messages)
        assertEquals(emptyList(), api.sentAssistantMessages)
        scope.cancel()
    }

    @Test
    fun `voice permission failure is exposed to the UI`() = runBlocking {
        val voiceInput = FakeVoiceInput()
        val scope = testScope()
        val controller = AssistantViewModel(FakeDashboardApi(), "token", "en", voiceInput, scopeOverride = scope)

        voiceInput.emit(VoiceInputState.Failed(VoiceInputError.PERMISSION_DENIED))
        controller.state.awaitFirst { it.voiceError != null }

        assertEquals(VoiceInputError.PERMISSION_DENIED, controller.state.value.voiceError)
        scope.cancel()
    }

    private suspend fun <T> StateFlow<T>.awaitFirst(predicate: (T) -> Boolean): T =
        withTimeout(10_000) { first(predicate) }
}
