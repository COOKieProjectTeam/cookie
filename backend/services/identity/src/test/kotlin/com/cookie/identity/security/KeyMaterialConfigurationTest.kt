package com.cookie.identity.security

import com.cookie.identity.config.IdentityProperties
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.time.Clock

class KeyMaterialConfigurationTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `production key material fails closed when mounted keys are absent`() {
        assertThatThrownBy {
            KeyMaterialConfiguration().productionKeyMaterial(IdentityProperties())
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("COOKIE_IDENTITY_JWT_PRIVATE_KEY_PATH")
    }

    @Test
    fun `valid key material performs startup self tests and redacts private material`() {
        val signing = signingKey("active")
        val material = KeyMaterial(signing, listOf(signingKey("retiring").toPublicJWK()), notificationPublicKey())

        assertThat(material.signingKey.keyID).isEqualTo("active")
        assertThat(material.toString()).contains("active", "retiring", "[redacted]")
        assertThat(material.toString()).doesNotContain(signing.d.toString())
    }

    @Test
    fun `rejects public active key private retiring key and duplicate signing kid`() {
        assertThatThrownBy {
            KeyMaterial(signingKey("active").toPublicJWK(), emptyList(), notificationPublicKey())
        }.hasMessageContaining("private key material")

        assertThatThrownBy {
            KeyMaterial(signingKey("active"), listOf(signingKey("retiring")), notificationPublicKey())
        }.hasMessageContaining("public key material only")

        assertThatThrownBy {
            KeyMaterial(signingKey("duplicate"), listOf(signingKey("duplicate").toPublicJWK()), notificationPublicKey())
        }.hasMessageContaining("kid values must be unique")
    }

    @Test
    fun `rejects key metadata that does not constrain intended cryptographic operations`() {
        val signingWithoutUse = ECKeyGenerator(Curve.P_256)
            .algorithm(JWSAlgorithm.ES256)
            .keyID("active")
            .generate()
        assertThatThrownBy {
            KeyMaterial(signingWithoutUse, emptyList(), notificationPublicKey())
        }.hasMessageContaining("use=sig")

        val notificationWithoutAlgorithm = RSAKeyGenerator(2048)
            .keyUse(KeyUse.ENCRYPTION)
            .keyID("notification")
            .generate()
            .toPublicJWK()
        assertThatThrownBy {
            KeyMaterial(signingKey("active"), emptyList(), notificationWithoutAlgorithm)
        }.hasMessageContaining("alg=RSA-OAEP-256")
    }

    @Test
    fun `rejects unsupported signing curve and weak notification rsa key`() {
        val wrongCurve = ECKeyGenerator(Curve.P_384)
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.ES384)
            .keyID("active")
            .generate()
        assertThatThrownBy {
            KeyMaterial(wrongCurve, emptyList(), notificationPublicKey())
        }.hasMessageContaining("P-256")

        val weakRsaPublic = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(1024) }
            .generateKeyPair()
            .public as RSAPublicKey
        val weakNotification = RSAKey.Builder(weakRsaPublic)
            .keyUse(KeyUse.ENCRYPTION)
            .algorithm(JWEAlgorithm.RSA_OAEP_256)
            .keyID("notification")
            .build()
        assertThatThrownBy {
            KeyMaterial(signingKey("active"), emptyList(), weakNotification)
        }.hasMessageContaining("at least 2048 bits")
    }

    @Test
    fun `rejects private notification material`() {
        val privateNotification = RSAKeyGenerator(2048)
            .keyUse(KeyUse.ENCRYPTION)
            .algorithm(JWEAlgorithm.RSA_OAEP_256)
            .keyID("notification")
            .generate()

        assertThatThrownBy {
            KeyMaterial(signingKey("active"), emptyList(), privateNotification)
        }.hasMessageContaining("public key material only")
    }

    @Test
    fun `development keys carry strict use and algorithm metadata`() {
        val material = KeyMaterialConfiguration().developmentKeyMaterial(
            IdentityProperties(),
            UuidV7IdGenerator(Clock.systemUTC(), SecureRandom()),
        )

        assertThat(material.signingKey.keyUse).isEqualTo(KeyUse.SIGNATURE)
        assertThat(material.signingKey.algorithm).isEqualTo(JWSAlgorithm.ES256)
        assertThat(material.notificationEncryptionKey.keyUse).isEqualTo(KeyUse.ENCRYPTION)
        assertThat(material.notificationEncryptionKey.algorithm).isEqualTo(JWEAlgorithm.RSA_OAEP_256)
        assertThat(material.notificationEncryptionKey.isPrivate).isFalse()
    }

    @Test
    fun `development notification private key survives restart instead of being overwritten`() {
        val privateKeyPath = tempDirectory.resolve("notification-private.jwk")
        val properties = IdentityProperties(devNotificationPrivateKeyOutputPath = privateKeyPath.toString())

        val first = KeyMaterialConfiguration().developmentKeyMaterial(
            properties,
            UuidV7IdGenerator(Clock.systemUTC(), SecureRandom()),
        )
        val persisted = RSAKey.parse(Files.readString(privateKeyPath))
        val second = KeyMaterialConfiguration().developmentKeyMaterial(
            properties,
            UuidV7IdGenerator(Clock.systemUTC(), SecureRandom()),
        )

        assertThat(persisted.isPrivate).isTrue()
        assertThat(persisted.keyID).isEqualTo(first.notificationEncryptionKey.keyID)
        assertThat(second.notificationEncryptionKey).isEqualTo(first.notificationEncryptionKey)
    }

    @Test
    fun `development startup rejects an invalid persisted notification key without replacing it`() {
        val privateKeyPath = tempDirectory.resolve("invalid-notification-private.jwk")
        val invalid = RSAKeyGenerator(2048)
            .keyUse(KeyUse.ENCRYPTION)
            .keyID("missing-alg")
            .generate()
            .toJSONString()
        Files.writeString(privateKeyPath, invalid)

        assertThatThrownBy {
            KeyMaterialConfiguration().developmentKeyMaterial(
                IdentityProperties(devNotificationPrivateKeyOutputPath = privateKeyPath.toString()),
                UuidV7IdGenerator(Clock.systemUTC(), SecureRandom()),
            )
        }.hasMessageContaining("alg=RSA-OAEP-256")
        assertThat(Files.readString(privateKeyPath)).isEqualTo(invalid)
    }

    private fun signingKey(kid: String): ECKey = ECKeyGenerator(Curve.P_256)
        .keyUse(KeyUse.SIGNATURE)
        .algorithm(JWSAlgorithm.ES256)
        .keyID(kid)
        .generate()

    private fun notificationPublicKey(): RSAKey = RSAKeyGenerator(2048)
        .keyUse(KeyUse.ENCRYPTION)
        .algorithm(JWEAlgorithm.RSA_OAEP_256)
        .keyID("notification")
        .generate()
        .toPublicJWK()
}
