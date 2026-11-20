package com.rfm.edubot.testing

import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.persistence.MongoModule
import io.ktor.server.auth.AuthenticationRouteSelector
import io.ktor.server.routing.getAllRoutes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EdubotOperationCatalogTest {
    @Test
    fun `CLI and MCP catalog covers every dashboard and backoffice API route`() {
        // Route registration needs the repositories, but it makes no database calls.
        val mongo = MongoModule(AppConfig.MongoConfig("mongodb://127.0.0.1:1", "edubot_route_catalog"))
        try {
            dashboardApiTest(mongo) {
                val operations = routes.getAllRoutes().mapNotNull { route ->
                    val parts = route.toString().split('/').filter(String::isNotEmpty)
                    val method = parts.lastOrNull { it.startsWith("(method:") }
                        ?.removePrefix("(method:")?.removeSuffix(")") ?: return@mapNotNull null
                    val path = "/" + parts.filterNot { it.startsWith("(") }.joinToString("/")
                    if (!catalogPath(path)) return@mapNotNull null
                    val expectedAuth = when {
                        path.startsWith("/app/api/") -> "dashboard"
                        path.startsWith("/admin/api/") -> "admin-jwt"
                        else -> null
                    }
                    if (expectedAuth != null) {
                        val providers = generateSequence(route) { it.parent }
                            .mapNotNull { it.selector as? AuthenticationRouteSelector }.flatMap { it.names }.toSet()
                        assertTrue(expectedAuth in providers, "$method $path must be mounted under $expectedAuth")
                    }
                    "$method $path"
                }.distinct().sorted()
                val expected = operations.joinToString(",\n", "[\n", "\n]\n") { "  " + Json.encodeToJsonElement(it) }
                val catalog = Path.of("tools", "edubot", "src", "catalog.json")
                if (System.getenv("UPDATE_EDUBOT_CATALOG") == "1") catalog.writeText(expected)
                else assertEquals(expected, catalog.readText(), "Refresh the catalog with UPDATE_EDUBOT_CATALOG=1")
            }
        } finally {
            mongo.shutdown()
        }
    }

    private fun catalogPath(path: String): Boolean = when {
        // These consume provider-signed callbacks or exchange credentials through dedicated commands.
        path in setOf(
            "/admin/api/instagram/callback",
            "/admin/api/instagram/deauthorize",
            "/admin/api/instagram/data-deletion",
        ) -> false
        path.startsWith("/app/api/") || path.startsWith("/admin/api/") -> true
        path.startsWith("/app/share/") -> path.count { it == '/' } >= 4 &&
            !path.startsWith("/app/share/assets/") && path != "/app/share/change/{token}"
        else -> path in setOf("/health", "/ready", "/app/auth/config", "/admin/auth/config")
    }
}
