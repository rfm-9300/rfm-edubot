package com.rfm.edubot.building

import com.rfm.edubot.tenant.model.TenantLocales
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaLocalDateTime
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Text the server writes itself, in the company's language: a measurement's invoice and the retention's, and
 * Building's PDFs (`pdf.*`), with the numbers and dates written the way that language writes them.
 */
internal object BuildingCopy {
    private val en = mapOf(
        "measurement" to "Measurement {n}",
        "retention" to "Retention held ({pct}%)",
        "retentionRelease" to "Release of the retention held · {project}",
        "pdf.measurementTitle" to "Measurement no. {n}",
        "pdf.diaryTitle" to "Site diary",
        "pdf.project" to "Project",
        "pdf.client" to "Client",
        "pdf.site" to "Site",
        "pdf.periodEnd" to "Measured up to",
        "pdf.period" to "Period",
        "pdf.approved" to "Approved",
        "pdf.invoice" to "Invoice",
        "pdf.manager" to "Site manager",
        "pdf.taxId" to "VAT no. {id}",
        "pdf.status.DRAFT" to "Draft",
        "pdf.status.APPROVED" to "Approved",
        "pdf.status.INVOICED" to "Invoiced",
        "pdf.thLine" to "Line",
        "pdf.thUnit" to "Unit",
        "pdf.thContract" to "Contract",
        "pdf.thPrevious" to "Before",
        "pdf.thPeriod" to "This period",
        "pdf.thToDate" to "To date",
        "pdf.thValue" to "Value",
        "pdf.totalContract" to "Contract",
        "pdf.totalPrevious" to "Measured before",
        "pdf.totalToDate" to "Measured to date",
        "pdf.totalPeriod" to "This period",
        "pdf.totalNet" to "Net to invoice",
        "pdf.done" to "Done to date: {pct}",
        "pdf.notes" to "Notes",
        "pdf.contractor" to "The contractor",
        "pdf.owner" to "The client",
        "pdf.signDate" to "Date",
        "pdf.page" to "Page {n} of {total}",
        "pdf.weather" to "Weather",
        "pdf.workers" to "{n} on site",
        "pdf.lostHours" to "{n} h lost",
        "pdf.workDone" to "Work done",
        "pdf.equipment" to "Equipment",
        "pdf.materials" to "Materials received",
        "pdf.visitors" to "Visits and inspections",
        "pdf.incidents" to "Occurrences",
        "pdf.progress" to "Progress",
        "pdf.phaseMoved" to "{name}: {from}% to {to}%",
        "pdf.morePhotos" to "+{n} more photos in the dashboard",
        "pdf.noEntries" to "No diary entries in this period.",
        "pdf.weather.CLEAR" to "Sunny",
        "pdf.weather.CLOUDY" to "Cloudy",
        "pdf.weather.RAIN" to "Rain",
        "pdf.weather.WIND" to "Wind",
        "pdf.weather.STORM" to "Storm",
        "pdf.weather.FOG" to "Fog",
        "pdf.changeTitle" to "Change {n}",
        "pdf.kind" to "Type",
        "pdf.kind.ADDITION" to "Extra work",
        "pdf.kind.OMISSION" to "Work taken out",
        "pdf.sent" to "Sent to the client",
        "pdf.description" to "Description",
        "pdf.changeStatus.DRAFT" to "Draft",
        "pdf.changeStatus.SENT" to "Waiting for the client",
        "pdf.changeStatus.APPROVED" to "Approved",
        "pdf.changeStatus.REJECTED" to "Rejected",
        "pdf.thQty" to "Quantity",
        "pdf.thUnitPrice" to "Unit price",
        "pdf.thTotal" to "Total",
        "pdf.changeTotal.ADDITION" to "Added to the contract",
        "pdf.changeTotal.OMISSION" to "Taken off the contract",
        "pdf.days" to "Time",
        "pdf.daysNone" to "No change to the end date",
        "pdf.daysMore1" to "1 more day",
        "pdf.daysLess1" to "1 day less",
        "pdf.daysMore" to "{n} more days",
        "pdf.daysLess" to "{n} days less",
        "pdf.effectTitle" to "Effect on the contract",
        "pdf.contractBefore" to "Contract before this change",
        "pdf.contractAfter" to "Contract with this change",
        "pdf.endBefore" to "Planned completion before",
        "pdf.endAfter" to "Planned completion with this change",
        "pdf.decisionTitle" to "Decision",
        "pdf.awaiting" to "Waiting for the client's decision.",
        "pdf.decided.APPROVED" to "Approved by {name} on {when}",
        "pdf.decided.REJECTED" to "Rejected by {name} on {when}",
        "pdf.via.CLIENT_LINK" to "The client answered from the change's link.",
        "pdf.via.TEAM" to "Recorded by the company's team.",
        "pdf.proof" to "Sent from the address {ip} · {device}",
    )

