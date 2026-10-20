package com.cookie.notification.messaging

import com.cookie.notification.application.ports.DeliveryQueue
import com.cookie.notification.persistence.JdbcNotificationRepository
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.util.UUID

class NotificationEventValidationTest {
    private val jdbc=mock(JdbcTemplate::class.java)
    private val mapper=JsonMapper.builder().findAndAddModules().build()
    private val handler=NotificationEventHandler(jdbc,mock(TransactionTemplate::class.java),mock(JdbcNotificationRepository::class.java),mock(DeliveryQueue::class.java),mapper)
    @Test fun `fractional string overflowing and nonpositive aggregate versions fail before persistence`() {
        listOf<Any>("1",BigDecimal("1.5"),BigInteger("9223372036854775808"),0,-1).forEach { version ->
            assertThatThrownBy { handler.handle("cookie.events.nutrition.water.changed.v1",body(version)) }.isInstanceOf(InvalidNotificationEvent::class.java)
        }
        verifyNoInteractions(jdbc)
    }
    @Test fun `overflowing event version and water amount cannot wrap into valid integers`() {
        assertThatThrownBy { handler.handle("cookie.events.nutrition.water.changed.v1",body(1,eventVersion=4294967297L)) }.isInstanceOf(InvalidNotificationEvent::class.java)
        assertThatThrownBy { handler.handle("cookie.events.nutrition.water.changed.v1",body(1,total=4294967297L)) }.isInstanceOf(InvalidNotificationEvent::class.java)
        verifyNoInteractions(jdbc)
    }
    private fun body(version:Any,eventVersion:Any=1,total:Any=100)=mapper.writeValueAsBytes(mapOf(
        "event_id" to UUID.randomUUID(),"event_type" to "nutrition.water.changed","event_version" to eventVersion,"occurred_at" to Instant.now().toString(),
        "producer" to "nutrition","aggregate_type" to "water_day","aggregate_id" to UUID.randomUUID().toString(),"aggregate_version" to version,
        "payload" to mapOf("userId" to UUID.randomUUID(),"localDate" to "2026-09-17","totalMl" to total,"lastConsumedAt" to null)))
}
