package com.rfm.edubot.persona

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

/**
 * Turns an uploaded persona file into plain text that can be fed into [PersonaCompiler].
 * Supports PDF (via PDFBox, already a project dependency) and plain text / markdown.
 * No vector store — extracted text becomes a [SourceKind.FILE] source and is distilled
 * into the single compiled instruction file like any other source.
 */
object PersonaFileExtractor {
    private const val MAX_CHARS = 200_000  // guardrail: avoid folding a giant doc verbatim

    class UnsupportedFileException(message: String) : Exception(message)

    fun extract(filename: String, bytes: ByteArray): String {
        val text = when (filename.substringAfterLast('.', "").lowercase()) {
            "pdf" -> extractPdf(bytes)
            "txt", "md", "markdown", "text" -> bytes.toString(Charsets.UTF_8)
            else -> throw UnsupportedFileException("Unsupported file type: $filename (use PDF, TXT or MD)")
        }
        return text.trim().take(MAX_CHARS)
    }

    private fun extractPdf(bytes: ByteArray): String =
        Loader.loadPDF(bytes).use { doc -> PDFTextStripper().getText(doc) }
}
