package com.rfm.edubot.events

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Who is acting in the current coroutine. Repositories read it when they record a domain event, so
 * attribution needs no extra parameters: the pipeline sets the bot, the agent runtime sets the run.
 */
class ActorContext(val actor: Actor, val depth: Int = 0) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ActorContext> {
        val SYSTEM = ActorContext(Actor.SYSTEM)
    }
}

/**
 * A per-request holder the auth validators fill once they know the caller. Requests start before
 * authentication runs, so the caller can't be put in the coroutine context up front.
 */
class RequestActorSlot : AbstractCoroutineContextElement(Key) {
    @Volatile var value: ActorContext? = null

    companion object Key : CoroutineContext.Key<RequestActorSlot>
}

suspend fun currentActor(): ActorContext {
    val context = coroutineContext
    return context[ActorContext] ?: context[RequestActorSlot]?.value ?: ActorContext.SYSTEM
}

/** Called by an auth validator: later writes in this request are attributed to [actor]. */
suspend fun rememberRequestActor(actor: Actor) {
    coroutineContext[RequestActorSlot]?.value = ActorContext(actor)
}

fun Application.installActorContext() {
    intercept(ApplicationCallPipeline.Plugins) {
        withContext(RequestActorSlot()) { proceed() }
    }
}
