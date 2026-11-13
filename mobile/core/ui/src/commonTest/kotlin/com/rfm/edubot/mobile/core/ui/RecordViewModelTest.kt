package com.rfm.edubot.mobile.core.ui

import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.Outcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordViewModelTest {
    @Test
    fun `an action's answer replaces the record`() = runTest {
        val vm = RecordViewModel({ Outcome.Success("draft") }, backgroundScope)
        vm.load().join()

        vm.act { Outcome.Success("sent") }.join()

        assertEquals("sent", vm.state.value.value)
        assertEquals(false, vm.state.value.busy)
    }

    @Test
    fun `a second tap while an action runs sends nothing`() = runTest {
        val vm = RecordViewModel({ Outcome.Success(1) }, backgroundScope)
        vm.load().join()
        val gate = CompletableDeferred<Unit>()
        var calls = 0

        vm.act { calls++; gate.await(); Outcome.Success(2) }
        runCurrent()
        vm.act { calls++; Outcome.Success(3) }.join()
        gate.complete(Unit)
        runCurrent()

        assertEquals(1, calls)
        assertEquals(2, vm.state.value.value)
    }

    @Test
    fun `a refused action keeps the record and says why`() = runTest {
        val vm = RecordViewModel({ Outcome.Success("open") }, backgroundScope)
        vm.load().join()

        vm.act { Outcome.Failure(AppError.Rejected("invoice_paid", 409)) }.join()

        assertEquals("open", vm.state.value.value)
        assertEquals(AppError.Rejected("invoice_paid", 409), vm.state.value.error)
    }

    @Test
    fun `removing the record tells the screen to step back`() = runTest {
        val vm = RecordViewModel({ Outcome.Success("quote") }, backgroundScope)
        vm.load().join()

        vm.remove { Outcome.Success(Unit) }.join()

        assertTrue(vm.state.value.gone)
    }

    @Test
    fun `an action that changes something else reloads the record`() = runTest {
        var loads = 0
        val vm = RecordViewModel({ loads++; Outcome.Success("v$loads") }, backgroundScope)
        vm.load().join()

        vm.actThenReload { Outcome.Success("invoice") }.join()

        assertEquals("v2", vm.state.value.value)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `a removed record forgets it once the screen has stepped back`() = runTest {
        val vm = RecordViewModel({ Outcome.Success("quote") }, backgroundScope)
        vm.load().join()
        vm.remove { Outcome.Success(Unit) }.join()

        vm.consumeGone()

        assertEquals(false, vm.state.value.gone)
    }
}
