package com.cookie.identity.messaging

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/** Provisions local-only broker topology before an empty outbox can short-circuit the publisher. */
@Component
@Profile("dev", "test")
class DevelopmentJetStreamProvisioner(
    private val nats: NatsJetStreamConnection,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                nats.jetStream()
                logger.info("Development JetStream topology is available")
                return
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Interrupted while provisioning development JetStream", interrupted)
            } catch (exception: Exception) {
                if (attempt == MAX_ATTEMPTS - 1) throw exception
                try {
                    Thread.sleep(RETRY_DELAY_MILLIS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("Interrupted while provisioning development JetStream", interrupted)
                }
            }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 30
        const val RETRY_DELAY_MILLIS = 500L
        val logger = LoggerFactory.getLogger(DevelopmentJetStreamProvisioner::class.java)
    }
}
