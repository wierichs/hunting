package org.wierichs.dogtracker.jagdkarte

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.widget.TextView
import io.noties.markwon.Markwon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import org.wierichs.dogtracker.HuntingMapCamera
import org.wierichs.dogtracker.TrackerPreferences
import org.wierichs.dogtracker.createDogTrackerDeviceToken
import org.wierichs.dogtracker.dogTrackerPairingQr
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class AccountDogsHelpInstrumentedTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun firstUseDefaultsToMapAndPersistsLaterTrackerChoice() {
        context.getSharedPreferences("tracker_settings", 0).edit().remove("last_tab_tracker").commit()
        assertFalse(TrackerPreferences.lastTabIsTracker(context))
        TrackerPreferences.setLastTabIsTracker(context, true)
        assertTrue(TrackerPreferences.lastTabIsTracker(context))
        TrackerPreferences.setLastTabIsTracker(context, false)
        assertFalse(TrackerPreferences.lastTabIsTracker(context))
    }

    @Test fun trackerDefaultsToMinimumIntervalButKeepsAChosenInterval() {
        val preferences = context.getSharedPreferences("tracker_settings", 0)
        preferences.edit().remove("interval_seconds").commit()
        assertEquals(15L, TrackerPreferences.intervalSeconds(context))

        TrackerPreferences.saveConfiguration(context, 60L)
        assertEquals(60L, TrackerPreferences.intervalSeconds(context))
        TrackerPreferences.saveConfiguration(context, 15L)
    }

    @Test fun rememberedProfileAndLocationConsentAreScopedPerAccountAndProfile() {
        val userId = UUID.randomUUID().toString()
        val otherUserId = UUID.randomUUID().toString()
        val profileId = UUID.randomUUID().toString()
        val otherProfileId = UUID.randomUUID().toString()
        TrackerPreferences.setLastHuntingProfileId(context, userId, profileId)
        TrackerPreferences.setLocationShareConsent(context, userId, profileId, true)
        TrackerPreferences.setHuntingParticipationConsent(context, userId, profileId, true)

        assertEquals(profileId, TrackerPreferences.lastHuntingProfileId(context, userId))
        assertTrue(TrackerPreferences.locationShareConsented(context, userId, profileId))
        assertTrue(TrackerPreferences.huntingParticipationConsented(context, userId, profileId))
        assertEquals(null, TrackerPreferences.lastHuntingProfileId(context, otherUserId))
        assertFalse(TrackerPreferences.locationShareConsented(context, userId, otherProfileId))
        assertFalse(TrackerPreferences.huntingParticipationConsented(context, otherUserId, profileId))
        assertFalse(TrackerPreferences.huntingParticipationConsented(context, userId, otherProfileId))

        TrackerPreferences.setLastHuntingProfileId(context, userId, null)
        TrackerPreferences.setLocationShareConsent(context, userId, profileId, false)
        TrackerPreferences.setHuntingParticipationConsent(context, userId, profileId, false)
        assertFalse(TrackerPreferences.huntingParticipationConsented(context, userId, profileId))
    }

    @Test fun mapCameraAndFullScreenStatePersistAcrossScreenRecreation() {
        val userId = UUID.randomUUID().toString()
        val profileId = UUID.randomUUID().toString()
        val camera = HuntingMapCamera(51.2345, 6.789, 14.5)
        TrackerPreferences.setMapCamera(context, userId, profileId, camera)
        TrackerPreferences.setMapFullScreen(context, true)

        assertEquals(camera, TrackerPreferences.mapCamera(context, userId, profileId))
        assertTrue(TrackerPreferences.mapFullScreen(context))
        assertEquals(null, TrackerPreferences.mapCamera(context, UUID.randomUUID().toString(), profileId))
        TrackerPreferences.setMapFullScreen(context, false)
    }

    @Test fun editorMustConfirmDogDeletionAndTheListUpdatesWithoutServerAccess() {
        val dog = HuntingDog(UUID.randomUUID().toString(), "Faehrte")
        val profile = HuntingProfile(UUID.randomUUID().toString(), "Testrevier", UUID.randomUUID().toString(),
            huntingMode = false, boundary = null)
        var deletedDogId: String? = null
        rule.setContent { MaterialTheme {
            DogsDialog(repository = JagdkarteRepository(context), profile = profile, canEdit = true,
                onUseDeviceToken = { false }, onDismiss = {}, loadDogs = { listOf(dog) },
                deleteDog = { deletedDogId = it }, loadDogPairingStates = { emptyMap() })
        } }

        rule.onNodeWithText("Faehrte").assertIsDisplayed()
        rule.onNodeWithText("Loeschen").performClick()
        rule.onNodeWithText("Hund loeschen?").assertIsDisplayed()
        rule.onNodeWithText("gespeicherte Hundeposition", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Hund dauerhaft loeschen").performClick()
        rule.waitUntil(5_000) { deletedDogId == dog.id }
        rule.onNodeWithText("Keine Hunde registriert.").assertIsDisplayed()
    }

    @Test fun guestCannotSeeDogRegistrationOrDeletionActions() {
        val dog = HuntingDog(UUID.randomUUID().toString(), "Faehrte")
        val profile = HuntingProfile(UUID.randomUUID().toString(), "Testrevier", UUID.randomUUID().toString(),
            huntingMode = false, boundary = null)
        rule.setContent { MaterialTheme {
            DogsDialog(repository = JagdkarteRepository(context), profile = profile, canEdit = false,
                onUseDeviceToken = { false }, onDismiss = {}, loadDogs = { listOf(dog) }, deleteDog = {},
                loadDogPairingStates = { emptyMap() })
        } }

        rule.onNodeWithText("Faehrte").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Loeschen").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Hund registrieren").fetchSemanticsNodes().size)
    }

    @Test fun jagdkarteCanScanAndRegisterDogTrackerQrWithoutTrackerCamera() {
        val dog = HuntingDog(UUID.randomUUID().toString(), "Faehrte")
        val profile = HuntingProfile(UUID.randomUUID().toString(), "Testrevier", UUID.randomUUID().toString(),
            huntingMode = false, boundary = null)
        val token = createDogTrackerDeviceToken()
        var registered: Pair<String, String>? = null
        rule.setContent { MaterialTheme {
            DogsDialog(repository = JagdkarteRepository(context), profile = profile, canEdit = true,
                onUseDeviceToken = { false }, onDismiss = {}, loadDogs = { listOf(dog) }, deleteDog = {},
                stageDeviceToken = { dogId, deviceToken -> registered = dogId to deviceToken },
                scanDogTrackerQr = { onScanned -> onScanned(dogTrackerPairingQr(token)) },
                loadPairingStatus = { _, _ -> DogDevicePairingStatus.PENDING },
                loadDogPairingStates = { emptyMap() })
        } }

        rule.onNodeWithText("Koppeln").performClick()
        rule.onNodeWithText("DogTracker-QR scannen").performClick()
        rule.waitUntil(5_000) { registered != null }
        assertEquals(dog.id to token, registered)
        rule.onNodeWithText("Warten auf Bestaetigung").assertIsDisplayed()
        rule.onNodeWithText("Faehrte in Testrevier").assertIsDisplayed()
        rule.onNodeWithText("QR-Code gelesen.", substring = true).assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Hunde im Revier").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Kopplung erfolgreich").fetchSemanticsNodes().size)
    }

    @Test fun jagdkarteShowsSuccessOnlyAfterServerConfirmation() {
        val dog = HuntingDog(UUID.randomUUID().toString(), "Faehrte")
        val profile = HuntingProfile(UUID.randomUUID().toString(), "Testrevier", UUID.randomUUID().toString(),
            huntingMode = false, boundary = null)
        val token = createDogTrackerDeviceToken()
        var registered: Pair<String, String>? = null
        val checks = AtomicInteger()
        rule.setContent { MaterialTheme {
            DogsDialog(repository = JagdkarteRepository(context), profile = profile, canEdit = true,
                onUseDeviceToken = { false }, onDismiss = {}, loadDogs = { listOf(dog) }, deleteDog = {},
                stageDeviceToken = { dogId, deviceToken -> registered = dogId to deviceToken },
                scanDogTrackerQr = { onScanned -> onScanned(dogTrackerPairingQr(token)) },
                loadPairingStatus = { dogId, deviceToken ->
                    assertEquals(dog.id, dogId)
                    assertEquals(token, deviceToken)
                    if (checks.incrementAndGet() == 1) DogDevicePairingStatus.PENDING else DogDevicePairingStatus.ACTIVE
                }, loadDogPairingStates = { emptyMap() })
        } }

        rule.onNodeWithText("Koppeln").performClick()
        rule.onNodeWithText("DogTracker-QR scannen").performClick()
        rule.waitUntil(5_000) { registered != null }
        assertEquals(dog.id to token, registered)
        rule.onNodeWithText("Warten auf Bestaetigung").assertIsDisplayed()
        rule.waitUntil(5_000) { checks.get() >= 2 }
        rule.onNodeWithText("Kopplung erfolgreich").assertIsDisplayed()
        rule.onNodeWithText("Das DogTracker-Smartphone ist jetzt", substring = true).assertIsDisplayed()
    }

    @Test fun pairingStatusFailureIsVisibleAndCanBeRetriedWithoutRescanning() {
        val dog = HuntingDog(UUID.randomUUID().toString(), "Faehrte")
        val profile = HuntingProfile(UUID.randomUUID().toString(), "Testrevier", UUID.randomUUID().toString(),
            huntingMode = false, boundary = null)
        val checks = AtomicInteger()
        val stages = AtomicInteger()
        rule.setContent { MaterialTheme {
            DogsDialog(repository = JagdkarteRepository(context), profile = profile, canEdit = true,
                onUseDeviceToken = { false }, onDismiss = {}, loadDogs = { listOf(dog) },
                stageDeviceToken = { _, _ -> stages.incrementAndGet(); Unit },
                scanDogTrackerQr = { it(dogTrackerPairingQr("a".repeat(64))) },
                loadPairingStatus = { _, _ ->
                    if (checks.incrementAndGet() == 1) throw IOException("Keine Internetverbindung.")
                    DogDevicePairingStatus.NOT_FOUND
                }, loadDogPairingStates = { emptyMap() })
        } }
        rule.onNodeWithText("Koppeln").performClick()
        rule.onNodeWithText("DogTracker-QR scannen").performClick()
        rule.onNodeWithText("Keine Internetverbindung.").assertIsDisplayed()
        rule.onNodeWithText("Kopplung erneut pruefen").performClick()
        rule.onNodeWithText("Kopplung nicht abgeschlossen").assertIsDisplayed()
        assertEquals(1, stages.get())
        assertEquals(0, rule.onAllNodesWithText("Kopplung erfolgreich").fetchSemanticsNodes().size)
    }

    @Test fun pairingStatusRejectsUnknownOrMalformedServerResponses() {
        assertEquals(DogDevicePairingStatus.PENDING, dogDevicePairingStatus("\"pending\""))
        assertEquals(DogDevicePairingStatus.ACTIVE, dogDevicePairingStatus("\"active\""))
        assertEquals(DogDevicePairingStatus.NOT_FOUND, dogDevicePairingStatus("\"not_found\""))
        listOf("\"activated\"", "null", "true", "[]", "{\"status\":\"active\"}").forEach { response ->
            assertThrows(IllegalStateException::class.java) { dogDevicePairingStatus(response) }
        }
    }

    @Test fun statusCheckingPausesWithTheAppAndResumesWithoutANewScan() {
        val dog = HuntingDog(UUID.randomUUID().toString(), "Faehrte")
        val profile = HuntingProfile(UUID.randomUUID().toString(), "Testrevier", UUID.randomUUID().toString(),
            huntingMode = false, boundary = null)
        val checks = AtomicInteger()
        val confirmed = AtomicBoolean()
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        try {
            rule.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { MaterialTheme {
                DogsDialog(repository = JagdkarteRepository(context), profile = profile, canEdit = true,
                    onUseDeviceToken = { false }, onDismiss = {}, loadDogs = { listOf(dog) },
                    stageDeviceToken = { _, _ -> },
                    scanDogTrackerQr = { it(dogTrackerPairingQr("a".repeat(64))) },
                    loadPairingStatus = { _, _ ->
                        checks.incrementAndGet()
                        if (confirmed.get()) DogDevicePairingStatus.ACTIVE else DogDevicePairingStatus.PENDING
                    }, loadDogPairingStates = { emptyMap() })
            } } }
            rule.onNodeWithText("Koppeln").performClick()
            rule.onNodeWithText("DogTracker-QR scannen").performClick()
            rule.onNodeWithText("Warten auf Bestaetigung").assertIsDisplayed()
            rule.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
            val pausedChecks = checks.get()
            Thread.sleep(2_200)
            assertEquals(pausedChecks, checks.get())
            confirmed.set(true)
            rule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            rule.onNodeWithText("Kopplung erfolgreich").assertIsDisplayed()
            assertTrue(checks.get() > pausedChecks)
        } finally {
            rule.runOnIdle { owner.registry.currentState = Lifecycle.State.DESTROYED }
        }
    }

    @Test fun nativeHelpCoversLoginDogsOfflineMapsAndTroubleshooting() {
        val markdown = context.assets.open("hilfe.md").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val rendered = TextView(context)
        Markwon.create(context).setMarkdown(rendered, markdown)
        assertTrue(rendered.text.toString().contains("Anmelden und registrieren"))
        assertTrue(rendered.text.toString().contains("Hunde und Tracker"))
        assertTrue(rendered.text.toString().contains("Karten und Offline-Nutzung"))

        rule.setContent { MaterialTheme { HelpDialog(onDismiss = {}) } }

        rule.onNodeWithText("Hilfe und Fehlerbehebung").assertIsDisplayed()
    }

    @Test fun dogListShowsServerConfirmedPairingAndPendingStateAlsoAfterReopening() {
        val dog = HuntingDog(UUID.randomUUID().toString(), "Faehrte")
        val profile = HuntingProfile(UUID.randomUUID().toString(), "Testrevier", UUID.randomUUID().toString(),
            huntingMode = false, boundary = null)
        val state = AtomicReference(DogPairingState(paired = false, pending = true))
        var open by mutableStateOf(true)
        rule.setContent { MaterialTheme {
            if (open) DogsDialog(repository = JagdkarteRepository(context), profile = profile, canEdit = true,
                onUseDeviceToken = { false }, onDismiss = { open = false }, loadDogs = { listOf(dog) },
                loadDogPairingStates = { mapOf(dog.id to state.get()) })
        } }
        rule.onNodeWithContentDescription("Faehrte: nicht gekoppelt").assertIsDisplayed()
        rule.onNodeWithText("Nicht gekoppelt").assertIsDisplayed()
        rule.onNodeWithText("Bestaetigung ausstehend").assertIsDisplayed()
        state.set(DogPairingState(paired = true, pending = false))
        rule.onNodeWithText("Kopplungsstatus aktualisieren").performClick()
        rule.onNodeWithContentDescription("Faehrte: gekoppelt").assertIsDisplayed()
        rule.onNodeWithText("Gekoppelt").assertIsDisplayed()
        rule.onNodeWithText("Schliessen").performClick()
        rule.runOnIdle { open = true }
        rule.onNodeWithContentDescription("Faehrte: gekoppelt").assertIsDisplayed()
    }

    @Test fun failedDogStatusRefreshShowsUnknownInsteadOfAnIncorrectCrossOrCheck() {
        val dog = HuntingDog(UUID.randomUUID().toString(), "Faehrte")
        val profile = HuntingProfile(UUID.randomUUID().toString(), "Testrevier", UUID.randomUUID().toString(),
            huntingMode = false, boundary = null)
        val requests = AtomicInteger()
        rule.setContent { MaterialTheme {
            DogsDialog(repository = JagdkarteRepository(context), profile = profile, canEdit = true,
                onUseDeviceToken = { false }, onDismiss = {}, loadDogs = { listOf(dog) },
                loadDogPairingStates = {
                    if (requests.incrementAndGet() > 1) throw IOException("Status nicht erreichbar.")
                    mapOf(dog.id to DogPairingState(paired = true, pending = true))
                })
        } }
        rule.onNodeWithContentDescription("Faehrte: gekoppelt").assertIsDisplayed()
        rule.onNodeWithText("Neue Kopplung wartet auf Bestaetigung").assertIsDisplayed()
        rule.onNodeWithText("Kopplungsstatus aktualisieren").performClick()
        rule.onNodeWithText("Status nicht erreichbar.").assertIsDisplayed()
        rule.onNodeWithText("Status unbekannt").assertIsDisplayed()
        rule.onNodeWithContentDescription("Faehrte: gekoppelt").assertDoesNotExist()
        rule.onNodeWithContentDescription("Faehrte: nicht gekoppelt").assertDoesNotExist()
    }
}