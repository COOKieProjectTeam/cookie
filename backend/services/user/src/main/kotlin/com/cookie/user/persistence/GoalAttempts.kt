package com.cookie.user.persistence

import com.cookie.contracts.assessment.AssessmentKeyUnavailable
import com.cookie.contracts.assessment.AssessmentWire
import com.cookie.user.config.AssessmentKeys
import com.cookie.user.domain.UserFailure
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Gauge
import org.springframework.jdbc.core.RowMapper
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

@Component
class GoalAttempts(private val store: JdbcUserStore, private val keys: AssessmentKeys, metrics: MeterRegistry) {
    private val db get() = store.jdbc
    private val mapper get() = store.mapper
    private val retryFailures = metrics.counter("cookie.user.goal_attempt.retry_failures")

    init {
        Gauge.builder("cookie.user.goal_attempt.pending", this) {
            it.db.queryForObject("SELECT count(*) FROM goal_attempts WHERE status='PENDING'", Long::class.java)!!.toDouble()
        }.register(metrics)
        Gauge.builder("cookie.user.goal_attempt.oldest.seconds", this) {
            it.db.queryForObject(
                "SELECT COALESCE(EXTRACT(EPOCH FROM now()-min(accepted_at)),0) FROM goal_attempts WHERE status='PENDING'",
                Double::class.java,
            )!!
        }.register(metrics)
    }
    private val row = RowMapper { r, _ ->
        Attempt(
            r.uuid("id"), r.uuid("user_id"), r.uuid("receipt_id"), r.getString("purpose"), r.getString("intent_hash"),
            r.getString("result_hash"), r.getString("status"), r.getObject("effective_from", LocalDate::class.java),
            r.getObject("accepted_at", OffsetDateTime::class.java), r.getString("encrypted_input"),
            r.getString("encrypted_command"), r.getString("response"),
        )
    }

    fun accept(account: UUID, key: UUID, purpose: String, receipt: String, input: JsonNode): JsonNode =
        store.command(account, key, "assessment:$purpose", listOf(receipt, AssessmentWire.hash(mapper, input)), JsonNode::class.java) {
            val now = store.clock.instant()
            val claims = try {
                AssessmentWire.claims(receipt, keys.receipt(), account, now)
            } catch (_: AssessmentKeyUnavailable) {
                throw UserFailure("ASSESSMENT_KEY_UNAVAILABLE", 503)
            } catch (_: Exception) {
                throw UserFailure("NEEDS_NEW_PREVIEW", 409)
            }
            val receiptId = UUID.fromString(claims.jwtid)
            val date = LocalDate.parse(claims.getStringClaim("assessmentDate"))
            val policy = claims.getStringClaim("policyVersion")
            val zone = ZoneId.of(claims.getStringClaim("timezone"))
            if (purpose != claims.getStringClaim("purpose") || input.path("purpose").asString() != purpose ||
                claims.getStringClaim("inputHash") != AssessmentWire.hash(mapper, input, receiptId) ||
                date != now.atZone(zone).toLocalDate()) throw UserFailure("NEEDS_NEW_PREVIEW", 409)
            val intent = AssessmentWire.hash(mapper, json(mapOf("input" to input, "date" to date, "policy" to policy)))
            val priorReceipt = db.query("SELECT * FROM goal_attempts WHERE user_id=? AND receipt_id=?", row, account, receiptId).singleOrNull()
            if (priorReceipt != null) return@command status(priorReceipt)
            val active = db.query("SELECT * FROM goal_attempts WHERE user_id=? AND status='PENDING'", row, account).singleOrNull()
            if (active != null) {
                if (active.intent != intent) throw UserFailure("ATTEMPT_IN_PROGRESS", 409)
                return@command status(active)
            }
            val profile = store.loadProfile(account)
            val revision = db.queryForObject("SELECT goal_revision FROM profiles WHERE user_id=?", Long::class.java, account)!!
            if (profile.onboardingCompleted != (purpose == "RECALC")) throw UserFailure("ATTEMPT_NOT_ALLOWED", 409)
            if (profile.version != claims.getLongClaim("profileVersion") || revision != claims.getLongClaim("goalRevision")) {
                throw UserFailure("NEEDS_NEW_PREVIEW", 409)
            }

            val attempt = store.newId()
            val body = json(mapOf(
                "accountId" to account, "attemptId" to attempt, "acceptedAt" to now.toString(), "receipt" to receipt,
                "calculation" to AssessmentWire.calculationInput(input),
            ))
            val (command, savedInput) = try {
                AssessmentWire.encrypt(keys.health().first(), body) to AssessmentWire.encrypt(keys.storage(), input)
            } catch (_: AssessmentKeyUnavailable) {
                throw UserFailure("ASSESSMENT_KEY_UNAVAILABLE", 503)
            }
            db.update(
                """INSERT INTO goal_attempts(id,user_id,purpose,receipt_id,intent_hash,result_hash,accepted_at,effective_from,
                    encrypted_input,encrypted_command) VALUES (?,?,?,?,?,?,?,?,?,?)""",
                attempt, account, purpose, receiptId, intent, claims.getStringClaim("resultHash"), now.atOffset(ZoneOffset.UTC),
                date, savedInput, command,
            )
            send(account, attempt, command)
            status(load(account, attempt)!!)
        }

