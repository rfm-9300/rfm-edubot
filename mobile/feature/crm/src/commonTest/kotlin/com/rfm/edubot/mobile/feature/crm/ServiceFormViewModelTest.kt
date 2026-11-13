package com.rfm.edubot.mobile.feature.crm

import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.model.CatalogItem
import com.rfm.edubot.mobile.core.model.LineItem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServiceFormViewModelTest {
    private val options = ServiceFormOptions(
        clients = listOf(ClientOption("c1", "Ana"), ClientOption("c2", "Bruno")),
        catalog = listOf(CatalogItem(id = "srv-1", type = "service", title = "Pintura", unit = "m2", defaultUnitPriceEur = 8.0)),
        employees = listOf("e1" to "Rui"),
    )

    private class Sink {
        val drafts = mutableListOf<ServiceDraft>()
        var answer: Outcome<Unit> = Outcome.Success(Unit)
        suspend fun submit(draft: ServiceDraft): Outcome<Unit> = answer.also { drafts += draft }
    }

    private fun kotlinx.coroutines.test.TestScope.vm(sink: Sink, presetClientId: String? = null) =
        ServiceFormViewModel({ options }, sink::submit, today = "2026-11-10", presetClientId = presetClientId, scopeOverride = backgroundScope)

    @Test
    fun `a service with no name is named after its lines as the web names it`() {
        assertEquals("Pintura + Limpeza", nameFromLines(listOf(LineItem("Pintura - sala"), LineItem("Limpeza"))))
    }

    @Test
    fun `it can't be saved without a client a valid day and a line`() = runTest {
        val sink = Sink()
        val vm = vm(sink)
        vm.load().join()
        assertFalse(vm.state.value.canSave)

        vm.pickClient(options.clients.first())
        vm.editLine(0) { copy(description = "Pintura", quantity = "2", unitPrice = "10") }
        assertTrue(vm.state.value.canSave)

        vm.setDay("10/11/2026")
        assertFalse(vm.state.value.canSave, "the day is typed as yyyy-MM-dd")
    }

    @Test
    fun `the draft carries the lines the day and who did it`() = runTest {
        val sink = Sink()
        val vm = vm(sink, presetClientId = "c2")
        vm.load().join()
        vm.applyCatalogItem(0, options.catalog.single())
        vm.editLine(0) { copy(quantity = "12") }
        vm.setEmployee("e1")

        vm.save().join()

        val draft = sink.drafts.single()
        assertEquals("c2", draft.clientId)
        assertEquals("2026-11-10", draft.day)
        assertEquals("Pintura", draft.name)
        assertEquals(96.0, draft.items.single().totalEur)
        assertEquals("e1", draft.employeeId)
        assertEquals("srv-1", draft.catalogItemId)
        assertTrue(vm.state.value.saved)
    }

    @Test
    fun `a refusal keeps the form as typed`() = runTest {
        val sink = Sink().apply { answer = Outcome.Failure(AppError.Rejected("date_in_future")) }
        val vm = vm(sink, presetClientId = "c1")
        vm.load().join()
        vm.editLine(0) { copy(description = "Pintura", quantity = "1") }

        vm.save().join()

        assertEquals(AppError.Rejected("date_in_future"), vm.state.value.error)
        assertFalse(vm.state.value.saved)
        assertEquals("Pintura", vm.state.value.lines.single().description)
    }

    @Test
    fun `typing a client again clears the one picked`() = runTest {
        val vm = vm(Sink(), presetClientId = "c1")
        vm.load().join()
        assertEquals("Ana", vm.state.value.clientQuery)

        vm.setClientQuery("Bru")

        assertNull(vm.state.value.clientId)
    }
}
