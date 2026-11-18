package org.wierichs.dogtracker.jagdkarte

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.wierichs.dogtracker.DeviceTokenStorage
import org.wierichs.dogtracker.TrackerPreferences
import org.wierichs.dogtracker.dogTrackerPairingQr
import org.wierichs.dogtracker.HuntingPermissionState
import org.wierichs.dogtracker.communication.RelayGateway

@RunWith(AndroidJUnit4::class)
class ProfileInvitationInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val sessionFile = "jagdkarte_auth_session"
    private val userId = "c76274b7-e21a-440e-ad0d-c51c6f6345e0"
    private val lifecycleOwner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Before fun prepareSignedInAccount() {
        DeviceTokenStorage.clear(context, EMAIL_AUTH_REQUEST_FILE, synchronous = true)
        DeviceTokenStorage.write(context, JSONObject()
            .put("access_token", "fixture-access").put("refresh_token", "fixture-refresh")
            .put("expires_at", System.currentTimeMillis() / 1000 + 3600)
            .put("user", JSONObject().put("id", userId).put("email", "editor@example.test"))
            .toString(), sessionFile, synchronous = true)
        composeRule.runOnIdle { lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED }
    }

    @After fun clearSignedInAccount() {
        composeRule.runOnIdle { lifecycleOwner.registry.currentState = Lifecycle.State.DESTROYED }
        TrackerPreferences.setLastHuntingProfileId(context, userId, null)
        DeviceTokenStorage.clear(context, sessionFile, synchronous = true)
        DeviceTokenStorage.clear(context, EMAIL_AUTH_REQUEST_FILE, synchronous = true)
    }

    private fun showMap(
        request: (String, String, Any?, String?) -> String,
        link: EmailAuthLink? = null,
        onLinkReceived: () -> Unit = {},
        dogTrackerScanner: (((String?) -> Unit) -> Unit)? = null,
        huntingAccess: HuntingPermissionState = HuntingPermissionState(),
        startRelay: (Context, String) -> Unit = { context, profileId -> RelayGateway.start(context, profileId) }
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                MaterialTheme {
                    JagdkarteScreen(onUseDeviceToken = { false }, incomingEmailAuthLink = link,
                        onEmailAuthLinkReceived = onLinkReceived,
                        repositoryFactory = { JagdkarteRepository(it,
                            authRequestOverride = { _, _ -> error("Unexpected authentication request") },
                            requestOverride = request) }, dogTrackerScanner = dogTrackerScanner,
                        huntingAccess = huntingAccess, startRelay = startRelay)
                }
            }
        }
    }

    private fun invitedProfile(): String = """[{
        "id":"5ebfd0bf-a38b-4b87-8628-0cb48298255e",
        "district_name":"Eingeladenes Revier",
        "owner_id":"1f9f1e27-f73a-4e69-9dd4-846c6a5fd509",
        "hunting_mode":false
    }]"""

    @Test fun dogPairingStatusUsesTheSignedInAccountAndTheExactScannedDogAndToken() = runBlocking {
        val dogId = "5ebfd0bf-a38b-4b87-8628-0cb48298255e"
        val token = "a".repeat(64)
        val repository = JagdkarteRepository(context, requestOverride = { path, method, body, bearer ->
            assertEquals("rest/v1/rpc/get_hunting_dog_device_pairing_status", path)
            assertEquals("POST", method)
            assertEquals("fixture-access", bearer)
            val parameters = body as? JSONObject ?: error("Expected JSON request body")
            assertEquals(setOf("p_dog_id", "p_device_token"), parameters.keys().asSequence().toSet())
            assertEquals(dogId, parameters.getString("p_dog_id"))
            assertEquals(token, parameters.getString("p_device_token"))
            "\"active\""
        })
        assertEquals(DogDevicePairingStatus.ACTIVE, repository.dogDevicePairingStatus(dogId, token))
    }

    @Test fun dogTrackerScanSurvivesTheMapStoppingAndStagesTheScannedToken() {
        val profileId = "77777777-7777-4777-8777-777777777777"
        val dogId = "88888888-8888-4888-8888-888888888888"
        val token = "a".repeat(64)
        val scannerResult = AtomicReference<((String?) -> Unit)?>(null)
        val staged = AtomicBoolean()
        TrackerPreferences.setLastHuntingProfileId(context, userId, profileId)
        try {
            showMap(request = { path, _, body, bearer ->
                assertEquals("fixture-access", bearer)
                when {
                    path == "rest/v1/rpc/accept_hunting_profile_invitations" -> "0"
                    path.startsWith("rest/v1/hunting_profiles?") ->
                        """[{"id":"$profileId","district_name":"Scanner-Testrevier","owner_id":"$userId","hunting_mode":false}]"""
                    path == "rest/v1/rpc/get_hunting_profile_owner_emails" -> "[]"
                    path.startsWith("rest/v1/hunting_markers?") -> "[]"
                    path.startsWith("rest/v1/hunting_dogs?") -> """[{"id":"$dogId","name":"Faehrte"}]"""
                    path == "rest/v1/rpc/stage_hunting_dog_device_token" -> {
                        val parameters = body as? JSONObject ?: error("Expected JSON request")
                        assertEquals(dogId, parameters.getString("p_dog_id"))
                        assertEquals(token, parameters.getString("p_device_token"))
                        staged.set(true)
                        "\"99999999-9999-4999-8999-999999999999\""
                    }
                    path == "rest/v1/rpc/get_hunting_dog_device_pairing_status" -> "\"pending\""
                    path == "rest/v1/rpc/get_hunting_dog_pairing_states" ->
                        """[{"dog_id":"$dogId","paired":false,"pending":false}]"""
                    else -> error("Unexpected request: $path")
                }
            }, dogTrackerScanner = { scannerResult.set(it) })
            composeRule.onAllNodesWithContentDescription("Revier und Verwaltung")[0].performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodes(hasText("Hunde") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Hunde").performScrollTo().performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("Koppeln").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Koppeln").performClick()
            composeRule.onNodeWithText("DogTracker-QR scannen").performClick()
            composeRule.runOnIdle { lifecycleOwner.registry.currentState = Lifecycle.State.CREATED }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("DogTracker-QR scannen").assertExists()
            composeRule.runOnIdle {
                scannerResult.get()!!(dogTrackerPairingQr(token))
                lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
            }
            composeRule.waitUntil(5_000) { staged.get() }
            composeRule.onNodeWithText("Warten auf Bestaetigung").assertIsDisplayed()
            composeRule.onNodeWithText("Faehrte in Scanner-Testrevier").assertIsDisplayed()
        } finally {
            TrackerPreferences.setLastHuntingProfileId(context, userId, null)
        }
    }

    @Test fun dogPairingStatesUsesTheAccountAndProfileWithoutTransferringDeviceSecrets() = runBlocking {
        val profileId = "77777777-7777-4777-8777-777777777777"
        val dogId = "88888888-8888-4888-8888-888888888888"
        val repository = JagdkarteRepository(context, requestOverride = { path, method, body, bearer ->
            assertEquals("rest/v1/rpc/get_hunting_dog_pairing_states", path)
            assertEquals("POST", method)
            assertEquals("fixture-access", bearer)
            val parameters = body as? JSONObject ?: error("Expected JSON request body")
            assertEquals(setOf("p_profile_id"), parameters.keys().asSequence().toSet())
            assertEquals(profileId, parameters.getString("p_profile_id"))
            """[{"dog_id":"$dogId","paired":false,"pending":true}]"""
        })
        assertEquals(mapOf(dogId to DogPairingState(false, true)), repository.dogPairingStates(profileId))
    }

    private fun showAutomaticRelayMap(
        permissionsReady: Boolean,
        huntingMode: Boolean,
        start: (Context, String) -> Unit
    ) {
        val profileId = "99999999-9999-4999-8999-999999999999"
        TrackerPreferences.setLastHuntingProfileId(context, userId, profileId)
        TrackerPreferences.setHuntingParticipationConsent(context, userId, profileId, false)
        showMap(request = { path, _, _, bearer ->
            assertEquals("fixture-access", bearer)
            when {
                path == "rest/v1/rpc/accept_hunting_profile_invitations" -> "0"
                path.startsWith("rest/v1/hunting_profiles?") ->
                    """[{"id":"$profileId","district_name":"Automatisches Testrevier","owner_id":"$userId","hunting_mode":$huntingMode}]"""
                path == "rest/v1/rpc/get_hunting_profile_owner_emails" ||
                    path == "rest/v1/rpc/get_hunting_profile_participants" ||
                    path.startsWith("rest/v1/hunting_markers?") ||
                    path.startsWith("rest/v1/hunting_profile_locations?") ||
                    path.startsWith("rest/v1/hunting_dogs?") ||
                    path.startsWith("rest/v1/hunting_dog_locations?") -> "[]"
                else -> error("Unexpected request: $path")
            }
        }, huntingAccess = HuntingPermissionState(consented = permissionsReady), startRelay = start)
    }

    @Test fun huntingModeStartsRelayWithoutAnAdditionalProfileConsentAndRetriesFailures() {
        val starts = java.util.concurrent.atomic.AtomicInteger()
        showAutomaticRelayMap(true, true) { _, profileId ->
            assertEquals("99999999-9999-4999-8999-999999999999", profileId)
            if (starts.incrementAndGet() == 1) throw java.io.IOException("Relaisstart voruebergehend fehlgeschlagen.")
        }
        composeRule.waitUntil(5_000) { starts.get() == 1 }
        composeRule.waitUntil(40_000) { starts.get() >= 2 }
    }

    @Test fun inactiveHuntingModeDoesNotAutomaticallyStartRelay() {
        val starts = java.util.concurrent.atomic.AtomicInteger()
        showAutomaticRelayMap(true, false) { _, _ -> starts.incrementAndGet() }
        composeRule.onAllNodesWithContentDescription("Revier und Verwaltung")[0].performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Jagdmodus aus:", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, starts.get())
    }

    @Test fun missingSetupDoesNotAutomaticallyStartRelayInHuntingMode() {
        val starts = java.util.concurrent.atomic.AtomicInteger()
        showAutomaticRelayMap(false, true) { _, _ -> starts.incrementAndGet() }
        composeRule.onAllNodesWithContentDescription("Revier und Verwaltung")[0].performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Jagdfunktionen sind ausgeblendet.", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, starts.get())
    }

    @Test fun openingAPwaMailWhileSignedInAcceptsInvitationsWithoutReplacingTheSession() {
        val accepted = AtomicBoolean(false)
        val received = AtomicBoolean(false)
        val calls = CopyOnWriteArrayList<String>()
        val originalSession = DeviceTokenStorage.read(context, sessionFile)
        showMap(request = { path, method, body, bearer ->
            assertEquals("fixture-access", bearer)
            calls.add(path)
            when {
                path == "rest/v1/rpc/accept_hunting_profile_invitations" -> {
                    assertEquals("POST", method)
                    assertEquals("{}", body.toString())
                    accepted.set(true)
                    "1"
                }
                path.startsWith("rest/v1/hunting_profiles?") -> if (accepted.get()) invitedProfile() else "[]"
                path == "rest/v1/rpc/get_hunting_profile_owner_emails" -> "[]"
                else -> error("Unexpected request: $path")
            }
        }, link = EmailAuthLink.TokenHash("a".repeat(64)), onLinkReceived = { received.set(true) })

        composeRule.waitUntil(5_000) {
            received.get() && calls.contains("rest/v1/rpc/get_hunting_profile_owner_emails")
        }
        composeRule.onNodeWithText("Revier auswaehlen").performClick()
        composeRule.onNodeWithText("Eingeladenes Revier", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("Anmeldung nicht abgeschlossen").assertCountEquals(0)
        assertTrue(calls.indexOf("rest/v1/rpc/accept_hunting_profile_invitations") <
            calls.indexOfFirst { it.startsWith("rest/v1/hunting_profiles?") })
        assertEquals(originalSession, DeviceTokenStorage.read(context, sessionFile))
    }

    @Test fun returningToTheMapAcceptsNewInvitationsForTheExistingAccount() {
        val pending = AtomicBoolean(false)
        val accepted = AtomicBoolean(false)
        val loads = CopyOnWriteArrayList<Boolean>()
        showMap(request = { path, _, _, bearer ->
            assertEquals("fixture-access", bearer)
            when {
                path == "rest/v1/rpc/accept_hunting_profile_invitations" -> {
                    if (pending.get()) accepted.set(true)
                    if (accepted.get()) "1" else "0"
                }
                path.startsWith("rest/v1/hunting_profiles?") -> {
                    loads.add(accepted.get())
                    if (accepted.get()) invitedProfile() else "[]"
                }
                path == "rest/v1/rpc/get_hunting_profile_owner_emails" -> "[]"
                else -> error("Unexpected request: $path")
            }
        })
        composeRule.waitUntil(5_000) { loads.isNotEmpty() }
        composeRule.runOnIdle {
            lifecycleOwner.registry.currentState = Lifecycle.State.STARTED
            pending.set(true)
            lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        }
        composeRule.waitUntil(5_000) { loads.contains(true) }
        composeRule.onNodeWithText("Revier auswaehlen").performClick()
        composeRule.onNodeWithText("Eingeladenes Revier", substring = true).assertIsDisplayed()
    }

    @Test
    fun invitationDialogSendsSelectedRoleAndEmail() {
        val profile = HuntingProfile("revier", "Testrevier", "owner", false, JSONObject())
        var invitedEmail = ""
        var invitedRole: HuntingProfileMemberRole? = null
        var dismissed = false
        composeRule.setContent {
            MaterialTheme {
                InviteMemberDialog(profile,
                    onInvite = { email, role -> invitedEmail = email; invitedRole = role },
                    onInvited = { dismissed = true }, onDismiss = {})
            }
        }

        composeRule.onNodeWithText("E-Mail-Adresse").performTextInput("Editor@Example.org")
        composeRule.onNodeWithText("Gast").performClick()
        composeRule.onNodeWithText("Bearbeiter").performClick()
        composeRule.onNodeWithText("Einladung senden").performClick()
        composeRule.runOnIdle {
            assertEquals("Editor@Example.org", invitedEmail)
            assertEquals(HuntingProfileMemberRole.EDITOR, invitedRole)
            assertEquals(true, dismissed)
        }
    }
}
