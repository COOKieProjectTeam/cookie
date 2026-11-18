package com.cookie.progress

import io.nats.client.Connection
import io.nats.client.ErrorListener
import io.nats.client.JetStreamOptions
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.client.PullSubscribeOptions
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.api.DeliverPolicy
import io.nats.client.api.StreamConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.Transferable
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@Tag("integration")
@Testcontainers
class ProgressBrokerPermissionsTest {
    @Test
    fun runtimeCanMonitorPublishAndAcknowledgeOnlyItsOwnDurable() {
        val stream = manifest.path("stream").asString()
        val durable = manifest.path("durable").asString()
        val filters = manifest.path("filter_subjects").values().map { it.asString() }
        val host = "${broker.host}:${broker.getMappedPort(4222)}"
        val ownerSubject = "cookie.events.nutrition.day.changed.v3"
        val ownerData = "synthetic owner event".toByteArray()
        assertThat(stream).isEqualTo(ProgressMessaging.STREAM)
        assertThat(durable).isEqualTo(ProgressMessaging.DURABLE)
        assertThat(filters.toSet()).isEqualTo(ProgressInbox.subjects)
        assertThat(manifest.path("ack_policy").asString()).isEqualTo("explicit")
        assertThat(manifest.path("deliver_policy").asString()).isEqualTo("all")

        Nats.connect("nats://admin:admin-fixture@$host").use { admin ->
            val management = admin.jetStreamManagement()
            management.addStream(StreamConfiguration.builder().name(stream).subjects("cookie.events.>").build())
            management.addOrUpdateConsumer(stream, ConsumerConfiguration.builder().durable(durable)
                .filterSubjects(filters).ackPolicy(AckPolicy.Explicit).deliverPolicy(DeliverPolicy.All)
                .ackWait(Duration.ofSeconds(manifest.path("ack_wait_seconds").asLong()))
                .maxAckPending(manifest.path("max_ack_pending").asLong()).build())
            val source = admin.jetStream().publish(ownerSubject, ownerData)

            val errors = LinkedBlockingQueue<String>()
            val runtime = Options.Builder().server("nats://progress:progress-fixture@$host")
                .inboxPrefix("_INBOX.cookie.progress").maxReconnects(0)
                .errorListener(object : ErrorListener {
                    override fun errorOccurred(connection: Connection, error: String) { errors.add(error) }
                }).build()
            Nats.connect(runtime).use { connection ->
                val timeout = Duration.ofSeconds(3)
                val options = JetStreamOptions.builder().requestTimeout(timeout).build()
                val info = connection.jetStreamManagement(options)
                assertThat(info.getStreamInfo(stream).streamState.lastSequence).isEqualTo(source.seqno)
                assertThat(info.getConsumerInfo(stream, durable).name).isEqualTo(durable)
                val js = connection.jetStream(options)
                val published = js.publish("cookie.events.progress.day.updated.v2", "synthetic progress day".toByteArray())
                assertThat(published.seqno).isGreaterThan(source.seqno)
                val subscription = js.subscribe(null, PullSubscribeOptions.bind(stream, durable))
                val message = subscription.fetch(1, timeout).single()
                assertThat(message.subject).isEqualTo(ownerSubject)
                assertThat(message.data).isEqualTo(ownerData)
                message.ackSync(timeout)
                val acknowledged = info.getConsumerInfo(stream, durable)
                assertThat(acknowledged.ackFloor.streamSequence).isGreaterThanOrEqualTo(source.seqno)
                assertThat(acknowledged.numAckPending).isZero()

                for (subject in listOf("cookie.events.health.weight.changed.v2", "cookie.events.account.deletion.requested.v1")) {
                    connection.publish(subject, "synthetic forbidden event".toByteArray())
                    connection.flush(timeout)
                    assertThat(errors.poll(3, TimeUnit.SECONDS)).contains("Permissions Violation", subject)
                }
                assertThat(management.getStreamInfo(stream).streamState.lastSequence).isEqualTo(published.seqno)
                val deniedRequests = mapOf(
                    "\$JS.API.STREAM.MSG.GET.$stream" to """{"seq":${source.seqno}}""",
                    "\$JS.API.CONSUMER.NAMES.$stream" to "{}",
                )
                for ((subject, body) in deniedRequests) {
                    assertThat(connection.request(subject, body.toByteArray(), Duration.ofMillis(500))).isNull()
                    assertThat(errors.poll(3, TimeUnit.SECONDS)).contains("Permissions Violation", subject)
                }
            }
        }
    }

    companion object {
        private val manifest = JsonMapper.builder().build().readTree(Files.readString(Path.of("ops", "jetstream.json")))
        private val permissions = manifest.path("runtime_permissions")
        private val config = """
            jetstream {}
            authorization {
              users: [
                {user: admin, password: admin-fixture},
                {user: progress, password: progress-fixture, permissions: {
                  publish: {allow: ${permissions.path("publish")}},
                  subscribe: {allow: ${permissions.path("subscribe")}}
                }}
              ]
            }
        """.trimIndent()

        @Container @JvmStatic val broker = GenericContainer("nats:2.14.6-alpine3.22")
            .withCopyToContainer(Transferable.of(config), "/etc/nats/progress.conf")
            .withCommand("-c", "/etc/nats/progress.conf").withExposedPorts(4222)
    }
}
