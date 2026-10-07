package com.rfm.edubot.mobile.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * Where the signed-in screens keep their view models. It outlives the screens (so a list keeps its
 * state across a rotation) but not the session: whoever signs in next starts clean. Several view
 * models poll, and one left over would call with the next person's token; an employee's is refused
 * by every company endpoint, which signs them out.
 */
internal class SessionViewModels : ViewModel(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    private var session: String? = null

    /** Clears what the previous session left, the first time a different [key] shows up. */
    fun enter(key: String?) {
        if (key == session) return
        viewModelStore.clear()
        session = key
    }

    override fun onCleared() = viewModelStore.clear()
}
