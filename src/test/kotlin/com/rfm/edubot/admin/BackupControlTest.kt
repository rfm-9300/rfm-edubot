package com.rfm.edubot.admin

import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** The file handshake between the backoffice and the host's backup-runner.sh. */
class BackupControlTest {
    @TempDir
    lateinit var root: Path

    private val now = Instant.parse("2026-09-30T10:00:00Z")
    private lateinit var archives: Path
    private lateinit var control: Path

    @BeforeEach
    fun dirs() {
        archives = root.resolve("backups").createDirectories()
        control = root.resolve("control").createDirectories()
    }

    private fun backups() = BackupControl(archives.toString(), control.toString(), clock = { now })

    private fun stamp(path: Path, at: Instant) {
        if (!path.exists()) path.writeText("")
        Files.setLastModifiedTime(path, FileTime.fromMillis(at.toEpochMilliseconds()))
    }

    @Test
    fun `without both mounts backups are unavailable and requests are refused`() {
        val missing = BackupControl(root.resolve("absent").toString(), control.toString(), clock = { now })
        assertFalse(missing.status().available)
        assertFailsWith<BackupControl.Unavailable> { missing.request("ops@acme.test") }
        assertFalse(BackupControl("", "", clock = { now }).status().available)
    }

    @Test
    fun `a request leaves request json for the runner and blocks another until it is claimed and finished`() {
        val status = backups().request("ops@acme.test")
        assertEquals("requested", status.current?.state)
        assertEquals("ops@acme.test", status.current?.requestedBy)

        val file = Json.parseToJsonElement(control.resolve("request.json").readText()).jsonObject
        assertEquals("ops@acme.test", file["requestedBy"]?.jsonPrimitive?.content)
        assertTrue(file["id"]!!.jsonPrimitive.content.isNotBlank())
        assertFalse(control.resolve("request.json.tmp").exists())

        assertFailsWith<BackupControl.Busy> { backups().request("other@acme.test") }
    }

    @Test
    fun `a claimed request runs from when the runner touched it, and stalls after two hours`() {
        val running = control.resolve("running.json")
        running.writeText("""{"id":"r1","requestedBy":"ops@acme.test","requestedAt":"2026-09-30T09:59:00Z"}""")
        stamp(running, now - 5.minutes)

        val current = backups().status().current!!
        assertEquals("running", current.state)
        assertEquals((now - 5.minutes).toString(), current.startedAt)
        assertEquals("ops@acme.test", current.requestedBy)
        assertFailsWith<BackupControl.Busy> { backups().request(null) }

        stamp(running, now - 3.hours)
        assertEquals("stalled", backups().status().current?.state)
        assertEquals("requested", backups().request(null).current?.state)
    }

    @Test
    fun `a finished run reports its archive, and a failed one the end of its output`() {
        control.resolve("last-request.json").writeText("""{"id":"r2","requestedBy":"ops@acme.test","requestedAt":"2026-09-30T09:00:00Z"}""")
        control.resolve("last.json").writeText(
            """{"id":"r2","exitCode":0,"startedAt":"2026-09-30T09:00:30Z","finishedAt":"2026-09-30T09:00:41Z","archive":"mongo-20260930T090031Z.archive.gz"}""",
        )
        val done = backups().status().current!!
        assertEquals("succeeded", done.state)
        assertEquals("mongo-20260930T090031Z.archive.gz", done.archive)
        assertEquals("ops@acme.test", done.requestedBy)
        assertEquals("2026-09-30T09:00:41Z", done.finishedAt)
        assertNull(done.log)

        control.resolve("last.json").writeText("""{"id":"r2","exitCode":1,"startedAt":"2026-09-30T09:00:30Z","finishedAt":"2026-09-30T09:00:33Z","archive":""}""")
        control.resolve("last.log").writeText((1..60).joinToString("\n") { "line $it" })
        val failed = backups().status().current!!
        assertEquals("failed", failed.state)
        assertNull(failed.archive)
        assertEquals(40, failed.log!!.lines().size)
        assertTrue(failed.log!!.endsWith("line 60"))
    }

    @Test
    fun `the runner counts as stopped after three minutes without a check`() {
        val never = backups().status().runner!!
        assertFalse(never.online)
        assertNull(never.lastSeenAt)

        stamp(control.resolve("heartbeat"), now - 1.minutes)
        assertTrue(backups().status().runner!!.online)

        stamp(control.resolve("heartbeat"), now - 4.minutes)
        assertFalse(backups().status().runner!!.online)
    }

    @Test
    fun `archives are listed newest first with their size and off-box copy, other files ignored`() {
        archives.resolve("mongo-20260929T031701Z.archive.gz").writeBytes(ByteArray(100))
        archives.resolve("mongo-20260930T031701Z.archive.gz").writeBytes(ByteArray(250))
        archives.resolve("mongo-20260930T031701Z.archive.gz.uploaded").writeText("")
        archives.resolve("mongo-20260930T090000Z.archive.gz.tmp").writeText("partial")
        archives.resolve("notes.txt").writeText("x")

        val listed = backups().status().archives
        assertEquals(listOf("mongo-20260930T031701Z.archive.gz", "mongo-20260929T031701Z.archive.gz"), listed.map { it.name })
        assertEquals(250L, listed[0].sizeBytes)
        assertTrue(listed[0].uploaded)
        assertFalse(listed[1].uploaded)
        assertEquals("2026-09-30T03:17:01Z", listed[0].createdAt)
    }
}
