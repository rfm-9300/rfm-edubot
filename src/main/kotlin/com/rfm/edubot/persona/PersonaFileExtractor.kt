package com.rfm.edubot.persona

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/**
 * Turns an uploaded persona file into plain text that can be fed into [PersonaCompiler].
 * Supports PDF (via PDFBox, already a project dependency), Word (.docx) and plain text, Markdown and CSV.
 * No vector store — extracted text becomes a [SourceKind.FILE] source and is distilled
 * into the single compiled instruction file like any other source.
 */
object PersonaFileExtractor {
    const val MAX_CHARS = 200_000  // guardrail: avoid folding a giant doc verbatim

    /** Extensions the dashboard accepts. */
    val EXTENSIONS = listOf("pdf", "docx", "txt", "md", "markdown", "text", "csv")

    /** A .docx is a zip; its text part may not unpack to more than this (a zip bomb guard). */
    private const val MAX_DOCX_XML_BYTES = 30L * 1024 * 1024

    class UnsupportedFileException(message: String) : Exception(message)

    /** The file has a supported type but its text can't be read (encrypted, damaged, scanned without text). */
    class UnreadableFileException(message: String, cause: Throwable? = null) : Exception(message, cause)

    data class Extracted(val text: String, val truncated: Boolean)

    fun extract(filename: String, bytes: ByteArray): String = read(filename, bytes).text

    fun read(filename: String, bytes: ByteArray): Extracted {
        val raw = when (filename.substringAfterLast('.', "").lowercase()) {
            "pdf" -> extractPdf(bytes)
            "docx" -> extractDocx(bytes)
            "txt", "md", "markdown", "text", "csv" -> decodeText(bytes)
            else -> throw UnsupportedFileException("Unsupported file type: $filename (use PDF, DOCX, TXT, MD or CSV)")
        }
        val text = normalize(raw)
        return Extracted(text.take(MAX_CHARS).trim(), truncated = text.length > MAX_CHARS)
    }

    private fun extractPdf(bytes: ByteArray): String = try {
        Loader.loadPDF(bytes).use { doc -> PDFTextStripper().getText(doc) }
    } catch (e: Exception) {
        throw UnreadableFileException("This PDF can't be read: ${e.message}", e)
    }

    /** The text of `word/document.xml`: one line per paragraph, tabs and line breaks kept. */
    private fun extractDocx(bytes: ByteArray): String = try {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.firstOrNull { it.name == "word/document.xml" }
                ?: throw UnreadableFileException("No document text in this .docx")
            documentText(LimitedInputStream(zip, MAX_DOCX_XML_BYTES))
        }
    } catch (e: UnreadableFileException) {
        throw e
    } catch (e: Exception) {
        throw UnreadableFileException("This .docx can't be read: ${e.message}", e)
    }

    private fun documentText(input: InputStream): String {
        val factory = XMLInputFactory.newInstance().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        }
        val reader = factory.createXMLStreamReader(input)
        val out = StringBuilder()
        var inText = false
        try {
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                        "t" -> inText = true
                        "tab" -> out.append('\t')
                        "br", "cr" -> out.append('\n')
                    }
                    XMLStreamConstants.END_ELEMENT -> when (reader.localName) {
                        "t" -> inText = false
                        "p" -> out.append('\n')
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> if (inText) out.append(reader.text)
                }
            }
        } finally {
            reader.close()
        }
        return out.toString()
    }

    /** UTF-8 (or UTF-16 with its byte-order mark); anything else is read as Windows-1252, the usual legacy encoding. */
    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        val body = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) bytes.copyOfRange(3, bytes.size) else bytes
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
        } catch (_: CharacterCodingException) {
            String(body, Charset.forName("windows-1252"))
        }
    }

    private fun normalize(text: String): String = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace('\u0000', ' ')
        .lines()
        .joinToString("\n") { it.trimEnd() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    private class LimitedInputStream(private val input: InputStream, private val limit: Long) : InputStream() {
        private var read = 0L

        override fun read(): Int {
            val b = input.read()
            if (b >= 0) count(1)
            return b
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            val n = input.read(buffer, off, len)
            if (n > 0) count(n.toLong())
            return n
        }

        private fun count(n: Long) {
            read += n
            if (read > limit) throw UnreadableFileException("This .docx is too large once unpacked")
        }
    }
}
