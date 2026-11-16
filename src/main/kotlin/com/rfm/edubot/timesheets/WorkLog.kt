package com.rfm.edubot.timesheets

import com.rfm.edubot.timesheets.model.Job
import com.rfm.edubot.timesheets.model.JobNeeds
import com.rfm.edubot.timesheets.model.SegmentSource
import com.rfm.edubot.timesheets.model.Shift
import com.rfm.edubot.timesheets.model.ShiftSegment
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.minutes

/** A segment with where it ends (null: still running) and the minutes of work it holds. */
data class SegmentSpan(val segment: ShiftSegment, val startAt: Instant, val endAt: Instant?, val minutes: Int)

/** A segment as someone typed it: where it starts and its job. */
data class SegmentDraft(val startAt: Instant, val job: Job?)

/**
 * What a shift's working time was for. A shift's time is cut into consecutive segments, each with a job or none;
 * these are the pure rules for cutting, fitting and checking them. The repository stores what they return.
 */
object WorkLog {
    const val MAX_SEGMENTS = 30

    /** A switch this soon after the current segment began changes that segment's job: a wrong pick fixed at once. */
    private val SAME_SEGMENT = 1.minutes

    /**
     * The shift's segments with their ends and minutes; a shift without any reads as one segment without a job.
     * Minutes are cumulative (whole minutes worked up to the end, less whole minutes worked up to the start), so
     * they add up to the shift's worked minutes exactly.
     */
    fun spans(shift: Shift, now: Instant): List<SegmentSpan> {
        val segments = shift.segments.ifEmpty { listOf(ShiftSegment(shift.startAt)) }
        val times = shift.times
        return segments.mapIndexed { i, segment ->
            val start = if (i == 0) shift.startAt else segment.startAt
            val end = segments.getOrNull(i + 1)?.startAt ?: shift.endAt
            val minutes = ShiftMath.workedSecondsUpTo(times, end ?: now, now) / 60 - ShiftMath.workedSecondsUpTo(times, start, now) / 60
            SegmentSpan(segment, start, end, minutes.toInt().coerceAtLeast(0))
        }
    }

    /**
     * The first part [needs] asks for that [job] lacks: `job` when there is none (or only a note, which a report
     * can't count, unless notes are all the company asks for), then `client`, `project`, `activity`, `note`.
     */
    fun missing(job: Job?, needs: JobNeeds): String? {
        if (job == null) return "job"
        if (needs.client && job.clientId == null) return "client"
        if (needs.project && job.projectId == null) return "project"
        if (needs.activity && job.activityId == null) return "activity"
        if (needs.note && job.note.isNullOrBlank()) return "note"
        val named = job.clientId != null || job.projectId != null || job.activityId != null
        return if (!named && !needs.note) "job" else null
    }

    /** Minutes of [shift] with no complete job. */
    fun unallocatedMinutes(shift: Shift, needs: JobNeeds, now: Instant): Int =
        spans(shift, now).filter { missing(it.segment.job, needs) != null }.sumOf { it.minutes }

    /**
     * The segments after a shift's times changed: a later start drops the segments that ended before it and trims
     * the next, an earlier start stretches the first, and an earlier end drops the ones that would start after it.
     */
    fun fit(segments: List<ShiftSegment>, startAt: Instant, endAt: Instant?): List<ShiftSegment> {
        if (segments.isEmpty()) return segments
        val sorted = segments.sortedBy { it.startAt }
        val first = sorted.indexOfLast { it.startAt <= startAt }.coerceAtLeast(0)
        val kept = sorted.drop(first).mapIndexed { i, s -> if (i == 0 && s.startAt != startAt) s.copy(startAt = startAt) else s }
        return kept.filterIndexed { i, s -> i == 0 || endAt == null || s.startAt < endAt }
    }

    /** True when switching to [job] would change nothing: it is what the shift is on now. */
    fun isCurrent(shift: Shift, job: Job?): Boolean {
        val current = shift.currentJob
        return if (current == null) job == null else current.sameWork(job)
    }

    /** The segments after switching to [job] at [at]: a new one, or the current one's job changed when it just began. */
    fun switched(shift: Shift, job: Job?, at: Instant, siteName: String?): List<ShiftSegment> {
        val current = shift.segments.ifEmpty { listOf(ShiftSegment(shift.startAt)) }
        val last = current.last()
        return if (at - last.startAt < SAME_SEGMENT) {
            current.dropLast(1) + last.copy(job = job, source = SegmentSource.CLOCK, siteName = siteName ?: last.siteName)
        } else {
            current + ShiftSegment(at, job, SegmentSource.CLOCK, siteName)
        }
    }

    /**
     * Typed segments in time order: the first stays first (it starts with the shift), the others by their start, so
     * a part added at the end of the form for a time in the middle of the shift lands where it belongs.
     */
    fun inOrder(drafts: List<SegmentDraft>): List<SegmentDraft> = drafts.take(1) + drafts.drop(1).sortedBy { it.startAt }

    /** The first problem with segments typed for [shift], as a stable error code; the first draft's start is ignored. */
    fun problem(shift: Shift, drafts: List<SegmentDraft>, now: Instant): String? {
        if (drafts.isEmpty()) return "segments_required"
        if (drafts.size > MAX_SEGMENTS) return "too_many_segments"
        val limit = shift.endAt ?: (now + 1.minutes)
        var previous = shift.startAt
        inOrder(drafts).drop(1).forEach { draft ->
            if (draft.startAt <= shift.startAt || draft.startAt >= limit) return "segment_outside_shift"
            if (draft.startAt <= previous) return "segments_same_start"
            previous = draft.startAt
        }
        return null
    }

    /**
     * Typed segments over the stored ones: the first starts with the shift; a segment whose start and work didn't
     * change keeps how it was set (its source, names and site); neighbours left on the same work become one.
     */
    fun replaced(shift: Shift, drafts: List<SegmentDraft>, source: SegmentSource): List<ShiftSegment> {
        val stored = shift.segments.associateBy { it.startAt }
        val segments = inOrder(drafts).mapIndexed { i, draft ->
            val start = if (i == 0) shift.startAt else draft.startAt
            val before = stored[start] ?: shift.segments.firstOrNull().takeIf { i == 0 }
            when {
                before != null && sameWork(before.job, draft.job) -> before.copy(startAt = start)
                else -> ShiftSegment(start, draft.job, source, before?.siteName)
            }
        }
        return segments.fold(mutableListOf()) { kept, segment ->
            if (kept.isNotEmpty() && sameWork(kept.last().job, segment.job)) kept else kept.apply { add(segment) }
        }
    }

    private fun sameWork(a: Job?, b: Job?): Boolean = if (a == null) b == null else a.sameWork(b)
}
