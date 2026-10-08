package com.rfm.edubot.mobile.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SessionViewModelsTest {
    private class Probe : ViewModel() {
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }

    private fun SessionViewModels.probe(): Probe =
        ViewModelProvider.create(this, viewModelFactory { initializer { Probe() } })[Probe::class]

    @Test
    fun `the same session keeps its view models`() {
        val holder = SessionViewModels()
        holder.enter("t1/tenant/u1/")
        val first = holder.probe()
        holder.enter("t1/tenant/u1/")

        assertSame(first, holder.probe())
        assertFalse(first.cleared)
    }

    @Test
    fun `whoever signs in next starts without the last session's view models`() {
        val holder = SessionViewModels()
        holder.enter("t1/tenant/u1/")
        val admin = holder.probe()
        holder.enter(null)
        holder.enter("t1/tenant/u2/e1")

        assertTrue(admin.cleared, "a poller left running would call with the employee's token")
        assertNotSame(admin, holder.probe())
    }
}
