package com.rfm.edubot.persona

import com.rfm.edubot.ai.AiClient
import com.rfm.edubot.ai.AiResponse
import com.rfm.edubot.ai.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Offline synthesis step for the bot persona. Folds incremental sources (notes / uploaded files)
 * into the single compiled instruction file that the pipeline injects at chat time.
 *
 * - [enqueue] is debounced per tenant so a burst of edits compiles once.
 * - Incremental compile merges *(current compiled file + new sources)* — bounded cost.
 * - [rebuild] re-compacts from all sources (drift control), ignoring the current file.
 * - On failure the previous compiled file is kept; status flips to ERROR, never a broken persona.
 */
class PersonaCompiler(
    private val repository: PersonaRepository,
    private val aiClient: AiClient,
    private val scope: CoroutineScope,
    private val onCompiled: (ObjectId) -> Unit,
    private val debounceMillis: Long = 4_000,
    private val maxCompiledChars: Int = 6_000,
) {
    private val log = LoggerFactory.getLogger("PersonaCompiler")
    private val pending = ConcurrentHashMap<ObjectId, Job>()
    private val locks = ConcurrentHashMap<ObjectId, Mutex>()

    /** Debounced incremental compile after a source is added/edited. */
    fun enqueue(tenantId: ObjectId, model: String?) {
        pending.remove(tenantId)?.cancel()
        pending[tenantId] = scope.launch {
            delay(debounceMillis)
            pending.remove(tenantId)
            runCatching { compile(tenantId, model, fromScratch = false) }
                .onFailure { log.error("Persona compile failed for tenant={}: {}", tenantId, it.message, it) }
        }
    }

    /** Force a full recompaction from every source. Runs immediately (used by the rebuild button). */
    suspend fun rebuild(tenantId: ObjectId, model: String?) {
        pending.remove(tenantId)?.cancel()
        compile(tenantId, model, fromScratch = true)
    }

    private suspend fun compile(tenantId: ObjectId, model: String?, fromScratch: Boolean) {
        locks.getOrPut(tenantId) { Mutex() }.withLock {
            val current = repository.findByTenant(tenantId)
            val baseFile = if (fromScratch) "" else current?.compiledInstructions.orEmpty()
            val sources = if (fromScratch) repository.allSources(tenantId) else repository.pendingSources(tenantId)

            if (sources.isEmpty() && baseFile.isBlank()) {
                repository.setStatus(tenantId, PersonaStatus.EMPTY)
                return
            }
            if (sources.isEmpty()) {
                // Nothing new to fold; leave the existing file as-is.
                repository.setStatus(tenantId, PersonaStatus.READY)
                return
            }

            repository.setStatus(tenantId, PersonaStatus.COMPILING)
            try {
                val compiled = synthesize(baseFile, sources, model).take(maxCompiledChars)
                val saved = repository.upsertCompiled(tenantId, compiled)
                repository.markSourcesCompiled(tenantId, sources.map { it.id }, saved.version)
                onCompiled(tenantId)
                log.info("Persona compiled for tenant={} version={} sources={}", tenantId, saved.version, sources.size)
            } catch (e: Exception) {
                repository.setStatus(tenantId, PersonaStatus.ERROR)
                throw e
            }
        }
    }

    private suspend fun synthesize(currentFile: String, sources: List<PersonaSource>, model: String?): String {
        val newMaterial = sources.joinToString("\n\n---\n\n") { "[${it.kind} · ${it.label}]\n${it.content}" }
        val userContent = buildString {
            append("CURRENT INSTRUCTION FILE:\n")
            append(currentFile.ifBlank { "(empty — this is the first compilation)" })
            append("\n\nNEW INFORMATION TO FOLD IN:\n")
            append(newMaterial)
        }
        val response = aiClient.complete(
            messages = listOf(
                ChatMessage(role = "system", content = SYNTHESIS_PROMPT),
                ChatMessage(role = "user", content = userContent),
            ),
            modelOverride = model,
        )
        val text = (response as? AiResponse.Text)?.content?.trim().orEmpty()
        return text.ifBlank { currentFile }  // never overwrite a good file with nothing
    }

    private companion object {
        const val SYNTHESIS_PROMPT = """
You maintain a chatbot's persona instruction file. You are given the CURRENT instruction file and NEW information to incorporate.

Produce an UPDATED, single, self-contained instruction file that captures the bot's:
- identity and role (who it is, who it serves)
- tone and voice (language, formality, style)
- domain facts and knowledge it should rely on
- explicit do / don't rules

Rules:
- Merge the new information into the current file; refine and deduplicate — do not just append.
- Keep it concise and well-structured (short sections / bullet points). Aim for under ~1000 words.
- Do NOT include tool mechanics, function names, or confirmation/safety logic — those live elsewhere.
- Write in the same language the persona should speak (infer from the material; default to the existing file's language).
- Output ONLY the instruction file text, no preamble, no commentary, no markdown code fences.
"""
    }
}
