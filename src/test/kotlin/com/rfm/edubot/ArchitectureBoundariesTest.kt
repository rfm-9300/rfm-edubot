package com.rfm.edubot

import com.rfm.edubot.dashboard.DashboardFeatures
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The layering the backend keeps (route → service → repository), checked on the sources. Each rule
 * names the files that break it, so a change that crosses a boundary fails here with the file to fix.
 */
class ArchitectureBoundariesTest {

    private val main = File("src/main/kotlin/com/rfm/edubot")

    private fun sources(dir: String): List<File> =
        main.resolve(dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()

    private fun File.path(): String = relativeTo(main).invariantSeparatorsPath

    private fun File.imports(): List<String> = readLines().filter { it.startsWith("import ") }.map { it.removePrefix("import ").trim() }

    @Test
    fun `CRM services and repositories don't know about HTTP`() {
        val domain = sources("crm").filterNot { it.path().startsWith("crm/api/") }
        assertTrue(domain.size > 10)
        val offenders = domain.filter { file -> file.imports().any { it.startsWith("io.ktor.server.") } }
        assertEquals(emptyList(), offenders.map { it.path() }, "requests, DTOs and responses belong in crm/api and the route files")
    }

    @Test
    fun `the HTTP error contract depends on no feature`() {
        val offenders = sources("http").filter { file -> file.imports().any { it.startsWith("com.rfm.edubot.") } }
        assertEquals(emptyList(), offenders.map { it.path() })
    }

    @Test
    fun `dashboard route files reach MongoDB through repositories and services`() {
        val routes = sources("dashboard").filter { it.name.endsWith("Routes.kt") || it.name.endsWith("RouteSupport.kt") }
        assertTrue(routes.size > 20)
        val offenders = routes.filter { "getCollection" in it.readText() }
        assertEquals(emptyList(), offenders.map { it.path() }, "query collections in a repository, or in a service for reports like Home")
    }

    @Test
    fun `every dashboard feature is mounted by the registry`() {
        val declared = sources("").flatMap { file -> FEATURE.findAll(file.readText()).map { it.groupValues[1] }.toList() }.toSet()
        val mounted = DashboardFeatures.all.map { it::class.simpleName!! }.toSet()
        assertEquals(declared, mounted, "add each DashboardFeature to DashboardFeatures.all")
    }

    private companion object {
        val FEATURE = Regex("""object (\w+) : DashboardFeature\(""")
    }
}
