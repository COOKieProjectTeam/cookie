package com.cookie.user.persistence

import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

class InvalidUserEvent(val reason: String): RuntimeException(reason)

@Component
class LifecycleEvents(private val store: JdbcUserStore) {
    fun handle(subject: String,bytes: ByteArray) {
        val event=try { parse(subject,bytes) } catch (e:InvalidUserEvent) { throw e }
            catch (_:Exception) { throw InvalidUserEvent("invalid_envelope") }
        store.transactions.executeWithoutResult {
            store.lock(event.userId)
            val inserted=store.jdbc.update("INSERT INTO inbox_events(consumer_name,event_id,event_type) VALUES (?,?,?) ON CONFLICT DO NOTHING",CONSUMER,event.id,event.type)
            if(inserted==0 || store.deleted(event.userId)) return@executeWithoutResult
            if(event.type=="account.deletion.requested") {
                store.jdbc.update("INSERT INTO account_tombstones(user_id,deletion_request_id) VALUES (?,?) ON CONFLICT DO NOTHING",event.userId,event.deletionRequestId)
                store.jdbc.update("DELETE FROM profiles WHERE user_id=?",event.userId)
                // Profile/goal payloads and idempotency evidence are user-owned data, too.
                store.jdbc.update("DELETE FROM outbox_events WHERE user_id=?",event.userId)
            } else {
                val created=store.jdbc.update("INSERT INTO profiles(user_id,locale) VALUES (?,?) ON CONFLICT DO NOTHING",event.userId,event.locale)
                if(created==1) store.profileEvent(store.loadProfile(event.userId),listOf("created"))
            }
        }
    }
    private data class Lifecycle(val id:UUID,val type:String,val userId:UUID,val locale:String,val deletionRequestId:UUID?)
    private fun parse(subject:String,bytes:ByteArray):Lifecycle {
        require(bytes.size<=65536)
        val n=store.mapper.readTree(bytes)
        require(n.isObject)
        val id=uuid(n,"event_id");val type=text(n,"event_type")
        require(type in TYPES && subject=="cookie.events.$type.v1")
        require(n.path("event_version").isIntegralNumber && n.path("event_version").canConvertToInt() && n.path("event_version").intValue()==1)
        require(text(n,"producer")=="identity")
        Instant.parse(text(n,"occurred_at")); require(text(n,"aggregate_type")=="account")
        val p=n.path("payload");require(p.isObject)
        val userId=uuid(p,if(type=="account.activated") "userId" else "accountId")
        require(uuid(n,"aggregate_id")==userId)
        return if(type=="account.activated") {
            require(p.path("accountMetadata").isObject)
            val locale=p.path("accountMetadata").path("locale").takeIf { it.isString }?.stringValue()?.substringBefore('-')?.lowercase()
            Lifecycle(id,type,userId,locale?.takeIf { it in setOf("ru","en") } ?: "ru",null)
        } else {
            Instant.parse(text(p,"requestedAt"))
            Lifecycle(id,type,userId,"ru",uuid(p,"deletionRequestId"))
        }
    }
    private fun text(n:JsonNode,key:String):String { val v=n.path(key);require(v.isString && v.stringValue().isNotBlank());return v.stringValue() }
    private fun uuid(n:JsonNode,key:String):UUID { val s=text(n,key);return UUID.fromString(s).also { require(it.toString().equals(s,true)) } }
    companion object {
        const val CONSUMER="user-lifecycle-v1"
        val TYPES=setOf("account.activated","account.deletion.requested")
    }
}
