package com.rfm.edubot.crm

data class ServiceTemplate(
    val id: String,
    val name: String,
    val category: String,
    val defaultDuration: String,
    val defaultWarranty: String,
    val paymentTerms: List<String>,
    val included: List<String>,
    val excluded: List<String>,
    val clientResponsibilities: List<String>,
    val workItems: List<String>,
    val notes: List<String>,
)

object ServiceTemplates {
    val all = listOf(
        ServiceTemplate(
            id = "claraboia-reabilitacao",
            name = "Reabilitacao de claraboia",
            category = "Impermeabilizacao / claraboia",
            defaultDuration = "2 dias",
            defaultWarranty = "5 anos nos trabalhos executados",
            paymentTerms = listOf("15% adjudicacao", "35% inicio das obras", "35% meio da obra", "15% final"),
            included = listOf("Seguro de Acidente de Trabalho", "Responsabilidade Civil"),
            excluded = listOf("Trabalhos nao previstos no orcamento", "Focos de infiltracao nao identificados antes da visita"),
            clientResponsibilities = listOf("Fornecimento de agua e electricidade caso necessario", "Acesso e passagem ao local dos trabalhos"),
            workItems = listOf(
                "Remocao de todo o vidro atual e despejo em vazadouro autorizado",
                "Aplicacao de novos vidros em acrilico",
                "Aplicacao de vedante Sikaflex 11FC",
            ),
            notes = listOf("Possiveis focos de infiltracao no telhado so podem ser orcados mediante visita ao local"),
        ),
        ServiceTemplate(
            id = "fachada-pintura",
            name = "Pintura exterior de fachada",
            category = "Pintura / fachada",
            defaultDuration = "5 a 8 dias",
            defaultWarranty = "3 anos nos trabalhos de pintura",
            paymentTerms = listOf("20% adjudicacao", "40% inicio dos trabalhos", "40% conclusao"),
            included = listOf("Protecao de pavimentos e caixilharias", "Primario fixador", "Duas demaos de tinta acrilica exterior"),
            excluded = listOf("Reparacao estrutural de fissuras profundas", "Licencas municipais", "Andaimes especiais nao previstos"),
            clientResponsibilities = listOf("Garantir acesso a fachadas e varandas", "Remover objetos soltos junto das paredes"),
            workItems = listOf("Lavagem de fachada", "Raspagem de tinta solta", "Tratamento de microfissuras", "Aplicacao de primario", "Pintura final"),
            notes = listOf("Cor final a confirmar pelo cliente antes da aplicacao"),
        ),
        ServiceTemplate(
            id = "telhado-impermeabilizacao",
            name = "Impermeabilizacao de telhado",
            category = "Cobertura / impermeabilizacao",
            defaultDuration = "3 a 6 dias",
            defaultWarranty = "5 anos no sistema aplicado",
            paymentTerms = listOf("30% adjudicacao", "40% inicio", "30% final"),
            included = listOf("Limpeza da cobertura", "Tratamento de pontos criticos", "Aplicacao de membrana liquida elastica"),
            excluded = listOf("Substituicao de estrutura em madeira", "Telhas partidas nao visiveis antes da intervencao"),
            clientResponsibilities = listOf("Disponibilizar ponto de agua", "Autorizar acesso a zonas comuns se aplicavel"),
            workItems = listOf("Inspecao e limpeza", "Selagem de juntas", "Tratamento de rufos", "Aplicacao de duas camadas impermeabilizantes"),
            notes = listOf("Trabalhos dependem de condicoes meteorologicas favoraveis"),
        ),
        ServiceTemplate(
            id = "wc-remodelacao",
            name = "Remodelacao parcial de casa de banho",
            category = "Remodelacao interior",
            defaultDuration = "7 a 12 dias",
            defaultWarranty = "2 anos nos trabalhos executados",
            paymentTerms = listOf("25% adjudicacao", "35% inicio", "25% apos assentamento", "15% final"),
            included = listOf("Remocao de revestimentos existentes", "Assentamento de ceramica", "Montagem de loucas fornecidas"),
            excluded = listOf("Fornecimento de ceramicas e loucas", "Alteracoes profundas de canalizacao nao previstas"),
            clientResponsibilities = listOf("Escolher materiais antes do inicio", "Garantir elevador ou acesso para entulho"),
            workItems = listOf("Demolicao controlada", "Regularizacao de paredes", "Impermeabilizacao de zona de duche", "Assentamento e remates"),
            notes = listOf("Medidas finais devem ser confirmadas em visita tecnica"),
        ),
        ServiceTemplate(
            id = "infiltracao-varanda",
            name = "Tratamento de infiltracao em varanda",
            category = "Impermeabilizacao / varanda",
            defaultDuration = "2 a 4 dias",
            defaultWarranty = "4 anos no ponto intervencionado",
            paymentTerms = listOf("20% adjudicacao", "50% inicio", "30% final"),
            included = listOf("Remocao de silicone degradado", "Tratamento de juntas", "Aplicacao de impermeabilizante transparente"),
            excluded = listOf("Substituicao integral de pavimento", "Reparacoes no apartamento inferior"),
            clientResponsibilities = listOf("Remover mobiliario da varanda", "Permitir teste de agua no final"),
            workItems = listOf("Diagnostico visual", "Limpeza e preparacao", "Selagem perimetral", "Aplicacao de produto impermeabilizante"),
            notes = listOf("Pode ser necessario teste adicional se a origem da infiltracao persistir"),
        ),
        ServiceTemplate(
            id = "pladur-divisoria",
            name = "Execucao de divisoria em pladur",
            category = "Interiores / pladur",
            defaultDuration = "1 a 3 dias",
            defaultWarranty = "2 anos nos trabalhos executados",
            paymentTerms = listOf("40% adjudicacao", "40% inicio", "20% final"),
            included = listOf("Estrutura metalica", "Placas de gesso cartonado", "Barramento de juntas", "Lixagem final"),
            excluded = listOf("Pintura final", "Instalacoes eletricas no interior da divisoria"),
            clientResponsibilities = listOf("Indicar local exato da divisoria", "Confirmar se existem cabos ou tubagens ocultas"),
            workItems = listOf("Marcacao", "Montagem de perfis", "Fixacao das placas", "Tratamento de juntas"),
            notes = listOf("Pode incluir isolamento acustico mediante pedido"),
        ),
    )

    fun search(query: String?): List<ServiceTemplate> {
        val term = query?.trim()?.lowercase().orEmpty()
        if (term.isBlank()) return all
        return all.filter { template ->
            listOf(template.id, template.name, template.category).any { it.lowercase().contains(term) } ||
                template.workItems.any { it.lowercase().contains(term) }
        }
    }
}