    private val pt = mapOf(
        "measurement" to "Auto n.º {n}",
        "retention" to "Retenção de garantia ({pct} %)",
        "retentionRelease" to "Devolução da retenção de garantia · {project}",
        "pdf.measurementTitle" to "Auto de medição n.º {n}",
        "pdf.diaryTitle" to "Diário de obra",
        "pdf.project" to "Obra",
        "pdf.client" to "Cliente",
        "pdf.site" to "Local da obra",
        "pdf.periodEnd" to "Medido até",
        "pdf.period" to "Período",
        "pdf.approved" to "Aprovado",
        "pdf.invoice" to "Fatura",
        "pdf.manager" to "Encarregado",
        "pdf.taxId" to "NIF {id}",
        "pdf.status.DRAFT" to "Rascunho",
        "pdf.status.APPROVED" to "Aprovado",
        "pdf.status.INVOICED" to "Faturado",
        "pdf.thLine" to "Artigo",
        "pdf.thUnit" to "Unid.",
        "pdf.thContract" to "Contrato",
        "pdf.thPrevious" to "Anterior",
        "pdf.thPeriod" to "Neste auto",
        "pdf.thToDate" to "Acumulado",
        "pdf.thValue" to "Valor",
        "pdf.totalContract" to "Contrato",
        "pdf.totalPrevious" to "Medido anteriormente",
        "pdf.totalToDate" to "Medido acumulado",
        "pdf.totalPeriod" to "Neste auto",
        "pdf.totalNet" to "Líquido a faturar",
        "pdf.done" to "Executado: {pct}",
        "pdf.notes" to "Observações",
        "pdf.contractor" to "O empreiteiro",
        "pdf.owner" to "O dono de obra",
        "pdf.signDate" to "Data",
        "pdf.page" to "Página {n} de {total}",
        "pdf.weather" to "Tempo",
        "pdf.workers" to "{n} em obra",
        "pdf.lostHours" to "{n} h perdidas",
        "pdf.workDone" to "Trabalhos executados",
        "pdf.equipment" to "Equipamento",
        "pdf.materials" to "Materiais recebidos",
        "pdf.visitors" to "Visitas e inspeções",
        "pdf.incidents" to "Ocorrências",
        "pdf.progress" to "Avanço",
        "pdf.phaseMoved" to "{name}: de {from} % para {to} %",
        "pdf.morePhotos" to "+{n} fotografias no painel",
        "pdf.noEntries" to "Sem registos neste período.",
        "pdf.weather.CLEAR" to "Sol",
        "pdf.weather.CLOUDY" to "Nublado",
        "pdf.weather.RAIN" to "Chuva",
        "pdf.weather.WIND" to "Vento",
        "pdf.weather.STORM" to "Tempestade",
        "pdf.weather.FOG" to "Nevoeiro",
        "pdf.changeTitle" to "Alteração {n}",
        "pdf.kind" to "Tipo",
        "pdf.kind.ADDITION" to "Trabalhos a mais",
        "pdf.kind.OMISSION" to "Trabalhos a menos",
        "pdf.sent" to "Enviada ao cliente",
        "pdf.description" to "Descrição",
        "pdf.changeStatus.DRAFT" to "Rascunho",
        "pdf.changeStatus.SENT" to "À espera do cliente",
        "pdf.changeStatus.APPROVED" to "Aprovada",
        "pdf.changeStatus.REJECTED" to "Recusada",
        "pdf.thQty" to "Quant.",
        "pdf.thUnitPrice" to "Preço unit.",
        "pdf.thTotal" to "Total",
        "pdf.changeTotal.ADDITION" to "Acresce ao contrato",
        "pdf.changeTotal.OMISSION" to "Deduz ao contrato",
        "pdf.days" to "Prazo",
        "pdf.daysNone" to "Sem alteração do prazo",
        "pdf.daysMore1" to "Mais 1 dia",
        "pdf.daysLess1" to "Menos 1 dia",
        "pdf.daysMore" to "Mais {n} dias",
        "pdf.daysLess" to "Menos {n} dias",
        "pdf.effectTitle" to "Efeito no contrato",
        "pdf.contractBefore" to "Contrato antes desta alteração",
        "pdf.contractAfter" to "Contrato com esta alteração",
        "pdf.endBefore" to "Conclusão prevista antes",
        "pdf.endAfter" to "Conclusão prevista com esta alteração",
        "pdf.decisionTitle" to "Decisão",
        "pdf.awaiting" to "À espera da decisão do cliente.",
        "pdf.decided.APPROVED" to "Aprovada por {name} em {when}",
        "pdf.decided.REJECTED" to "Recusada por {name} em {when}",
        "pdf.via.CLIENT_LINK" to "O cliente respondeu pelo link da alteração.",
        "pdf.via.TEAM" to "Registada pela equipa da empresa.",
        "pdf.proof" to "Enviada a partir do endereço {ip} · {device}",
    )

