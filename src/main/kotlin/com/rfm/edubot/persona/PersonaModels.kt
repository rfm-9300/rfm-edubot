package com.rfm.edubot.persona

import kotlinx.datetime.Instant
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId

data class TenantPersona(
    @BsonId val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val compiledInstructions: String = "",
    val version: Int = 0,
    val tokenEstimate: Int = 0,
    val status: PersonaStatus = PersonaStatus.EMPTY,
    val updatedAt: Instant,
)

enum class PersonaStatus { EMPTY, COMPILING, READY, ERROR }

/**
 * A raw incremental input the tenant supplies (a typed note or an uploaded file's extracted text).
 * Sources are the gradual "sync" feed; [PersonaCompiler] folds them into the single compiled file.
 * They are kept after compilation so we can re-compact from scratch ([PersonaCompiler.rebuild]).
 */
data class PersonaSource(
    @BsonId val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val kind: SourceKind,
    val content: String,
    val label: String,                  // human label: note preview or original filename
    val compiledIntoVersion: Int? = null,  // null until folded into the compiled file
    val createdAt: Instant,
)

enum class SourceKind { TEXT_NOTE, FILE }