    fun read(account: UUID, attempt: UUID): JsonNode =
        store.read(account) { status(load(account, attempt) ?: throw UserFailure("ATTEMPT_NOT_FOUND", 404)) }

    private fun load(account: UUID, attempt: UUID): Attempt? =
        db.query("SELECT * FROM goal_attempts WHERE user_id=? AND id=?", row, account, attempt).singleOrNull()

    private fun status(attempt: Attempt): JsonNode =
        attempt.response?.let(mapper::readTree) ?: status(attempt.id, attempt.status)

    private fun status(id: UUID, state: String, extra: Map<String, Any?> = emptyMap()): JsonNode =
        json(mapOf("onboardingId" to id, "status" to state, "statusUrl" to "/v1/me/goal-attempts/$id") + extra)

    private fun send(account: UUID, attempt: UUID, encrypted: String) =
        store.event(account, AssessmentWire.REQUEST, store.bump(account),
            mapOf("accountId" to account, "attemptId" to attempt, "encryptedPayload" to encrypted))

    @Scheduled(fixedDelay = 30000)
    fun retry() {
        val due = db.query(
            "SELECT * FROM goal_attempts WHERE status='PENDING' AND next_retry_at<=now() ORDER BY next_retry_at,id LIMIT 50", row,
        )
        for (candidate in due) {
            try {
                store.transactions.executeWithoutResult {
                    store.lock(candidate.account)
                    if (store.deleted(candidate.account)) return@executeWithoutResult
                    val updated = db.update(
                        """UPDATE goal_attempts SET next_retry_at=now()+interval '5 minutes'
                            WHERE id=? AND user_id=? AND status='PENDING' AND next_retry_at<=now()""",
                        candidate.id, candidate.account,
                    )
                    if (updated == 1) send(candidate.account, candidate.id, requireNotNull(candidate.command))
                }
            } catch (_: org.springframework.dao.DataAccessException) {
                retryFailures.increment()
            }
        }
    }

