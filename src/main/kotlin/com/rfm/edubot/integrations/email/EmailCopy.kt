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
        "greeting.named" to "Hello {name},",
        "greeting.anon" to "Hello,",
        "share.change.subject" to "Change {number} for your approval · {company}",
        "share.change.body" to "{company} sent you a change to {project}: {number}, {title}.\n\nOpen the link below to read it and approve or reject it:\n\n{url}\n\nIf you have any questions, just reply to this email.",
        "share.measurement.subject" to "Measurement {number} for your approval · {company}",
        "share.measurement.body" to "{company} sent you measurement {number} of {project} to check.\n\nOpen the link below to read it and approve it or tell us what is wrong:\n\n{url}\n\nIf you have any questions, just reply to this email.",
        "share.issues.subject" to "Your site issues · {company}",
        "share.issues.body" to "{company} shares with you the site issues assigned to you.\n\nOpen the link below to see them and tell us when you start or finish each one:\n\n{url}\n\nIf you have any questions, just reply to this email.",
        "share.project.subject" to "Follow {project} · {company}",
        "share.project.body" to "{company} gives you a page to follow the progress of {project}.\n\nOpen the link below, no account needed:\n\n{url}\n\nIf you have any questions, just reply to this email.",
    )

    private val pt = mapOf(
        "test.subject" to "Email de teste de {company}",
        "test.body" to "Este é um email de teste de {company}. Se o consegue ler, os emails para os seus clientes saem de {account} com este nome, endereço de resposta e assinatura.\n\nNão precisa de responder.",
        "quote.subject" to "Orçamento {number} · {company}",
        "quote.body" to "Olá {name},\n\nSegue em anexo o orçamento {number}. Se tiver alguma dúvida, basta responder a este email.",
        "invoice.subject" to "Fatura {number} · {company}",
        "invoice.body" to "Olá {name},\n\nSegue em anexo a fatura {number}. Se tiver alguma dúvida, basta responder a este email.",
        "signoff" to "Com os melhores cumprimentos,\n{company}",
        "greeting.named" to "Olá {name},",
        "greeting.anon" to "Olá,",
        "share.change.subject" to "Alteração {number} para a sua aprovação · {company}",
        "share.change.body" to "{company} enviou-lhe uma alteração a {project}: {number}, {title}.\n\nAbra o link abaixo para a ler e aprovar ou rejeitar:\n\n{url}\n\nSe tiver alguma dúvida, basta responder a este email.",
        "share.measurement.subject" to "Medição {number} para a sua aprovação · {company}",
        "share.measurement.body" to "{company} enviou-lhe a medição {number} de {project} para verificar.\n\nAbra o link abaixo para a ler e aprová-la ou dizer-nos o que está errado:\n\n{url}\n\nSe tiver alguma dúvida, basta responder a este email.",
        "share.issues.subject" to "As suas ocorrências em obra · {company}",
        "share.issues.body" to "{company} partilha consigo as ocorrências de obra que lhe foram atribuídas.\n\nAbra o link abaixo para as ver e dizer-nos quando começa ou termina cada uma:\n\n{url}\n\nSe tiver alguma dúvida, basta responder a este email.",
        "share.project.subject" to "Acompanhe {project} · {company}",
        "share.project.body" to "{company} dá-lhe uma página para acompanhar o progresso de {project}.\n\nAbra o link abaixo, sem precisar de conta:\n\n{url}\n\nSe tiver alguma dúvida, basta responder a este email.",
    )

    private val es = mapOf(
        "test.subject" to "Email de prueba de {company}",
        "test.body" to "Este es un email de prueba de {company}. Si puedes leerlo, los emails a tus clientes salen desde {account} con este nombre, dirección de respuesta y firma.\n\nNo hace falta responder.",
        "quote.subject" to "Presupuesto {number} · {company}",
        "quote.body" to "Hola {name}:\n\nTe adjunto el presupuesto {number}. Si tienes cualquier duda, responde a este email.",
        "invoice.subject" to "Factura {number} · {company}",
        "invoice.body" to "Hola {name}:\n\nTe adjunto la factura {number}. Si tienes cualquier duda, responde a este email.",
        "signoff" to "Un saludo,\n{company}",
        "greeting.named" to "Hola {name}:",
        "greeting.anon" to "Hola:",
        "share.change.subject" to "Cambio {number} para tu aprobación · {company}",
        "share.change.body" to "{company} te envía un cambio de {project}: {number}, {title}.\n\nAbre el enlace de abajo para leerlo y aprobarlo o rechazarlo:\n\n{url}\n\nSi tienes cualquier duda, responde a este email.",
        "share.measurement.subject" to "Medición {number} para tu aprobación · {company}",
        "share.measurement.body" to "{company} te envía la medición {number} de {project} para que la revises.\n\nAbre el enlace de abajo para leerla y aprobarla o decirnos qué está mal:\n\n{url}\n\nSi tienes cualquier duda, responde a este email.",
        "share.issues.subject" to "Tus incidencias de obra · {company}",
        "share.issues.body" to "{company} comparte contigo las incidencias de obra que tienes asignadas.\n\nAbre el enlace de abajo para verlas y decirnos cuándo empiezas o terminas cada una:\n\n{url}\n\nSi tienes cualquier duda, responde a este email.",
        "share.project.subject" to "Sigue {project} · {company}",
        "share.project.body" to "{company} te da una página para seguir el avance de {project}.\n\nAbre el enlace de abajo, sin necesidad de cuenta:\n\n{url}\n\nSi tienes cualquier duda, responde a este email.",
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

    /**
     * The email that sends a link to someone outside the company: [kind] is `change`, `measurement`, `issues` or
     * `project`. It names the company, the item and the link, and no money; an account with a signature signs it already.
     */
    fun shareLink(locale: String, kind: String, name: String, params: Map<String, String>, signed: Boolean): Pair<String, String> {
        val all = params.entries.map { it.key to it.value }.toTypedArray()
        val subject = t(locale, "share.$kind.subject", *all)
        val hello = if (name.isBlank()) t(locale, "greeting.anon") else t(locale, "greeting.named", "name" to name)
        val body = hello + "\n\n" + t(locale, "share.$kind.body", *all)
        val company = params["company"].orEmpty()
        return subject to if (signed) body else body + "\n\n" + t(locale, "signoff", "company" to company)
    }

    /** The first draft for a quote or invoice; an account with a signature signs it already. */
    fun documentBody(locale: String, type: String, name: String, number: String, company: String, signed: Boolean): String {
        val body = t(locale, "$type.body", "name" to name, "number" to number)
        return if (signed) body else body + "\n\n" + t(locale, "signoff", "company" to company)
    }
}
