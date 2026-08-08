package com.rfm.edubot.mobile.core.localization

import com.rfm.edubot.mobile.core.common.SessionError
import com.rfm.edubot.mobile.core.common.VoiceInputError

data class MobileCopy(
    val loginTitle: String, val loginDescription: String, val email: String, val password: String, val signIn: String, val signOut: String,
    val overview: String, val refresh: String, val messagesToday: String, val messages: String, val contacts: String, val conversations: String,
    val inbox: String, val backToInbox: String, val message: String, val send: String, val webReplyUnavailable: String, val assistant: String,
    val newConversation: String, val confirm: String, val cancel: String, val persona: String, val instructions: String, val save: String,
    val version: String, val settings: String, val channels: String, val language: String, val quotes: String, val invoices: String,
    val clientName: String, val clientPhone: String, val clientAddress: String, val createClient: String,
    val itemId: String, val itemDescription: String, val itemCategory: String, val itemUnit: String, val itemPrice: String, val createCatalogItem: String,
    val more: String, val synced: String, val operationalSnapshot: String, val cacheReady: String, val cacheDescription: String,
    val noAssistantThreads: String, val startAssistantThread: String, val active: String, val modules: Map<String, String>, val errors: Map<SessionError, String>,
    val voice: String = "Voice", val stopListening: String = "Stop", val voicePermissionDenied: String = "Microphone and speech recognition permission is required.", val voiceUnavailable: String = "Speech recognition is unavailable on this device.", val voiceRecognitionFailed: String = "Speech could not be recognized. Please try again.",
    val newDocument: String = "New", val clientId: String = "Client ID", val quantity: String = "Quantity", val validUntil: String = "Valid until (YYYY-MM-DD)", val dueDate: String = "Due date (YYYY-MM-DD)", val notes: String = "Notes", val createQuote: String = "Create quote", val createInvoice: String = "Create invoice",
) {
    fun module(id: String): String = modules[id] ?: id
    fun moduleDescription(id: String): String = when (id) { "contacts" -> contacts; "clients" -> "CRM"; "quotes", "invoices", "catalog" -> "CRM"; "persona" -> persona; "settings" -> settings; else -> "" }
    fun error(error: SessionError): String = errors.getValue(error)
    fun error(error: String): String = errors[if (error == "send" || error == "update") SessionError.INVALID_CREDENTIALS else SessionError.CONNECTION_FAILED].orEmpty()
    fun voiceError(error: VoiceInputError): String = when (error) {
        VoiceInputError.PERMISSION_DENIED -> voicePermissionDenied
        VoiceInputError.UNAVAILABLE -> voiceUnavailable
        VoiceInputError.RECOGNITION_FAILED -> voiceRecognitionFailed
    }
}

object MobileStrings {
    val english = MobileCopy(
        "Sign in", "Manage your assistant from anywhere.", "Email", "Password", "Sign in", "Sign out", "Overview", "Refresh", "Messages today", "Messages", "Contacts", "Conversations", "Inbox", "Back", "Message", "Send", "Replies are unavailable for website conversations.", "Assistant", "New", "Confirm", "Cancel", "Persona", "Instructions", "Save", "Version", "Settings", "Channels", "Language", "Quotes", "Invoices", "Client name", "Phone", "Address", "Create client", "Item ID", "Description", "Category", "Unit", "Price", "Create catalog item", "More", "Synced", "Operational snapshot", "Cache ready", "Showing the last successful snapshot when offline.", "No assistant threads", "Start a new thread to work with your assistant.", "Active",
        mapOf("overview" to "Overview", "conversations" to "Inbox", "contacts" to "Contacts", "ai-assistant" to "Assistant", "clients" to "Clients", "quotes" to "Quotes", "invoices" to "Invoices", "catalog" to "Catalog", "persona" to "Persona", "settings" to "Settings"),
        mapOf(SessionError.MISSING_CREDENTIALS to "Enter your email and password.", SessionError.INVALID_CREDENTIALS to "Unable to sign in. Check your credentials and try again.", SessionError.SESSION_EXPIRED to "Your session has expired. Please sign in again.", SessionError.CONNECTION_FAILED to "Unable to connect to the dashboard."),
    )
    private val portuguese = english.copy(loginTitle = "Entrar", loginDescription = "Gira o seu assistente em qualquer lugar.", signIn = "Entrar", signOut = "Sair", overview = "Visão geral", refresh = "Atualizar", messagesToday = "Mensagens hoje", contacts = "Contactos", conversations = "Conversas", inbox = "Caixa de entrada", send = "Enviar", assistant = "Assistente", newConversation = "Novo", confirm = "Confirmar", cancel = "Cancelar", instructions = "Instruções", save = "Guardar", settings = "Definições", channels = "Canais", language = "Idioma", more = "Mais", voice = "Voz", stopListening = "Parar", voicePermissionDenied = "É necessária autorização para o microfone e reconhecimento de voz.", voiceUnavailable = "O reconhecimento de voz não está disponível neste dispositivo.", voiceRecognitionFailed = "Não foi possível reconhecer a fala. Tente novamente.", newDocument = "Novo", clientId = "ID do cliente", quantity = "Quantidade", validUntil = "Válido até (AAAA-MM-DD)", dueDate = "Data de vencimento (AAAA-MM-DD)", notes = "Notas", createQuote = "Criar orçamento", createInvoice = "Criar fatura")
    private val spanish = english.copy(loginTitle = "Iniciar sesión", loginDescription = "Gestiona tu asistente desde cualquier lugar.", signIn = "Iniciar sesión", signOut = "Cerrar sesión", overview = "Resumen", refresh = "Actualizar", messagesToday = "Mensajes de hoy", conversations = "Conversaciones", inbox = "Bandeja de entrada", send = "Enviar", assistant = "Asistente", newConversation = "Nuevo", confirm = "Confirmar", cancel = "Cancelar", persona = "Personalidad", instructions = "Instruções", save = "Guardar", settings = "Configuración", channels = "Canales", language = "Idioma", more = "Más", voice = "Voz", stopListening = "Parar", voicePermissionDenied = "Se requiere permiso para el micrófono y el reconocimiento de voz.", voiceUnavailable = "El reconocimiento de voz no está disponible en este dispositivo.", voiceRecognitionFailed = "No se pudo reconocer la voz. Inténtalo de nuevo.", newDocument = "Nuevo", clientId = "ID del cliente", quantity = "Cantidad", validUntil = "Válido hasta (AAAA-MM-DD)", dueDate = "Data de vencimiento (AAAA-MM-DD)", notes = "Notas", createQuote = "Crear presupuesto", createInvoice = "Crear factura")
    fun forLocale(locale: String): MobileCopy = when (locale.lowercase().substringBefore('-')) { "pt" -> portuguese; "es" -> spanish; else -> english }
}
