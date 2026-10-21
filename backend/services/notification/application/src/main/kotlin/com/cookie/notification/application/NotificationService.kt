package com.cookie.notification.application

import com.cookie.notification.application.ports.NotificationRepository
import com.cookie.notification.domain.*
import java.util.UUID

class NotificationService(private val repository: NotificationRepository) {
    fun getSettings(userId: UUID): NotificationSettings = repository.settings(userId)
    fun updateSettings(userId: UUID, patch: SettingsPatch): NotificationSettings = repository.updateSettings(userId, patch)
    fun registerDevice(userId: UUID, command: RegisterDevice): DeviceRegistration = repository.registerDevice(userId, command)
    fun unregisterDevice(userId: UUID, deviceId: String) = repository.unregisterDevice(userId, deviceId)
}
