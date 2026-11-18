package com.cookie.health.security

import com.cookie.health.config.HealthProperties
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component
import com.cookie.contracts.assessment.AssessmentWire
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.Date
import java.util.UUID

@Component
class AssessmentReceipts(properties: HealthProperties, environment: Environment, private val mapper: ObjectMapper) {
    private val key = if (properties.receiptKeyPath.isBlank() && environment.acceptsProfiles(Profiles.of("dev", "test"))) {
        ECKeyGenerator(Curve.P_256).keyID("local-preview").generate()
    } else {
        val path = Path.of(properties.receiptKeyPath)
        require(Files.isRegularFile(path) && Files.size(path) in 1..16384) { "Invalid assessment signing key file" }
        ECKey.parse(Files.readString(path))
    }
    val publicKey get() = key.toPublicJWK()
    private val signer: ECDSASigner

    init {
        require(key.curve == Curve.P_256 && key.isPrivate && !key.keyID.isNullOrBlank())
        signer = ECDSASigner(key)
    }

    fun issue(account: UUID, input: JsonNode, result: JsonNode, version: String, date: LocalDate,
              now: Instant, expires: Instant): String {
        val nonce = UUID.randomUUID()
        val claims = JWTClaimsSet.Builder()
            .issuer("health").audience("user-goal-acceptance").subject(account.toString()).jwtID(nonce.toString())
            .issueTime(Date.from(now)).expirationTime(Date.from(expires))
            .claim("purpose", input.path("purpose").asString())
            .claim("profileVersion", input.path("profileVersion").asLong())
            .claim("goalRevision", input.path("goalRevision").asLong())
            .claim("timezone", input.path("timezone").asString())
            .claim("assessmentDate", date.toString()).claim("policyVersion", version)
            .claim("calculationHash", AssessmentWire.hash(mapper, AssessmentWire.calculationInput(input), nonce))
            .claim("inputHash", AssessmentWire.hash(mapper, input, nonce)).claim("resultHash", AssessmentWire.hash(mapper, result, nonce)).build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType("health-receipt+jwt"))
            .keyID(key.keyID).build(), claims).apply { sign(signer) }.serialize()
    }

}
