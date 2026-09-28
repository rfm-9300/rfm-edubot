package com.rfm.edubot.messaging

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Runs submitted work on a fixed set of lanes. Work with the same key always lands on the same lane,
 * so one conversation's messages are handled one at a time and in arrival order, and the lane count
 * caps how many messages (and LLM calls) run at once. Unrelated keys that share a lane wait on each
 * other, so the count trades throughput against the concurrency cap.
 */
class ConversationLanes(scope: CoroutineScope, laneCount: Int = DEFAULT_LANE_COUNT) {
    private val log = LoggerFactory.getLogger("ConversationLanes")
    private val lanes = List(laneCount) { Channel<suspend () -> Unit>(Channel.UNLIMITED) }

    init {
        require(laneCount > 0) { "laneCount must be positive" }
        for (lane in lanes) {
            scope.launch {
                for (work in lane) {
                    try {
                        work()
                    } catch (e: Exception) {
                        ensureActive()
                        log.error("Conversation lane job failed: {}", e.message, e)
                    }
                }
            }
        }
    }

    fun submit(key: String, work: suspend () -> Unit) {
        lanes[laneOf(key)].trySend(work).getOrThrow()
    }

    internal fun laneOf(key: String): Int = Math.floorMod(key.hashCode(), lanes.size)

    companion object {
        const val DEFAULT_LANE_COUNT = 16
    }
}
