package com.rfm.edubot.web

import kotlinx.coroutines.channels.SendChannel
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide registry of live website chat sessions. Unlike WhatsApp/Instagram — where replies
 * are pushed to Meta's Graph API — a web reply must reach the browser that is currently connected.
 * Each open WebSocket registers its outgoing sink here keyed by session id, so the async pipeline's
 * [com.rfm.edubot.web.WebChatOutboundClient] can deliver messages (including mid-turn feedback) back
 * to the right socket without holding a reference to it.
 */
class WebChannelRegistry {
    private val log = LoggerFactory.getLogger("WebChannelRegistry")
    private val sessions = ConcurrentHashMap<String, SendChannel<String>>()

    fun register(sessionId: String, sink: SendChannel<String>) {
        sessions[sessionId] = sink
    }

    fun unregister(sessionId: String) {
        sessions.remove(sessionId)
    }

    /** Returns true if a live session received the frame. */
    fun send(sessionId: String, frame: String): Boolean {
        val sink = sessions[sessionId]
        if (sink == null) {
            log.warn("Dropping web frame for disconnected session: sessionId={}", sessionId)
            return false
        }
        val result = sink.trySend(frame)
        if (result.isFailure) {
            log.warn("Failed to enqueue web frame: sessionId={} closed={}", sessionId, result.isClosed)
            return false
        }
        return true
    }
}
