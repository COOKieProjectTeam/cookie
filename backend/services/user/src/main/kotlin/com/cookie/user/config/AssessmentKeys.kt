package com.cookie.user.config

import com.cookie.contracts.assessment.AssessmentWire
import com.cookie.contracts.assessment.AssessmentKeyUnavailable
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component

@Component
class AssessmentKeys(private val properties: UserProperties, environment: Environment) {
    init {
        if (!environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            receipt()
            health()
            own()
            storage()
        }
    }

    fun receipt() = AssessmentWire.signingKeys(required(properties.healthReceiptKeys))
    fun health() = AssessmentWire.encryptionKeys(required(properties.healthEncryptionKeys), false)
    fun own() = AssessmentWire.encryptionKeys(required(properties.assessmentDecryptionKeys), true)
    fun storage() = own().first().toPublicJWK()

    private fun required(path: String): String {
        if (path.isBlank()) throw AssessmentKeyUnavailable()
        return path
    }
}
