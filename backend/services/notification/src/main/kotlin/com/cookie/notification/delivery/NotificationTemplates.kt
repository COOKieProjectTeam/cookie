package com.cookie.notification.delivery

import com.cookie.notification.security.VerificationEmail
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class EmailContent(val subject: String, val text: String, val html: String) {
    override fun toString(): String = "EmailContent([redacted])"
}

data class PushContent(val title: String, val body: String)

class NotificationTemplates(private val verificationBase: URI) {
    init {
        require(verificationBase.scheme == "https" && !verificationBase.host.isNullOrBlank())
        require(verificationBase.userInfo == null && verificationBase.query == null && verificationBase.fragment == null)
    }
    fun verification(email: VerificationEmail): EmailContent {
        val link = verificationBase.toASCIIString() + "#token=" + URLEncoder.encode(email.token, StandardCharsets.UTF_8)
        val english = email.locale.startsWith("en", ignoreCase = true)
        val subject = if (english) "Confirm your COOKie email" else "Подтвердите email в COOKie"
        val instruction = if (english) "Confirm your email using this link:" else "Подтвердите email по ссылке:"
        val ignore = if (english) "If you did not request this email, you can ignore it." else "Если вы не запрашивали письмо, просто проигнорируйте его."
        return EmailContent(subject, "$instruction\n$link\n\n$ignore",
            "<html><body><p>${escape(instruction)}</p><p><a href=\"${escape(link)}\">${escape(subject)}</a></p><p>${escape(ignore)}</p></body></html>")
    }
    fun push(kind: String, locale: String): PushContent {
        val english = locale.startsWith("en", ignoreCase = true)
        val body = when (kind) {
            "FOOD" -> if (english) "Remember to log your meal." else "Не забудьте записать приём пищи."
            "WATER" -> if (english) "Time for some water." else "Пора выпить воды."
            "WEIGH" -> if (english) "Remember to log your weight." else "Не забудьте записать вес."
            "CALORIES_EXCEEDED" -> if (english) "Your daily calorie goal has been exceeded." else "Дневная цель по калориям превышена."
            "CALORIES_UNDER_TARGET" -> if (english) "Check your progress towards today's calorie goal." else "Проверьте прогресс по дневной цели калорий."
            else -> error("Unknown notification template")
        }
        return PushContent("COOKie", body)
    }
    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
