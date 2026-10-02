package com.rfm.edubot.mobile.app

import com.rfm.edubot.mobile.core.model.DashboardModules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Back used to close the app from inside an open conversation, because the shell held the current
 * module in a `remember` with no stack behind it.
 */
class NavigatorTest {
    private fun navigator() = Navigator(DashboardModules.OVERVIEW)

    @Test
    fun `it starts at the module it was given`() {
        val navigator = navigator()
        assertEquals(Destination.Module(DashboardModules.OVERVIEW), navigator.state.value.current)
        assertFalse(navigator.state.value.canGoBack)
    }

    @Test
    fun `back at a module root is left to the platform, which closes the app`() {
        val navigator = navigator()
        assertFalse(navigator.back(), "returning false is what lets the OS handle it")
    }

    @Test
    fun `back from a conversation returns to the inbox`() {
        val navigator = navigator()
        navigator.selectModule(DashboardModules.CONVERSATIONS)
        navigator.open(Destination.Conversation("c1"))

        assertTrue(navigator.state.value.canGoBack)
        assertTrue(navigator.back())
        assertEquals(Destination.Module(DashboardModules.CONVERSATIONS), navigator.state.value.current)
    }

    @Test
    fun `switching module replaces the stack instead of piling tabs onto it`() {
        val navigator = navigator()
        navigator.selectModule(DashboardModules.CONVERSATIONS)
        navigator.open(Destination.Conversation("c1"))
        navigator.selectModule(DashboardModules.CLIENTS)

        assertFalse(navigator.state.value.canGoBack, "back from a tab root should leave the app")
        assertEquals(Destination.Module(DashboardModules.CLIENTS), navigator.state.value.current)
    }

    @Test
    fun `the tab stays highlighted while a detail screen is on top of it`() {
        val navigator = navigator()
        navigator.selectModule(DashboardModules.CONVERSATIONS)
        navigator.open(Destination.Conversation("c1"))

        assertEquals(DashboardModules.CONVERSATIONS, navigator.state.value.activeModule)
    }

    @Test
    fun `opening the same screen twice does not stack duplicates`() {
        val navigator = navigator()
        navigator.open(Destination.Conversation("c1"))
        navigator.open(Destination.Conversation("c1"))

        assertTrue(navigator.back())
        assertFalse(navigator.state.value.canGoBack, "a double tap should not need two backs")
    }

    @Test
    fun `different conversations do stack, so back walks the trail`() {
        val navigator = navigator()
        navigator.open(Destination.Conversation("c1"))
        navigator.open(Destination.Conversation("c2"))

        assertTrue(navigator.back())
        assertEquals(Destination.Conversation("c1"), navigator.state.value.current)
    }

    @Test
    fun `a new client form pops back to the list once saved`() {
        val navigator = navigator()
        navigator.selectModule(DashboardModules.CLIENTS)
        navigator.open(Destination.NewClient)

        assertTrue(navigator.back())
        assertEquals(Destination.Module(DashboardModules.CLIENTS), navigator.state.value.current)
    }

    @Test
    fun `resetting after a company switch drops the previous company's screens`() {
        val navigator = navigator()
        navigator.selectModule(DashboardModules.CLIENTS)
        navigator.open(Destination.Client("cl1"))

        navigator.reset(DashboardModules.OVERVIEW)

        assertEquals(Destination.Module(DashboardModules.OVERVIEW), navigator.state.value.current)
        assertFalse(navigator.state.value.canGoBack, "a record from the other company must be unreachable")
    }

    @Test
    fun `the notifications screen is a push, so back returns where it was opened from`() {
        val navigator = navigator()
        navigator.selectModule(DashboardModules.BOOKINGS)
        navigator.open(Destination.Notifications)

        assertTrue(navigator.back())
        assertEquals(Destination.Module(DashboardModules.BOOKINGS), navigator.state.value.current)
    }
}
