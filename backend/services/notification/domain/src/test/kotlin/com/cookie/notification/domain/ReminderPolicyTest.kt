package com.cookie.notification.domain

import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.*
import java.util.UUID

class ReminderPolicyTest {
    private val settings=NotificationSettings(UUID.randomUUID(),timezone="Europe/London")
    @Test fun `quiet hours cross midnight and end is inclusive for sending`() {
        assertThat(ReminderPolicy.isQuiet(Instant.parse("2026-01-01T23:00:00Z"),settings)).isTrue()
        assertThat(ReminderPolicy.isQuiet(Instant.parse("2026-01-02T07:59:59Z"),settings)).isTrue()
        assertThat(ReminderPolicy.isQuiet(Instant.parse("2026-01-02T08:00:00Z"),settings)).isFalse()
        assertThat(ReminderPolicy.afterQuiet(Instant.parse("2026-01-01T23:00:00Z"),settings)).isEqualTo(Instant.parse("2026-01-02T08:00:00Z"))
    }
    @Test fun `DST gap and overlap have a single deterministic instant`() {
        val zone=ZoneId.of("Europe/London")
        assertThat(ReminderPolicy.atLocalTime(LocalDate.of(2026,3,29),LocalTime.of(1,30),zone)).isEqualTo(Instant.parse("2026-03-29T01:00:00Z"))
        assertThat(ReminderPolicy.atLocalTime(LocalDate.of(2026,10,25),LocalTime.of(1,30),zone)).isEqualTo(Instant.parse("2026-10-25T00:30:00Z"))
    }
    @Test fun `quiet end in second repeated hour remains in the future`() {
        val repeated = settings.copy(quietStart = LocalTime.MIDNIGHT, quietEnd = LocalTime.of(1, 30))
        val now = Instant.parse("2026-10-25T01:15:00Z")
        assertThat(ReminderPolicy.isQuiet(now, repeated)).isTrue()
        assertThat(ReminderPolicy.afterQuiet(now, repeated)).isEqualTo(Instant.parse("2026-10-25T01:30:00Z"))
    }
    @Test fun `missing or unlimited calorie goal cannot warn and thresholds are strict`() {
        assertThat(ReminderPolicy.caloriesUnderTarget(null,BigDecimal(2000),false)).isFalse()
        assertThat(ReminderPolicy.caloriesUnderTarget(BigDecimal.ZERO,BigDecimal.ZERO,false)).isFalse()
        assertThat(ReminderPolicy.caloriesExceeded(BigDecimal(2001),BigDecimal(2000),true)).isFalse()
        assertThat(ReminderPolicy.caloriesExceeded(BigDecimal(2000),BigDecimal(2000),false)).isFalse()
        assertThat(ReminderPolicy.caloriesExceeded(BigDecimal(2001),BigDecimal(2000),false)).isTrue()
        assertThat(ReminderPolicy.caloriesUnderTarget(BigDecimal(1600),BigDecimal(2000),false)).isFalse()
        assertThat(ReminderPolicy.caloriesUnderTarget(BigDecimal(1599),BigDecimal(2000),false)).isTrue()
    }
    @Test fun `timezone is explicit and first configuration starts schedule once`() {
        val now=Instant.parse("2026-01-01T10:00:00Z")
        val unset=NotificationSettings(UUID.randomUUID())
        assertThat(unset.timezone).isNull()
        val configured=unset.apply(SettingsPatch(timezone="Europe/London"),now)
        assertThat(configured.schedulingStartedAt).isEqualTo(now)
        assertThat(configured.apply(SettingsPatch(timezone="Europe/Berlin"),now.plusSeconds(10)).schedulingStartedAt).isEqualTo(now)
        assertThatThrownBy { unset.apply(SettingsPatch(timezone="+01:00"),now) }.isInstanceOf(InvalidNotificationInput::class.java)
    }
}
