package com.cookie.identity.domain

open class IdentityException(message: String) : RuntimeException(message)

open class InvalidInputException(message: String) : IdentityException(message)

class InvalidLocaleTagException : InvalidInputException("Invalid locale tag")
