package com.cookie.health

class HealthFailure(val code: String, val status: Int, val field: String? = null,
                    val index: Int? = null, val expectedSequence: Long? = null) : RuntimeException(code)
