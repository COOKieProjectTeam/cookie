package com.cookie.health.messaging

import com.cookie.contracts.assessment.AssessmentKeyUnavailable
import com.cookie.contracts.assessment.AssessmentWire
import com.cookie.health.config.HealthProperties
import com.cookie.health.domain.AssessmentPolicy
import com.cookie.health.generated.model.HealthAssessmentInput
import com.cookie.health.generated.model.HealthAssessmentPreview
import com.cookie.health.persistence.HealthMeasurements
import com.cookie.health.security.AssessmentReceipts
import com.cookie.health.transport.calculateAssessment
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Component
class HealthAssessmentExecution(
    private val store: HealthMeasurements,
    private val receipts: AssessmentReceipts,
    private val properties: HealthProperties,
    private val mapper: ObjectMapper,
    environment: Environment,
) {
    init {
        if (!environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            AssessmentWire.encryptionKeys(properties.assessmentDecryptionKeys, true)
            AssessmentWire.encryptionKeys(properties.userEncryptionKeys, false)
        }
    }

    fun handle(subject: String, data: ByteArray) {
        val request = parse(subject, data)
        store.db.transactions.executeWithoutResult {
            store.db.lock(request.account)
            val inserted = store.jdbc.update(
                "INSERT INTO inbox_events(consumer_name,event_id,event_type) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                HealthDeletion.CONSUMER, request.event, AssessmentWire.REQUEST,
            )
            if (inserted == 0 || store.db.deleted(request.account)) return@executeWithoutResult
            val prior = store.jdbc.query(
                "SELECT encrypted_result FROM assessment_executions WHERE user_id=? AND attempt_id=?",
                { row, _ -> row.getString(1) }, request.account, request.attempt,
            ).singleOrNull()
            val encrypted = prior ?: compute(request)
            store.event(request.account, AssessmentWire.RESULT, 1, "goal_attempt", request.attempt.toString(), 1,
                mapOf("accountId" to request.account, "attemptId" to request.attempt, "encryptedPayload" to encrypted))
        }
    }

    private fun compute(request: Request): String {
        if (properties.assessmentDecryptionKeys.isBlank() || properties.userEncryptionKeys.isBlank()) throw AssessmentKeyUnavailable()
        val key = AssessmentWire.encryptionKeys(properties.userEncryptionKeys, false).first()
        val own = AssessmentWire.encryptionKeys(properties.assessmentDecryptionKeys, true)
        val decrypted = try {
            AssessmentWire.decrypt(own, request.encrypted, mapper)
        } catch (e: AssessmentKeyUnavailable) {
            throw e
        } catch (_: Exception) {
            throw InvalidHealthEvent("invalid_assessment_ciphertext")
        }
        val checks = if (properties.receiptVerificationKeys.isBlank()) listOf(receipts.publicKey)
            else AssessmentWire.signingKeys(properties.receiptVerificationKeys)
        val verified = try {
            require(decrypted.path("attemptId").asString() == request.attempt.toString())
            require(decrypted.path("accountId").asString() == request.account.toString())
            val accepted = Instant.parse(decrypted.path("acceptedAt").asString())
            val receipt = decrypted.path("receipt").asString()
            val claims = AssessmentWire.claims(receipt, checks, request.account, accepted)
            val nonce = UUID.fromString(claims.jwtid)
            val input = decrypted.path("calculation")
            require(AssessmentWire.hash(mapper, input, nonce) == claims.getStringClaim("calculationHash"))
            val date = LocalDate.parse(claims.getStringClaim("assessmentDate"))
            require(accepted.atZone(ZoneId.of(claims.getStringClaim("timezone"))).toLocalDate() == date)
            val fullInput = (input.deepCopy() as ObjectNode).apply {
                for (name in listOf("foodRestrictions", "dietStyles", "allergies")) putArray(name)
            }
            val model = if (claims.getStringClaim("policyVersion") == AssessmentPolicy.VERSION) {
                mapper.treeToValue(fullInput, HealthAssessmentInput::class.java).also {
                    require(it.purpose.value == claims.getStringClaim("purpose"))
                }
            } else null
            Triple(claims, model, date)
        } catch (e: AssessmentKeyUnavailable) {
            throw e
        } catch (_: Exception) {
            throw InvalidHealthEvent("invalid_assessment_receipt")
        }
        val (claims, input, date) = verified
        val nonce = UUID.fromString(claims.jwtid)
        val preview = input?.let { calculateAssessment(it, date) }
        val result = preview?.let { mapper.valueToTree<JsonNode>(it) }
        val matches = preview != null && preview.outcome != HealthAssessmentPreview.Outcome.MAINTENANCE_PREVIEW_AVAILABLE &&
            AssessmentWire.hash(mapper, result!!, nonce) == claims.getStringClaim("resultHash")

        var weightId: UUID? = null
        if (matches && input.purpose == HealthAssessmentInput.Purpose.INITIAL) {
            weightId = store.jdbc.query(
                "SELECT id FROM weight_measurements WHERE user_id=? AND first_weight",
                { row, _ -> row.getObject(1, UUID::class.java) }, request.account,
            ).singleOrNull()
            if (weightId == null) {
                weightId = UUID.randomUUID()
                store.insert(request.account, HealthMeasurements.Weight(weightId, input.measuredAt, input.currentWeightKg,
                    1, "MANUAL", null, false), first = true)
            }
        }
        val payload = store.json(mapOf(
            "accountId" to request.account, "attemptId" to request.attempt, "receiptId" to nonce,
            "resultHash" to claims.getStringClaim("resultHash"), "initialWeightId" to weightId,
            "outcome" to if (matches) preview.outcome.value else "NEEDS_NEW_PREVIEW",
            "assessment" to if (matches) result else null,
        ))
        val encrypted = AssessmentWire.encrypt(key, payload)
        store.jdbc.update("INSERT INTO assessment_executions(user_id,attempt_id,receipt_id,encrypted_result) VALUES (?,?,?,?)",
            request.account, request.attempt, nonce, encrypted)
        return encrypted
    }

    private data class Request(val event: UUID, val account: UUID, val attempt: UUID, val encrypted: String)
    private fun parse(subject: String, data: ByteArray): Request = try {
        require(subject == SUBJECT && data.size <= 65536)
        val event = mapper.readTree(data)
        require(event.path("producer").asString() == "user" && event.path("event_type").asString() == AssessmentWire.REQUEST)
        require(event.path("event_version").isIntegralNumber && event.path("event_version").asLong() == 1L)
        Instant.parse(event.path("occurred_at").asString())
        val payload = event.path("payload")
        val attempt = UUID.fromString(payload.path("attemptId").asString())
        require(event.path("aggregate_type").asString() == "user")
        val account = UUID.fromString(payload.path("accountId").asString())
        require(event.path("aggregate_id").asString() == account.toString())
        Request(UUID.fromString(event.path("event_id").asString()), account, attempt, payload.path("encryptedPayload").asString())
    } catch (_: Exception) {
        throw InvalidHealthEvent("invalid_assessment_command")
    }

    companion object { const val SUBJECT = "cookie.events." + AssessmentWire.REQUEST + ".v1" }
}
