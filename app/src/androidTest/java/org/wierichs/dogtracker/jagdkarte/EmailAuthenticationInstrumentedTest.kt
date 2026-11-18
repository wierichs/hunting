package org.wierichs.dogtracker.jagdkarte

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.wierichs.dogtracker.DeviceTokenStorage

@RunWith(AndroidJUnit4::class)
class EmailAuthenticationInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val email = "user@example.test"

    @Before @After fun clearFixtureCredentials() {
        DeviceTokenStorage.clear(context, EMAIL_AUTH_REQUEST_FILE, synchronous = true)
        DeviceTokenStorage.clear(context, "jagdkarte_auth_session", synchronous = true)
    }

    private fun fixtureSession(address: String = email): String = JSONObject()
        .put("access_token", "fixture-access").put("refresh_token", "fixture-refresh")
        .put("expires_in", 3600).put("user", JSONObject().put("id", "c76274b7-e21a-440e-ad0d-c51c6f6345e0")
            .put("email", address)).toString()

    @Test fun pendingProofSurvivesRepositoryRecreationAndTheVerifiedSessionIsPersisted() = runBlocking {
        var challenge = ""
        val first = JagdkarteRepository(context, authRequestOverride = { path, body ->
            assertEquals(emailOtpRequestPath(), path)
            challenge = body.getString("code_challenge")
            "{}"
        })
        first.requestCode(" USER@example.test ", true)
        val pending = JagdkarteRepository(context).pendingEmailSignIn()!!
        assertEquals(email, pending.email)
        assertEquals(challenge, pkceCodeChallenge(pending.codeVerifier))
        val encrypted = context.getSharedPreferences(EMAIL_AUTH_REQUEST_FILE, 0).all.values.joinToString()
        assertFalse(encrypted.contains(email))
        assertFalse(encrypted.contains(pending.codeVerifier))

        val recreated = JagdkarteRepository(context, authRequestOverride = { path, body ->
            assertEquals("auth/v1/token?grant_type=pkce", path)
            assertEquals("auth-code", body.getString("auth_code"))
            assertEquals(pending.codeVerifier, body.getString("code_verifier"))
            fixtureSession()
        })
        recreated.verifyEmailLink("auth-code")
        assertTrue(JagdkarteRepository(context).signedIn)
        assertEquals(email, JagdkarteRepository(context).email)
        assertNull(recreated.pendingEmailSignIn())
    }

    @Test fun aNewMailReplacesThePreviousProofAndCodeLoginRemainsAvailable() = runBlocking {
        val repository = JagdkarteRepository(context, authRequestOverride = { path, body ->
            if (path.startsWith("auth/v1/otp")) "{}"
            else {
                assertEquals("auth/v1/verify", path)
                assertEquals(email, body.getString("email"))
                assertEquals("123456", body.getString("token"))
                assertEquals("email", body.getString("type"))
                fixtureSession()
            }
        })
        repository.requestCode(email, true)
        val oldProof = repository.pendingEmailSignIn()!!.codeVerifier
        repository.requestCode(email, false)
        val newer = repository.pendingEmailSignIn()!!
        assertFalse(newer.register)
        assertNotEquals(oldProof, newer.codeVerifier)
        repository.verifyCode(email, "123456")
        assertTrue(JagdkarteRepository(context).signedIn)
        assertNull(repository.pendingEmailSignIn())
    }

    @Test fun tokenHashFromTheExistingMailTemplateIsVerifiedForTheLocallyRequestedAccount() = runBlocking {
        val hash = "pkce_" + "a".repeat(64)
        val repository = JagdkarteRepository(context, authRequestOverride = { path, body ->
            if (path.startsWith("auth/v1/otp")) "{}" else {
                assertEquals("auth/v1/verify", path)
                assertEquals(hash, body.getString("token_hash"))
                assertEquals("email", body.getString("type"))
                assertFalse(body.has("email"))
                fixtureSession()
            }
        })
        repository.requestCode(email, false)
        repository.verifyEmailLink(EmailAuthLink.TokenHash(hash))
        assertTrue(JagdkarteRepository(context).signedIn)
        assertEquals(email, JagdkarteRepository(context).email)
        assertNull(repository.pendingEmailSignIn())
    }

    @Test fun callbacksWithoutAnActiveRequestNeverContactTheServer() {
        var calls = 0
        val repository = JagdkarteRepository(context, authRequestOverride = { _, _ -> calls++; fixtureSession() })
        assertThrows(IllegalStateException::class.java) { runBlocking { repository.verifyEmailLink("auth-code") } }
        val expired = PendingEmailSignIn.create(email, false).copy(requestedAtEpochMillis = System.currentTimeMillis() - 3_600_000L)
        DeviceTokenStorage.write(context, expired.toJson().toString(), EMAIL_AUTH_REQUEST_FILE, synchronous = true)
        assertThrows(IllegalStateException::class.java) { runBlocking { repository.verifyEmailLink("auth-code") } }
        assertEquals(0, calls)
        assertFalse(repository.signedIn)
    }

    @Test fun aSessionForAnotherAccountIsRejectedBeforeSavingIt() = runBlocking {
        val repository = JagdkarteRepository(context, authRequestOverride = { path, _ ->
            if (path.startsWith("auth/v1/otp")) "{}" else fixtureSession("other@example.test")
        })
        repository.requestCode(email, false)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.verifyEmailLink("auth-code") } }
        assertFalse(JagdkarteRepository(context).signedIn)
        assertNotNull(repository.pendingEmailSignIn())
    }
}
