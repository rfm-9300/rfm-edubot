package com.rfm.edubot.dashboard

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Each dashboard page (/app, /backoffice) runs its own scripts and the shared /admin/common.js as classic scripts
 * in one global scope (see app/core.js). index.html has to load every one of them, a second top-level `const`
 * with a name another script declared stops that whole script, and a second `function` silently replaces the first.
 */
class DashboardScriptsTest {
    private val resources: Path = Paths.get(requireNotNull(javaClass.classLoader.getResource("app/index.html")).toURI()).parent.parent
    private val pages = listOf("app", "backoffice")
    private val scriptTag = Regex("""<script src="/(app|backoffice|admin)/([^"]+)"([^>]*)></script>""")
    private val declarations = Regex("""^(?:async\s+)?function\*?\s+([A-Za-z_$][\w$]*)|^(?:const|let|var|class)\s+([A-Za-z_$][\w$]*)""", RegexOption.MULTILINE)
    private val declarationStart = Regex("""^(?:async\s+)?function\b|^(?:const|let|var|class)\b""")
    private val statementStart = Regex("""^[A-Za-z_$(].*""", RegexOption.MULTILINE)

    private data class Script(val folder: String, val name: String, val attributes: String) {
        val path get() = "$folder/$name"
    }

    private fun scripts(page: String) = scriptTag.findAll(resources.resolve("$page/index.html").readText())
        .map { Script(it.groupValues[1], it.groupValues[2], it.groupValues[3].trim()) }.toList()

    /** The page's own scripts and the /admin ones it defers; theme.js, i18n.js and the catalogs load in <head> and keep to themselves. */
    private fun sharedScope(page: String) = scripts(page).filter { it.folder == page || it.attributes == "defer" }

    private fun source(path: String) = resources.resolve(path).readText()

    /** Top-level names, found as the scripts write them: declarations start at column 0, nested code is indented. */
    private fun declared(path: String) = declarations.findAll(source(path)).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()

    @Test
    fun `each page loads every one of its scripts once, deferred, with app js last`() {
        val problems = pages.flatMap { page ->
            val own = scripts(page).filter { it.folder == page }
            val loaded = own.map { it.name }
            val onDisk = resources.resolve(page).listDirectoryEntries("*.js").map { it.name }.toSet()
            buildList {
                (onDisk - loaded.toSet()).sorted().forEach { add("$page/index.html doesn't load $page/$it") }
                scripts(page).filterNot { resources.resolve(it.path).exists() }.forEach { add("$page/index.html loads ${it.path}, which isn't there") }
                loaded.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { add("$page/index.html loads $page/$it twice") }
                own.filter { it.attributes != "defer" }.forEach { add("$page/index.html loads ${it.path} without defer") }
                if (loaded.lastOrNull() != "app.js") add("$page/app.js must load last: its init() starts the page")
                if (sharedScope(page).none { it.path == "admin/common.js" }) add("$page/index.html must load /admin/common.js with defer")
            }
        }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `no two scripts in a page's scope declare the same top-level name`() {
        val clashes = pages.flatMap { page ->
            sharedScope(page).flatMap { script -> declared(script.path).map { it to script.path } }
                .groupBy({ it.first }, { it.second })
                .filterValues { it.size > 1 }
                .map { (name, paths) -> "/$page: $name is declared by ${paths.joinToString()}" }
        }
        assertEquals(emptyList(), clashes)
    }

    @Test
    fun `the shared scripts only declare at their top level, and app js ends by calling init`() {
        val problems = pages.flatMap { page ->
            val shared = sharedScope(page).filter { declared(it.path).isNotEmpty() }.map { it.path }
            val stray = shared.flatMap { path ->
                statementStart.findAll(source(path)).map { it.value }.filterNot { declarationStart.containsMatchIn(it) }.map { "$path: $it" }.toList()
            }
            buildList {
                if ("$page/core.js" !in shared || "$page/app.js" !in shared) add("found no declarations in /$page's core.js or app.js")
                if ("$page/app.js: init();" !in stray) add("$page/app.js must end by calling init()")
                addAll(stray.filter { it != "$page/app.js: init();" }.map { "top-level code outside a declaration (wire it up in init()): $it" })
            }
        }.distinct()
        assertEquals(emptyList(), problems)
    }
}
