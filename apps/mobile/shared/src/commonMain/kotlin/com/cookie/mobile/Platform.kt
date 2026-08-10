package com.cookie.mobile

expect fun platformName(): String

fun greeting(): String = "COOKie on ${platformName()}"
