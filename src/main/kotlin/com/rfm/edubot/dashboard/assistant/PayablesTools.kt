package com.rfm.edubot.dashboard.assistant

import com.rfm.edubot.crm.EmployeeRepository
import com.rfm.edubot.crm.SupplierRepository
import com.rfm.edubot.crm.model.Payment
import com.rfm.edubot.crm.model.PaymentStatus
import com.rfm.edubot.dashboard.DashboardModules
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Money going out: `list_payments`, `mark_payment_paid`, and the suppliers and employees the company pays. */
internal class PayablesTools(private val s: AssistantToolSession) : AssistantToolGroup {
    override val spec: ToolGroupSpec get() = Companion

    override suspend fun execute(name: String, args: JsonObject): JsonObject = when (name) {
        LIST_PAYMENTS -> listPayments(args)
        MARK_PAYMENT_PAID -> markPaymentPaid(args)
        LIST_SUPPLIERS -> listSuppliers(args)
        LIST_EMPLOYEES -> listEmployees(args)
        else -> throw ToolProblem("unknown_tool", "Unknown tool.")
    }

    override suspend fun describe(name: String, args: JsonObject): JsonObject? = when (name) {
        MARK_PAYMENT_PAID -> {
            val payment = s.resolvePayment(args.text("payment_id") ?: return null)
            buildJsonObject {
                put("payment", payment.number)
                s.payee(payment)?.let { put("payee", it) }
                put("total_eur", eur(payment.totalCents))
                put("due_date", payment.dueDate.toString())
                put("status", s.effective(payment).name)
            }
        }
        else -> null
    }

    override suspend fun check(name: String, args: JsonObject) {
        if (name == MARK_PAYMENT_PAID) openPayment(args)
    }

    private suspend fun listPayments(args: JsonObject): JsonObject {
        val today = s.today()
        val status = args.text("status")?.uppercase()
        if (status != null && status != "OPEN" && PaymentStatus.entries.none { it.name == status }) {
            throw ToolProblem("invalid_status", "Use PENDING, OVERDUE, OPEN, PAID or CANCELLED.")
        }
        val query = args.text("query")?.lowercase()
        val dueFrom = args.date("due_from")
        val dueTo = args.date("due_to")
        val all = s.payments.list()
        val payees = payees(all)
        val rows = all.map { it to payees(it) }.filter { (payment, payee) ->
            val state = s.effective(payment, today)
            (status == null || (status == "OPEN" && state in OPEN_PAYMENTS) || state.name == status) &&
                (query == null || payee?.lowercase()?.contains(query) == true || payment.number.lowercase() == query) &&
                (dueFrom == null || payment.dueDate >= dueFrom) && (dueTo == null || payment.dueDate <= dueTo)
        }
        val shown = rows.take(s.limit(args))
        val open = rows.filter { s.effective(it.first, today) in OPEN_PAYMENTS }
        return buildJsonObject {
            put("today", today.toString())
            putJsonArray("payments") {
                shown.forEach { (p, payee) ->
                    addJsonObject {
                        put("id", p.id.toHexString())
                        put("number", p.number)
                        payee?.let { put("payee", it) }
                        put("payee_type", if (p.supplierId != null) "supplier" else "employee")
                        put("status", s.effective(p, today).name)
                        put("due_date", p.dueDate.toString())
                        put("amount_eur", eur(p.totalCents))
                        p.paidAt?.let { put("paid_on", it.toLocalDateTime(s.zone).date.toString()) }
                        p.notes?.let { put("notes", it) }
                    }
                }
            }
            putJsonObject("summary") {
                put("count", rows.size)
                put("total_eur", eur(rows.filter { it.first.status != PaymentStatus.CANCELLED }.sumOf { it.first.totalCents }))
                put("to_pay_eur", eur(open.sumOf { it.first.totalCents }))
                put("overdue", open.count { s.effective(it.first, today) == PaymentStatus.OVERDUE })
            }
            if (rows.size > shown.size) put("more", rows.size - shown.size)
        }
    }

    /** Who each of [payments] is paid to, with one query for the suppliers and one for the employees. */
    private suspend fun payees(payments: List<Payment>): (Payment) -> String? {
        val suppliers = SupplierRepository(s.mongo, s.tenant.id).findByIds(payments.mapNotNull { it.supplierId })
        val employees = EmployeeRepository(s.mongo, s.tenant.id).findByIds(payments.mapNotNull { it.employeeId })
        return { payment ->
            when {
                payment.supplierId != null -> suppliers[payment.supplierId]?.name
                payment.employeeId != null -> employees[payment.employeeId]?.name
                else -> null
            }
        }
    }

