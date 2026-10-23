package com.cookie.user.persistence

import com.cookie.user.application.UserStore
import com.cookie.user.domain.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.ResultSet
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Repository
class JdbcUserStore(val jdbc: JdbcTemplate, val transactions: TransactionTemplate,
                    val mapper: ObjectMapper, val clock: Clock) : UserStore {
    /** Advisory locks also serialize activation/deletion before a profile row exists. */
    fun lock(userId: UUID) {
        check(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Any::class.java, userId.mostSignificantBits xor userId.leastSignificantBits)
    }
    fun deleted(userId: UUID) = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM account_tombstones WHERE user_id=?)", Boolean::class.java, userId) == true
    fun active(userId: UUID) {
        if (deleted(userId)) throw UserFailure("ACCOUNT_DELETED",403)
        // A verified, unexpired Identity access token is sufficient to bootstrap
        // an older account whose activation is no longer retained by JetStream.
        // The caller holds the same lock used by deletion; tombstones win above.
        val created=jdbc.update("INSERT INTO profiles(user_id,locale) VALUES (?,'ru') ON CONFLICT DO NOTHING",userId)
        if(created==1) profileEvent(loadProfile(userId),listOf("created"))
    }
    private fun <T : Any> read(userId: UUID, block: () -> T): T = requireNotNull(transactions.execute {
        lock(userId); active(userId); block()
    })
    private fun <T : Any> command(userId: UUID, key: UUID, operation: String, input: Any, type: Class<T>, block: () -> T): T =
        read(userId) {
            ensure(key.version() == 4 && key.variant() == 2)
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(input)).joinToString("") { "%02x".format(it) }
            val previous = jdbc.query("SELECT fingerprint,response::text FROM idempotency_commands WHERE user_id=? AND operation=? AND key=?",
                { rs, _ -> rs.getString(1) to rs.getString(2) },userId,operation,key).singleOrNull()
            if (previous != null) {
                if(previous.first != fingerprint) throw UserFailure("IDEMPOTENCY_CONFLICT",409)
                mapper.readValue(previous.second,type)
            } else {
                val result = block()
                jdbc.update("INSERT INTO idempotency_commands(user_id,operation,key,fingerprint,response) VALUES (?,?,?,?,?::jsonb)",
                    userId,operation,key,fingerprint,mapper.writeValueAsString(result))
                result
            }
        }
    override fun profile(userId: UUID) = read(userId) { loadProfile(userId) }
    fun loadProfile(userId: UUID): Profile = jdbc.query("SELECT * FROM profiles WHERE user_id=?", { r, _ ->
        Profile(userId,r.getString("sex")?.let(Sex::valueOf),r.getObject("birth_date",LocalDate::class.java),r.getBigDecimal("height_cm"),
            r.getString("activity_level")?.let(ActivityLevel::valueOf),r.getString("timezone"),r.getString("locale"),
            jdbc.query("SELECT diet_type FROM diet_preferences WHERE user_id=? ORDER BY diet_type",{ row,_ -> DietType.valueOf(row.getString(1)) },userId).toSet(),
            jdbc.query("SELECT allergen FROM allergies WHERE user_id=? ORDER BY allergen",{ row,_ -> Allergen.valueOf(row.getString(1)) },userId).toSet(),
            r.getBoolean("onboarding_completed"),r.getLong("version"))
    },userId).single()
    override fun update(userId: UUID,key: UUID,value: ProfileChange): Profile = command(userId,key,"profile",value,Profile::class.java) {
        val old = loadProfile(userId)
        if(old.version != value.version) throw UserFailure("VERSION_CONFLICT",409)
        jdbc.update("""UPDATE profiles SET height_cm=COALESCE(?,height_cm),activity_level=COALESCE(?,activity_level),
            timezone=COALESCE(?,timezone),locale=COALESCE(?,locale),version=version+1,updated_at=now() WHERE user_id=?""",
            value.heightCm,value.activityLevel?.name,value.timezone,value.locale,userId)
        value.dietPreferences?.let { values ->
            jdbc.update("DELETE FROM diet_preferences WHERE user_id=?",userId)
            values.forEach { jdbc.update("INSERT INTO diet_preferences VALUES (?,?)",userId,it.name) }
        }
        value.allergies?.let { values ->
            jdbc.update("DELETE FROM allergies WHERE user_id=?",userId)
            values.forEach { jdbc.update("INSERT INTO allergies VALUES (?,?)",userId,it.name) }
        }
        val updated = loadProfile(userId)
        val changed = listOfNotNull(value.heightCm?.let { "heightCm" },value.activityLevel?.let { "activityLevel" },
            value.timezone?.let { "timezone" },value.locale?.let { "locale" },value.dietPreferences?.let { "dietPreferences" },value.allergies?.let { "allergies" })
        profileEvent(updated,changed)
        updated
    }
    fun profileEvent(profile: Profile,changed: List<String>) = event(profile.userId,"user.profile.updated",profile.version,
        mapOf("userId" to profile.userId,"changedFields" to changed,"timezone" to profile.timezone,"locale" to profile.locale,"onboardingCompleted" to profile.onboardingCompleted))
    fun event(userId: UUID,type: String,version: Long,payload: Any) {
        jdbc.update("INSERT INTO outbox_events(event_id,user_id,event_type,aggregate_version,payload) VALUES (?,?,?,?,?::jsonb)",
            newId(),userId,type,version,mapper.writeValueAsString(payload))
    }
    private fun bump(userId: UUID): Long = jdbc.queryForObject("UPDATE profiles SET version=version+1,updated_at=now() WHERE user_id=? RETURNING version",Long::class.java,userId)!!
    private fun today(userId: UUID) = LocalDate.now(clock.withZone(ZoneId.of(loadProfile(userId).timezone)))
    override fun currentGoal(userId: UUID) = read(userId) {
        jdbc.query("SELECT * FROM nutrition_goal_versions WHERE user_id=? AND effective_from<=? ORDER BY effective_from DESC,revision DESC LIMIT 1",
            { r,_ -> goal(r) },userId,today(userId)).singleOrNull() ?: throw UserFailure("GOAL_NOT_FOUND",404)
    }
    override fun setGoal(userId: UUID,key: UUID,value: NutritionGoalCommand): NutritionGoal = command(userId,key,"goal",value,NutritionGoal::class.java) {
        val goal = NutritionGoal(newId(),today(userId),bump(userId),value)
        insertGoal(userId,goal)
        event(userId,"user.goal.changed",goal.revision,mapOf("userId" to userId,"nutritionGoalVersion" to goal))
        goal
    }
    fun insertGoal(userId: UUID,goal: NutritionGoal) {
        val v=goal.value; val n=v.nutrients
        jdbc.update("""INSERT INTO nutrition_goal_versions(id,user_id,revision,effective_from,calorie_target,protein_g,fat_g,carbs_g,fiber_g,
            target_weight_kg,target_weight_change_per_week,source) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
            goal.id,userId,goal.revision,goal.effectiveFrom,n.calories,n.proteinG,n.fatG,n.carbsG,n.fiberG,v.targetWeightKg,v.targetWeightChangeKgPerWeek,goal.source)
    }
    private fun goal(r: ResultSet) = NutritionGoal(r.getObject("id",UUID::class.java),r.getObject("effective_from",LocalDate::class.java),r.getLong("revision"),
        NutritionGoalCommand(Nutrients(r.getBigDecimal("calorie_target"),r.getBigDecimal("protein_g"),r.getBigDecimal("fat_g"),r.getBigDecimal("carbs_g"),r.getBigDecimal("fiber_g")),
            r.getBigDecimal("target_weight_kg"),r.getBigDecimal("target_weight_change_per_week")),r.getString("source"))
    override fun goalHistory(userId: UUID,cursor: Long?,limit: Int) = read(userId) {
        val items=jdbc.query("SELECT * FROM nutrition_goal_versions WHERE user_id=? AND revision<? ORDER BY revision DESC LIMIT ?",
            { r,_ -> goal(r) },userId,cursor ?: Long.MAX_VALUE,limit+1)
        GoalPage(items.take(limit),if(items.size>limit) items[limit-1].revision.toString() else null)
    }
    override fun hydration(userId: UUID) = read(userId) {
        jdbc.query("SELECT * FROM hydration_goal_versions WHERE user_id=? AND effective_from<=? ORDER BY effective_from DESC,revision DESC LIMIT 1",
            { r,_ -> HydrationGoal(r.getObject("id",UUID::class.java),r.getObject("effective_from",LocalDate::class.java),r.getLong("revision"),r.getInt("target_ml")) },userId,today(userId))
            .singleOrNull() ?: throw UserFailure("GOAL_NOT_FOUND",404)
    }
    override fun setHydration(userId: UUID,key: UUID,targetMl: Int): HydrationGoal = command(userId,key,"hydration",targetMl,HydrationGoal::class.java) {
        val goal=HydrationGoal(newId(),today(userId),bump(userId),targetMl)
        jdbc.update("INSERT INTO hydration_goal_versions(id,user_id,revision,effective_from,target_ml,source) VALUES (?,?,?,?,?,'MANUAL')",
            goal.id,userId,goal.revision,goal.effectiveFrom,targetMl)
        event(userId,"user.hydration_goal.changed",goal.revision,mapOf("userId" to userId,"targetMl" to targetMl,"effectiveFrom" to goal.effectiveFrom))
        goal
    }
    override fun override(userId: UUID,date: LocalDate) = read(userId) { loadOverride(userId,date) ?: throw UserFailure("OVERRIDE_NOT_FOUND",404) }
    private fun loadOverride(userId: UUID,date: LocalDate) = jdbc.query("SELECT * FROM daily_goal_overrides WHERE user_id=? AND local_date=?",{ r,_ ->
        DailyOverride(r.getObject("id",UUID::class.java),date,OverrideCommand(OverrideType.valueOf(r.getString("type")),r.getBigDecimal("calorie_target"),
            r.getBigDecimal("protein_g"),r.getBigDecimal("fat_g"),r.getBigDecimal("carbs_g"),r.getBigDecimal("fiber_g"),r.getBoolean("unlimited_calories"))) },userId,date).singleOrNull()
    override fun setOverride(userId: UUID,key: UUID,date: LocalDate,value: OverrideCommand): DailyOverride =
        command(userId,key,"override:$date",value,DailyOverride::class.java) {
            if(value.type==OverrideType.CHEAT_DAY && jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM daily_goal_overrides WHERE user_id=? AND cheat_month=? AND local_date<>?)",
                    Boolean::class.java,userId,date.withDayOfMonth(1),date)==true) throw UserFailure("CHEAT_DAY_LIMIT_REACHED",409)
            val id=loadOverride(userId,date)?.id ?: newId()
            jdbc.update("""INSERT INTO daily_goal_overrides(id,user_id,local_date,calorie_target,protein_g,fat_g,carbs_g,fiber_g,unlimited_calories,type)
                VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(user_id,local_date) DO UPDATE SET calorie_target=EXCLUDED.calorie_target,
                protein_g=EXCLUDED.protein_g,fat_g=EXCLUDED.fat_g,carbs_g=EXCLUDED.carbs_g,fiber_g=EXCLUDED.fiber_g,
                unlimited_calories=EXCLUDED.unlimited_calories,type=EXCLUDED.type,updated_at=now()""",id,userId,date,value.calories,value.proteinG,value.fatG,value.carbsG,value.fiberG,value.unlimitedCalories,value.type.name)
            val result=DailyOverride(id,date,value)
            event(userId,"user.goal_override.changed",bump(userId),mapOf("userId" to userId,"localDate" to date,"override" to result))
            result
        }
    override fun deleteOverride(userId: UUID,key: UUID,date: LocalDate) {
        command(userId,key,"delete-override:$date",date,String::class.java) {
            val count=jdbc.update("DELETE FROM daily_goal_overrides WHERE user_id=? AND local_date=?",userId,date)
            if(count==0) throw UserFailure("OVERRIDE_NOT_FOUND",404)
            event(userId,"user.goal_override.changed",bump(userId),mapOf("userId" to userId,"localDate" to date,"override" to null))
            "deleted"
        }
    }
    fun newId(): UUID {
        val random=SecureRandom()
        val high=(clock.millis() shl 16) or 0x7000L or (random.nextLong() and 0xfff)
        return UUID(high,(random.nextLong() and 0x3fffffffffffffffL) or Long.MIN_VALUE)
    }
}
