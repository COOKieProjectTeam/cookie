package com.cookie.user.application

import com.cookie.user.domain.*
import java.time.LocalDate
import java.util.UUID

/** Persistence owns the local transaction; callers can never supply a second owner. */
interface UserStore {
    fun profile(userId: UUID): Profile
    fun update(userId: UUID, key: UUID, value: ProfileChange): Profile
    fun hydration(userId: UUID): HydrationGoal
    fun setHydration(userId: UUID, key: UUID, targetMl: Int): HydrationGoal
    fun override(userId: UUID, date: LocalDate): DailyOverride
    fun setOverride(userId: UUID, key: UUID, date: LocalDate, value: OverrideCommand): DailyOverride
    fun deleteOverride(userId: UUID, key: UUID, date: LocalDate)
}

class UserService(private val store: UserStore) {
    fun profile(userId: UUID) = store.profile(userId)
    fun update(userId: UUID, key: UUID, value: ProfileChange): Profile { value.validate(); return store.update(userId, key, value) }
    fun hydration(userId: UUID) = store.hydration(userId)
    fun setHydration(userId: UUID, key: UUID, targetMl: Int): HydrationGoal {
        ensure(targetMl in 1..20000); return store.setHydration(userId, key, targetMl)
    }
    fun override(userId: UUID, date: LocalDate) = store.override(userId, date)
    fun setOverride(userId: UUID, key: UUID, date: LocalDate, value: OverrideCommand): DailyOverride {
        value.validate(); return store.setOverride(userId, key, date, value)
    }
    fun deleteOverride(userId: UUID, key: UUID, date: LocalDate) = store.deleteOverride(userId, key, date)
}
