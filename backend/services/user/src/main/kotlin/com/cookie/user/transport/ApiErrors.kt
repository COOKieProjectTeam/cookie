package com.cookie.user.transport

import com.cookie.user.domain.UserFailure
import com.cookie.user.generated.model.UserError
import org.springframework.dao.DataAccessException
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.transaction.TransactionException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(UserFailure::class)
    fun domain(e:UserFailure):ResponseEntity<UserError> = response(e.status,e.code)
    @ExceptionHandler(HttpMessageNotReadableException::class,IllegalArgumentException::class)
    fun invalid()=response(400,"INVALID_REQUEST")
    @ExceptionHandler(DataAccessException::class,TransactionException::class)
    fun unavailable()=response(503,"SERVICE_UNAVAILABLE")
    @ExceptionHandler(Exception::class)
    fun unexpected(e:Exception):ResponseEntity<UserError> {
        if(e is ErrorResponse) return response(e.statusCode.value(),"HTTP_ERROR")
        return response(500,"INTERNAL_ERROR")
    }
    private fun response(status:Int,code:String):ResponseEntity<UserError> {
        val builder=ResponseEntity.status(status).cacheControl(CacheControl.noStore())
        if(status==401) builder.header("WWW-Authenticate","Bearer")
        if(status==503 || code=="PROFILE_NOT_READY") builder.header("Retry-After","1")
        return builder.body(UserError(code,code))
    }
}
