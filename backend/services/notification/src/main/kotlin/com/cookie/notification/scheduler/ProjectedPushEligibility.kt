package com.cookie.notification.scheduler

import com.cookie.notification.application.ports.PushEligibility
import com.cookie.notification.domain.ReminderKind
import com.cookie.notification.domain.ReminderPlanner
import com.cookie.notification.persistence.JdbcNotificationRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

@Component
class ProjectedPushEligibility(private val jdbc:JdbcTemplate,private val repository:JdbcNotificationRepository,
                               private val transactions:TransactionTemplate,mapper:ObjectMapper) : PushEligibility {
    private val loader=JdbcReminderStateLoader(jdbc,repository,mapper)
    override fun isEligible(userId:UUID,kind:String,occurrenceId:UUID,now:Instant):Boolean=transactions.execute {
        repository.lockUser(userId)
        if(repository.isDeleted(userId))return@execute false
        val state=loader.load(userId,includeOccurrences=false)?:return@execute false
        val occurrence=jdbc.query("SELECT cycle_key,scheduled_at FROM reminder_occurrences WHERE id=? AND user_id=? AND kind=?",{rs,_->
            rs.getString(1) to rs.getTimestamp(2).toInstant()},occurrenceId,userId,kind).singleOrNull()?:return@execute false
        val reminderKind=runCatching { ReminderKind.valueOf(kind) }.getOrNull()?:return@execute false
        ReminderPlanner.isEligible(state,reminderKind,occurrence.first,occurrence.second,now)
    }
}
