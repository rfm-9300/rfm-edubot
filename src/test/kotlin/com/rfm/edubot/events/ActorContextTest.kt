package com.rfm.edubot.events

import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

class ActorContextTest {
    @Test
    fun `nothing set means the system is acting`() = runBlocking {
        assertEquals(ActorType.SYSTEM, currentActor().actor.type)
    }

    @Test
    fun `an explicit actor wins over the request slot`() = runBlocking {
        val slot = RequestActorSlot().apply { value = ActorContext(Actor(ActorType.USER, "u1")) }
        withContext(slot) {
            assertEquals("u1", currentActor().actor.id)
            withContext(ActorContext(Actor(ActorType.AGENT, "a1"), depth = 2)) {
                assertEquals("a1", currentActor().actor.id)
                assertEquals(2, currentActor().depth)
            }
        }
    }

    @Test
    fun `the caller an auth validator remembers reaches the route handler`() = testApplication {
        application {
            installActorContext()
            install(Authentication) {
                basic("test") {
                    validate { credentials ->
                        rememberRequestActor(Actor(ActorType.USER, id = credentials.name))
                        UserIdPrincipal(credentials.name)
                    }
                }
            }
            routing {
                authenticate("test") {
                    get("/who") { call.respondText("${currentActor().actor.type}:${currentActor().actor.id}") }
                }
                get("/public") { call.respondText(currentActor().actor.type.name) }
            }
        }

        assertEquals("USER:user-1", client.get("/who") { basicAuth("user-1", "secret") }.bodyAsText())
        assertEquals("SYSTEM", client.get("/public").bodyAsText())
    }
}
