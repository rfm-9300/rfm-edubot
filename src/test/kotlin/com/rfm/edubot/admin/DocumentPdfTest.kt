package com.rfm.edubot.admin

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DocumentPdfTest {

    @Test
    fun `returns existing file without regenerating`() = runBlocking {
        val dir = Files.createTempDirectory("pdf-existing")
        val stored = savePdf(dir.toString(), "invoices", "Fatura FAT-004.pdf", byteArrayOf(1, 2, 3))
        var persisted = 0
        val bytes = persistGeneratedPdf(
            storedPath = stored.toString(),
            basePath = dir.toString(),
            folder = "invoices",
            filename = "Fatura FAT-004.pdf",
            generate = { error("should not regenerate") },
            persist = { persisted++ },
        )
        assertContentEquals(byteArrayOf(1, 2, 3), bytes)
        assertEquals(0, persisted)
    }

    @Test
    fun `regenerates when stored file is missing`() = runBlocking {
        val dir = Files.createTempDirectory("pdf-missing")
        var savedPath = ""
        val bytes = persistGeneratedPdf(
            storedPath = dir.resolve("gone.pdf").toString(),
            basePath = dir.toString(),
            folder = "invoices",
            filename = "Fatura FAT-004.pdf",
            generate = { byteArrayOf(9, 8, 7) },
            persist = { savedPath = it },
        )
        assertContentEquals(byteArrayOf(9, 8, 7), bytes)
        assertTrue(savedPath.endsWith("Fatura FAT-004.pdf"))
        assertContentEquals(byteArrayOf(9, 8, 7), Files.readAllBytes(dir.resolve("invoices").resolve("Fatura FAT-004.pdf")))
    }

    @Test
    fun `generates when invoice never had a pdf path`() = runBlocking {
        val dir = Files.createTempDirectory("pdf-none")
        val bytes = persistGeneratedPdf(
            storedPath = null,
            basePath = dir.toString(),
            folder = "invoices",
            filename = "Fatura FAT-004.pdf",
            generate = { byteArrayOf(4, 5) },
            persist = {},
        )
        assertContentEquals(byteArrayOf(4, 5), bytes)
        assertTrue(Files.exists(dir.resolve("invoices").resolve("Fatura FAT-004.pdf")))
    }
}
