package com.rfm.edubot.building

import com.rfm.edubot.ai.AiClient
import com.rfm.edubot.ai.AiResponse
import com.rfm.edubot.ai.ChatMessage
import com.rfm.edubot.ai.TenantUsageRepository
import com.rfm.edubot.ai.UsageSources
import com.rfm.edubot.building.model.ChangeOrder
import com.rfm.edubot.building.model.ChangeOrderStatus
import com.rfm.edubot.building.model.Project
import com.rfm.edubot.building.model.ProjectReport
import com.rfm.edubot.building.model.SiteLogEntry
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantLocales
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import org.bson.types.ObjectId
import kotlin.math.roundToInt

/**
 * A project's updates for its client: the AI drafts one for a period, which the team edits and posts to the client's
 * page, edits later or removes. The draft reads only what the client may read (the diary days shared with them, the
 * phases' progress, the changes they approved), within the company's monthly token budget, counted as Building's.
 */
class ProjectReportService(private val records: BuildingRecords, private val ai: AiClient, private val usage: TenantUsageRepository) {
    /** [sharedDays] is how many diary days shown to the client the draft was written from. */
    data class Draft(val from: LocalDate, val to: LocalDate, val text: String, val sharedDays: Int)

    /**
     * A draft for the period from [from] to [to] (the 7 days to today when null). Refused when the period has
     * nothing to tell, past the token budget, or when the model doesn't answer ([AI_UNAVAILABLE]).
     */
    suspend fun draft(projectId: ObjectId, from: LocalDate?, to: LocalDate?, tenant: Tenant, stamp: Stamp): Outcome<Draft> {
        val project = records.projects.findById(projectId) ?: return Outcome.NotFound
        val (start, end) = period(from, to, stamp.today).orElse { return it.refused() }
        val entries = records.logs.list(project.id, start, end).sortedBy { it.day }
        val shared = entries.filter { it.shared }
        val changes = records.changes.list(project.id, listOf(ChangeOrderStatus.APPROVED))
        val approved = changes.filter { change -> change.decidedAt?.let { stamp.day(it) in start..end } == true }
        val moved = phaseMoves(project, entries)
        if (shared.isEmpty() && moved.isEmpty() && approved.isEmpty()) return conflict("nothing_to_report")
        if (usage.tokensUsedThisMonth() >= tenant.monthlyTokenBudget) return conflict("token_budget")
        val messages = listOf(
            ChatMessage(role = "system", content = instructions(tenant)),
            ChatMessage(role = "user", content = facts(project, start, end, shared, moved, approved, approvedEffect(changes).days)),
        )
        val response = try {
            ai.complete(messages, modelOverride = tenant.openrouterModel)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return conflict(AI_UNAVAILABLE)
        }
        val spent = when (response) {
            is AiResponse.Text -> response.usage
            is AiResponse.ToolUse -> response.usage
        }
        usage.recordUsage((spent?.total_tokens ?: 0).toLong(), UsageSources.BUILDING)
        val text = (response as? AiResponse.Text)?.content?.trim()?.takeIf { it.isNotEmpty() } ?: return conflict(AI_UNAVAILABLE)
        return Outcome.Done(Draft(start, end, text.take(BuildingLimits.TEXT), shared.size))
    }

    /** Posts [text] about the period to the client's page. */
    suspend fun post(projectId: ObjectId, from: LocalDate?, to: LocalDate?, text: String?, stamp: Stamp): Outcome<ProjectReport> {
        val project = records.projects.findById(projectId) ?: return Outcome.NotFound
        val (start, end) = period(from, to, stamp.today).orElse { return it.refused() }
        val body = body(text).orElse { return it.refused() }
        val report = ProjectReport(
            tenantId = records.tenantId, projectId = project.id, from = start, to = end, text = body,
            postedBy = stamp.who, postedAt = stamp.now, updatedAt = stamp.now,
        )
        records.reports.insert(report)
        return Outcome.Done(report)
    }

    suspend fun edit(id: ObjectId, text: String?, stamp: Stamp): Outcome<ProjectReport> {
        val report = records.reports.findById(id) ?: return Outcome.NotFound
        val body = body(text).orElse { return it.refused() }
        return records.reports.replace(report.copy(text = body, updatedAt = stamp.now))?.let { Outcome.Done(it) } ?: Outcome.NotFound
    }

