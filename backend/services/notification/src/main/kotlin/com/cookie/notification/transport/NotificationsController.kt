package com.cookie.notification.transport

import com.cookie.notification.application.NotificationService
import com.cookie.notification.domain.DevicePlatform
import com.cookie.notification.domain.InvalidNotificationInput
import com.cookie.notification.domain.RegisterDevice
import com.cookie.notification.domain.SettingsPatch
import com.cookie.notification.generated.api.NotificationsApi
import com.cookie.notification.generated.model.NotificationSettings
import com.cookie.notification.generated.model.NotificationSettingsFood
import com.cookie.notification.generated.model.NotificationSettingsPatch
import com.cookie.notification.generated.model.NotificationSettingsQuietHours
import com.cookie.notification.generated.model.NotificationSettingsWater
import com.cookie.notification.generated.model.NotificationSettingsWeighIn
import com.cookie.notification.generated.model.PushDevice
import com.cookie.notification.generated.model.PushDeviceInput
import com.cookie.notification.security.InvalidAccessTokenException
import com.cookie.notification.security.RemoteJwksAccessTokenVerifier
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Controller
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class NotificationsController(
    private val service: NotificationService,
    private val verifier: RemoteJwksAccessTokenVerifier,
    private val request: HttpServletRequest,
) : NotificationsApi {
    override fun getNotificationSettings(): ResponseEntity<NotificationSettings> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.getSettings(accountId()).transport())

    override fun updateNotificationSettings(notificationSettingsPatch: NotificationSettingsPatch): ResponseEntity<NotificationSettings> {
        val userId = accountId()
        val value = notificationSettingsPatch
        if (value.water != null && value.water.enabled == null ||
            value.food != null && value.food.enabled == null && value.food.inactiveForMinutes == null ||
            value.weighIn != null && value.weighIn.enabled == null && value.weighIn.time == null ||
            value.goalWarning != null && value.goalWarning.enabled == null ||
            value.quietHours != null && value.quietHours.start == null && value.quietHours.end == null
        ) throw InvalidNotificationInput("Empty settings section")
        val patch = SettingsPatch(
            waterEnabled = value.water?.enabled,
            foodEnabled = value.food?.enabled,
            foodInactiveMinutes = value.food?.inactiveForMinutes,
            weighEnabled = value.weighIn?.enabled,
            weighTime = value.weighIn?.time?.let(::parseTime),
            goalWarningEnabled = value.goalWarning?.enabled,
            quietStart = value.quietHours?.start?.let(::parseTime),
            quietEnd = value.quietHours?.end?.let(::parseTime),
            timezone = value.timezone,
            locale = value.locale?.value,
        )
        if (patch == SettingsPatch()) throw InvalidNotificationInput("Empty settings patch")
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.updateSettings(userId, patch).transport())
    }

    override fun registerDevice(pushDeviceInput: PushDeviceInput): ResponseEntity<PushDevice> {
        val result = service.registerDevice(accountId(), RegisterDevice(
            DevicePlatform.valueOf(pushDeviceInput.platform.value), pushDeviceInput.deviceId, pushDeviceInput.token,
        ))
        val device = result.device
        return ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .cacheControl(CacheControl.noStore())
            .body(PushDevice(PushDevice.Platform.valueOf(device.platform.name), device.token, device.deviceId, device.id, device.enabled))
    }

    override fun unregisterDevice(deviceId: String): ResponseEntity<Unit> {
        service.unregisterDevice(accountId(), deviceId)
        return ResponseEntity.noContent().build()
    }

    private fun accountId(): UUID {
        val headers = request.getHeaders("Authorization").toList()
        if (headers.size != 1) throw InvalidAccessTokenException()
        val header = headers.single()
        if (!header.startsWith("Bearer ", ignoreCase = true)) throw InvalidAccessTokenException()
        val token = header.substring(7)
        if (token.isBlank() || token.length > 8192 || token.any(Char::isWhitespace)) throw InvalidAccessTokenException()
        return verifier.verify(token)
    }

    private fun parseTime(value: String): LocalTime {
        if (!TIME.matches(value)) throw InvalidNotificationInput("Time must use HH:mm")
        return LocalTime.parse(value)
    }

    private fun com.cookie.notification.domain.NotificationSettings.transport() = NotificationSettings(
        locale = NotificationSettings.Locale.forValue(locale),
        water = NotificationSettingsWater(waterEnabled),
        food = NotificationSettingsFood(foodEnabled, foodInactiveMinutes),
        weighIn = NotificationSettingsWeighIn(weighEnabled, weighTime.format(TIME_FORMAT)),
        goalWarning = NotificationSettingsWater(goalWarningEnabled),
        quietHours = NotificationSettingsQuietHours(quietStart.format(TIME_FORMAT), quietEnd.format(TIME_FORMAT)),
        timezone = timezone,
    )

    private companion object {
        val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
