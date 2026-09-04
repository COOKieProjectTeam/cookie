package com.cookie.identity.transport

import com.cookie.identity.application.IdentityUnavailableException
import com.cookie.identity.application.InvalidActionTokenException
import com.cookie.identity.application.InvalidCredentialsException
import com.cookie.identity.application.InvalidTokenException
import com.cookie.identity.application.RateLimitExceededException
import com.cookie.identity.application.RegistrationAttemptConflictException
import com.cookie.identity.domain.EmailDomainNotAllowedException
import com.cookie.identity.domain.InvalidDeviceIdException
import com.cookie.identity.domain.InvalidEmailException
import com.cookie.identity.domain.InvalidEmailReason
import com.cookie.identity.domain.InvalidInputException
import com.cookie.identity.domain.InvalidLocaleTagException
import com.cookie.identity.domain.InvalidPasswordException
import com.cookie.identity.domain.InvalidPasswordReason
import com.cookie.identity.generated.model.Error
import com.cookie.platform.web.RequestIdFilter
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.RecoverableDataAccessException
import org.springframework.dao.TransientDataAccessException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.web.ErrorResponse as SpringErrorResponse
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.ServletRequestBindingException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.support.MissingServletRequestPartException
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.TransactionTimedOutException
import java.util.UUID

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(InvalidEmailException::class)
    fun invalidEmail(exception: InvalidEmailException, request: HttpServletRequest): ResponseEntity<Error> =
        validationResponse(exception.reason.toPublicError(), request)

    @ExceptionHandler(EmailDomainNotAllowedException::class)
    fun unsupportedEmailDomain(request: HttpServletRequest): ResponseEntity<Error> =
        validationResponse(
            PublicValidationError("EMAIL_DOMAIN_NOT_SUPPORTED", "Email domain is not supported"),
            request,
        )

    @ExceptionHandler(InvalidPasswordException::class)
    fun invalidPassword(exception: InvalidPasswordException, request: HttpServletRequest): ResponseEntity<Error> =
        validationResponse(exception.reason.toPublicError(), request)

    @ExceptionHandler(InvalidLocaleTagException::class)
    fun invalidLocale(request: HttpServletRequest): ResponseEntity<Error> =
        validationResponse(
            PublicValidationError("INVALID_LOCALE", "Locale must be a well-formed BCP 47 language tag"),
            request,
        )

    @ExceptionHandler(InvalidDeviceIdException::class)
    fun invalidDeviceId(request: HttpServletRequest): ResponseEntity<Error> =
        validationResponse(
            PublicValidationError("INVALID_DEVICE_ID", "Device id is invalid"),
            request,
        )

    @ExceptionHandler(InvalidInputException::class)
    fun badRequest(request: HttpServletRequest): ResponseEntity<Error> =
        response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request", request)

    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        HttpMessageNotReadableException::class,
        MissingRequestHeaderException::class,
        MissingServletRequestParameterException::class,
        MissingServletRequestPartException::class,
        ServletRequestBindingException::class,
        MethodArgumentTypeMismatchException::class,
        HandlerMethodValidationException::class,
    )
    fun beanValidation(request: HttpServletRequest): ResponseEntity<Error> =
        response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request", request)

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun methodNotAllowed(
        exception: HttpRequestMethodNotSupportedException,
        request: HttpServletRequest,
    ): ResponseEntity<Error> = frameworkResponse(
        exception,
        "METHOD_NOT_ALLOWED",
        "HTTP method is not allowed for this resource",
        request,
    )

    @ExceptionHandler(HttpMediaTypeNotAcceptableException::class)
    fun mediaTypeNotAcceptable(
        exception: HttpMediaTypeNotAcceptableException,
        request: HttpServletRequest,
    ): ResponseEntity<Error> = frameworkResponse(
        exception,
        "NOT_ACCEPTABLE",
        "Requested response media type is not supported",
        request,
    )

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun mediaTypeNotSupported(
        exception: HttpMediaTypeNotSupportedException,
        request: HttpServletRequest,
    ): ResponseEntity<Error> = frameworkResponse(
        exception,
        "UNSUPPORTED_MEDIA_TYPE",
        "Request media type is not supported",
        request,
    )

    @ExceptionHandler(NoHandlerFoundException::class, NoResourceFoundException::class)
    fun notFound(exception: Exception, request: HttpServletRequest): ResponseEntity<Error> = frameworkResponse(
        exception as SpringErrorResponse,
        "NOT_FOUND",
        "Resource not found",
        request,
    )

    @ExceptionHandler(InvalidActionTokenException::class)
    fun invalidActionToken(exception: InvalidActionTokenException, request: HttpServletRequest): ResponseEntity<Error> =
        response(HttpStatus.BAD_REQUEST, "INVALID_VERIFICATION_TOKEN", exception.message.orEmpty(), request)

    @ExceptionHandler(InvalidCredentialsException::class)
    fun invalidCredentials(request: HttpServletRequest): ResponseEntity<Error> =
        response(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid credentials", request)

    @ExceptionHandler(InvalidTokenException::class)
    fun invalidRefreshToken(request: HttpServletRequest): ResponseEntity<Error> =
        response(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Invalid or expired refresh token", request)

    @ExceptionHandler(RateLimitExceededException::class)
    fun tooManyRequests(
        exception: RateLimitExceededException,
        request: HttpServletRequest,
    ): ResponseEntity<Error> = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, exception.retryAfterSeconds.toString())
        .body(error("RATE_LIMITED", "Too many requests", request))

    @ExceptionHandler(RegistrationAttemptConflictException::class)
    fun registrationAttemptConflict(request: HttpServletRequest): ResponseEntity<Error> =
        response(
            HttpStatus.CONFLICT,
            "REGISTRATION_ATTEMPT_CONFLICT",
            "Registration attempt id or proof was already used for a different request",
            request,
        )

    @ExceptionHandler(IdentityUnavailableException::class)
    fun unavailable(exception: Exception, request: HttpServletRequest): ResponseEntity<Error> {
        logger.warn("Identity dependency unavailable", exception)
        return unavailableResponse(request)
    }

    @ExceptionHandler(
        TransientDataAccessException::class,
        RecoverableDataAccessException::class,
        DataAccessResourceFailureException::class,
        CannotCreateTransactionException::class,
        TransactionTimedOutException::class,
    )
    fun databaseUnavailable(exception: Exception, request: HttpServletRequest): ResponseEntity<Error> {
        logger.warn("Identity database temporarily unavailable", exception)
        return unavailableResponse(request)
    }

    @ExceptionHandler(UncategorizedSQLException::class)
    fun uncategorizedDatabaseFailure(
        exception: UncategorizedSQLException,
        request: HttpServletRequest,
    ): ResponseEntity<Error> = if (exception.sqlException?.sqlState in RETRYABLE_UNCATEGORIZED_SQL_STATES) {
        databaseUnavailable(exception, request)
    } else {
        unexpected(exception, request)
    }

    @ExceptionHandler(Exception::class)
    fun unexpected(exception: Exception, request: HttpServletRequest): ResponseEntity<Error> {
        logger.error("Unexpected Identity request failure", exception)
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error", request)
    }

    private fun response(
        status: HttpStatus,
        code: String,
        message: String,
        request: HttpServletRequest,
    ): ResponseEntity<Error> = ResponseEntity.status(status).body(error(code, message, request))

    private fun unavailableResponse(request: HttpServletRequest): ResponseEntity<Error> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, "1")
            .body(error("SERVICE_UNAVAILABLE", "Service is temporarily unavailable", request))

    private fun validationResponse(
        validationError: PublicValidationError,
        request: HttpServletRequest,
    ): ResponseEntity<Error> = response(
        HttpStatus.BAD_REQUEST,
        validationError.code,
        validationError.message,
        request,
    )

    private fun frameworkResponse(
        exception: SpringErrorResponse,
        code: String,
        message: String,
        request: HttpServletRequest,
    ): ResponseEntity<Error> = ResponseEntity.status(exception.statusCode)
        .headers(exception.headers)
        .body(error(code, message, request))

    private fun InvalidEmailReason.toPublicError(): PublicValidationError = when (this) {
        InvalidEmailReason.ADDRESS_TOO_LONG,
        InvalidEmailReason.LOCAL_PART_TOO_LONG,
        InvalidEmailReason.DOMAIN_TOO_LONG,
        -> PublicValidationError("EMAIL_TOO_LONG", "Email address is too long")

        InvalidEmailReason.LOCAL_PART_HAS_UNSUPPORTED_CHARACTERS ->
            PublicValidationError("EMAIL_LOCAL_PART_UNSUPPORTED", "Email local part contains unsupported characters")

        InvalidEmailReason.INVALID_SEPARATOR,
        InvalidEmailReason.LOCAL_PART_EMPTY,
        InvalidEmailReason.LOCAL_PART_HAS_INVALID_DOTS,
        InvalidEmailReason.DOMAIN_EMPTY,
        InvalidEmailReason.DOMAIN_INVALID,
        -> PublicValidationError("INVALID_EMAIL_FORMAT", "Email address is invalid")
    }

    private fun InvalidPasswordReason.toPublicError(): PublicValidationError = when (this) {
        InvalidPasswordReason.TOO_SHORT -> PublicValidationError("PASSWORD_TOO_SHORT", "Password is too short")
        InvalidPasswordReason.TOO_LONG -> PublicValidationError("PASSWORD_TOO_LONG", "Password is too long")
        InvalidPasswordReason.MALFORMED_UNICODE,
        InvalidPasswordReason.CONTROL_CHARACTER,
        -> PublicValidationError("PASSWORD_INVALID_CHARACTERS", "Password contains unsupported characters")
    }

    private fun error(code: String, message: String, request: HttpServletRequest) = Error(
        code = code,
        message = message,
        requestId = (request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE) as? String)?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        },
    )

    companion object {
        // PostgreSQL lock_not_available and query_canceled. Spring's generic
        // SQL-state translator does not classify every server version of these
        // failures as TransientDataAccessException.
        private val RETRYABLE_UNCATEGORIZED_SQL_STATES = setOf("55P03", "57014")
        private val logger = LoggerFactory.getLogger(ApiExceptionHandler::class.java)
    }
}

private data class PublicValidationError(val code: String, val message: String)
