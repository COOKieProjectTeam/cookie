package com.cookie.health.transport

import com.cookie.health.HealthFailure
import com.cookie.health.domain.InvalidAssessment
import org.springframework.dao.DataAccessException
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.transaction.TransactionException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class HealthErrors {
    @ExceptionHandler(HealthFailure::class)
    fun failure(error: HealthFailure) = response(error.status, error.code, error.field, error.index, error.expectedSequence)

    @ExceptionHandler(InvalidAssessment::class)
    fun assessment(error: InvalidAssessment) = response(400, "INVALID_ASSESSMENT", error.field)

    @ExceptionHandler(HttpMessageNotReadableException::class, IllegalArgumentException::class)
    fun invalid() = response(400, "INVALID_REQUEST")

    @ExceptionHandler(DataAccessException::class, TransactionException::class)
    fun unavailable() = response(503, "SERVICE_UNAVAILABLE")

    @ExceptionHandler(Exception::class)
    fun unexpected(error: Exception) = if (error is ErrorResponse) response(error.statusCode.value(), "HTTP_ERROR")
        else response(500, "INTERNAL_ERROR")

    private fun response(status: Int, code: String, field: String? = null,
                         index: Int? = null, expected: Long? = null): ResponseEntity<Map<String, Any>> {
        val builder = ResponseEntity.status(status).cacheControl(CacheControl.noStore())
        if (status == 401) builder.header("WWW-Authenticate", "Bearer")
        if (status == 503) builder.header("Retry-After", "1")
        val payload = buildMap<String, Any> {
            put("code", code)
            if (field != null) put("field", field)
            if (index != null) put("index", index)
            if (expected != null) put("expectedSeq", expected)
        }
        return builder.body(payload)
    }
}
