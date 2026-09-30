package com.rfm.edubot.integrations.email

import com.rfm.edubot.tenant.model.TenantLocales

/** Email text the platform writes itself (the settings test email), in the company's language. */
internal object EmailCopy {
    private val en = mapOf(
        "test.subject" to "Test email from {company}",
        "test.body" to "This is a test email from {company}. If you can read it, emails to your clients go out from {account} with this name, reply-to address and signature.\n\nThere's no need to reply.",
    )

    private val pt = mapOf(
        "test.subject" to "Email de teste de {company}",
        "test.body" to "Este é um email de teste de {company}. Se o consegue ler, os emails para os seus clientes saem de {account} com este nome, endereço de resposta e assinatura.\n\nNão precisa de responder.",
    )

    private val es = mapOf(
        "test.subject" to "Email de prueba de {company}",
        "test.body" to "Este es un email de prueba de {company}. Si puedes leerlo, los emails a tus clientes salen desde {account} con este nombre, dirección de respuesta y firma.\n\nNo hace falta responder.",
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
