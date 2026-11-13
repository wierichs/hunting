package org.wierichs.dogtracker.jagdkarte

import java.net.URI
import java.net.URLDecoder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailAuthenticationTest {
    private val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    private val pending = PendingEmailSignIn("user@example.test", verifier, 100_000L, true)

    @Test fun pkceUsesTheRfc7636Sha256ChallengeAndSupabaseWireContract() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkceCodeChallenge(verifier))
        val body = pending.otpRequestBody()
        assertEquals(setOf("email", "create_user", "code_challenge", "code_challenge_method"), body.keys().asSequence().toSet())
        assertEquals("user@example.test", body.getString("email"))
        assertTrue(body.getBoolean("create_user"))
        assertEquals("s256", body.getString("code_challenge_method"))
        assertEquals(pkceCodeChallenge(verifier), body.getString("code_challenge"))
        val redirect = URI("https://example.test/${emailOtpRequestPath()}").rawQuery.substringAfter("redirect_to=")
        assertEquals(EMAIL_AUTH_REDIRECT, URLDecoder.decode(redirect, Charsets.UTF_8.name()))
        assertEquals(setOf("auth_code", "code_verifier"), pending.exchangeRequestBody("auth-code").keys().asSequence().toSet())
        assertEquals(verifier, pending.exchangeRequestBody("auth-code").getString("code_verifier"))
    }

    @Test fun linkParserAcceptsOnlyTheTrustedHttpsCallbackAndKeepsGuestLinksSeparate() {
        assertEquals(EmailAuthLink.Code("auth-code"),
            emailAuthLinkFromUrl("https://WIERICHS.GITHUB.IO:443/Jagdkarte/?%63ode=auth-code"))
        listOf(
            "http://wierichs.github.io/Jagdkarte/?code=auth-code",
            "https://wierichs.github.io.evil.test/Jagdkarte/?code=auth-code",
            "https://evil.test@wierichs.github.io/Jagdkarte/?code=auth-code",
            "https://wierichs.github.io:8443/Jagdkarte/?code=auth-code",
            "https://wierichs.github.io/Jagdkarte/other/?code=auth-code",
            "not a URL",
            EMAIL_AUTH_REDIRECT,
            guestLinkUrl(createGuestLinkToken())
        ).forEach { assertNull(emailAuthLinkFromUrl(it)) }
    }

    @Test fun existingTokenHashTemplatesUseEmailVerificationWithoutChangingThePwaLink() {
        val hash = "pkce_" + "a".repeat(64)
        assertEquals(EmailAuthLink.TokenHash(hash),
            emailAuthLinkFromUrl("$EMAIL_AUTH_REDIRECT?token_hash=$hash&type=email"))
        assertEquals(EmailAuthLink.TokenHash("a".repeat(64)),
            emailAuthLinkFromUrl("$EMAIL_AUTH_REDIRECT?token_hash=${"a".repeat(64)}&type=email"))
        val body = pending.tokenHashRequestBody(hash)
        assertEquals(setOf("token_hash", "type"), body.keys().asSequence().toSet())
        assertEquals(hash, body.getString("token_hash"))
        assertEquals("email", body.getString("type"))
        listOf(
            "$EMAIL_AUTH_REDIRECT?token_hash=$hash",
            "$EMAIL_AUTH_REDIRECT?token_hash=$hash&type=recovery",
            "$EMAIL_AUTH_REDIRECT?token_hash=$hash&type=email&type=signup",
            "$EMAIL_AUTH_REDIRECT?token_hash=$hash&type=email&code=other-code"
        ).forEach { assertTrue(emailAuthLinkFromUrl(it) is EmailAuthLink.Rejected) }
    }

    @Test fun ambiguousImplicitExpiredAndMalformedCallbacksNeverSupplyAnAuthCode() {
        val guest = createGuestLinkToken()
        listOf(
            "$EMAIL_AUTH_REDIRECT?code=first&%63ode=second",
            "$EMAIL_AUTH_REDIRECT?code=",
            "$EMAIL_AUTH_REDIRECT?code=hello%20world",
            "$EMAIL_AUTH_REDIRECT?other=value#%63ode=auth-code",
            "$EMAIL_AUTH_REDIRECT?code=auth-code#gast=$guest",
            "$EMAIL_AUTH_REDIRECT#access_token=not-a-session&refresh_token=not-a-session",
            "$EMAIL_AUTH_REDIRECT?token_hash=not-a-session&type=email",
            "$EMAIL_AUTH_REDIRECT?code=auth-code#error=access_denied&error_code=otp_expired"
        ).forEach { assertTrue(emailAuthLinkFromUrl(it) is EmailAuthLink.Rejected) }
    }

    @Test fun pendingRequestsExpireAfterAnHourAndRejectFutureTimestamps() {
        assertFalse(pending.current(99_999L))
        assertTrue(pending.current(100_000L))
        assertTrue(pending.current(3_699_999L))
        assertFalse(pending.current(3_700_000L))
    }

    @Test fun exchangedSessionMustMatchTheRequestedEmail() {
        pending.checkSession(JSONObject().put("user", JSONObject().put("email", "USER@example.test")))
        assertThrows(IllegalArgumentException::class.java) {
            pending.checkSession(JSONObject().put("user", JSONObject().put("email", "other@example.test")))
        }
        assertThrows(IllegalArgumentException::class.java) { pending.checkSession(JSONObject()) }
        assertThrows(IllegalArgumentException::class.java) { pkceCodeChallenge("short") }
    }
}
