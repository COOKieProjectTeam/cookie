package com.cookie.notification.messaging

import io.nats.client.Nats
import io.nats.client.JetStreamApiException
import io.nats.client.api.MessageGetRequest
import io.nats.client.api.MessageInfo
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/** One-off operator process, never an HTTP route or an automated retry of UNKNOWN SMTP. */
@Component
@Profile("notification-replay")
class OperatorQuarantineReplay(
    private val jdbc:JdbcTemplate,
    private val handler:NotificationEventHandler,
    private val nats:NotificationNatsConnection,
    private val context:ConfigurableApplicationContext,
    @param:Value("\${cookie.notification.operator-nats-credentials-path:}") private val credentials:String,
) : ApplicationRunner {
    override fun run(args:ApplicationArguments) {
        try {
            val id=runCatching { UUID.fromString(args.getOptionValues("notification-replay-id")?.single()) }.getOrNull()
                ?:throw ReplayFailure("A single valid --notification-replay-id is required")
            if(credentials.isBlank() || !Files.isRegularFile(Path.of(credentials)) || !Files.isReadable(Path.of(credentials)))
                throw ReplayFailure("Separate operator NATS credentials are required")
            val row=jdbc.query("SELECT stream_sequence,message_hash,subject FROM message_quarantine WHERE id=? AND replayed_at IS NULL",
                {rs,_->Triple(rs.getLong(1),rs.getString(2),rs.getString(3))},id).singleOrNull()
                ?:throw ReplayFailure("Replay record not found or already replayed")
            jdbc.update("UPDATE message_quarantine SET replay_requested_at=clock_timestamp(),replay_attempts=replay_attempts+1 WHERE id=?",id)
            Nats.connect(nats.options(credentials)).use { connection ->
                val original=try {
                    // getMessage() also probes STREAM.INFO in a fresh JVM. The operator deliberately
                    // has only MSG.GET, so issue that exact request without broader discovery grants.
                    val response=connection.request("\$JS.API.STREAM.MSG.GET.${NotificationNatsConnection.STREAM}",
                        MessageGetRequest.forSequence(row.first).serialize(),Duration.ofSeconds(2))
                        ?:throw ReplayFailure("Replay lookup timed out; quarantine remains available for retry")
                    MessageInfo(response,NotificationNatsConnection.STREAM,false).throwOnHasError()
                }
                catch(exception:JetStreamApiException) {
                    if(exception.errorCode==404) throw ReplayFailure("Original message no longer retained; cannot recover or replay")
                    throw exception
                }
                val originalSubject=original.subject?:throw ReplayFailure("Original message no longer retained; cannot recover or replay")
                val originalData=original.data?:throw ReplayFailure("Original message no longer retained; cannot recover or replay")
                if(originalSubject!=row.third || NotificationQuarantine.digest(originalData)!=row.second)
                    throw ReplayFailure("Original message does not match quarantine record")
                handler.handle(originalSubject,originalData)
                jdbc.update("UPDATE message_quarantine SET replayed_at=clock_timestamp() WHERE id=?",id)
            }
        } catch(failure:ReplayFailure) {
            throw failure
        } catch(invalid:InvalidNotificationEvent) {
            throw ReplayFailure("Replay rejected: ${invalid.reason}")
        } catch(_:Exception) {
            // JDBC/broker exception details can contain event bytes or failed-row contents.
            // The operator gets a safe failure and can retry the same durable record.
            throw ReplayFailure("Replay failed due to broker or database error; quarantine remains available for retry")
        } finally { context.close() }
    }

    private class ReplayFailure(message:String):IllegalStateException(message)
}
