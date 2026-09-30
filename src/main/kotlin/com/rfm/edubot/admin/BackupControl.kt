package com.rfm.edubot.admin

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The backoffice's side of manual backups. The app never runs a backup itself: it leaves
 * `request.json` in [controlDir], and the host's `backup-runner.sh` (cron, every minute) claims it,
 * runs `backup-mongo.sh` and leaves the outcome next to it:
 * - `request.json`: written here; the runner renames it to `running.json` when it starts
 * - `last-request.json` and `last.json` (`id`, `exitCode`, `startedAt`, `finishedAt`, `archive`): the last finished run
 * - `last.log`: that run's output
 * - `heartbeat`: touched on every check, so a runner that stopped shows up here
 *
 * Archives are listed from [archiveDir], mounted read-only; `<archive>.uploaded` marks the off-box copy.
 */
class BackupControl(
    archiveDir: String,
    controlDir: String,
    private val clock: () -> Instant = { Clock.System.now() },
) {
    object Unavailable : Exception("backups_unavailable")
    object Busy : Exception("backup_in_progress")

    private val archives = archiveDir.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    private val control = controlDir.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val available: Boolean
        get() = archives?.isDirectory() == true && control?.isDirectory() == true && Files.isWritable(control)

    fun status(): BackupStatusDto {
        if (!available) return BackupStatusDto(available = false, runner = null, current = null, archives = emptyList())
        val lastSeen = mtime(file(HEARTBEAT))
        return BackupStatusDto(
            available = true,
            runner = RunnerDto(lastSeenAt = lastSeen?.toString(), online = lastSeen != null && clock() - lastSeen < RUNNER_TIMEOUT),
            current = currentRun(),
            archives = listArchives(),
        )
    }

    /** @throws Unavailable when the mounts are missing, [Busy] while a request waits or a run is going. */
    @Synchronized
    fun request(requestedBy: String?): BackupStatusDto {
        if (!available) throw Unavailable
        val running = mtime(file(RUNNING))
        if (file(REQUEST).exists() || (running != null && clock() - running < STALE_RUN)) throw Busy
        val request = RequestFile(id = UUID.randomUUID().toString().replace("-", ""), requestedBy = requestedBy, requestedAt = clock().toString())
        val tmp = file("$REQUEST.tmp")
        Files.writeString(tmp, json.encodeToString(RequestFile.serializer(), request))
        Files.move(tmp, file(REQUEST), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return status()
    }

    private fun currentRun(): BackupRunDto? {
        readRequest(REQUEST)?.let { return it.run("requested") }
        readRequest(RUNNING)?.let { request ->
            val started = mtime(file(RUNNING))
            val stalled = started != null && clock() - started >= STALE_RUN
            return request.run(if (stalled) "stalled" else "running", startedAt = started?.toString())
        }
        val last = runCatching { json.decodeFromString(LastFile.serializer(), Files.readString(file(LAST))) }.getOrNull() ?: return null
        val request = readRequest(LAST_REQUEST)
        val failed = last.exitCode != 0
        return BackupRunDto(
            state = if (failed) "failed" else "succeeded",
            requestedBy = request?.requestedBy,
            requestedAt = request?.requestedAt,
            startedAt = last.startedAt,
            finishedAt = last.finishedAt,
            archive = last.archive.ifBlank { null },
            log = if (failed) logTail() else null,
        )
    }

    private fun listArchives(): List<BackupArchiveDto> =
        Files.list(archives!!).use { files -> files.filter { ARCHIVE.matches(it.name) }.toList() }
            .sortedByDescending { it.name }
            .take(MAX_LISTED)
            .map { path ->
                BackupArchiveDto(
                    name = path.name,
                    createdAt = createdAt(path),
                    sizeBytes = Files.size(path),
                    uploaded = path.resolveSibling("${path.name}.uploaded").exists(),
                )
            }

    private fun createdAt(path: Path): String {
        val stamp = ARCHIVE.matchEntire(path.name)!!.groupValues[1]
        return runCatching { LocalDateTime.parse(stamp, STAMP).toInstant(ZoneOffset.UTC).toString() }
            .getOrElse { Files.getLastModifiedTime(path).toInstant().toString() }
    }

    private fun logTail(): String? = runCatching {
        Files.readAllLines(file(LOG)).takeLast(LOG_LINES).joinToString("\n").ifBlank { null }
    }.getOrNull()

    private fun readRequest(name: String): RequestFile? =
        runCatching { json.decodeFromString(RequestFile.serializer(), Files.readString(file(name))) }.getOrNull()

    private fun RequestFile.run(state: String, startedAt: String? = null) = BackupRunDto(
        state = state, requestedBy = requestedBy, requestedAt = requestedAt,
        startedAt = startedAt, finishedAt = null, archive = null, log = null,
    )

    private fun file(name: String): Path = control!!.resolve(name)

    private fun mtime(path: Path): Instant? =
        if (path.exists()) Instant.fromEpochMilliseconds(Files.getLastModifiedTime(path).toMillis()) else null

    @Serializable
    private data class RequestFile(val id: String, val requestedBy: String? = null, val requestedAt: String)

    @Serializable
    private data class LastFile(val exitCode: Int, val startedAt: String? = null, val finishedAt: String? = null, val archive: String = "")

    companion object {
        private const val REQUEST = "request.json"
        private const val RUNNING = "running.json"
        private const val LAST = "last.json"
        private const val LAST_REQUEST = "last-request.json"
        private const val LOG = "last.log"
        private const val HEARTBEAT = "heartbeat"

        /** Cron checks every minute; three missed checks and the runner counts as stopped. */
        val RUNNER_TIMEOUT: Duration = 3.minutes

        /** A run this old never finished (the runner died mid-backup), so it no longer blocks a new request. */
        val STALE_RUN: Duration = 2.hours

        private const val MAX_LISTED = 30
        private const val LOG_LINES = 40
        private val ARCHIVE = Regex("""mongo-(\d{8}T\d{6}Z)\.archive\.gz""")
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    }
}

@Serializable
data class BackupStatusDto(
    /** False when the archive or control directory isn't mounted (local dev, or the server isn't set up). */
    val available: Boolean,
    val runner: RunnerDto?,
    /** The last backup requested from the backoffice: waiting, running or finished. */
    val current: BackupRunDto?,
    /** Newest first; nightly and manual backups alike. */
    val archives: List<BackupArchiveDto>,
)

@Serializable
data class RunnerDto(val lastSeenAt: String?, val online: Boolean)

@Serializable
data class BackupRunDto(
    /** requested, running, succeeded, failed, or stalled (claimed but never finished; a new one can start). */
    val state: String,
    val requestedBy: String?,
    val requestedAt: String?,
    val startedAt: String?,
    val finishedAt: String?,
    val archive: String?,
    /** The end of the run's output, only when it failed. */
    val log: String?,
)

@Serializable
data class BackupArchiveDto(val name: String, val createdAt: String, val sizeBytes: Long, val uploaded: Boolean)
