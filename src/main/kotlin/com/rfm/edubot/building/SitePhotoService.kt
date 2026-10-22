package com.rfm.edubot.building

import com.rfm.edubot.building.model.SitePhoto
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.plus
import org.bson.types.ObjectId

/** A project's photos: uploaded from the gallery, a diary day or an issue, captioned, shared with the client or not. */
class SitePhotoService(private val records: BuildingRecords, private val store: PhotoStore) {
    /**
     * An upload as the browser sends it, already shrunk: [image] and its [thumb], both JPEG. [logId], [issueId] and
     * [phaseId] say what it is of; without [shared] a photo of an issue stays inside the team.
     */
    class Upload(
        val image: ByteArray,
        val thumb: ByteArray,
        val logId: String? = null,
        val issueId: String? = null,
        val phaseId: String? = null,
        val caption: String? = null,
        val shared: Boolean? = null,
        val takenAt: String? = null,
    )

    suspend fun add(projectId: ObjectId, upload: Upload, companySlug: String, stamp: Stamp): Outcome<SitePhoto> {
        val project = records.projects.findById(projectId) ?: return Outcome.NotFound
        if (records.photos.count(project.id) >= SitePhotoRepository.MAX_PER_PROJECT) return conflict("too_many_photos")
        val log = upload.logId?.takeIf { it.isNotBlank() }?.let { raw ->
            objectIdOrNull(raw)?.let { records.logs.findById(it) }?.takeIf { it.projectId == project.id } ?: return invalid("log_not_found")
        }
        val issue = upload.issueId?.takeIf { it.isNotBlank() }?.let { raw ->
            objectIdOrNull(raw)?.let { records.issues.findById(it) }?.takeIf { it.projectId == project.id } ?: return invalid("issue_not_found")
        }
        val phaseId = upload.phaseId?.takeIf { it.isNotBlank() }?.also { phase ->
            if (project.phases.none { it.id == phase }) return invalid("phase_not_found")
        }
        // A camera's clock may be off; a date more than a day ahead is ignored.
        val takenAt = upload.takenAt?.let { runCatching { Instant.parse(it.trim()) }.getOrNull() }
            ?.takeIf { it <= stamp.now.plus(1, DateTimeUnit.DAY, stamp.zone) }
        val id = ObjectId()
        val (fullPath, thumbPath) = store.write(companySlug, project.id, id, upload.image, upload.thumb)
        val photo = SitePhoto(
            id = id,
            tenantId = records.tenantId,
            projectId = project.id,
            logId = log?.id,
            issueId = issue?.id,
            phaseId = phaseId,
            caption = upload.caption?.trim()?.take(BuildingLimits.CAPTION)?.ifEmpty { null },
            day = log?.day ?: takenAt?.let(stamp::day) ?: stamp.today,
            takenAt = takenAt,
            shared = upload.shared ?: (issue == null),
            path = fullPath,
            thumbPath = thumbPath,
            bytes = upload.image.size.toLong(),
            uploadedBy = stamp.who,
            createdAt = stamp.now,
        )
        records.photos.insert(photo)
        return Outcome.Done(photo)
    }

    /** A blank caption clears it; null keeps either. */
    suspend fun edit(id: ObjectId, caption: String?, shared: Boolean?): SitePhoto? =
        records.photos.update(id, caption?.trim()?.take(BuildingLimits.CAPTION), shared)

    suspend fun delete(id: ObjectId): SitePhoto? = records.photos.delete(id)?.also(store::delete)

    fun bytes(photo: SitePhoto, thumb: Boolean): ByteArray? = store.read(photo, thumb)
}