    private suspend fun openPayment(args: JsonObject): Payment {
        val payment = s.resolvePayment(args.text("payment_id") ?: throw missing("payment_id"))
        if (payment.status == PaymentStatus.CANCELLED) throw ToolProblem("payment_cancelled", "${payment.number} is cancelled.")
        if (payment.status == PaymentStatus.PAID) throw ToolProblem("already_paid", "${payment.number} is already paid.")
        return payment
    }

    private suspend fun markPaymentPaid(args: JsonObject): JsonObject {
        val payment = openPayment(args)
        val paid = s.payments.markPaid(payment.id) ?: throw ToolProblem("payment_not_found", "That payment no longer exists.")
        return buildJsonObject {
            put("updated", true)
            put("type", "payment")
            put("id", paid.id.toHexString())
            put("number", paid.number)
            put("status", paid.status.name)
        }
    }

    private suspend fun listSuppliers(args: JsonObject): JsonObject {
        val found = SupplierRepository(s.mongo, s.tenant.id).search(args.text("query").orEmpty())
        return buildJsonObject {
            putJsonArray("suppliers") {
                found.take(s.limit(args)).forEach { supplier ->
                    addJsonObject {
                        put("id", supplier.id.toHexString()); put("number", supplier.number); put("name", supplier.name); put("phone", supplier.phone)
                        supplier.type?.let { put("type", it) }
                        if (supplier.services.isNotEmpty()) put("services", JsonArray(supplier.services.map { JsonPrimitive(it.description) }))
                    }
                }
            }
        }
    }

    private suspend fun listEmployees(args: JsonObject): JsonObject {
        val found = EmployeeRepository(s.mongo, s.tenant.id).search(args.text("query").orEmpty())
        return buildJsonObject {
            putJsonArray("employees") {
                found.take(s.limit(args)).forEach { e ->
                    addJsonObject {
                        put("id", e.id.toHexString()); put("number", e.number); put("name", e.name); put("phone", e.phone)
                        e.role?.let { put("role", it) }
                    }
                }
            }
        }
    }

    companion object : ToolGroupSpec {
        const val LIST_PAYMENTS = "list_payments"
        const val MARK_PAYMENT_PAID = "mark_payment_paid"
        const val LIST_SUPPLIERS = "list_suppliers"
        const val LIST_EMPLOYEES = "list_employees"

        private val OPEN_PAYMENTS = setOf(PaymentStatus.PENDING, PaymentStatus.OVERDUE)

        override val definitions = listOf(
            tool(
                LIST_PAYMENTS,
                "Bills the company pays to suppliers and employees, by due date. OVERDUE includes pending ones past their due date; OPEN is pending or overdue.",
                buildJsonObject {
                    put("status", prop("string", null, listOf("PENDING", "OVERDUE", "OPEN", "PAID", "CANCELLED")))
                    put("query", prop("string", "Part of the supplier's or employee's name, or the payment number"))
                    put("due_from", prop("string", DATE))
                    put("due_to", prop("string", DATE))
                    put("limit", prop("integer"))
                },
            ),
            tool(
                MARK_PAYMENT_PAID,
                "Mark a bill the company owes as paid.",
                buildJsonObject { put("payment_id", prop("string", "The payment's id or number, e.g. PAG-002")) },
                listOf("payment_id"),
            ),
            tool(LIST_SUPPLIERS, "Find suppliers by name, phone or type.", buildJsonObject { put("query", prop("string")); put("limit", prop("integer")) }),
            tool(LIST_EMPLOYEES, "Find employees by name, phone or role.", buildJsonObject { put("query", prop("string")); put("limit", prop("integer")) }),
        )
        override val moduleOf = mapOf(
            LIST_PAYMENTS to DashboardModules.PAYMENTS,
            MARK_PAYMENT_PAID to DashboardModules.PAYMENTS,
            LIST_SUPPLIERS to DashboardModules.SUPPLIERS,
            LIST_EMPLOYEES to DashboardModules.EMPLOYEES,
        )
        override val readOnly = setOf(LIST_PAYMENTS, LIST_SUPPLIERS, LIST_EMPLOYEES)
    }
}
