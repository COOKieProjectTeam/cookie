package com.cookie.nutrition

import com.cookie.nutrition.generated.runtime.api.SystemApi
import com.cookie.nutrition.generated.runtime.model.ProbeStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import javax.sql.DataSource

@Controller
class RuntimeController(private val database: DataSource,private val tokens: AccessTokens): SystemApi {
    override fun getLiveness() = ResponseEntity.ok(ProbeStatus(ProbeStatus.Status.ok))
    override fun getReadiness(): ResponseEntity<ProbeStatus> {
        val ready=runCatching { database.connection.use { it.isValid(1) } && tokens.keys().isNotEmpty() }.getOrDefault(false)
        return ResponseEntity.status(if(ready) 200 else 503).body(ProbeStatus(if(ready) ProbeStatus.Status.ok else ProbeStatus.Status.not_ready))
    }
}
@RestControllerAdvice
class Errors {
    @ExceptionHandler(NutritionFailure::class)
    fun failure(e: NutritionFailure) = ResponseEntity.status(e.status).body(mapOf("code" to e.code,"message" to e.code))
    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException::class,org.springframework.web.bind.MissingServletRequestParameterException::class,org.springframework.http.converter.HttpMessageNotReadableException::class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException::class,java.time.DateTimeException::class)
    fun input() = ResponseEntity.badRequest().body(mapOf("code" to "INVALID_INPUT","message" to "Invalid request"))
    @ExceptionHandler(org.springframework.dao.DataAccessException::class,org.springframework.transaction.TransactionException::class)
    fun database() = ResponseEntity.status(503).body(mapOf("code" to "TEMPORARILY_UNAVAILABLE","message" to "Retry later"))
}
