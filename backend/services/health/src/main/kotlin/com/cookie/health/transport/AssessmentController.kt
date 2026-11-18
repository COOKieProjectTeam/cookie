package com.cookie.health.transport

import com.cookie.health.HealthFailure
import com.cookie.health.domain.AssessmentPolicy
import com.cookie.health.generated.api.HealthApi
import com.cookie.health.generated.model.*
import com.cookie.health.persistence.HealthDatabase
import com.cookie.health.persistence.HealthImports
import com.cookie.health.persistence.HealthMeasurements
import com.cookie.health.security.AssessmentReceipts
import com.cookie.health.security.IdentityTokens
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.LocalDate
import tools.jackson.databind.JsonNode
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

@Controller
class AssessmentController(
    private val database: HealthDatabase,
    private val tokens: IdentityTokens,
    private val receipts: AssessmentReceipts,
    private val request: HttpServletRequest,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    private val measurements: HealthMeasurements,
    private val imports: HealthImports,
) : HealthApi {
    override fun calculateNutritionAssessment(healthAssessmentInput: HealthAssessmentInput): ResponseEntity<HealthAssessmentPreview> {
        val account = account()
        val input = healthAssessmentInput
        if (input.timezone !in ZoneId.getAvailableZoneIds()) invalid("timezone")
        if (input.profileVersion < 1) invalid("profileVersion")
        if (input.goalRevision < 0) invalid("goalRevision")
        val zone = ZoneId.of(input.timezone)
        return database.forAccount(account) {
            val now = clock.instant().truncatedTo(ChronoUnit.SECONDS)
            if (input.measuredAt.toInstant() > now.plusSeconds(1) || input.measuredAt.year !in 1..9999 ||
                input.measuredAt.nano % 1000 != 0) invalid("measuredAt")
            val date = now.atZone(zone).toLocalDate()
            val preview = calculateAssessment(input, date)
            val result = if (preview.outcome == HealthAssessmentPreview.Outcome.MAINTENANCE_PREVIEW_AVAILABLE) preview else {
                val expires = minOf(now.plusSeconds(1800), date.plusDays(1).atStartOfDay(zone).toInstant())
                val receipt = receipts.issue(account, mapper.valueToTree(input), mapper.valueToTree(preview),
                    AssessmentPolicy.VERSION, date, now, expires)
                preview.copy(receipt = receipt, expiresAt = expires.atOffset(ZoneOffset.UTC))
            }
            ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result)
        }
    }

    override fun addWeightMeasurement(idempotencyKey: UUID, healthWeightInput: HealthWeightInput): ResponseEntity<HealthWeight> =
        respond(measurements.addManual(account(), idempotencyKey, healthWeightInput.valueKg, healthWeightInput.measuredAt), 201)

    override fun updateWeightMeasurement(weightId: UUID, ifMatch: Long, idempotencyKey: UUID,
                                         healthWeightPatch: HealthWeightPatch): ResponseEntity<HealthWeight> =
        respond(measurements.editManual(account(), weightId, ifMatch, idempotencyKey, healthWeightPatch.valueKg, healthWeightPatch.measuredAt))

    override fun deleteWeightMeasurement(weightId: UUID, ifMatch: Long, idempotencyKey: UUID): ResponseEntity<Unit> {
        measurements.deleteManual(account(), weightId, ifMatch, idempotencyKey)
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }

    override fun listWeightMeasurements(from: LocalDate?, to: LocalDate?, timezone: String?, cursor: String?, limit: Int): ResponseEntity<HealthWeightPage> =
        respond(measurements.page(account(), from, to, timezone, cursor, limit))

    override fun registerHealthConnection(idempotencyKey: UUID, healthConnectionInput: HealthConnectionInput): ResponseEntity<HealthConnection> =
        respond(imports.register(account(), idempotencyKey, healthConnectionInput.platform.value, healthConnectionInput.timezone), 201)

    override fun listHealthConnections(): ResponseEntity<HealthConnections> = respond(imports.list(account()))

    override fun resumeHealthConnection(connectionId: UUID, ifMatch: Long, idempotencyKey: UUID): ResponseEntity<HealthConnection> =
        respond(imports.changeConnection(account(), connectionId, ifMatch, idempotencyKey, resume = true))

    override fun disconnectHealthConnection(connectionId: UUID, ifMatch: Long, idempotencyKey: UUID): ResponseEntity<HealthConnection> =
        respond(imports.changeConnection(account(), connectionId, ifMatch, idempotencyKey, resume = false))

    override fun importHealthBatch(connectionId: UUID, idempotencyKey: UUID, healthBatch: HealthBatch): ResponseEntity<HealthBatchResult> =
        respond(imports.applyBatch(account(), connectionId, idempotencyKey, request.getAttribute(ImportBodyAdvice.BODY) as JsonNode))

    override fun schedulePrimaryHealthConnection(idempotencyKey: UUID, healthPrimaryInput: HealthPrimaryInput): ResponseEntity<HealthPrimary> =
        respond(imports.setPrimary(account(), idempotencyKey, healthPrimaryInput.connectionId, healthPrimaryInput.revision, healthPrimaryInput.timezone))

    override fun purgeHealthConnection(connectionId: UUID, ifMatch: Long, idempotencyKey: UUID): ResponseEntity<HealthPurge> =
        respond(imports.startPurge(account(), connectionId, idempotencyKey, ifMatch), 202)

    override fun getHealthPurge(connectionId: UUID, purgeId: UUID): ResponseEntity<HealthPurge> =
        respond(imports.purge(account(), connectionId, purgeId))

    private inline fun <reified T : Any> respond(node: JsonNode, status: Int = 200): ResponseEntity<T> =
        ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(mapper.treeToValue(node, T::class.java))

    private fun account(): UUID {
        val headers = request.getHeaders("Authorization").toList()
        if (headers.size != 1 || !headers.single().startsWith("Bearer ", ignoreCase = true)) {
            throw HealthFailure("INVALID_ACCESS_TOKEN", 401)
        }
        return tokens.verify(headers.single().substring(7))
    }

    private fun invalid(field: String): Nothing = throw HealthFailure("INVALID_ASSESSMENT", 400, field)

}
