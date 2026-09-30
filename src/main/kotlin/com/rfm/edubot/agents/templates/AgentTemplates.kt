package com.rfm.edubot.agents.templates

import com.rfm.edubot.agents.model.AgentDefinition
import com.rfm.edubot.agents.model.AgentKind
import com.rfm.edubot.agents.model.CompanyAgentSettings
import kotlinx.serialization.json.JsonObject

/** A new agent made from a template, ready to save as a draft. */
data class TemplatedAgent(
    val name: String,
    val description: String?,
    val icon: String,
    val kind: AgentKind,
    val definition: AgentDefinition,
    val params: JsonObject,
)

/** Built-in agent templates: parameterised definitions with copy in the company's language. */
object AgentTemplates {
    /** Gallery entries for the catalog. */
    fun catalog(locale: String): List<JsonObject> = emptyList()

    fun build(key: String, params: JsonObject?, locale: String, company: CompanyAgentSettings): TemplatedAgent? = null
}
