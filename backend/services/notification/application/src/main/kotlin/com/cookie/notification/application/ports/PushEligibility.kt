package com.cookie.notification.application.ports

import java.time.Instant
import java.util.UUID

fun interface PushEligibility {
    fun isEligible(userId:UUID,kind:String,occurrenceId:UUID,now:Instant):Boolean
}
