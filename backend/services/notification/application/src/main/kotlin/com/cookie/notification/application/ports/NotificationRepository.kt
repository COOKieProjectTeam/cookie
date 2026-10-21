package com.cookie.notification.application.ports

import com.cookie.notification.domain.*
import java.util.UUID

interface NotificationRepository {
    fun settings(userId: UUID): NotificationSettings
    fun updateSettings(userId: UUID, patch: SettingsPatch): NotificationSettings
    fun registerDevice(userId: UUID, command: RegisterDevice): DeviceRegistration
    fun unregisterDevice(userId: UUID, deviceId: String)
}