    private val es = mapOf(
        "measurement" to "Certificación n.º {n}",
        "retention" to "Retención de garantía ({pct} %)",
        "retentionRelease" to "Devolución de la retención de garantía · {project}",
        "pdf.measurementTitle" to "Certificación n.º {n}",
        "pdf.diaryTitle" to "Diario de obra",
        "pdf.project" to "Obra",
        "pdf.client" to "Cliente",
        "pdf.site" to "Emplazamiento",
        "pdf.periodEnd" to "Medido hasta",
        "pdf.period" to "Periodo",
        "pdf.approved" to "Aprobada",
        "pdf.invoice" to "Factura",
        "pdf.manager" to "Jefe de obra",
        "pdf.taxId" to "NIF {id}",
        "pdf.status.DRAFT" to "Borrador",
        "pdf.status.APPROVED" to "Aprobada",
        "pdf.status.INVOICED" to "Facturada",
        "pdf.thLine" to "Partida",
        "pdf.thUnit" to "Unid.",
        "pdf.thContract" to "Contrato",
        "pdf.thPrevious" to "Anterior",
        "pdf.thPeriod" to "Este periodo",
        "pdf.thToDate" to "A origen",
        "pdf.thValue" to "Importe",
        "pdf.totalContract" to "Contrato",
        "pdf.totalPrevious" to "Certificado anterior",
        "pdf.totalToDate" to "Certificado a origen",
        "pdf.totalPeriod" to "En esta certificación",
        "pdf.totalNet" to "Neto a facturar",
        "pdf.done" to "Ejecutado: {pct}",
        "pdf.notes" to "Observaciones",
        "pdf.contractor" to "El contratista",
        "pdf.owner" to "La propiedad",
        "pdf.signDate" to "Fecha",
        "pdf.page" to "Página {n} de {total}",
        "pdf.weather" to "Tiempo",
        "pdf.workers" to "{n} en obra",
        "pdf.lostHours" to "{n} h perdidas",
        "pdf.workDone" to "Trabajos realizados",
        "pdf.equipment" to "Maquinaria",
        "pdf.materials" to "Materiales recibidos",
        "pdf.visitors" to "Visitas e inspecciones",
        "pdf.incidents" to "Incidencias",
        "pdf.progress" to "Avance",
        "pdf.phaseMoved" to "{name}: del {from} % al {to} %",
        "pdf.morePhotos" to "+{n} fotos en el panel",
        "pdf.noEntries" to "Sin partes en este periodo.",
        "pdf.weather.CLEAR" to "Sol",
        "pdf.weather.CLOUDY" to "Nublado",
        "pdf.weather.RAIN" to "Lluvia",
        "pdf.weather.WIND" to "Viento",
        "pdf.weather.STORM" to "Tormenta",
        "pdf.weather.FOG" to "Niebla",
        "pdf.changeTitle" to "Modificado {n}",
        "pdf.kind" to "Tipo",
        "pdf.kind.ADDITION" to "Trabajos adicionales",
        "pdf.kind.OMISSION" to "Trabajos suprimidos",
        "pdf.sent" to "Enviado al cliente",
        "pdf.description" to "Descripción",
        "pdf.changeStatus.DRAFT" to "Borrador",
        "pdf.changeStatus.SENT" to "Esperando al cliente",
        "pdf.changeStatus.APPROVED" to "Aprobado",
        "pdf.changeStatus.REJECTED" to "Rechazado",
        "pdf.thQty" to "Cant.",
        "pdf.thUnitPrice" to "Precio unit.",
        "pdf.thTotal" to "Importe",
        "pdf.changeTotal.ADDITION" to "Se suma al contrato",
        "pdf.changeTotal.OMISSION" to "Se descuenta del contrato",
        "pdf.days" to "Plazo",
        "pdf.daysNone" to "Sin cambio de plazo",
        "pdf.daysMore1" to "1 día más",
        "pdf.daysLess1" to "1 día menos",
        "pdf.daysMore" to "{n} días más",
        "pdf.daysLess" to "{n} días menos",
        "pdf.effectTitle" to "Efecto en el contrato",
        "pdf.contractBefore" to "Contrato antes de este modificado",
        "pdf.contractAfter" to "Contrato con este modificado",
        "pdf.endBefore" to "Fin previsto antes",
        "pdf.endAfter" to "Fin previsto con este modificado",
        "pdf.decisionTitle" to "Decisión",
        "pdf.awaiting" to "Esperando la decisión del cliente.",
        "pdf.decided.APPROVED" to "Aprobado por {name} el {when}",
        "pdf.decided.REJECTED" to "Rechazado por {name} el {when}",
        "pdf.via.CLIENT_LINK" to "El cliente respondió desde el enlace del modificado.",
        "pdf.via.TEAM" to "Registrado por el equipo de la empresa.",
        "pdf.proof" to "Enviado desde la dirección {ip} · {device}",
    )

