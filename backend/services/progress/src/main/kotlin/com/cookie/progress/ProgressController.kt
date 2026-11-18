package com.cookie.progress

import com.cookie.progress.generated.api.ProgressApi
import com.cookie.progress.generated.model.ProgressDay
import com.cookie.progress.generated.model.ProgressError
import com.cookie.progress.generated.model.ProgressRangeResponse
import com.cookie.progress.generated.model.ProgressWeightResponse
import com.cookie.progress.generated.runtime.api.SystemApi
import com.cookie.progress.generated.runtime.model.ProbeStatus
import jakarta.servlet.http.HttpServletRequest
import org.springframework.dao.DataAccessException
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Controller
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate

@Controller
internal class ProgressController(
    private val reads: ProgressReads, private val tokens: ProgressTokens, private val request: HttpServletRequest,
    private val mapper: ObjectMapper,
) : ProgressApi {
    override fun getDailyProgress(date: LocalDate): ResponseEntity<ProgressDay> =
        ResponseEntity.ok(mapper.convertValue(reads.day(user(), date), ProgressDay::class.java))

    override fun getProgressRange(from: LocalDate, to: LocalDate): ResponseEntity<ProgressRangeResponse> =
        ResponseEntity.ok(mapper.convertValue(reads.range(user(), from, to), ProgressRangeResponse::class.java))

    override fun getWeightProgress(from: LocalDate, to: LocalDate): ResponseEntity<ProgressWeightResponse> =
        ResponseEntity.ok(mapper.convertValue(reads.weights(user(), from, to), ProgressWeightResponse::class.java))

    private fun user() = tokens.requireUser(request.getHeader("Authorization"))
}

@Controller
internal class ProgressSystem(private val db: JdbcTemplate, private val tokens: ProgressTokens, private val gate: ProgressEvidenceGate) : SystemApi {
    override fun getLiveness() = ResponseEntity.ok(ProbeStatus(ProbeStatus.Status.ok))

    override fun getReadiness(): ResponseEntity<ProbeStatus> {
        val ready = runCatching {
            db.queryForObject("SELECT EXISTS(SELECT 1 FROM progress_accounts,source_cuts,day_results WHERE false)", Boolean::class.java) == false &&
                tokens.ready() && gate.ready()
        }.getOrDefault(false)
        return ResponseEntity.status(if (ready) 200 else 503)
            .body(ProbeStatus(if (ready) ProbeStatus.Status.ok else ProbeStatus.Status.not_ready))
    }
}

@RestControllerAdvice
internal class ProgressErrors {
    @ExceptionHandler(ProgressFailure::class)
    fun failure(error: ProgressFailure): ResponseEntity<ProgressError> = response(error.status, error.code)

    @ExceptionHandler(MissingServletRequestParameterException::class, MethodArgumentTypeMismatchException::class)
    fun request(): ResponseEntity<ProgressError> = response(400, "INVALID_DATE_RANGE")

    @ExceptionHandler(DataAccessException::class)
    fun storage(): ResponseEntity<ProgressError> = response(503, "PROGRESS_UNAVAILABLE")

    private fun response(status: Int, code: String) = ResponseEntity.status(status).body(ProgressError(code, code))
}
