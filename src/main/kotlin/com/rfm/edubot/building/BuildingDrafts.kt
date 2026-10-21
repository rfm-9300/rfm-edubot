package com.rfm.edubot.building

import com.rfm.edubot.building.model.ChangeOrder
import com.rfm.edubot.building.model.ChangeOrderKind
import com.rfm.edubot.building.model.ContractLine
import com.rfm.edubot.building.model.IssueStatus
import com.rfm.edubot.building.model.ProjectPhase
import com.rfm.edubot.building.model.SiteIssue
import com.rfm.edubot.building.model.SiteLogEntry
import com.rfm.edubot.building.model.Weather
import com.rfm.edubot.crm.eurToCents
import com.rfm.edubot.crm.model.LineItem
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.bson.types.ObjectId
import java.security.SecureRandom
import java.util.Base64
import kotlin.math.abs
import kotlin.math.roundToLong

// What staff type for each Building record. On an edit an omitted (null) field keeps what is stored and an empty
// string clears it; the services check what needs a lookup (a client, an employee, a phase of the project).

data class ContractLineDraft(
    /** A line of the stored contract keeps its id, so measurements still find it. */
    val id: String? = null,
    val description: String,
    val quantity: Double,
    val unit: String,
    val unitPriceEur: Double,
)

data class ProjectDraft(
    val name: String? = null,
    val status: String? = null,
    val clientId: String? = null,
    val managerId: String? = null,
    val address: String? = null,
    val description: String? = null,
    val plannedStart: String? = null,
    val plannedEnd: String? = null,
    val actualStart: String? = null,
    val actualEnd: String? = null,
    val retentionPct: Double? = null,
    val manualProgressPct: Int? = null,
    val contract: List<ContractLineDraft>? = null,
)

data class PhaseDraft(
    val id: String? = null,
    val name: String,
    val plannedStart: String? = null,
    val plannedEnd: String? = null,
    val progressPct: Int = 0,
    val weight: Int? = null,
)

data class PhaseProgressDraft(val phaseId: String, val progressPct: Int)

data class SiteLogDraft(
    /** The company's day the entry is of: today on a new entry, the stored one on an edit, when null. */
    val day: LocalDate? = null,
    val weather: String? = null,
    val temperatureC: Int? = null,
    val workers: Int? = null,
    val workDone: String? = null,
    val equipment: String? = null,
    val materials: String? = null,
    val visitors: String? = null,
    val incidents: String? = null,
    val lostHours: Double? = null,
    val shared: Boolean? = null,
    /** Phases the entry moves, to these percentages; null leaves the phases and what the entry recorded. */
    val phaseProgress: List<PhaseProgressDraft>? = null,
    /** [temperatureC], [workers] or [lostHours] cleared on an edit, which a null can't say. */
    val clear: Set<String> = emptySet(),
)

data class IssueDraft(
    val title: String? = null,
    val description: String? = null,
    val location: String? = null,
    val kind: String? = null,
    val priority: String? = null,
    val status: String? = null,
    val phaseId: String? = null,
    /** One assignee: naming an employee drops the supplier, and the other way round. */
    val employeeId: String? = null,
    val supplierId: String? = null,
    val dueDate: String? = null,
    /** Kept in the history with a status change. */
    val note: String? = null,
)

data class LineDraft(val description: String, val quantity: Double, val unit: String, val unitPriceEur: Double)

data class ChangeOrderDraft(
    val title: String? = null,
    val description: String? = null,
    val kind: String? = null,
    val items: List<LineDraft>? = null,
    val daysImpact: Int? = null,
)

internal sealed interface Parsed<out T> {
    data class Good<T>(val value: T) : Parsed<T>
    data class Bad(val error: String, val detail: String? = null) : Parsed<Nothing>
}

internal inline fun <T> Parsed<T>.orElse(fail: (Parsed.Bad) -> Nothing): T = when (this) {
    is Parsed.Good -> value
    is Parsed.Bad -> fail(this)
}

internal fun Parsed.Bad.refused() = invalid(error, detail)

internal object BuildingLimits {
    const val NAME = 160
    const val ADDRESS = 300
    const val TEXT = 4000
    const val NOTE = 1000
    const val CAPTION = 300
    const val PHASES = 60
    const val PHASE_NAME = 120
    const val CONTRACT_LINES = 300
    const val LINE_DESCRIPTION = 300
    const val UNIT = 20
    const val ITEMS = 100
    const val RETENTION_PCT = 20.0
    const val WEIGHT = 10_000
    const val QUANTITY = 1_000_000_000.0
    const val PRICE_EUR = 100_000_000.0
    const val DAYS_IMPACT = 730
}

