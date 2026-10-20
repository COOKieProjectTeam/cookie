package com.cookie.notification

import com.cookie.platform.testing.TlsNatsServer
import com.cookie.notification.bootstrap.NotificationMigrationApplication
import com.cookie.notification.bootstrap.NotificationReplayApplication
import com.cookie.notification.config.DeliveryConfiguration
import com.cookie.notification.config.NotificationConfiguration
import com.cookie.notification.config.SecurityConfiguration
import com.cookie.notification.delivery.DeliveryWorker
import com.cookie.notification.messaging.NotificationEventHandler
import com.cookie.notification.messaging.NotificationNatsConnection
import com.cookie.notification.messaging.OperatorQuarantineReplay
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.datasource.DriverManagerDataSource
import javax.sql.DataSource

class NotificationBatchApplicationTest {
    @TempDir lateinit var directory: Path
    @Test
    fun `replay boots without migration provider keys workload credentials redis or runtime configuration`() {
        TlsNatsServer(directory, "dns:localhost").use { broker ->
            val operatorCredentials = Files.writeString(directory.resolve("operator.creds"), "not read until explicit replay")
            ApplicationContextRunner().withUserConfiguration(NotificationReplayApplication::class.java)
                .withInitializer { it.environment.setActiveProfiles("notification-replay") }
                .withBean(DataSource::class.java, { DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/unused") })
                // Even inherited Flyway variables must not create a migrator in the replay context.
                .withPropertyValues("spring.flyway.enabled=true", "spring.flyway.url=jdbc:postgresql://127.0.0.1:1/forbidden",
                    "cookie.notification.security.private-key-path=/missing/key",
                    "cookie.notification.delivery.smtp.host=", "cookie.notification.nats-credentials-path=/missing/workload",
                    "cookie.notification.nats-url=tls://localhost:${broker.port}",
                    "cookie.notification.operator-nats-credentials-path=$operatorCredentials",
                    "cookie.notification.nats-truststore-path=${broker.truststore}",
                    "cookie.notification.nats-truststore-password=${broker.password}")
                .run { context ->
                    assertThat(context).hasNotFailed().hasSingleBean(NotificationEventHandler::class.java)
                        .hasSingleBean(OperatorQuarantineReplay::class.java).hasSingleBean(NotificationNatsConnection::class.java)
                        .doesNotHaveBean(Flyway::class.java).doesNotHaveBean(DeliveryWorker::class.java)
                        .doesNotHaveBean(DeliveryConfiguration::class.java).doesNotHaveBean(SecurityConfiguration::class.java)
                        .doesNotHaveBean(NotificationConfiguration::class.java)
                    assertThat(context.beanDefinitionNames).noneMatch { it.contains("redis", true) || it.contains("servlet", true) }
                }
        }
    }

    @Test
    fun `migration context contains no runtime or replay components`() {
        ApplicationContextRunner().withUserConfiguration(NotificationMigrationApplication::class.java)
            // The separate process integration test exercises the actual migration against PostgreSQL.
            .withPropertyValues("spring.flyway.enabled=false")
            .run { context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(DataSource::class.java)
                    .doesNotHaveBean(NotificationEventHandler::class.java).doesNotHaveBean(NotificationNatsConnection::class.java)
                    .doesNotHaveBean(OperatorQuarantineReplay::class.java).doesNotHaveBean(DeliveryWorker::class.java)
                    .doesNotHaveBean(DeliveryConfiguration::class.java).doesNotHaveBean(SecurityConfiguration::class.java)
                    .doesNotHaveBean(NotificationConfiguration::class.java)
            }
    }
}