    fun consume(subject: String, bytes: ByteArray) {
        val envelope = try {
            require(subject == SUBJECT && bytes.size in 1..65536)
            val event = mapper.readTree(bytes)
            require(event.path("event_type").asString() == AssessmentWire.RESULT && event.path("producer").asString() == "health")
            require(event.path("event_version").isIntegralNumber && event.path("event_version").asLong() == 1L)
            require(event.path("aggregate_type").asString() == "goal_attempt")
            Instant.parse(event.path("occurred_at").asString())
            val p = event.path("payload")
            val id = UUID.fromString(p.path("attemptId").asString())
            require(event.path("aggregate_id").asString() == id.toString())
            Incoming(UUID.fromString(event.path("event_id").asString()), UUID.fromString(p.path("accountId").asString()),
                id, p.path("encryptedPayload").asString())
        } catch (_: Exception) { throw InvalidUserEvent("invalid_assessment_result") }

        store.transactions.executeWithoutResult {
            store.lock(envelope.account)
            val inserted = db.update(
                "INSERT INTO inbox_events(consumer_name,event_id,event_type) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                LifecycleEvents.CONSUMER, envelope.event, AssessmentWire.RESULT,
            )
            if (inserted == 0 || store.deleted(envelope.account)) return@executeWithoutResult
            val attempt = load(envelope.account, envelope.attempt) ?: return@executeWithoutResult
            if (attempt.status != "PENDING") return@executeWithoutResult
            apply(attempt, envelope)
        }
    }

    private fun apply(attempt: Attempt, incoming: Incoming) {
        val result = try {
            AssessmentWire.decrypt(keys.own(), incoming.encrypted, mapper)
        } catch (e: AssessmentKeyUnavailable) {
            throw e
        } catch (_: com.nimbusds.jose.JOSEException) {
            throw InvalidUserEvent("invalid_assessment_result_ciphertext")
        } catch (_: java.text.ParseException) {
            throw InvalidUserEvent("invalid_assessment_result_ciphertext")
        }
        if (result.path("attemptId").asString() != attempt.id.toString() ||
            result.path("accountId").asString() != attempt.account.toString() ||
            result.path("receiptId").asString() != attempt.receipt.toString() ||
            result.path("resultHash").asString() != attempt.resultHash) throw InvalidUserEvent("assessment_result_mismatch")
        val outcome = result.path("outcome").asString()
        if (outcome == "NEEDS_NEW_PREVIEW") {
            finish(attempt, "NEEDS_NEW_PREVIEW", status(attempt.id, "NEEDS_NEW_PREVIEW", mapOf("reason" to "ASSESSMENT_INCOMPATIBLE")))
            return
        }
        val assessment = result.path("assessment")
        if (outcome !in setOf("CALCULATED", "NO_CALORIE_GOAL") || outcome != assessment.path("outcome").asString() ||
            AssessmentWire.hash(mapper, assessment, attempt.receipt) != attempt.resultHash) {
            throw InvalidUserEvent("invalid_assessment_result")
        }

        val input = AssessmentWire.decrypt(keys.own(), requireNotNull(attempt.input), mapper)
        val account = attempt.account
        val revision = db.queryForObject(
            """UPDATE profiles SET sex=?,birth_date=?,height_cm=?,activity_level=?,timezone=?,onboarding_completed=true,
                goal_revision=goal_revision+1,profile_version=profile_version+1,updated_at=now()
                WHERE user_id=? RETURNING goal_revision""", Long::class.java,
            input.path("sex").asString(), LocalDate.parse(input.path("birthDate").asString()),
            input.path("heightCm").decimalValue(), input.path("activity").asString(), input.path("timezone").asString(), account,
        )!!
        db.update("DELETE FROM diet_preferences WHERE user_id=?", account)
        val macro = input.path("macroProfile").asString()
        val restrictions = input.path("foodRestrictions").toList().map { it.asString() }
        for (tag in setOf(macro) + restrictions + input.path("dietStyles").toList().map { it.asString() }) {
            db.update("INSERT INTO diet_preferences(user_id,diet_type) VALUES (?,?)", account, tag)
        }
        db.update("DELETE FROM allergies WHERE user_id=?", account)
        for (allergy in input.path("allergies")) db.update("INSERT INTO allergies(user_id,allergen) VALUES (?,?)", account, allergy.asString())

        val reason = assessment.get("reason")?.takeUnless { it.isNull }?.asString()
        val policy = assessment.path("policyVersion").asString()
        val goalId = store.newId()
        val goal = json(buildMap {
            put("id", goalId)
            put("outcome", outcome)
            put("source", "CALCULATED")
            put("revision", revision)
            put("effectiveFrom", attempt.effectiveFrom)
            put("policyVersion", policy)
            put("macroProfile", macro)
            put("foodRestrictions", restrictions)
            for (field in listOf("calories", "proteinG", "fatG", "carbsG", "fiberG", "reason")) {
                assessment.get(field)?.takeUnless { it.isNull }?.let { put(field, it) }
            }
            input.get("targetWeightKg")?.takeUnless { it.isNull }?.let { put("targetWeightKg", it) }
            put("weeklyChangeKg", input.path("weeklyChangeKg"))
        })
        val kcal = assessment.path("calories")
        db.update(
            """INSERT INTO accepted_goal_versions(id,user_id,attempt_id,revision,effective_from,accepted_at,policy_version,
                outcome,reason,calorie_low,calorie_center,calorie_high,goal) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)""",
            goalId, account, attempt.id, revision, attempt.effectiveFrom, attempt.acceptedAt, policy, outcome, reason,
            kcal.get("low")?.asInt(), kcal.get("center")?.asInt(), kcal.get("high")?.asInt(), goal.toString(),
        )
        store.bump(account)
        store.profileEvent(store.loadProfile(account), listOf("onboardingCompleted", "assessmentInputs"))
        store.event(account, "user.goal.changed", store.bump(account), mapOf("userId" to account, "nutritionGoalVersion" to goal), 2)
        val water = createDefaultWater(attempt)
        finish(attempt, "COMPLETED", status(attempt.id, "COMPLETED", mapOf(
            "outcome" to outcome, "reason" to reason, "goalRevision" to revision, "hydrationRevision" to water,
            "initialWeightId" to result.get("initialWeightId")?.takeUnless { it.isNull },
        )))
    }

