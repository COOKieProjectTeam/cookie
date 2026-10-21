package com.cookie.notification.transport

import com.cookie.notification.domain.InvalidNotificationInput
import com.cookie.notification.domain.NotificationAccountDeleted
import com.cookie.notification.domain.NotificationDeviceNotFound
import com.cookie.notification.generated.model.NotificationError
import com.cookie.notification.security.AuthenticationUnavailableException
import com.cookie.notification.security.InvalidAccessTokenException
import com.cookie.platform.web.RequestIdFilter
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.transaction.TransactionException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.UUID

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(InvalidNotificationInput::class, HttpMessageNotReadableException::class)
    fun invalid(request: HttpServletRequest): ResponseEntity<NotificationError> =
        response(400, "INVALID_REQUEST", "Invalid notification input", request)

    @ExceptionHandler(InvalidAccessTokenException::class)
    fun unauthorized(request: HttpServletRequest): ResponseEntity<NotificationError> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
            .cacheControl(CacheControl.noStore()).body(error("INVALID_ACCESS_TOKEN", "Invalid or expired access token", request))

    @ExceptionHandler(NotificationAccountDeleted::class)
    fun deleted(request: HttpServletRequest): ResponseEntity<NotificationError> =
        response(403, "ACCOUNT_DELETED", "Account deletion has been received", request)

    @ExceptionHandler(NotificationDeviceNotFound::class)
    fun notFound(request: HttpServletRequest): ResponseEntity<NotificationError> =
        response(404, "NOT_FOUND", "Device not found", request)

    @ExceptionHandler(AuthenticationUnavailableException::class, DataAccessException::class, TransactionException::class)
    fun unavailable(request: HttpServletRequest): ResponseEntity<NotificationError> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "1")
            .cacheControl(CacheControl.noStore()).body(error("SERVICE_UNAVAILABLE", "Notification dependency unavailable", request))

    @ExceptionHandler(Exception::class)
    fun unexpected(exception: Exception, request: HttpServletRequest): ResponseEntity<NotificationError> {
        if (exception is ErrorResponse) {
            return ResponseEntity.status(exception.statusCode).headers(exception.headers)
                .cacheControl(CacheControl.noStore()).body(error("HTTP_ERROR", "Request cannot be processed", request))
        }
        // Exception messages/causes can contain JDBC parameters, tokens or provider bodies.
        logger.error("Notification request failed; errorType={}", exception.javaClass.simpleName)
        return response(500, "INTERNAL_ERROR", "Internal server error", request)
    }

    private fun response(status: Int, code: String, message: String, request: HttpServletRequest) =
        ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(error(code, message, request))

    private fun error(code: String, message: String, request: HttpServletRequest) = NotificationError(
        code, message,
        (request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE) as? String)?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        },
    )

    private companion object { val logger = LoggerFactory.getLogger(ApiExceptionHandler::class.java) }
}
