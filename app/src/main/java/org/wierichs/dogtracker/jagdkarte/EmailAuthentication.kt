package org.wierichs.dogtracker.jagdkarte

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import org.json.JSONObject

internal const val EMAIL_AUTH_REDIRECT = "https://wierichs.github.io/Jagdkarte/"
internal const val EMAIL_AUTH_REQUEST_FILE = "jagdkarte_email_auth_request"
private const val EMAIL_AUTH_REQUEST_LIFETIME_MILLIS = 3_600_000L
private val CODE_VERIFIER_PATTERN = Regex("[A-Za-z0-9._~-]{43,128}")
private val AUTH_CODE_PATTERN = Regex("[A-Za-z0-9_-]{1,512}")
private val TOKEN_HASH_PATTERN = Regex("[A-Za-z0-9_-]{32,256}")
private val AUTH_PARAMETER_NAMES = setOf("code", "error", "error_code", "error_description",
    "access_token", "refresh_token", "token_hash", "type")

internal sealed interface EmailAuthLink {
    data class Code(val value: String) : EmailAuthLink {
        override fun toString(): String = "EmailAuthLink.Code(<redacted>)"
    }
    data class TokenHash(val value: String) : EmailAuthLink {
        override fun toString(): String = "EmailAuthLink.TokenHash(<redacted>)"
    }
    data class Rejected(val message: String) : EmailAuthLink
}

private fun emailAuthParameters(part: String?): List<Pair<String, String>> =
    part?.split('&')?.map { entry ->
        val pieces = entry.split('=', limit = 2)
        URLDecoder.decode(pieces[0], Charsets.UTF_8.name()) to
            URLDecoder.decode(pieces.getOrElse(1) { "" }, Charsets.UTF_8.name())
    } ?: emptyList()

internal fun emailAuthLinkFromUrl(url: String?): EmailAuthLink? {
    val uri = jagdkarteLinkUri(url) ?: return null
    if (uri.rawPath != "/Jagdkarte/") return null
    val query: List<Pair<String, String>>
    val parameters = try {
        query = emailAuthParameters(uri.rawQuery)
        query + emailAuthParameters(uri.rawFragment)
    } catch (_: IllegalArgumentException) {
        return EmailAuthLink.Rejected("Der Anmelde-Link ist unvollstaendig. Bitte eine neue Anmelde-Mail anfordern.")
    }
    val authParameters = parameters.filter { it.first in AUTH_PARAMETER_NAMES }
    if (authParameters.none { it.first != "type" }) return null
    if (authParameters.groupBy { it.first }.any { it.value.size != 1 } ||
        guestLinkTokenFromFragment(uri.rawFragment) != null) {
        return EmailAuthLink.Rejected("Der Anmelde-Link ist ungueltig. Bitte eine neue Anmelde-Mail anfordern.")
    }
    val values = authParameters.toMap()
    if ("error" in values || "error_code" in values) {
        val expired = values["error_code"] in setOf("otp_expired", "flow_state_expired")
        return EmailAuthLink.Rejected(if (expired)
            "Der Anmelde-Link ist abgelaufen oder schon verwendet. Bitte eine neue Anmelde-Mail anfordern."
        else "Die Anmeldung wurde nicht bestaetigt. Bitte eine neue Anmelde-Mail anfordern.")
    }
    if (values.keys.any { it in setOf("access_token", "refresh_token") }) {
        return EmailAuthLink.Rejected("Dieser Link stammt nicht aus der neuen Android-Anmeldung. " +
            "Bitte auf diesem Handy eine neue Anmelde-Mail anfordern oder den Zahlencode verwenden.")
    }
    if ("token_hash" in values) {
        val hash = query.singleOrNull { it.first == "token_hash" }?.second
        return if (hash != null && TOKEN_HASH_PATTERN.matches(hash) && values["type"] == "email" &&
            "code" !in values) EmailAuthLink.TokenHash(hash)
        else EmailAuthLink.Rejected("Der Anmelde-Link ist ungueltig. Bitte eine neue Anmelde-Mail anfordern.")
    }
    val code = query.singleOrNull { it.first == "code" }?.second
    return if (code != null && AUTH_CODE_PATTERN.matches(code)) EmailAuthLink.Code(code)
    else EmailAuthLink.Rejected("Der Anmelde-Link ist unvollstaendig. Bitte eine neue Anmelde-Mail anfordern.")
}

internal fun pkceCodeChallenge(verifier: String): String {
    require(CODE_VERIFIER_PATTERN.matches(verifier)) { "Die lokale Anmeldeanforderung ist ungueltig." }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}

internal data class PendingEmailSignIn(
    val email: String,
    val codeVerifier: String,
    val requestedAtEpochMillis: Long,
    val register: Boolean
) {
    init {
        require(email.isNotBlank() && email.length <= 320 && !email.any(Char::isWhitespace) &&
            email.count { it == '@' } == 1) { "Bitte eine gueltige E-Mail-Adresse eingeben." }
        require(CODE_VERIFIER_PATTERN.matches(codeVerifier) && requestedAtEpochMillis >= 0) {
            "Die lokale Anmeldeanforderung ist ungueltig."
        }
    }

    fun current(nowEpochMillis: Long = System.currentTimeMillis()): Boolean =
        nowEpochMillis - requestedAtEpochMillis in 0 until EMAIL_AUTH_REQUEST_LIFETIME_MILLIS

    fun toJson(): JSONObject = JSONObject().put("email", email).put("code_verifier", codeVerifier)
        .put("requested_at", requestedAtEpochMillis).put("register", register)

    fun otpRequestBody(): JSONObject = JSONObject().put("email", email).put("create_user", register)
        .put("code_challenge", pkceCodeChallenge(codeVerifier)).put("code_challenge_method", "s256")

    fun exchangeRequestBody(code: String): JSONObject {
        require(AUTH_CODE_PATTERN.matches(code)) { "Der Anmelde-Link ist ungueltig." }
        return JSONObject().put("auth_code", code).put("code_verifier", codeVerifier)
    }

    fun tokenHashRequestBody(hash: String): JSONObject {
        require(TOKEN_HASH_PATTERN.matches(hash)) { "Der Anmelde-Link ist ungueltig." }
        return JSONObject().put("token_hash", hash).put("type", "email")
    }

    fun checkSession(value: JSONObject) {
        require(value.optJSONObject("user")?.optString("email")?.equals(email, ignoreCase = true) == true) {
            "Der Anmelde-Link gehoert nicht zur angeforderten E-Mail-Adresse."
        }
    }

    override fun toString(): String = "PendingEmailSignIn(codeVerifier=<redacted>)"

    companion object {
        fun create(email: String, register: Boolean): PendingEmailSignIn {
            val bytes = ByteArray(64).also { SecureRandom().nextBytes(it) }
            return PendingEmailSignIn(email.trim().lowercase(Locale.ROOT),
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), System.currentTimeMillis(), register)
        }

        fun fromJson(value: JSONObject): PendingEmailSignIn = PendingEmailSignIn(
            value.getString("email"), value.getString("code_verifier"), value.getLong("requested_at"),
            value.getBoolean("register"))
    }
}

internal fun emailOtpRequestPath(): String =
    "auth/v1/otp?redirect_to=${URLEncoder.encode(EMAIL_AUTH_REDIRECT, Charsets.UTF_8.name())}"
