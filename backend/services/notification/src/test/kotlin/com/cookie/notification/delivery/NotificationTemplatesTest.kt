package com.cookie.notification.delivery

import com.cookie.notification.security.VerificationEmail
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant

class NotificationTemplatesTest {
    @Test fun `verification uses configured HTTPS fragment and locale without leaking through toString`() {
        val templates = NotificationTemplates(URI.create("https://links.example.test/verify"))
        val email = VerificationEmail("person@example.test", "secret-token", "en-GB", Instant.now())
        val content = templates.verification(email)
        assertThat(content.text).contains("https://links.example.test/verify#token=secret-token")
        assertThat(content.text).doesNotContain("registrationProof", "person@example.test")
        assertThat(content.subject).isEqualTo("Confirm your COOKie email")
        assertThat(content.toString()).doesNotContain("secret-token")
        assertThat(templates.verification(VerificationEmail(email.recipient, email.token, "ru", email.expiresAt)).subject)
            .contains("Подтвердите")
    }
    @Test fun `link configuration rejects credentials fragments and plain HTTP`() {
        listOf("http://links.example.test", "https://u:p@links.example.test", "https://links.example.test/#x", "https://links.example.test/?token=x")
            .forEach { value -> assertThatThrownBy { NotificationTemplates(URI.create(value)) }.isInstanceOf(IllegalArgumentException::class.java) }
    }
}
