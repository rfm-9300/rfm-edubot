package com.rfm.edubot.building

import com.rfm.edubot.building.model.SitePhoto
import org.bson.types.ObjectId
import java.nio.file.Files
import java.nio.file.Path

/**
 * Project photos on disk, beside the company's logo on the PDF volume: `<root>/<slug>/building/<projectId>/`.
 * The browser shrinks each photo and makes its thumbnail before uploading, so the server only checks that both
 * are JPEG and never decodes an image.
 */
class PhotoStore(private val root: () -> String) {
    fun write(slug: String, projectId: ObjectId, photoId: ObjectId, full: ByteArray, thumb: ByteArray): Pair<String, String> {
        val dir = projectDir(slug, projectId)
        Files.createDirectories(dir)
        val fullPath = dir.resolve("${photoId.toHexString()}.jpg")
        val thumbPath = dir.resolve("${photoId.toHexString()}.thumb.jpg")
        Files.write(fullPath, full)
        Files.write(thumbPath, thumb)
        return fullPath.toString() to thumbPath.toString()
    }

    fun read(photo: SitePhoto, thumb: Boolean): ByteArray? =
        Path.of(if (thumb) photo.thumbPath else photo.path).takeIf { Files.isRegularFile(it) }?.let(Files::readAllBytes)

    fun delete(photo: SitePhoto) {
        listOf(photo.path, photo.thumbPath).filter { it.isNotBlank() }.forEach { runCatching { Files.deleteIfExists(Path.of(it)) } }
    }

    fun deleteProject(slug: String, projectId: ObjectId) {
        val dir = projectDir(slug, projectId)
        if (!Files.isDirectory(dir)) return
        runCatching { Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }

    private fun projectDir(slug: String, projectId: ObjectId): Path = Path.of(root(), slug, "building", projectId.toHexString())

    companion object {
        const val MAX_FULL_BYTES = 4 * 1024 * 1024
        const val MAX_THUMB_BYTES = 512 * 1024

        fun isJpeg(bytes: ByteArray): Boolean =
            bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
    }
}