    fun t(locale: String, key: String, vararg params: Pair<String, String>): String {
        val catalog = when (language(locale)) {
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
        return if (language(locale) == "en") plain else plain.replace('.', ',')
    }

    /** "45%" in English, "45 %" otherwise, to one decimal at most. */
    fun pct(locale: String, value: Double): String {
        val number = decimal(locale, value, 1)
        return if (language(locale) == "en") "$number%" else "$number %"
    }

    /** €1,234.56 in English, 1 234,56 € in Portuguese and 1.234,56 € in Spanish. */
    fun money(locale: String, cents: Long): String {
        val language = language(locale)
        val sign = if (cents < 0) "-" else ""
        val whole = group((abs(cents) / 100).toString(), separator = when (language) { "en" -> ","; "es" -> "."; else -> " " })
        val fraction = (abs(cents) % 100).toString().padStart(2, '0')
        return if (language == "en") "$sign€$whole.$fraction" else "$sign$whole,$fraction €"
    }

    /** A quantity to [places] decimals at most, without trailing zeros. */
    fun decimal(locale: String, value: Double, places: Int = 3): String {
        val plain = BigDecimal.valueOf(value).setScale(places, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return if (language(locale) == "en") plain else plain.replace('.', ',')
    }

    /** 21/10/2026. */
    fun date(day: LocalDate): String = day.toJavaLocalDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))

    /** 21/10/2026 14:32, in the company's time. */
    fun dateTime(at: LocalDateTime): String = at.toJavaLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))

    /** "Tuesday, 14 October 2026", "Terça-feira, 14 de outubro de 2026", "Martes, 14 de octubre de 2026". */
    fun longDate(locale: String, day: LocalDate): String {
        val language = language(locale)
        val pattern = if (language == "en") "EEEE, d MMMM yyyy" else "EEEE, d 'de' MMMM 'de' yyyy"
        val javaLocale = when (language) {
            "en" -> Locale.UK
            "es" -> Locale.forLanguageTag("es-ES")
            else -> Locale.forLanguageTag("pt-PT")
        }
        return day.toJavaLocalDate().format(DateTimeFormatter.ofPattern(pattern, javaLocale)).replaceFirstChar { it.uppercase(javaLocale) }
    }

    private fun language(locale: String): String = when (TenantLocales.normalize(locale)) {
        "en" -> "en"
        "es" -> "es"
        else -> "pt"
    }

    private fun group(digits: String, separator: String): String = digits.reversed().chunked(3).joinToString(separator).reversed()
}