    private fun createDefaultWater(attempt: Attempt): Long {
        val account = attempt.account
        val existing = db.queryForObject("SELECT hydration_revision FROM profiles WHERE user_id=?", Long::class.java, account)!!
        if (existing != 0L || attempt.purpose != "INITIAL") return existing
        val id = store.newId()
        db.update("UPDATE profiles SET hydration_revision=1 WHERE user_id=?", account)
        db.update(
            """INSERT INTO hydration_goal_versions(id,user_id,revision,effective_from,target_ml,source)
                VALUES (?,?,1,?,1500,'CALCULATED')""", id, account, attempt.effectiveFrom,
        )
        store.event(account, "user.hydration_goal.changed", store.bump(account), mapOf(
            "userId" to account, "id" to id, "revision" to 1, "targetMl" to 1500, "effectiveFrom" to attempt.effectiveFrom,
        ))
        return 1
    }

    private fun finish(a: Attempt, state: String, response: JsonNode) {
        db.update(
            """UPDATE goal_attempts SET status=?,response=?::jsonb,encrypted_input=NULL,encrypted_command=NULL
                WHERE user_id=? AND id=? AND status='PENDING'""", state, response.toString(), a.account, a.id,
        )
    }

    private fun json(value: Any): JsonNode = mapper.valueToTree(value)
    private fun ResultSet.uuid(field: String) = getObject(field, UUID::class.java)
    private data class Incoming(val event: UUID, val account: UUID, val attempt: UUID, val encrypted: String)
    private data class Attempt(
        val id: UUID, val account: UUID, val receipt: UUID, val purpose: String, val intent: String, val resultHash: String,
        val status: String, val effectiveFrom: LocalDate, val acceptedAt: OffsetDateTime, val input: String?,
        val command: String?, val response: String?,
    )

    companion object { const val SUBJECT = "cookie.events." + AssessmentWire.RESULT + ".v1" }
}
