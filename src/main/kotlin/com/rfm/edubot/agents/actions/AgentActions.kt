package com.rfm.edubot.agents.actions

import com.rfm.edubot.agents.registry.AgentAction
import com.rfm.edubot.bookings.model.BookingStatus

/** Every built-in action, in the order the builder lists them. */
object AgentActions {
    val builtIn: List<AgentAction> = listOf(
        WhatsAppSendAction,
        InstagramReplyAction,
        NotifyTeamAction,
        CreateTaskAction,
        CreateClientAction,
        UpdateClientAction,
        SetQuoteStatusAction,
        InvoiceFromQuoteAction,
        InvoiceOpenServicesAction,
        CreatePaymentAction,
        CreateServiceAction,
        SetBookingStatusAction("booking.confirm", BookingStatus.CONFIRMED),
        SetBookingStatusAction("booking.cancel", BookingStatus.CANCELLED),
        CreateBookingAction,
        GeneratePdfAction,
        SummaryAction,
        WaitAction,
        BranchAction,
        StopAction,
    )
}