internal inline fun <reified E : Enum<E>> enumOf(value: String?): E? =
    value?.trim()?.uppercase()?.let { raw -> enumValues<E>().firstOrNull { it.name == raw } }

internal fun newLineId(): String = ObjectId().toHexString()

private val random = SecureRandom()

/** A link only its holder can open: 32 random bytes, base64url. */
internal fun secretToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

internal fun objectIdOrNull(raw: String): ObjectId? = raw.takeIf(ObjectId::isValid)?.let(::ObjectId)

/** A text field of an edit: null keeps [current], blank clears it. */
internal fun text(value: String?, current: String?, max: Int, tooLong: String): Parsed<String?> {
    if (value == null) return Parsed.Good(current)
    val trimmed = value.trim()
    return if (trimmed.length > max) Parsed.Bad(tooLong) else Parsed.Good(trimmed.ifEmpty { null })
}

/** A date field of an edit: null keeps [current], blank clears it. */
internal fun date(value: String?, current: LocalDate?, field: String): Parsed<LocalDate?> {
    if (value == null) return Parsed.Good(current)
    if (value.isBlank()) return Parsed.Good(null)
    return runCatching { LocalDate.parse(value.trim()) }.getOrNull()?.let { Parsed.Good(it) } ?: Parsed.Bad("invalid_date", field)
}

/** A phase moved to [progress]: it started the first day it moved, and ended the day it reached 100%. */
internal fun ProjectPhase.withProgress(progress: Int, day: LocalDate): ProjectPhase = copy(
    progressPct = progress,
    actualStart = actualStart ?: day.takeIf { progress > 0 },
    actualEnd = if (progress >= 100) actualEnd ?: day else null,
)

internal fun LineItem.toContractLine(id: String) = ContractLine(id, description, quantity, unit, unitPriceCents, totalCents)

/** Lines typed for a contract; a line keeps its id when it names one of [current]'s, so measurements still find it. */
internal fun contractFrom(lines: List<ContractLineDraft>, current: List<ContractLine>): Parsed<List<ContractLine>> {
    if (lines.size > BuildingLimits.CONTRACT_LINES) return Parsed.Bad("too_many_lines")
    val known = current.map { it.id }.toSet()
    val used = mutableSetOf<String>()
    return Parsed.Good(
        lines.map { line ->
            val description = line.description.trim()
            when {
                description.isEmpty() -> return Parsed.Bad("line_description_required")
                description.length > BuildingLimits.LINE_DESCRIPTION -> return Parsed.Bad("line_description_too_long")
                line.unit.trim().length > BuildingLimits.UNIT -> return Parsed.Bad("line_unit_too_long")
                !line.quantity.isFinite() || line.quantity <= 0 || line.quantity > BuildingLimits.QUANTITY -> return Parsed.Bad("invalid_quantity")
                !line.unitPriceEur.isFinite() || abs(line.unitPriceEur) > BuildingLimits.PRICE_EUR -> return Parsed.Bad("invalid_price")
            }
            val id = line.id?.takeIf { it in known && used.add(it) } ?: newLineId().also { used.add(it) }
            val unitPriceCents = eurToCents(line.unitPriceEur)
            ContractLine(id, description, line.quantity, line.unit.trim(), unitPriceCents, (line.quantity * unitPriceCents).roundToLong())
        },
    )
}

/** The phases typed in the editor, keeping each known phase's id and actual dates. */
internal fun phasesFrom(drafts: List<PhaseDraft>, current: List<ProjectPhase>, today: LocalDate): Parsed<List<ProjectPhase>> {
    if (drafts.size > BuildingLimits.PHASES) return Parsed.Bad("too_many_phases")
    val byId = current.associateBy { it.id }
    val used = mutableSetOf<String>()
    return Parsed.Good(
        drafts.map { draft ->
            val name = draft.name.trim()
            if (name.isEmpty()) return Parsed.Bad("phase_name_required")
            if (name.length > BuildingLimits.PHASE_NAME) return Parsed.Bad("phase_name_too_long")
            val start = date(draft.plannedStart, null, "plannedStart").orElse { return it }
            val end = date(draft.plannedEnd, null, "plannedEnd").orElse { return it }
            if (start != null && end != null && end < start) return Parsed.Bad("invalid_dates")
            if (draft.progressPct !in 0..100) return Parsed.Bad("invalid_progress")
            if (draft.weight != null && draft.weight !in 1..BuildingLimits.WEIGHT) return Parsed.Bad("invalid_weight")
            val existing = draft.id?.let { byId[it] }?.takeIf { used.add(it.id) }
            val phase = existing ?: ProjectPhase(id = newLineId().also { used.add(it) }, name = name)
            phase.copy(name = name, plannedStart = start, plannedEnd = end, weight = draft.weight)
                .let { if (it.progressPct != draft.progressPct || existing == null) it.withProgress(draft.progressPct, today) else it }
        },
    )
}

