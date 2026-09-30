package com.rfm.edubot.agents.runtime

import com.rfm.edubot.agents.AgentsModule
import com.rfm.edubot.agents.model.Agent
import com.rfm.edubot.agents.model.RunTrigger
import com.rfm.edubot.agents.registry.TriggerTypes
import com.rfm.edubot.agents.registry.bool
import com.rfm.edubot.agents.registry.string
import com.rfm.edubot.agents.registry.strings
import com.rfm.edubot.events.ActorType
import com.rfm.edubot.events.DomainEvent
import com.rfm.edubot.events.DomainEventInterest
import com.rfm.edubot.events.DomainEventLog
import com.rfm.edubot.events.DomainEventSignal
import com.rfm.edubot.events.DomainEventTypes
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantTimeZones
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Reads the domain event outbox: each event ends matching open runs through their agents' exit rules,
 * then starts runs for agents whose event triggers match. Events an agent caused never trigger that
 * agent again, and nothing reacts past [DomainEventTypes.MAX_DEPTH].
 */
class AgentDispatcher(
    private val module: AgentsModule,
    private val events: DomainEventLog,
    private val tenants: suspend (ObjectId) -> Tenant?,
    private val starter: AgentRunStarter,
) {
    private val log = LoggerFactory.getLogger("AgentDispatcher")
    private val clock get() = module.services.clock
    private val activeByTenant = ConcurrentHashMap<ObjectId, List<Agent>>()

    fun start(scope: CoroutineScope): Job = scope.launch {
        warm()
        while (isActive) {
            try {
                drain()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Agent dispatcher failed: {}", e.message, e)
            }
            DomainEventSignal.await(POLL)
        }
    }

    /** Loads active agents at boot so on-demand events are recorded from the first message on. */
    suspend fun warm() {
        module.agents.tenantsWithActiveAgents().forEach { refresh(it) }
    }

    /** Called when a company's agents change (saved, activated, paused, archived). */
    suspend fun refresh(tenantId: ObjectId) {
        val active = module.agents.activeFor(tenantId)
        if (active.isEmpty()) activeByTenant.remove(tenantId) else activeByTenant[tenantId] = active
        val listened = active.flatMap { agent ->
            agent.definition.triggers.filter { it.type == TriggerTypes.EVENT }.mapNotNull { it.config.string("event") } + agent.definition.exitRules.map { it.event }
        }.toSet()
        DomainEventInterest.set(tenantId, listened intersect DomainEventTypes.onDemand)
    }

    suspend fun drain(limit: Int = 500) {
        repeat(limit) {
            val event = events.claimNext(clock() - STALE_CLAIM) ?: return
            try {
                handle(event)
                events.markDone(event.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Could not dispatch event {} ({}): {}", event.id, event.type, e.message, e)
                events.markFailed(event.id, e.message ?: "dispatch_failed")
            }
        }
    }

    suspend fun handle(event: DomainEvent) {
        val agents = activeByTenant[event.tenantId] ?: return
        if (event.depth > DomainEventTypes.MAX_DEPTH) {
            log.warn("Ignoring event {} ({}) at depth {}: agents are feeding each other", event.id, event.type, event.depth)
            return
        }
        val tenant = tenants(event.tenantId) ?: return
        applyExitRules(tenant, agents, event)
        for (agent in agents) {
            if (event.actor.type == ActorType.AGENT && event.actor.id == agent.id.toHexString()) continue
            for (trigger in agent.definition.triggers) {
                val fires = when (trigger.type) {
                    TriggerTypes.EVENT -> trigger.config.string("event") == event.type && eventFilters(trigger.config, event)
                    TriggerTypes.EMAIL_RECEIVED -> event.type == DomainEventTypes.EMAIL_RECEIVED && emailFilters(trigger.config, event)
                    else -> false
                }
                if (!fires) continue
                starter.start(
                    tenant = tenant,
                    agent = agent,
                    trigger = RunTrigger(type = trigger.type, triggerId = trigger.id, eventId = event.id.toHexString(), eventType = event.type, firedAt = clock()),
                    subject = event.subject,
                    dedupeKey = "event:${event.id.toHexString()}:${trigger.id}",
                    event = event,
                )
            }
        }
    }

    /** Ends open runs on the event's record, or on records it relates to, whose agent says this event ends them. */
    private suspend fun applyExitRules(tenant: Tenant, agents: List<Agent>, event: DomainEvent) {
        val zone = TimeZone.of(TenantTimeZones.normalize(tenant.timezone))
        val today = clock().toLocalDateTime(zone).date
        val variables = buildJsonObject {
            put("event", buildJsonObject {
                put("type", event.type)
                event.payload.forEach { (key, value) -> put(key, value) }
            })
        }
        val subjects: List<SubjectRef> = listOf(event.subject) + event.related
        for (agent in agents) {
            val rules = agent.definition.exitRules.filter { it.event == event.type }
            if (rules.isEmpty() || rules.none { ConditionEvaluator.matches(it.conditions, variables, today, zone) }) continue
            for (subject in subjects) {
                module.runs.openForSubject(tenant.id, subject)
                    .filter { it.agentId == agent.id && !it.dryRun }
                    .forEach { run ->
                        module.runs.cancelIfOpen(run.id, "exit:${event.type}")?.let {
                            module.approvals.cancelForRun(run.id)
                            log.info("Agent run {} ended by {} on {} {}", run.id, event.type, subject.type, subject.id)
                        }
                    }
            }
        }
    }

    companion object {
        private val POLL = 2.seconds
        private val STALE_CLAIM = 5.minutes

        /** The event trigger's narrowing: a new status, a channel, words in a message. */
        fun eventFilters(config: JsonObject, event: DomainEvent): Boolean {
            config.string("toStatus")?.let { wanted ->
                val to = (event.payload["to"] as? JsonPrimitive)?.contentOrNull ?: (event.payload["status"] as? JsonPrimitive)?.contentOrNull
                if (!wanted.equals(to, ignoreCase = true)) return false
            }
            config.string("channel")?.let { wanted ->
                val channel = (event.payload["channel"] as? JsonPrimitive)?.contentOrNull
                if (!wanted.equals(channel, ignoreCase = true)) return false
            }
            val keywords = config.strings("keywords")
            if (keywords.isNotEmpty()) {
                val text = (event.payload["text"] as? JsonPrimitive)?.contentOrNull?.lowercase().orEmpty()
                if (keywords.none { text.contains(it.lowercase()) }) return false
            }
            return true
        }

        fun emailFilters(config: JsonObject, event: DomainEvent): Boolean {
            fun field(name: String) = (event.payload[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
            when (config.string("sender")) {
                "known" -> if (field("clientId").isBlank()) return false
                "unknown" -> if (field("clientId").isNotBlank()) return false
            }
            config.string("fromContains")?.let { if (!field("from").contains(it, ignoreCase = true)) return false }
            config.strings("subjectContains").takeIf { it.isNotEmpty() }?.let { words ->
                if (words.none { field("subject").contains(it, ignoreCase = true) }) return false
            }
            config.strings("bodyContains").takeIf { it.isNotEmpty() }?.let { words ->
                val text = field("snippet") + " " + field("text")
                if (words.none { text.contains(it, ignoreCase = true) }) return false
            }
            if (config.bool("hasPdf") == true && field("hasPdf") != "true") return false
            return true
        }
    }
}
