package com.rfm.edubot.mobile.feature.settings

import com.rfm.edubot.mobile.core.testing.FakeDashboardApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsViewModelTest {
    @Test
    fun `selects locale immediately and keeps server response`() = runBlocking {
        val appliedLocales = mutableListOf<String>()
        val controller = SettingsViewModel(
            FakeDashboardApi(localeUpdateDelay = 100),
            "token",
            "en",
            appliedLocales::add,
            scopeOverride = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )

        controller.updateLocale("pt-PT")

        controller.state.awaitFirst { it.selectedLocale == "pt-PT" }
        assertEquals("pt-PT", controller.state.value.selectedLocale)
        assertEquals(true, controller.state.value.updatingLocale)
        assertEquals("pt-PT", appliedLocales.first())
        controller.state.awaitFirst { !it.updatingLocale }
        assertEquals("pt-PT", controller.state.value.selectedLocale)
        assertEquals("pt-PT", appliedLocales.last())
    }

    private suspend fun <T> StateFlow<T>.awaitFirst(predicate: (T) -> Boolean) {
        withTimeout(10_000) { first(predicate) }
    }
}
