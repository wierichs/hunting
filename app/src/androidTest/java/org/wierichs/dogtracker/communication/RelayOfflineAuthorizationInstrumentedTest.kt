package org.wierichs.dogtracker.communication

import android.os.SystemClock
import android.content.Context
import android.Manifest
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.UnknownHostException
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import org.wierichs.dogtracker.DeviceTokenStorage
import org.wierichs.dogtracker.jagdkarte.HuntingProfile
import org.wierichs.dogtracker.jagdkarte.JagdkarteRepository

@RunWith(AndroidJUnit4::class)
class RelayOfflineAuthorizationInstrumentedTest {
    private val owner = "test-account"
    private val profile = "c76274b7-e21a-440e-ad0d-c51c6f6345e0"
    private val device = "a16cb2bc-6dca-40bb-8e10-fc33b4d9272e"

    private data class Fixture(val capabilities: RelayCapabilities, val document: JSONObject, val lease: RelayOfflineLease)

    private fun fixture(context: Context): Fixture {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(pair.public.encoded)
        val capabilities = RelayCapabilities(RelayServerEncryptionKey("encryption", publicKey), "receipt", publicKey)
        val now = System.currentTimeMillis()
        val body = JSONObject().put("document_type", "offline_directory").put("profile_id", profile)
            .put("device_id", device).put("issued_at", Instant.ofEpochMilli(now).toString())
            .put("expires_at", Instant.ofEpochMilli(now + 86_400_000).toString())
            .put("devices", JSONArray().put(JSONObject().put("device_id", device).put("device_kind", "participant")
                .put("public_key", RelaySigningIdentity.publicKeyBase64Url())))
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(body.toString().toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(pair.private)
            update("location-relay-document-v1\nreceipt\n$encoded".toByteArray())
            Base64.getUrlEncoder().withoutPadding().encodeToString(sign())
        }
        val document = JSONObject().put("action", "relay_document").put("key_id", "receipt")
            .put("document", encoded).put("signature", signature)
        val boot = RelayOfflineLease.bootCount(context)
        assertTrue("Android muss den Boot-Zähler bereitstellen.", boot >= 0)
        return Fixture(capabilities, document, RelayOfflineLease(boot, SystemClock.elapsedRealtime(), now))
    }

    @Test fun encryptedAuthorizationIsBoundToOwnerDeviceProfileAndBoot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test_offline_relay_authorization"
        val (capabilities, document, lease) = fixture(context)
        val boot = lease.bootCount
        try {
            RelayOfflineAuthorization.verify(context, capabilities, document, device, profile, lease)
            RelayOfflineAuthorization.save(context, name, owner, device, capabilities, document, lease)
            assertNotNull(RelayOfflineAuthorization.load(context, name, owner, device, profile))
            assertNull(RelayOfflineAuthorization.load(context, name, "other-account", device, profile))
            assertNull(RelayOfflineAuthorization.load(context, name, owner, "b16cb2bc-6dca-40bb-8e10-fc33b4d9272e", profile))
            assertNull(RelayOfflineAuthorization.load(context, name, owner, device, "d76274b7-e21a-440e-ad0d-c51c6f6345e0"))

            RelayOfflineAuthorization.save(context, name, owner, device, capabilities, document, lease.copy(bootCount = boot + 1))
            assertNull(RelayOfflineAuthorization.load(context, name, owner, device, profile))
            RelayOfflineAuthorization.save(context, name, owner, device, capabilities,
                JSONObject(document.toString()).put("signature", "invalid"), lease)
            assertNull(RelayOfflineAuthorization.load(context, name, owner, device, profile))
        } finally {
            DeviceTokenStorage.clear(context, name, synchronous = true)
        }
    }

    @Test fun gatewayRestoresAuthorizationWhenTheServerIsUnreachable() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Nur mit isolierter Test-Application-ID ausführen.", context.packageName.contains(".test"))
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (Build.VERSION.SDK_INT >= 37) permissions.add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        permissions.forEach { permission ->
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} $permission").close()
        }
        val (capabilities, document, lease) = fixture(context)
        val authorizationFile = "gateway_relay_authorization_v2"
        val sessionFile = "jagdkarte_auth_session"
        val originalState = RelayGateway.state.value
        DeviceTokenStorage.write(context, JSONObject().put("access_token", "test-token")
            .put("refresh_token", "test-refresh").put("expires_at", Long.MAX_VALUE)
            .put("user", JSONObject().put("id", owner)).toString(), sessionFile, synchronous = true)
        RelayOfflineAuthorization.save(context, authorizationFile, owner, device, capabilities, document, lease)
        val repository = JagdkarteRepository(context, requestOverride = { _, _, _, _ ->
            throw UnknownHostException("test-offline")
        })
        val engine = RelayGatewayEngine(context, repository, HuntingProfile(profile, "Test", owner, true, null),
            onIncoming = {}, onDocument = { _, _ -> })
        try {
            engine.initialize()
            assertTrue(RelayGateway.state.value.active)
            assertTrue(RelayGateway.state.value.status.contains("Offline"))
            engine.synchronize()
            engine.close(revoke = false)
            assertNotNull(RelayOfflineAuthorization.load(context, authorizationFile, owner, device, profile))
            val resumed = RelayGatewayEngine(context, repository, HuntingProfile(profile, "Test", owner, true, null),
                onIncoming = {}, onDocument = { _, _ -> })
            try {
                resumed.initialize()
                assertTrue(RelayGateway.state.value.active)
                try {
                    resumed.close(revoke = true)
                    throw AssertionError("Serverwiderruf hätte offline fehlschlagen müssen.")
                } catch (_: IOException) {
                    assertNull(RelayOfflineAuthorization.load(context, authorizationFile, owner, device, profile))
                }
            } finally {
                resumed.close(revoke = false)
            }
        } finally {
            engine.close(revoke = false)
            DeviceTokenStorage.clear(context, authorizationFile, synchronous = true)
            DeviceTokenStorage.clear(context, sessionFile, synchronous = true)
            context.deleteDatabase("relay_gateway_$device.db")
            RelayGateway.state.value = originalState
        }
    }
}