/** [draft] over [base], every field but the day and the phases' progress, which the diary sets itself. */
internal fun logFrom(draft: SiteLogDraft, base: SiteLogEntry): Parsed<SiteLogEntry> {
    val workDone = draft.workDone?.trim() ?: base.workDone
    if (workDone.isEmpty()) return Parsed.Bad("work_required")
    if (workDone.length > BuildingLimits.TEXT) return Parsed.Bad("work_too_long")
    val weather = when (val raw = draft.weather?.trim()) {
        null -> base.weather
        "" -> null
        else -> enumOf<Weather>(raw) ?: return Parsed.Bad("invalid_weather")
    }
    val temperature = if ("temperatureC" in draft.clear) null else draft.temperatureC ?: base.temperatureC
    if (temperature != null && temperature !in -50..60) return Parsed.Bad("invalid_temperature")
    val workers = if ("workers" in draft.clear) null else draft.workers ?: base.workers
    if (workers != null && workers !in 0..10_000) return Parsed.Bad("invalid_workers")
    val lost = if ("lostHours" in draft.clear) null else draft.lostHours ?: base.lostHours
    if (lost != null && (!lost.isFinite() || lost < 0 || lost > 10_000)) return Parsed.Bad("invalid_hours")
    fun more(value: String?, current: String?) = text(value, current, BuildingLimits.TEXT, "text_too_long")
    return Parsed.Good(
        base.copy(
            weather = weather,
            temperatureC = temperature,
            workers = workers,
            workDone = workDone,
            equipment = more(draft.equipment, base.equipment).orElse { return it },
            materials = more(draft.materials, base.materials).orElse { return it },
            visitors = more(draft.visitors, base.visitors).orElse { return it },
            incidents = more(draft.incidents, base.incidents).orElse { return it },
            lostHours = lost,
            shared = draft.shared ?: base.shared,
        ),
    )
}

/** When it was resolved and closed, following [status]: reopening clears them. */
internal fun SiteIssue.stamped(status: IssueStatus, now: Instant): SiteIssue = when (status) {
    IssueStatus.OPEN, IssueStatus.IN_PROGRESS -> copy(resolvedAt = null, closedAt = null)
    IssueStatus.RESOLVED -> copy(resolvedAt = resolvedAt ?: now, closedAt = null)
    IssueStatus.CLOSED -> copy(resolvedAt = resolvedAt ?: now, closedAt = closedAt ?: now)
}

internal fun changeFrom(draft: ChangeOrderDraft, base: ChangeOrder): Parsed<ChangeOrder> {
    val title = draft.title?.trim() ?: base.title
    if (draft.title != null && title.isEmpty()) return Parsed.Bad("title_required")
    if (title.length > BuildingLimits.NAME) return Parsed.Bad("title_too_long")
    val description = text(draft.description, base.description, BuildingLimits.TEXT, "description_too_long").orElse { return it }
    val kind = draft.kind?.let { enumOf<ChangeOrderKind>(it) ?: return Parsed.Bad("invalid_kind") } ?: base.kind
    val days = draft.daysImpact ?: base.daysImpact
    if (abs(days) > BuildingLimits.DAYS_IMPACT) return Parsed.Bad("invalid_days")
    val items = draft.items?.let { lines ->
        if (lines.size > BuildingLimits.ITEMS) return Parsed.Bad("too_many_items")
        lines.map { line -> line.error()?.let { return Parsed.Bad(it) } ?: line.toLineItem() }
    } ?: base.items
    return Parsed.Good(base.copy(title = title, description = description, kind = kind, daysImpact = days, items = items, totalCents = items.sumOf { it.totalCents }))
}

private fun LineDraft.error(): String? = when {
    description.isBlank() -> "item_description_required"
    description.trim().length > BuildingLimits.LINE_DESCRIPTION -> "item_description_too_long"
    unit.trim().length > BuildingLimits.UNIT -> "item_unit_too_long"
    !quantity.isFinite() || quantity <= 0 || quantity > BuildingLimits.QUANTITY -> "invalid_quantity"
    !unitPriceEur.isFinite() || unitPriceEur < 0 || unitPriceEur > BuildingLimits.PRICE_EUR -> "invalid_price"
    else -> null
}

private fun LineDraft.toLineItem(): LineItem {
    val unitPriceCents = eurToCents(unitPriceEur)
    return LineItem(description.trim(), quantity, unit.trim(), unitPriceCents, (quantity * unitPriceCents).roundToLong())
}
