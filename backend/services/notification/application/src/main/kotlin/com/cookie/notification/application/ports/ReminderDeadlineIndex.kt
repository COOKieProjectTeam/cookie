package com.cookie.notification.application.ports

import java.time.Instant
import java.util.UUID

/** Best-effort ephemeral index; callers publish committed PostgreSQL generations only. */
fun interface ReminderDeadlineIndex {
    fun put(userId:UUID,at:Instant,generation:Long)
}
