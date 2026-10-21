package com.cookie.notification.messaging

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.context.annotation.Profile

/** Thirty days exceeds the seven-day stream/redelivery horizon. User tombstones never age out. */
@Component
@Profile("!notification-replay")
class NotificationRetention(private val jdbc:JdbcTemplate) {
    @Scheduled(fixedDelayString="\${cookie.notification.retention-poll-millis:60000}")
    fun clean() {
        jdbc.update("""DELETE FROM inbox_events WHERE (consumer_name,event_id) IN (
            SELECT consumer_name,event_id FROM inbox_events WHERE processed_at<clock_timestamp()-interval '30 days'
            ORDER BY processed_at LIMIT 500)""")
        jdbc.update("""DELETE FROM message_quarantine WHERE id IN (
            SELECT id FROM message_quarantine WHERE created_at<clock_timestamp()-interval '30 days'
            ORDER BY created_at LIMIT 500)""")
        // Only dated daily occurrences expire. FOOD cycle evidence survives until account deletion.
        jdbc.update("""DELETE FROM reminder_occurrences WHERE id IN (
            SELECT id FROM reminder_occurrences WHERE kind<>'FOOD' AND created_at<clock_timestamp()-interval '30 days'
            ORDER BY created_at LIMIT 500)""")
    }
}
