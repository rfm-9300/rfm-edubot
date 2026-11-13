package com.rfm.edubot.mobile.app

import com.rfm.edubot.mobile.feature.building.BuildingRoute
import com.rfm.edubot.mobile.feature.crm.CrmRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface Destination {
    /** A module's root screen. */
    data class Module(val id: String) : Destination

    data class Conversation(val conversationId: String) : Destination

    /** A CRM record or form: a client, a quote, an invoice, a service, an employee… */
    data class Crm(val route: CrmRoute) : Destination

    /** A Building record or form, the team's or a site manager's. */
    data class Building(val route: BuildingRoute) : Destination

    /** An employee registering a service ([submissionId] null) or changing a pending one. */
    data class MyService(val submissionId: String?) : Destination

    data object Notifications : Destination

    data object MoreMenu : Destination
}

data class NavState(val stack: List<Destination>) {
    val current: Destination get() = stack.last()

    val canGoBack: Boolean get() = stack.size > 1

    /** The module whose tab looks selected, even while a detail screen sits on top of it. */
    val activeModule: String?
        get() = stack.lastOrNull { it is Destination.Module }?.let { (it as Destination.Module).id }
}

/**
 * The app's back stack.
 *
 * The shell used to hold `var selectedModule by remember`, so the Android back gesture closed the
 * app from inside an open conversation and there was no way to express "a detail screen above a
 * list". [back] gives the platform something to pop.
 */
class Navigator(startModule: String) {
    private val mutable = MutableStateFlow(NavState(listOf(Destination.Module(startModule))))
    val state: StateFlow<NavState> = mutable.asStateFlow()

    /** Pushes a screen above the current one. */
    fun open(destination: Destination) {
        val stack = mutable.value.stack
        if (destination == stack.last()) return
        mutable.value = NavState(stack + destination)
    }

    /**
     * Switches module, resetting the stack: tapping a bottom-bar item is a change of place, not a
     * step forward, so back from a module root should leave the app rather than retrace tabs.
     */
    fun selectModule(id: String) {
        mutable.value = NavState(listOf(Destination.Module(id)))
    }

    /** True when something was popped, so the platform knows whether to handle back itself. */
    fun back(): Boolean {
        val stack = mutable.value.stack
        if (stack.size <= 1) return false
        mutable.value = NavState(stack.dropLast(1))
        return true
    }

    /** After a sign-in or a company switch the whole stack belongs to the previous state. */
    fun reset(startModule: String) {
        mutable.value = NavState(listOf(Destination.Module(startModule)))
    }
}
