package com.rfm.edubot.agents.templates

import com.rfm.edubot.tenant.model.TenantLocales

/**
 * Text agents write themselves (digest headings, fallback tasks, email subjects), in the company's
 * language. Dashboard UI strings live in the web catalogs; this is message content.
 */
object AgentCopy {
    private val en = mapOf(
        "summary.agenda_today" to "Today's bookings",
        "summary.pending_bookings" to "Bookings waiting for confirmation",
        "summary.payables_week" to "Payments due in the next 7 days",
        "summary.receivables_overdue" to "Overdue invoices",
        "summary.cash_week" to "This week in numbers",
        "summary.waiting_chats" to "Conversations waiting for an answer",
        "summary.missing_client_data" to "Clients missing an email or tax number",
        "summary.open_services" to "Work not invoiced yet",
        "summary.empty" to "Nothing to report.",
        "summary.cash.collected" to "Collected: {amount}",
        "summary.cash.outstanding" to "To collect: {amount}",
        "summary.cash.overdue" to "Overdue: {amount} ({count})",
        "summary.cash.payables" to "To pay this week: {amount}",
        "summary.line.due" to "due {date}",
        "summary.line.missing_email" to "no email",
        "summary.line.missing_tax" to "no tax number",
        "summary.more" to "…and {count} more",
        "fallback.task.title" to "Contact {name}",
        "fallback.task.detail" to "The agent couldn't send this message on {channel} ({reason}). Send it yourself:\n\n{text}",
        "fallback.email.subject" to "Message from {company}",
        "reason.window_closed" to "the 24-hour WhatsApp window is closed",
        "reason.no_phone" to "there is no phone number",
        "reason.no_channel" to "the channel isn't connected",
        "reason.send_failed" to "sending failed",
        "notify.default" to "{agent}: {subject}",
    )

    private val pt = mapOf(
        "summary.agenda_today" to "Marcações de hoje",
        "summary.pending_bookings" to "Marcações por confirmar",
        "summary.payables_week" to "Pagamentos a vencer nos próximos 7 dias",
        "summary.receivables_overdue" to "Faturas em atraso",
        "summary.cash_week" to "A semana em números",
        "summary.waiting_chats" to "Conversas à espera de resposta",
        "summary.missing_client_data" to "Clientes sem email ou NIF",
        "summary.open_services" to "Trabalho ainda por faturar",
        "summary.empty" to "Nada a assinalar.",
        "summary.cash.collected" to "Recebido: {amount}",
        "summary.cash.outstanding" to "A receber: {amount}",
        "summary.cash.overdue" to "Em atraso: {amount} ({count})",
        "summary.cash.payables" to "A pagar esta semana: {amount}",
        "summary.line.due" to "vence a {date}",
        "summary.line.missing_email" to "sem email",
        "summary.line.missing_tax" to "sem NIF",
        "summary.more" to "…e mais {count}",
        "fallback.task.title" to "Contactar {name}",
        "fallback.task.detail" to "O agente não conseguiu enviar esta mensagem por {channel} ({reason}). Envie-a manualmente:\n\n{text}",
        "fallback.email.subject" to "Mensagem de {company}",
        "reason.window_closed" to "a janela de 24 horas do WhatsApp está fechada",
        "reason.no_phone" to "não há número de telefone",
        "reason.no_channel" to "o canal não está ligado",
        "reason.send_failed" to "o envio falhou",
        "notify.default" to "{agent}: {subject}",
    )

    private val es = mapOf(
        "summary.agenda_today" to "Reservas de hoy",
        "summary.pending_bookings" to "Reservas pendientes de confirmar",
        "summary.payables_week" to "Pagos que vencen en los próximos 7 días",
        "summary.receivables_overdue" to "Facturas vencidas",
        "summary.cash_week" to "La semana en cifras",
        "summary.waiting_chats" to "Conversaciones esperando respuesta",
        "summary.missing_client_data" to "Clientes sin email o NIF",
        "summary.open_services" to "Trabajo aún sin facturar",
        "summary.empty" to "Nada que destacar.",
        "summary.cash.collected" to "Cobrado: {amount}",
        "summary.cash.outstanding" to "Por cobrar: {amount}",
        "summary.cash.overdue" to "Vencido: {amount} ({count})",
        "summary.cash.payables" to "Por pagar esta semana: {amount}",
        "summary.line.due" to "vence el {date}",
        "summary.line.missing_email" to "sin email",
        "summary.line.missing_tax" to "sin NIF",
        "summary.more" to "…y {count} más",
        "fallback.task.title" to "Contactar a {name}",
        "fallback.task.detail" to "El agente no pudo enviar este mensaje por {channel} ({reason}). Envíalo tú:\n\n{text}",
        "fallback.email.subject" to "Mensaje de {company}",
        "reason.window_closed" to "la ventana de 24 horas de WhatsApp está cerrada",
        "reason.no_phone" to "no hay número de teléfono",
        "reason.no_channel" to "el canal no está conectado",
        "reason.send_failed" to "el envío falló",
        "notify.default" to "{agent}: {subject}",
    )

    fun t(locale: String, key: String, vararg params: Pair<String, String>): String {
        val catalog = when (TenantLocales.normalize(locale)) {
            "en" -> en
            "es" -> es
            else -> pt
        }
        val template = catalog[key] ?: en[key] ?: key
        return params.fold(template) { text, (name, value) -> text.replace("{$name}", value) }
    }
}
