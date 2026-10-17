package com.rfm.edubot.dashboard

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The /app page's own scripts are classic scripts sharing one global scope (see app/core.js). index.html
 * has to load every one of them, a second top-level `const` with a name another script declared stops
 * that whole script, and a second `function` silently replaces the first.
 */
class DashboardScriptsTest {
    private val dir: Path = Paths.get(requireNotNull(javaClass.classLoader.getResource("app/index.html")).toURI()).parent
    private val tags = Regex("""<script src="/app/([^"]+)"([^>]*)></script>""").findAll(dir.resolve("index.html").readText()).toList()
    private val loaded = tags.map { it.groupValues[1] }
    private val declarations = Regex("""^(?:async\s+)?function\*?\s+([A-Za-z_$][\w$]*)|^(?:const|let|var|class)\s+([A-Za-z_$][\w$]*)""", RegexOption.MULTILINE)
    private val declarationStart = Regex("""^(?:async\s+)?function\b|^(?:const|let|var|class)\b""")
    private val statementStart = Regex("""^[A-Za-z_$(].*""", RegexOption.MULTILINE)

    private fun source(file: String) = dir.resolve(file).readText()

    /** Top-level names, found as the scripts write them: declarations start at column 0, nested code is indented. */
    private fun declared(file: String) = declarations.findAll(source(file)).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()

    @Test
    fun `index html loads every app script once, deferred, with app js last`() {
        val onDisk = dir.listDirectoryEntries("*.js").map { it.name }.toSet()
        assertEquals(emptySet(), onDisk - loaded.toSet(), "app/*.js that index.html doesn't load")
        assertEquals(emptySet(), loaded.toSet() - onDisk, "scripts index.html loads that aren't in app/")
        assertEquals(loaded.distinct(), loaded, "index.html loads a script twice")
        assertEquals(emptyList(), tags.filter { it.groupValues[2].trim() != "defer" }.map { it.groupValues[1] }, "scripts not loaded with defer")
        assertEquals("app.js", loaded.last(), "app.js loads last, since its init() starts the page")
    }

    @Test
    fun `no two app scripts declare the same top-level name`() {
        val clashes = loaded.flatMap { file -> declared(file).map { it to file } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
        assertEquals(emptyMap(), clashes, "top-level names declared by more than one script")
    }

    @Test
    fun `the shared app scripts only declare at their top level, and app js ends by calling init`() {
        val shared = loaded.filter { declared(it).isNotEmpty() }
        assertTrue("core.js" in shared && "app.js" in shared, "found the shared scripts: $shared")
        val stray = shared.flatMap { file ->
            statementStart.findAll(source(file)).map { it.value }.filterNot { declarationStart.containsMatchIn(it) }.map { "$file: $it" }.toList()
        }
        assertEquals(listOf("app.js: init();"), stray, "top-level code outside a declaration; wire things up in app.js's init()")
    }
}
