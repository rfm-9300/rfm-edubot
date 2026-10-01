package com.rfm.edubot.integrations.email

import com.rfm.edubot.tenant.model.TenantLocales

/** Email text the platform writes itself (the test email, the first draft of "Send by email"), in the company's language. */
internal object EmailCopy {
    private val en = mapOf(
        "test.subject" to "Test email from {company}",
        "test.body" to "This is a test email from {company}. If you can read it, emails to your clients go out from {account} with this name, reply-to address and signature.\n\nThere's no need to reply.",
        "quote.subject" to "Quote {number} · {company}",
        "quote.body" to "Hello {name},\n\nPlease find attached quote {number}. If you have any questions, just reply to this email.",
        "invoice.subject" to "Invoice {number} · {company}",
        "invoice.body" to "Hello {name},\n\nPlease find attached invoice {number}. If you have any questions, just reply to this email.",
        "signoff" to "Kind regards,\n{company}",
    )

    private val pt = mapOf(
        "test.subject" to "Email de teste de {company}",
        "test.body" to "Este é um email de teste de {company}. Se o consegue ler, os emails para os seus clientes saem de {account} com este nome, endereço de resposta e assinatura.\n\nNão precisa de responder.",
        "quote.subject" to "Orçamento {number} · {company}",
        "quote.body" to "Olá {name},\n\nSegue em anexo o orçamento {number}. Se tiver alguma dúvida, basta responder a este email.",
        "invoice.subject" to "Fatura {number} · {company}",
        "invoice.body" to "Olá {name},\n\nSegue em anexo a fatura {number}. Se tiver alguma dúvida, basta responder a este email.",
        "signoff" to "Com os melhores cumprimentos,\n{company}",
    )

    private val es = mapOf(
        "test.subject" to "Email de prueba de {company}",
        "test.body" to "Este es un email de prueba de {company}. Si puedes leerlo, los emails a tus clientes salen desde {account} con este nombre, dirección de respuesta y firma.\n\nNo hace falta responder.",
        "quote.subject" to "Presupuesto {number} · {company}",
        "quote.body" to "Hola {name}:\n\nTe adjunto el presupuesto {number}. Si tienes cualquier duda, responde a este email.",
        "invoice.subject" to "Factura {number} · {company}",
        "invoice.body" to "Hola {name}:\n\nTe adjunto la factura {number}. Si tienes cualquier duda, responde a este email.",
        "signoff" to "Un saludo,\n{company}",
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

    /** The first draft for a quote or invoice; an account with a signature signs it already. */
    fun documentBody(locale: String, type: String, name: String, number: String, company: String, signed: Boolean): String {
        val body = t(locale, "$type.body", "name" to name, "number" to number)
        return if (signed) body else body + "\n\n" + t(locale, "signoff", "company" to company)
    }
}
