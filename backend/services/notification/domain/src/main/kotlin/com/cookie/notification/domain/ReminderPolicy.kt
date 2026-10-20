package com.cookie.notification.domain

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

object ReminderPolicy {
    fun isQuiet(now: Instant, settings: NotificationSettings): Boolean {
        val zone = settings.timezone?.let(ZoneId::of) ?: return true
        val time = now.atZone(zone).toLocalTime()
        val start = settings.quietStart
        val end = settings.quietEnd
        return if (start == end) false else if (start < end) time >= start && time < end else time >= start || time < end
    }

    fun afterQuiet(deadline: Instant, settings: NotificationSettings): Instant {
        if (!isQuiet(deadline, settings)) return deadline
        val zone = ZoneId.of(requireNotNull(settings.timezone))
        val local = deadline.atZone(zone)
        val nextDate = if (settings.quietStart > settings.quietEnd && local.toLocalTime() >= settings.quietStart) local.toLocalDate().plusDays(1) else local.toLocalDate()
        return localTimeAfter(nextDate, settings.quietEnd, zone, deadline)
    }

    /** A quiet interval in a repeated hour ends at the first matching boundary still ahead. */
    fun localTimeAfter(date: LocalDate, time: LocalTime, zone: ZoneId, after: Instant): Instant {
        val local = date.atTime(time)
        val offsets = zone.rules.getValidOffsets(local)
        val candidates = if (offsets.isEmpty()) listOf(zone.rules.getTransition(local).instant) else offsets.map(local::toInstant)
        return candidates.filter { it > after }.minOrNull() ?: atLocalTime(date.plusDays(1), time, zone)
    }

    fun atLocalTime(date: LocalDate, time: LocalTime, zone: ZoneId): Instant {
        val local=date.atTime(time)
        val offsets=zone.rules.getValidOffsets(local)
        return if(offsets.isEmpty()) zone.rules.getTransition(local).instant else local.toInstant(offsets.first())
    }

    fun caloriesExceeded(consumed: BigDecimal?, target: BigDecimal?, unlimited: Boolean?): Boolean =
        consumed != null && target != null && target.signum() > 0 && unlimited == false && consumed > target

    fun caloriesUnderTarget(consumed: BigDecimal?, target: BigDecimal?, unlimited: Boolean?): Boolean =
        consumed != null && target != null && target.signum() > 0 && unlimited == false && consumed < target.multiply(BigDecimal("0.8"))

    fun waterGoalReached(consumed: Int?, target: Int?): Boolean = consumed != null && target != null && target > 0 && consumed >= target
}
