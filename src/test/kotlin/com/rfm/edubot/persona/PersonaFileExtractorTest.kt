package com.rfm.edubot.persona

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Uploaded files a company teaches its bot with, turned into the text the synthesis reads. */
class PersonaFileExtractorTest {
    private fun docx(documentXml: String, extra: Map<String, String> = emptyMap()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            (extra + ("word/document.xml" to documentXml)).forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun wordDocument(body: String) =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body</w:body></w:document>"""

    private fun pdf(text: String): ByteArray = PDDocument().use { doc ->
        val page = PDPage()
        doc.addPage(page)
        PDPageContentStream(doc, page).use { stream ->
            stream.beginText()
            stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
            stream.newLineAtOffset(72f, 700f)
            stream.showText(text)
            stream.endText()
        }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    @Test
    fun `plain text, markdown and csv are read as UTF-8`() {
        assertEquals("Olá, somos a Clínica.", PersonaFileExtractor.extract("notas.txt", "Olá, somos a Clínica.".toByteArray()))
        assertEquals("# Preços\n- Limpeza: 40 €", PersonaFileExtractor.extract("precos.MD", "# Preços\n- Limpeza: 40 €".toByteArray()))
        assertEquals("servico;preco\nlimpeza;40", PersonaFileExtractor.extract("tabela.csv", "servico;preco\nlimpeza;40".toByteArray()))
        assertEquals("x", PersonaFileExtractor.extract("a.markdown", "x".toByteArray()))
    }

    @Test
    fun `byte-order marks are dropped and legacy Windows text is still read correctly`() {
        assertEquals("Olá", PersonaFileExtractor.extract("bom.txt", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Olá".toByteArray()))
        assertEquals("Olá", PersonaFileExtractor.extract("utf16.txt", byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Olá".toByteArray(Charsets.UTF_16LE)))
        assertEquals("Café com açúcar", PersonaFileExtractor.extract("legado.txt", "Café com açúcar".toByteArray(charset("windows-1252"))))
    }

    @Test
    fun `line endings and runs of blank lines are tidied`() {
        assertEquals("a\nb\n\nc", PersonaFileExtractor.extract("n.txt", "a\r\nb  \r\n\r\n\r\n\r\nc  \n\n".toByteArray()))
    }

    @Test
    fun `a Word document keeps its paragraphs, tabs and line breaks`() {
        val bytes = docx(
            wordDocument(
                "<w:p><w:r><w:t>Clínica Sorriso</w:t></w:r></w:p>" +
                    "<w:p><w:r><w:t xml:space=\"preserve\">Horário: </w:t></w:r><w:r><w:t>9h–18h</w:t></w:r></w:p>" +
                    "<w:p><w:r><w:t>Limpeza</w:t><w:tab/><w:t>40 €</w:t><w:br/><w:t>IVA incluído</w:t></w:r></w:p>",
            ),
            extra = mapOf("[Content_Types].xml" to "<Types/>", "word/styles.xml" to "<w:styles/>"),
        )
        assertEquals("Clínica Sorriso\nHorário: 9h–18h\nLimpeza\t40 €\nIVA incluído", PersonaFileExtractor.extract("guia.docx", bytes))
    }

    @Test
    fun `a Word document can't make the server read its own files`() {
        val secret = Files.createTempFile("persona-xxe", ".txt").also { Files.writeString(it, "TOP-SECRET-${System.nanoTime()}") }
        val xml = """<?xml version="1.0"?><!DOCTYPE w:document [<!ENTITY xxe SYSTEM "${secret.toUri()}">]>""" +
            """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>A&xxe;B</w:t></w:r></w:p></w:body></w:document>"""
        val text = runCatching { PersonaFileExtractor.extract("xxe.docx", docx(xml)) }
        text.exceptionOrNull()?.let { assertTrue(it is PersonaFileExtractor.UnreadableFileException, "unexpected $it") }
        assertFalse(text.getOrDefault("").contains("TOP-SECRET"), "the entity was resolved: ${text.getOrNull()}")
        Files.deleteIfExists(secret)
    }

    @Test
    fun `a zip without Word text, or bytes that aren't a zip, can't be read`() {
        assertFailsWith<PersonaFileExtractor.UnreadableFileException> { PersonaFileExtractor.extract("vazio.docx", docxWithout()) }
        assertFailsWith<PersonaFileExtractor.UnreadableFileException> { PersonaFileExtractor.extract("falso.docx", "isto não é um zip".toByteArray()) }
    }

    private fun docxWithout(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("word/other.xml"))
            zip.write("<x/>".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `a PDF's text is read, and a damaged one is refused as unreadable`() {
        assertEquals("Clinica Sorriso - Limpeza 40 EUR", PersonaFileExtractor.extract("folheto.pdf", pdf("Clinica Sorriso - Limpeza 40 EUR")))
        assertFailsWith<PersonaFileExtractor.UnreadableFileException> { PersonaFileExtractor.extract("partido.pdf", "%PDF-1.7 garbage".toByteArray()) }
    }

    @Test
    fun `other types are refused before reading`() {
        listOf("menu.doc", "precos.xlsx", "foto.jpg", "sem-extensao").forEach { name ->
            assertFailsWith<PersonaFileExtractor.UnsupportedFileException>(name) { PersonaFileExtractor.extract(name, "x".toByteArray()) }
        }
    }

    @Test
    fun `a very long file keeps its start and says it was cut`() {
        val long = "a".repeat(PersonaFileExtractor.MAX_CHARS + 10)
        val read = PersonaFileExtractor.read("longo.txt", long.toByteArray())
        assertEquals(PersonaFileExtractor.MAX_CHARS, read.text.length)
        assertTrue(read.truncated)
        assertFalse(PersonaFileExtractor.read("curto.txt", "curto".toByteArray()).truncated)
    }
}
