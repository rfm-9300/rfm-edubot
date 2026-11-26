package com.rfm.edubot.agents.registry

import com.rfm.edubot.agents.actions.WhatsAppSendAction
import com.rfm.edubot.agents.model.AgentDefinition
import com.rfm.edubot.agents.model.StepSpec
import com.rfm.edubot.agents.model.TriggerSpec
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.events.SubjectTypes
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Agents that run once per employee: what of a person's day they may read, and who their messages may go to. */
class EmployeeAgentValidationTest {
    private val registry = AgentRegistry(listOf(WhatsAppSendAction), TriggerTypes.all)
    private val validator = AgentDefinitionValidator(registry)
    private val everything = Availability(DashboardModules.catalog.toSet(), IntegrationKind.entries.toSet())
    private fun without(module: String) = Availability(DashboardModules.catalog.toSet() - module, IntegrationKind.entries.toSet())

    private val eachEmployee = TriggerSpec("t1", TriggerTypes.SCHEDULE, buildJsonObject { put("frequency", "daily"); put("time", "08:00"); put("forEach", SubjectTypes.EMPLOYEE) })
    private val invoiceCreated = TriggerSpec("t1", TriggerTypes.EVENT, buildJsonObject { put("event", "invoice.created") })

    private fun send(to: String, text: String) = StepSpec("s1", "whatsapp.send", buildJsonObject { put("to", to); put("text", text) })

    private fun problems(definition: AgentDefinition, availability: Availability = everything) =
        validator.validate(definition, availability).map { "${it.code}:${it.detail.orEmpty()}" }

    @Test
    fun `a group of the day needs its module, the person and the schedule don't`() {
        val issues = AgentDefinition(listOf(eachEmployee), steps = listOf(send("employee", "Olá {{employee.firstName}}: {{issues.list}}")))

        assertEquals(emptyList(), problems(issues))
        assertEquals(listOf("needs_module:${DashboardModules.SITE_ISSUES}"), problems(issues, without(DashboardModules.SITE_ISSUES)))
        assertEquals(emptyList(), problems(issues, without(DashboardModules.TIMESHEETS)), "a briefing that reads no shifts doesn't need the time clock")
    }

    @Test
    fun `the employee is who an employee's agent writes to, and only such an agent writes to an employee`() {
        assertEquals(emptyList(), problems(AgentDefinition(listOf(eachEmployee), steps = listOf(send("employee", "Olá {{employee.firstName}}")))))
        assertEquals(listOf("recipient_not_employee:"), problems(AgentDefinition(listOf(eachEmployee), steps = listOf(send("client", "Olá {{employee.firstName}}")))))
        assertEquals(listOf("recipient_needs_employee:"), problems(AgentDefinition(listOf(invoiceCreated), steps = listOf(send("employee", "Olá {{client.firstName}}")))))
    }

    @Test
    fun `the editor only offers the groups the company can read`() {
        val offered = AgentCatalog.build(registry, without(DashboardModules.SITE_ISSUES)).variables.getValue(SubjectTypes.EMPLOYEE).map { it.path }

        assertTrue("shifts.today" in offered && "services.today" in offered && "employee.tags" in offered, offered.toString())
        assertFalse(offered.any { it.startsWith("issues.") }, offered.toString())
    }
}