    suspend fun delete(id: ObjectId): Outcome<Unit> = if (records.reports.delete(id)) Outcome.Done(Unit) else Outcome.NotFound

    private fun period(from: LocalDate?, to: LocalDate?, today: LocalDate): Parsed<Pair<LocalDate, LocalDate>> {
        val end = to ?: today
        val start = from ?: end.minus(DEFAULT_DAYS - 1, DateTimeUnit.DAY)
        if (end < start) return Parsed.Bad("invalid_range")
        if (start.daysUntil(end) >= MAX_DAYS) return Parsed.Bad("range_too_long")
        return Parsed.Good(start to end)
    }

    private fun body(text: String?): Parsed<String> {
        val body = text?.trim().orEmpty()
        if (body.isEmpty()) return Parsed.Bad("text_required", "text")
        if (body.length > BuildingLimits.TEXT) return Parsed.Bad("text_too_long", "text")
        return Parsed.Good(body)
    }

    private class Move(val name: String, val from: Int, val to: Int)

    /** Each phase the period's diary moved, from where its first entry found it to where its last one left it. */
    private fun phaseMoves(project: Project, entries: List<SiteLogEntry>): List<Move> =
        entries.flatMap { it.progress }.groupBy { it.phaseId }.mapNotNull { (id, moves) ->
            val name = project.phases.firstOrNull { it.id == id }?.name ?: moves.last().phaseName
            Move(name, moves.first().fromPct, moves.last().toPct).takeIf { it.from != it.to }
        }

    private fun instructions(tenant: Tenant): String {
        val language = when (TenantLocales.normalize(tenant.locale)) {
            "en" -> "English"
            "es" -> "Spanish (Spain)"
            else -> "European Portuguese (Portugal)"
        }
        val company = tenant.documentTemplate.companyName.ifBlank { tenant.name }
        return """
            You write the update a construction company sends the client of a project about a period of the work.
            Write in $language, as the company "$company" writing to its client: warm, clear and brief, 60 to 160 words.
            Say what was done in the period, how far the work is and which phases moved, and what comes next when the facts show it. Mention a change the client approved when it moves the completion date.
            Use only the facts given. Never mention prices or costs, problems on site, delays, or anyone's name.
            Plain text in short paragraphs: no title, no markdown, nothing left to fill in. Sign off with the company's name.
        """.trimIndent()
    }

    private fun facts(project: Project, start: LocalDate, end: LocalDate, shared: List<SiteLogEntry>, moved: List<Move>, approved: List<ChangeOrder>, approvedDays: Int) =
        buildString {
            appendLine("Project: ${project.name}")
            project.address?.let { appendLine("Site: $it") }
            appendLine("Period: $start to $end")
            appendLine("Overall progress now: ${ProjectProgress.actualPct(project).roundToInt()}%")
            ProjectProgress.revisedEnd(project, approvedDays)?.let { appendLine("Planned completion: $it") }
            if (project.phases.isNotEmpty()) {
                appendLine("Phases and their progress now:")
                project.phases.forEach { appendLine("- ${it.name}: ${it.progressPct}%") }
            }
            if (moved.isNotEmpty()) {
                appendLine("Phases that moved in the period:")
                moved.forEach { appendLine("- ${it.name}: from ${it.from}% to ${it.to}%") }
            }
            if (shared.isNotEmpty()) {
                appendLine("Work done, from the site diary days shown to the client:")
                shared.forEach { appendLine("- ${it.day}: ${it.workDone.replace(Regex("\\s+"), " ")}") }
            }
            if (approved.isNotEmpty()) {
                appendLine("Changes the client approved in the period:")
                approved.forEach { change ->
                    val days = if (change.daysImpact != 0) " (${if (change.daysImpact > 0) "+" else ""}${change.daysImpact} days on the completion date)" else ""
                    appendLine("- ${change.title}$days")
                }
            }
        }

    companion object {
        /** The model didn't answer, or answered nothing: the route says 502. */
        const val AI_UNAVAILABLE = "ai_unavailable"
        const val DEFAULT_DAYS = 7
        const val MAX_DAYS = 92
    }
}
