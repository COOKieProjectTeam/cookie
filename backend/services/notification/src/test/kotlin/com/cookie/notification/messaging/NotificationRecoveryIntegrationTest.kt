package com.cookie.notification.messaging

import com.cookie.notification.delivery.JdbcDeliveryRepository
import com.cookie.notification.persistence.JdbcNotificationRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.mock.env.MockEnvironment
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Tag("integration")
@Testcontainers
class NotificationRecoveryIntegrationTest {
    @Test fun `quarantine SQL failure leaves poison pending while healthy deletion commits then retry quarantines`() {
        val source=DriverManagerDataSource(postgres.jdbcUrl,postgres.username,postgres.password)
        Flyway.configure().dataSource(source).load().migrate()
        val jdbc=JdbcTemplate(source);val manager=DataSourceTransactionManager(source);val tx=TransactionTemplate(manager)
        val mapper=JsonMapper.builder().findAndAddModules().build()
        val registry=SimpleMeterRegistry()
        val handler=NotificationEventHandler(jdbc,tx,JdbcNotificationRepository(jdbc,tx),JdbcDeliveryRepository(jdbc,manager),mapper)
        val properties=NotificationMessagingProperties().apply { natsUrl="nats://${broker.host}:${broker.getMappedPort(4222)}" }
        val nats=NotificationNatsConnection(properties,MockEnvironment().apply { setActiveProfiles("test") })
        try {
            val connection=nats.connection()
            connection.jetStreamManagement().addOrUpdateConsumer(NotificationNatsConnection.STREAM,
                ConsumerConfiguration.builder().durable(NotificationEventHandler.CONSUMER).ackPolicy(AckPolicy.Explicit)
                    .ackWait(Duration.ofSeconds(2)).maxAckPending(100).maxDeliver(-1)
                    .filterSubjects(NotificationEventHandler.PRODUCERS.keys.map { "cookie.events.$it.v1" }).build())
            val consumer=NotificationConsumer(nats,properties,handler,NotificationQuarantine(jdbc,mapper),registry)
            jdbc.execute("""CREATE FUNCTION test_quarantine_fault() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN RAISE EXCEPTION 'synthetic quarantine SQL failure'; END ${'$'}${'$'}""")
            jdbc.execute("CREATE TRIGGER test_quarantine_fault BEFORE INSERT ON message_quarantine FOR EACH ROW EXECUTE FUNCTION test_quarantine_fault()")
            val user=UUID.randomUUID();val eventId=UUID.randomUUID()
            val subject="cookie.events.account.deletion.requested.v1"
            connection.jetStream().publish(subject,"invalid JSON".toByteArray())
            connection.jetStream().publish(subject,"""{"event_id":"$eventId","event_type":"account.deletion.requested","event_version":1,"producer":"identity","occurred_at":"${Instant.now()}","aggregate_type":"account","aggregate_id":"$user","payload":{"accountId":"$user","deletionRequestId":"${UUID.randomUUID()}","requestedAt":"${Instant.now()}"}}""".toByteArray())
            try {
                consumer.poll()
                assertThat(jdbc.queryForObject("SELECT count(*) FROM account_tombstones WHERE user_id=?",Long::class.java,user)).isEqualTo(1)
                assertThat(jdbc.queryForObject("SELECT count(*) FROM message_quarantine",Long::class.java)).isZero()
                assertThat(registry.get("notification.inbox.recovery.failures").counter().count()).isGreaterThanOrEqualTo(1.0)
            } finally {
                jdbc.execute("DROP TRIGGER test_quarantine_fault ON message_quarantine")
                jdbc.execute("DROP FUNCTION test_quarantine_fault()")
            }
            await().atMost(Duration.ofSeconds(10)).untilAsserted {
                consumer.poll()
                assertThat(jdbc.queryForObject("SELECT count(*) FROM message_quarantine",Long::class.java)).isEqualTo(1)
                assertThat(connection.jetStreamManagement().getConsumerInfo(NotificationNatsConnection.STREAM,NotificationEventHandler.CONSUMER).numAckPending).isZero()
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox_events WHERE event_id=?",Long::class.java,eventId)).isEqualTo(1)
        } finally { nats.close() }
    }
    companion object {
        @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:18.6")
        class Broker: GenericContainer<Broker>("nats:2.14.6-alpine")
        @Container @JvmStatic val broker=Broker().withCommand("-js").withExposedPorts(4222)
    }
}
