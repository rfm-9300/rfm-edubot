package com.rfm.edubot.building

import com.rfm.edubot.tenant.model.TenantLocales
import java.math.BigDecimal

/** Text the server writes itself on a measurement's invoice and the retention's, in the company's language. */
internal object BuildingCopy {
    private val en = mapOf(
        "measurement" to "Measurement {n}",
        "retention" to "Retention held ({pct}%)",
        "retentionRelease" to "Release of the retention held · {project}",
    )

    private val pt = mapOf(
        "measurement" to "Auto n.º {n}",
        "retention" to "Retenção de garantia ({pct} %)",
        "retentionRelease" to "Devolução da retenção de garantia · {project}",
    )

    private val es = mapOf(
        "measurement" to "Certificación n.º {n}",
        "retention" to "Retención de garantía ({pct} %)",
        "retentionRelease" to "Devolución de la retención de garantía · {project}",
    )

    fun t(locale: String, key: String, vararg params: Pair<String, String>): String {
        val language = TenantLocales.normalize(locale)
        val catalog = when (language) {
            "en" -> en
            "es" -> es
            else -> pt
        }
        val template = catalog[key] ?: en[key] ?: key
        return params.fold(template) { text, (name, value) -> text.replace("{$name}", value) }
    }

    /** "5" or "2,5" (a decimal point in English). */
    fun percent(locale: String, value: Double): String {
        val plain = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
        return if (TenantLocales.normalize(locale) == "en") plain else plain.replace('.', ',')
    }
}
