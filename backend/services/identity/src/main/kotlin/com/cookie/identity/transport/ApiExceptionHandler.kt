package com.cookie.identity.transport

import com.cookie.identity.domain.IdentityUnavailableException
import com.cookie.identity.domain.InvalidActionTokenException
import com.cookie.identity.domain.InvalidCredentialsException
import com.cookie.identity.domain.InvalidInputException
import com.cookie.identity.domain.InvalidTokenException
import com.cookie.identity.domain.RateLimitExceededException
import com.cookie.identity.generated.model.Error
import com.cookie.platform.web.RequestIdFilter
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.UUID

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(InvalidInputException::class)
    fun badRequest(exception: InvalidInputException, request: HttpServletRequest): ResponseEntity<Error> =
        response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.message.orEmpty(), request)

    @ExceptionHandler(MethodArgumentNotValidException::class, HttpMessageNotReadableException::class)
    fun beanValidation(request: HttpServletRequest): ResponseEntity<Error> =
        response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request", request)

    @ExceptionHandler(InvalidActionTokenException::class)
    fun invalidActionToken(exception: InvalidActionTokenException, request: HttpServletRequest): ResponseEntity<Error> =
        response(HttpStatus.BAD_REQUEST, "INVALID_VERIFICATION_TOKEN", exception.message.orEmpty(), request)

    @ExceptionHandler(InvalidCredentialsException::class, InvalidTokenException::class)
    fun unauthorized(exception: Exception, request: HttpServletRequest): ResponseEntity<Error> =
        response(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", exception.message ?: "Unauthorized", request)

    @ExceptionHandler(RateLimitExceededException::class)
    fun tooManyRequests(
        exception: RateLimitExceededException,
        request: HttpServletRequest,
    ): ResponseEntity<Error> = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, exception.retryAfterSeconds.toString())
        .body(error("RATE_LIMITED", "Too many requests", request))

    @ExceptionHandler(IdentityUnavailableException::class, DataAccessException::class)
    fun unavailable(exception: Exception, request: HttpServletRequest): ResponseEntity<Error> {
        logger.warn("Identity dependency unavailable", exception)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, "1")
            .body(error("SERVICE_UNAVAILABLE", "Service is temporarily unavailable", request))
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

    private fun error(code: String, message: String, request: HttpServletRequest) = Error(
        code = code,
        message = message,
        requestId = (request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE) as? String)?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        },
    )

    companion object {
        private val logger = LoggerFactory.getLogger(ApiExceptionHandler::class.java)
    }
}
