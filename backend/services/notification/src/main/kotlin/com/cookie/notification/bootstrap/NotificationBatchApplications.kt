package com.cookie.notification.bootstrap

import com.cookie.notification.delivery.JdbcDeliveryRepository
import com.cookie.notification.messaging.NotificationEventHandler
import com.cookie.notification.messaging.NotificationMessagingProperties
import com.cookie.notification.messaging.NotificationNatsConnection
import com.cookie.notification.messaging.OperatorQuarantineReplay
import com.cookie.notification.persistence.JdbcNotificationRepository
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration
import org.springframework.context.annotation.Import

// Deliberately not @Configuration/@Component: the HTTP application's component scan
// must never discover these entrypoints. Only main() registers the chosen source.
@ImportAutoConfiguration(FlywayAutoConfiguration::class)
class NotificationMigrationApplication

@ImportAutoConfiguration(
    DataSourceAutoConfiguration::class, JdbcTemplateAutoConfiguration::class,
    DataSourceTransactionManagerAutoConfiguration::class, TransactionAutoConfiguration::class,
    JacksonAutoConfiguration::class,
)
@EnableConfigurationProperties(NotificationMessagingProperties::class)
@Import(JdbcNotificationRepository::class, JdbcDeliveryRepository::class,
    NotificationEventHandler::class, NotificationNatsConnection::class, OperatorQuarantineReplay::class)
class NotificationReplayApplication
