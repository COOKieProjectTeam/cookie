package com.cookie.contracts.assessment

import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWEHeader
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSADecrypter
import com.nimbusds.jose.crypto.RSAEncrypter
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** Canonical wire profile shared by the producer and consumer of accepted assessments. */
object AssessmentWire {
    const val REQUEST = "user.goal_assessment.requested"
    const val RESULT = "health.goal_assessment.resolved"
    const val TYPE = "health-receipt+jwt"

    fun validInput(node: JsonNode): Boolean {
        val text = setOf("purpose","timezone","sex","birthDate","activity","macroProfile","measuredAt")
        val integral = setOf("profileVersion","goalRevision")
        val decimal = setOf("heightCm","currentWeightKg","weeklyChangeKg")
        val tags = mapOf("foodRestrictions" to 5, "dietStyles" to 1, "allergies" to 14)
        val required = text + integral + decimal + tags.keys + "standardAdultPolicyApplies"
        if (!node.isObject || required.any { !node.hasNonNull(it) }) return false
        return node.properties().all { (field, value) ->
            when (field) {
                in text -> value.isString && value.asString().length in 1..100
                in integral -> value.isIntegralNumber && value.canConvertToLong()
                in decimal, "targetWeightKg" -> value.isNumber
                "standardAdultPolicyApplies" -> value.isBoolean
                in tags -> value.isArray && value.size() <= tags.getValue(field) && value.all {
                    it.isString && it.asString().length in 1..40
                } && value.toList().map(JsonNode::asString).toSet().size == value.size()
                else -> false
            }
        }
    }

    fun calculationInput(node: JsonNode): JsonNode = (node.deepCopy() as tools.jackson.databind.node.ObjectNode)
        .also { it.remove(listOf("foodRestrictions", "dietStyles", "allergies")) }

    fun hash(mapper: ObjectMapper, node: JsonNode, nonce: UUID? = null): String {
        val canonical = mapper.writeValueAsBytes(canonical(mapper, node))
        val digest = MessageDigest.getInstance("SHA-256")
        if (nonce != null) {
            digest.update(nonce.toString().toByteArray(Charsets.US_ASCII))
            digest.update(0)
        }
        return HexFormat.of().formatHex(digest.digest(canonical))
    }

    private fun canonical(mapper: ObjectMapper, node: JsonNode): Any? = when {
        node.isObject -> node.properties().filterNot { it.value.isNull }
            .associate { it.key to canonical(mapper, it.value) }.toSortedMap()
        node.isArray -> node.toList().map { canonical(mapper, it) }.sortedBy { mapper.writeValueAsString(it) }
        node.isNumber -> node.decimalValue().stripTrailingZeros()
        node.isString -> node.asString()
        node.isBoolean -> node.asBoolean()
        else -> null
    }

    fun claims(receipt: String, keys: List<ECKey>, account: UUID, acceptedAt: Instant): JWTClaimsSet {
        require(receipt.length in 1..8192)
        val jwt = SignedJWT.parse(receipt)
        require(jwt.header.algorithm == JWSAlgorithm.ES256 && jwt.header.type == JOSEObjectType(TYPE))
        require(jwt.header.criticalParams.isNullOrEmpty())
        val key = keys.singleOrNull { it.keyID == jwt.header.keyID } ?: throw AssessmentKeyUnavailable()
        require(key.curve == Curve.P_256 && jwt.verify(ECDSAVerifier(key.toPublicJWK())))
        val value = jwt.jwtClaimsSet
        require(value.issuer == "health" && value.audience == listOf("user-goal-acceptance") && value.subject == account.toString())
        UUID.fromString(value.jwtid)
        val issued = value.issueTime.toInstant()
        val expires = value.expirationTime.toInstant()
        require(!issued.isAfter(acceptedAt) && expires.isAfter(acceptedAt) && !expires.isAfter(issued.plusSeconds(1800)))
        return value
    }

    fun signingKeys(path: String): List<ECKey> =
        readKeys(path).map { key ->
            if (key !is ECKey || key.isPrivate || key.curve != Curve.P_256) throw AssessmentKeyUnavailable()
            key
        }

    fun encryptionKeys(path: String, privateKey: Boolean): List<RSAKey> =
        readKeys(path).map { key ->
            if (key !is RSAKey || key.isPrivate != privateKey || key.size() < 2048) throw AssessmentKeyUnavailable()
            key
        }

    private fun readKeys(value: String): List<com.nimbusds.jose.jwk.JWK> {
        try {
            return loadKeys(value)
        } catch (_: Exception) {
            throw AssessmentKeyUnavailable()
        }
    }

    private fun loadKeys(value: String): List<com.nimbusds.jose.jwk.JWK> {
        val path = Path.of(value)
        require(Files.isRegularFile(path) && Files.size(path) in 1..65536)
        val keys = JWKSet.parse(Files.readString(path)).keys
        require(keys.size in 1..16 && keys.all { !it.keyID.isNullOrBlank() } && keys.map { it.keyID }.toSet().size == keys.size)
        return keys
    }

    fun encrypt(key: RSAKey, payload: JsonNode): String =
        JWEObject(JWEHeader.Builder(JWEAlgorithm.RSA_OAEP_256, EncryptionMethod.A256GCM)
            .keyID(key.keyID).contentType("application/json").build(), Payload(payload.toString()))
            .apply { encrypt(RSAEncrypter(key)) }.serialize()

    fun decrypt(keys: List<RSAKey>, compact: String, mapper: ObjectMapper): JsonNode {
        require(compact.length in 1..65536)
        val message = JWEObject.parse(compact)
        require(message.header.algorithm == JWEAlgorithm.RSA_OAEP_256 && message.header.encryptionMethod == EncryptionMethod.A256GCM)
        require(message.header.contentType == "application/json" && message.header.criticalParams.isNullOrEmpty())
        val key = keys.singleOrNull { it.keyID == message.header.keyID } ?: throw AssessmentKeyUnavailable()
        message.decrypt(RSADecrypter(key))
        return mapper.readTree(message.payload.toBytes()).also { require(it.isObject) }
    }
}

class AssessmentKeyUnavailable : IllegalStateException("Assessment key unavailable")
