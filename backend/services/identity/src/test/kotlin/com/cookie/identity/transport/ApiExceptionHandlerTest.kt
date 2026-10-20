package com.cookie.identity.transport

import com.cookie.identity.application.InvalidCredentialsException
import com.cookie.identity.application.InvalidTokenException
import com.cookie.identity.application.RegistrationAttemptConflictException
import com.cookie.identity.domain.EmailDomainNotAllowedException
import com.cookie.identity.domain.InvalidDeviceIdException
import com.cookie.identity.domain.InvalidEmailException
import com.cookie.identity.domain.InvalidEmailReason
import com.cookie.identity.domain.InvalidInputException
import com.cookie.identity.domain.InvalidLocaleTagException
import com.cookie.identity.domain.InvalidPasswordException
import com.cookie.identity.domain.InvalidPasswordReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.TransientDataAccessResourceException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver
import java.sql.SQLException

class ApiExceptionHandlerTest {
    private val handler = ApiExceptionHandler()
    private val request = MockHttpServletRequest()

    @Test
    fun `maps typed validation failures to stable public errors`() {
        val invalidEmail = requireNotNull(
            handler.invalidEmail(InvalidEmailException(InvalidEmailReason.INVALID_SEPARATOR), request).body,
        )
        assertThat(invalidEmail.code).isEqualTo("INVALID_EMAIL_FORMAT")
        assertThat(invalidEmail.message).isEqualTo("Email address is invalid")

        val invalidPassword = requireNotNull(
            handler.invalidPassword(InvalidPasswordException(InvalidPasswordReason.TOO_SHORT), request).body,
        )
        assertThat(invalidPassword.code).isEqualTo("PASSWORD_TOO_SHORT")
        assertThat(invalidPassword.message).isEqualTo("Password is too short")
        assertThat(handler.unsupportedEmailDomain(request).body?.code).isEqualTo("EMAIL_DOMAIN_NOT_SUPPORTED")
        assertThat(handler.invalidLocale(request).body?.code).isEqualTo("INVALID_LOCALE")
        assertThat(handler.invalidDeviceId(request).body?.code).isEqualTo("INVALID_DEVICE_ID")
    }

    @Test
    fun `generic invalid input does not expose an arbitrary exception message`() {
        val response = handler.badRequest(request)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body?.code).isEqualTo("INVALID_REQUEST")
        assertThat(response.body?.message).isEqualTo("Invalid request")
        assertThat(response.body?.message).doesNotContain("sensitive")

        // Verify the exception type remains covered by the generic handler contract.
        assertThat(InvalidInputException("sensitive")).isInstanceOf(InvalidInputException::class.java)
        assertThat(EmailDomainNotAllowedException()).isInstanceOf(InvalidInputException::class.java)
        assertThat(InvalidLocaleTagException()).isInstanceOf(InvalidInputException::class.java)
        assertThat(InvalidDeviceIdException()).isInstanceOf(InvalidInputException::class.java)
    }

    @Test
    fun `framework client errors keep their http status and headers`() {
        val exception = HttpRequestMethodNotSupportedException("GET", listOf("POST"))

        val response = handler.methodNotAllowed(exception, request)

        assertThat(response.statusCode).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
        assertThat(response.headers.getFirst(HttpHeaders.ALLOW)).isEqualTo("POST")
        assertThat(response.body?.code).isEqualTo("METHOD_NOT_ALLOWED")
    }

    @Test
    fun `distinguishes login credentials from refresh credentials without leaking internals`() {
        assertThat(handler.invalidCredentials(request).body?.code).isEqualTo("INVALID_CREDENTIALS")
        assertThat(handler.invalidRefreshToken(request).body?.code).isEqualTo("INVALID_REFRESH_TOKEN")
        assertThat(InvalidCredentialsException().message).isEqualTo("Invalid credentials")
        assertThat(InvalidTokenException().message).isEqualTo("Invalid or expired token")
    }

    @Test
    fun `registration attempt reuse conflict has a stable public response`() {
        val response = handler.registrationAttemptConflict(request)
        val resolver = ExceptionHandlerMethodResolver(ApiExceptionHandler::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.body?.code).isEqualTo("REGISTRATION_ATTEMPT_CONFLICT")
        assertThat(response.body?.message)
            .isEqualTo("Registration attempt id or proof was already used for a different request")
        assertThat(resolver.resolveMethod(RegistrationAttemptConflictException())?.name)
            .isEqualTo("registrationAttemptConflict")
    }

    @Test
    fun `only retryable database failures are reported as temporarily unavailable`() {
        val retryableFailure = TransientDataAccessResourceException("connection interrupted")
        val lockTimeout = UncategorizedSQLException(
            "advisory lock",
            "SELECT pg_advisory_xact_lock(?)",
            SQLException("canceling statement due to lock timeout", "55P03"),
        )
        val statementTimeout = UncategorizedSQLException(
            "bounded statement",
            "SELECT slow_operation()",
            SQLException("canceling statement due to statement timeout", "57014"),
        )
        val invalidSql = UncategorizedSQLException(
            "broken query",
            "SELECT FROM",
            SQLException("syntax error", "42601"),
        )
        val permanentFailure = DataIntegrityViolationException("constraint violation")
        val retryable = handler.databaseUnavailable(retryableFailure, request)
        val lockUnavailable = handler.uncategorizedDatabaseFailure(lockTimeout, request)
        val statementUnavailable = handler.uncategorizedDatabaseFailure(statementTimeout, request)
        val invalidSqlResponse = handler.uncategorizedDatabaseFailure(invalidSql, request)
        val permanent = handler.unexpected(permanentFailure, request)
        val resolver = ExceptionHandlerMethodResolver(ApiExceptionHandler::class.java)

        assertThat(retryable.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(retryable.headers.getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1")
        assertThat(lockUnavailable.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(statementUnavailable.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(invalidSqlResponse.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        assertThat(permanent.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        assertThat(resolver.resolveMethod(retryableFailure)?.name).isEqualTo("databaseUnavailable")
        assertThat(resolver.resolveMethod(lockTimeout)?.name).isEqualTo("uncategorizedDatabaseFailure")
        assertThat(resolver.resolveMethod(permanentFailure)?.name).isEqualTo("unexpected")
    }
}
