package com.rfm.edubot.crm

import kotlinx.serialization.Serializable

@Serializable
data class StandardItem(
    val id: String,
    val type: String,
    val category: String,
    val description: String,
    val unit: String,
    val defaultUnitPriceEur: Double,
)

object StandardItems {
    val defaults = listOf(
        StandardItem("srv-remocao-vidro", "service", "Claraboias", "Remocao de vidro existente e despejo autorizado", "servico", 320.0),
        StandardItem("srv-aplicacao-acrilico", "service", "Claraboias", "Aplicacao de novos vidros em acrilico", "m2", 95.0),
        StandardItem("srv-vedacao-sikaflex", "service", "Claraboias", "Aplicacao de vedante Sikaflex 11FC", "servico", 340.0),
        StandardItem("srv-lavagem-fachada", "service", "Fachadas", "Lavagem e preparacao de fachada", "m2", 4.5),
        StandardItem("srv-primario-fachada", "service", "Fachadas", "Aplicacao de primario fixador", "m2", 3.5),
        StandardItem("srv-pintura-fachada", "service", "Fachadas", "Pintura exterior com duas demaos", "m2", 8.5),
        StandardItem("srv-tratamento-fissuras", "service", "Fachadas", "Tratamento de microfissuras", "m", 12.0),
        StandardItem("srv-limpeza-telhado", "service", "Coberturas", "Limpeza e preparacao de telhado", "m2", 5.0),
        StandardItem("srv-selagem-rufos", "service", "Coberturas", "Selagem de rufos e pontos criticos", "m", 18.0),
        StandardItem("srv-membrana-liquida", "service", "Coberturas", "Aplicacao de membrana liquida impermeabilizante", "m2", 22.0),
        StandardItem("srv-demolicao-wc", "service", "Interiores", "Demolicao controlada e remocao de entulho", "servico", 450.0),
        StandardItem("srv-assentamento-ceramica", "service", "Interiores", "Assentamento de ceramica", "m2", 28.0),
        StandardItem("srv-pladur-divisoria", "service", "Interiores", "Execucao de divisoria em pladur", "m2", 42.0),
        StandardItem("mat-acrilico", "material", "Claraboias", "Vidro acrilico transparente", "m2", 58.0),
        StandardItem("mat-sikaflex-11fc", "material", "Claraboias", "Sikaflex 11FC", "un", 14.5),
        StandardItem("mat-primario", "material", "Fachadas", "Primario fixador exterior", "l", 9.0),
        StandardItem("mat-tinta-acrilica", "material", "Fachadas", "Tinta acrilica exterior premium", "l", 12.0),
        StandardItem("mat-membrana-liquida", "material", "Coberturas", "Membrana liquida elastica", "kg", 7.5),
        StandardItem("mat-fita-juntas", "material", "Coberturas", "Fita de reforco para juntas", "m", 2.2),
        StandardItem("mat-ceramica", "material", "Interiores", "Ceramica fornecida pelo empreiteiro", "m2", 24.0),
        StandardItem("mat-pladur", "material", "Interiores", "Placa de gesso cartonado", "m2", 8.0),
        StandardItem("mat-la-rocha", "material", "Interiores", "La de rocha para isolamento", "m2", 6.5),
    )

}
