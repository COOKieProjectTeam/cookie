package com.cookie.notification.domain

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

enum class DevicePlatform { IOS, ANDROID }
enum class ReminderKind { FOOD, WATER, WEIGH, CALORIES_EXCEEDED, CALORIES_UNDER_TARGET }
class InvalidNotificationInput(message: String) : RuntimeException(message)
class NotificationAccountDeleted : RuntimeException("Account is deleted")
class NotificationDeviceNotFound : RuntimeException("Device not found")

data class NotificationSettings(
    val userId: UUID,
    val waterEnabled: Boolean = true,
    val foodEnabled: Boolean = true,
    val foodInactiveMinutes: Int = 240,
    val weighEnabled: Boolean = true,
    val weighTime: LocalTime = LocalTime.of(8, 0),
    val goalWarningEnabled: Boolean = true,
    val quietStart: LocalTime = LocalTime.of(23, 0),
    val quietEnd: LocalTime = LocalTime.of(8, 0),
    val timezone: String? = null,
    val locale: String = "ru",
    val schedulingStartedAt: Instant? = null,
) {
    fun apply(patch: SettingsPatch, now: Instant): NotificationSettings {
        patch.timezone?.let {
            if (it !in ZoneId.getAvailableZoneIds()) throw InvalidNotificationInput("timezone must be an IANA zone")
        }
        patch.foodInactiveMinutes?.let { if (it !in 1..10080) throw InvalidNotificationInput("food interval must be 1..10080 minutes") }
        patch.locale?.let { if (it !in setOf("ru", "en")) throw InvalidNotificationInput("locale must be ru or en") }
        return copy(
            waterEnabled = patch.waterEnabled ?: waterEnabled,
            foodEnabled = patch.foodEnabled ?: foodEnabled,
            foodInactiveMinutes = patch.foodInactiveMinutes ?: foodInactiveMinutes,
            weighEnabled = patch.weighEnabled ?: weighEnabled,
            weighTime = patch.weighTime ?: weighTime,
            goalWarningEnabled = patch.goalWarningEnabled ?: goalWarningEnabled,
            quietStart = patch.quietStart ?: quietStart,
            quietEnd = patch.quietEnd ?: quietEnd,
            timezone = patch.timezone ?: timezone,
            locale = patch.locale ?: locale,
            schedulingStartedAt = schedulingStartedAt ?: if (patch.timezone != null) now else null,
        )
    }
}

data class SettingsPatch(
    val waterEnabled: Boolean? = null,
    val foodEnabled: Boolean? = null,
    val foodInactiveMinutes: Int? = null,
    val weighEnabled: Boolean? = null,
    val weighTime: LocalTime? = null,
    val goalWarningEnabled: Boolean? = null,
    val quietStart: LocalTime? = null,
    val quietEnd: LocalTime? = null,
    val timezone: String? = null,
    val locale: String? = null,
)

data class PushDevice(val id: UUID, val userId: UUID, val platform: DevicePlatform, val deviceId: String, val token: String, val enabled: Boolean = true) {
    override fun toString() = "PushDevice(id=$id,platform=$platform,token=[redacted])"
}

data class RegisterDevice(val platform: DevicePlatform, val deviceId: String, val token: String) {
    init {
        if (deviceId.isBlank() || deviceId.length > 255 || token.isBlank() || token.length > 4096 || token.any { it.isISOControl() }) {
            throw InvalidNotificationInput("Invalid push device")
        }
    }
    // APNs uses hexadecimal bytes; FCM tokens are opaque and case-sensitive.
    val canonicalToken: String
        get() = if (platform == DevicePlatform.IOS && token.matches(Regex("[0-9a-fA-F]{32,512}"))) token.lowercase() else token
    override fun toString() = "RegisterDevice(platform=$platform,token=[redacted])"
}
data class DeviceRegistration(val device: PushDevice, val created: Boolean)
