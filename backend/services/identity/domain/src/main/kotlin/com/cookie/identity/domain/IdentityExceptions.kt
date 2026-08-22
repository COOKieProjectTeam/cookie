package com.cookie.identity.domain

open class IdentityException(message: String) : RuntimeException(message)

class InvalidInputException(message: String) : IdentityException(message)
