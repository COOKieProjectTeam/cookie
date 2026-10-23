package com.cookie.user.messaging

import com.cookie.user.persistence.JdbcUserStore
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class UserMessagingMetrics(private val store:JdbcUserStore,registry:MeterRegistry) {
    @Volatile private var pending=0.0
    @Volatile private var oldest=0.0
    @Volatile private var quarantined=0.0
    @Volatile private var healthy=0.0
    init {
        registry.gauge("cookie.user.outbox.pending",this) { it.pending }
        registry.gauge("cookie.user.outbox.oldest.age.seconds",this) { it.oldest }
        registry.gauge("cookie.user.quarantine.pending",this) { it.quarantined }
        registry.gauge("cookie.user.messaging.metrics.healthy",this) { it.healthy }
    }
    @Scheduled(fixedDelay=10000)
    fun sample() {
        try {
            store.jdbc.query("SELECT count(*) AS pending,COALESCE(GREATEST(0,extract(epoch FROM now()-min(occurred_at))),0) AS age FROM outbox_events WHERE published_at IS NULL") {
                pending=it.getDouble("pending");oldest=it.getDouble("age")
            }
            quarantined=store.jdbc.queryForObject("SELECT count(*) FROM message_quarantine",Double::class.java)!!
            healthy=1.0
        } catch (_:Exception) { healthy=0.0 }
    }
}
