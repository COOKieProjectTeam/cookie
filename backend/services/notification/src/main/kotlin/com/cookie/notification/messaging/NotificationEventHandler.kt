package com.cookie.notification.messaging

import com.cookie.notification.application.ports.DeliveryQueue
import com.cookie.notification.persistence.JdbcNotificationRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class InvalidNotificationEvent(val reason: String) : RuntimeException(reason)

@Component
class NotificationEventHandler(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
    private val repository: JdbcNotificationRepository,
    private val deliveries: DeliveryQueue,
    private val mapper: ObjectMapper,
) {
    fun handle(subject: String, data: ByteArray) {
        val event = try { mapper.readTree(data) } catch (_: Exception) { throw InvalidNotificationEvent("invalid_json") }
        val id = uuid(event,"event_id")
        val type = text(event,"event_type")
        val version = event.path("event_version")
        if (!version.isIntegralNumber || !version.canConvertToInt() || version.intValue() != 1) invalid("unsupported_event_version")
        if (subject != "cookie.events.$type.v1") invalid("subject_mismatch")
        val expectedProducer = PRODUCERS[type] ?: invalid("unsupported_event_type")
        if (text(event,"producer") != expectedProducer) invalid("producer_mismatch")
        text(event,"aggregate_type")
        val aggregateId = text(event,"aggregate_id")
        val occurredAt = instant(event,"occurred_at")
        val payload = event.path("payload")
        if (!payload.isObject) invalid("invalid_payload")
        val userId = if (type == "notification.email.requested") null else uuid(payload,if (type == "account.deletion.requested") "accountId" else "userId")
        val projection = when(type) {
            "account.activated", "account.deletion.requested", "notification.email.requested" -> null
            else -> validateProjection(type,event,payload)
        }
        if(type == "notification.email.requested") {
            if (text(payload,"template") != "EMAIL_VERIFICATION") invalid("unsupported_template")
            text(payload,"encryptedPayload")
            instant(payload,"expiresAt")
            try { UUID.fromString(aggregateId) } catch (_: Exception) { invalid("invalid_registration_attempt") }
        }
        if(type == "account.deletion.requested") { uuid(payload,"deletionRequestId"); instant(payload,"requestedAt") }
        transactions.executeWithoutResult {
            userId?.let(repository::lockUser)
            val added = jdbc.update("INSERT INTO inbox_events(consumer_name,event_id,event_type) VALUES (?,?,?) ON CONFLICT DO NOTHING",CONSUMER,id,type)
            if(added == 0) return@executeWithoutResult
            if(userId != null && repository.isDeleted(userId)) return@executeWithoutResult
            when(type) {
                "notification.email.requested" -> {
                    val expiresAt = instant(payload,"expiresAt")
                    if(expiresAt.isAfter(repository.now())) deliveries.enqueueEmail(id,UUID.fromString(aggregateId),text(payload,"encryptedPayload"),expiresAt,occurredAt)
                }
                "account.deletion.requested" -> deleteAccount(requireNotNull(userId),payload)
                "account.activated" -> repository.ensureSettings(requireNotNull(userId))
                else -> {
                    val projectionValue = requireNotNull(projection)
                    repository.ensureSettings(requireNotNull(userId))
                    val updated = jdbc.update("""INSERT INTO event_projections(kind,user_id,entity_id,version,payload,deleted) VALUES (?,?,?,?,?::jsonb,?)
                        ON CONFLICT(kind,user_id,entity_id) DO UPDATE SET version=EXCLUDED.version,payload=EXCLUDED.payload,deleted=EXCLUDED.deleted,updated_at=clock_timestamp()
                        WHERE event_projections.version < EXCLUDED.version""",
                        projectionValue.kind,userId,projectionValue.key,projectionValue.version,mapper.writeValueAsString(minimalPayload(type,payload)),projectionValue.deleted)
                    if(updated > 0) {
                        if(type=="user.profile.updated") {
                            val locale=payload.path("locale").takeIf { it.isString }?.stringValue()?.substringBefore('-')?.lowercase()
                            if(locale in setOf("ru","en")) jdbc.update("UPDATE settings SET locale=?,updated_at=clock_timestamp() WHERE user_id=? AND NOT locale_overridden",locale,userId)
                        }
                        repository.dirty(userId,repository.now())
                    }
                }
            }
        }
    }

    private fun deleteAccount(userId: UUID,payload: JsonNode) {
        jdbc.update("INSERT INTO account_tombstones(user_id,deletion_request_id,deleted_at) VALUES (?,?,?) ON CONFLICT DO NOTHING",userId,uuid(payload,"deletionRequestId"),Timestamp.from(instant(payload,"requestedAt")))
        deliveries.cancelUser(userId)
        listOf("push_devices","settings","event_projections","reminder_deadlines","reminder_occurrences").forEach { table -> jdbc.update("DELETE FROM $table WHERE user_id=?",userId) }
    }

    private fun validateProjection(type: String,event: JsonNode,payload: JsonNode): Projection {
        val versionNode = event.path("aggregate_version")
        if(!versionNode.isIntegralNumber || !versionNode.canConvertToLong() || versionNode.longValue() <= 0) invalid("missing_aggregate_version")
        val version = versionNode.longValue()
        return when(type) {
            "user.profile.updated" -> {
                // Notification settings are the sole scheduling timezone authority.
                if(payload.has("locale") && !payload.path("locale").isNull) text(payload,"locale")
                Projection("PROFILE",uuid(payload,"userId").toString(),version,false)
            }
            "nutrition.meal.created", "nutrition.meal.updated", "nutrition.meal.deleted" -> {
                val deleted = type.endsWith("deleted")
                val key = uuid(payload,"mealId").toString()
                if(!deleted) instant(payload,"eatenAt")
                Projection("MEAL",key,version,deleted)
            }
            "nutrition.water.changed" -> {
                val date = date(payload,"localDate")
                number(payload,"totalMl",false,true)
                nullableInstant(payload,"lastConsumedAt")
                Projection("WATER",date.toString(),version,false)
            }
            "health.weight.changed" -> {
                val key = uuid(payload,"measurementId").toString()
                val deleted = boolean(payload,"deleted")
                if(!deleted) { instant(payload,"measuredAt"); number(payload,"valueKg",false,false) }
                Projection("WEIGHT",key,version,deleted)
            }
            "progress.day.updated" -> {
                val day = date(payload,"localDate")
                val zone = text(payload,"timezone")
                if(zone !in ZoneId.getAvailableZoneIds()) invalid("invalid_timezone")
                val calories = payload.path("calories")
                val water = payload.path("water")
                if(!calories.isObject || !water.isObject) invalid("incomplete_progress_snapshot")
                number(calories,"consumed",true,false); number(calories,"target",true,false)
                if(!calories.has("unlimitedCalories") || (!calories.path("unlimitedCalories").isNull && !calories.path("unlimitedCalories").isBoolean)) invalid("invalid_unlimited_calories")
                number(water,"consumedMl",true,true); number(water,"targetMl",true,true)
                Projection("PROGRESS","$zone:$day",version,false)
            }
            else -> invalid("unsupported_event_type")
        }
    }
    private fun minimalPayload(type:String,payload:JsonNode):JsonNode {
        val fields=when(type) {
            "user.profile.updated" -> listOf("userId","locale")
            "nutrition.meal.created","nutrition.meal.updated" -> listOf("mealId","userId","eatenAt")
            "nutrition.meal.deleted" -> listOf("mealId","userId")
            "nutrition.water.changed" -> listOf("userId","localDate","totalMl","lastConsumedAt")
            "health.weight.changed" -> if(payload.path("deleted").booleanValue()) listOf("measurementId","userId","deleted") else listOf("measurementId","userId","deleted","measuredAt")
            "progress.day.updated" -> listOf("userId","localDate","timezone")
            else -> emptyList()
        }
        val result=mapper.createObjectNode()
        fields.forEach { field -> if(payload.has(field)) result.set(field,payload.path(field)) }
        if(type=="progress.day.updated") {
            val calories=mapper.createObjectNode()
            listOf("consumed","target","unlimitedCalories").forEach { calories.set(it,payload.path("calories").path(it)) }
            val water=mapper.createObjectNode()
            listOf("consumedMl","targetMl").forEach { water.set(it,payload.path("water").path(it)) }
            result.set("calories",calories); result.set("water",water)
        }
        return result
    }
    private data class Projection(val kind:String,val key:String,val version:Long,val deleted:Boolean)
    private fun text(node:JsonNode,name:String):String { val value=node.path(name); if(!value.isString || value.stringValue().isBlank()) invalid("invalid_$name"); return value.stringValue() }
    private fun uuid(node:JsonNode,name:String):UUID = try { UUID.fromString(text(node,name)) } catch (_: IllegalArgumentException) { invalid("invalid_$name") }
    private fun instant(node:JsonNode,name:String):Instant = try { Instant.parse(text(node,name)) } catch (_: java.time.DateTimeException) { invalid("invalid_$name") }
    private fun nullableInstant(node:JsonNode,name:String) { if(!node.has(name)) invalid("missing_$name"); if(!node.path(name).isNull) instant(node,name) }
    private fun date(node:JsonNode,name:String):LocalDate = try { LocalDate.parse(text(node,name)) } catch (_: java.time.DateTimeException) { invalid("invalid_$name") }
    private fun boolean(node:JsonNode,name:String):Boolean { if(!node.path(name).isBoolean) invalid("invalid_$name"); return node.path(name).booleanValue() }
    private fun number(node:JsonNode,name:String,nullable:Boolean,integer:Boolean) {
        val value=node.path(name)
        if(nullable && value.isNull) return
        if(!value.isNumber || (integer && (!value.isIntegralNumber || !value.canConvertToInt())) || value.decimalValue().signum()<0) invalid("invalid_$name")
    }
    private fun invalid(reason:String):Nothing = throw InvalidNotificationEvent(reason)
    companion object {
        const val CONSUMER="notification-v1"
        val PRODUCERS=mapOf(
            "account.activated" to "identity","account.deletion.requested" to "identity","notification.email.requested" to "identity",
            "nutrition.meal.created" to "nutrition","nutrition.meal.updated" to "nutrition","nutrition.meal.deleted" to "nutrition","nutrition.water.changed" to "nutrition",
            "health.weight.changed" to "health","user.profile.updated" to "user","progress.day.updated" to "progress")
    }
}
