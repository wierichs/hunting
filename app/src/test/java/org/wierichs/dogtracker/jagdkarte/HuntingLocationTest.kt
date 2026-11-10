package org.wierichs.dogtracker.jagdkarte

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class HuntingLocationTest {
    private val now = Instant.parse("2026-11-02T10:00:00Z")
    private val boundary = JSONObject("""{
        "type":"Polygon","coordinates":[
            [[6,50],[8,50],[8,52],[6,52],[6,50]],
            [[6.5,50.5],[6.5,51],[7,51],[7,50.5],[6.5,50.5]]
        ]
    }""")

    @Test fun polygonUsesLongitudeLatitudeAndExcludesHoles() {
        assertTrue(locationInsideBoundary(boundary, 51.5, 7.5))
        assertTrue(locationInsideBoundary(boundary, 50.0, 6.0))
        assertFalse(locationInsideBoundary(boundary, 50.75, 6.75))
        assertFalse(locationInsideBoundary(boundary, 7.5, 51.5))
        assertFalse(locationInsideBoundary(boundary, 53.0, 7.0))
    }

    @Test fun multiPolygonChecksAllParts() {
        val multi = JSONObject("""{"type":"MultiPolygon","coordinates":[
            [[[6,50],[7,50],[7,51],[6,51],[6,50]]],
            [[[8,52],[9,52],[9,53],[8,53],[8,52]]]
        ]}""")
        assertTrue(locationInsideBoundary(multi, 52.5, 8.5))
        assertFalse(locationInsideBoundary(multi, 51.5, 7.5))
    }

    @Test fun missingMalformedOrNonPolygonBoundariesFailClosed() {
        assertFalse(locationInsideBoundary(null, 51.0, 7.0))
        assertFalse(locationInsideBoundary(JSONObject("""{"type":"Point","coordinates":[7,51]}"""), 51.0, 7.0))
        assertFalse(locationInsideBoundary(JSONObject("""{"type":"Polygon","coordinates":[]}"""), 51.0, 7.0))
        assertFalse(locationInsideBoundary(boundary, Double.NaN, 7.0))
    }

    @Test fun participantPositionsExpireAfterNinetySeconds() {
        val point = HuntingMapPoint(51.0, 7.0, "Gast", "", MapPointKind.PARTICIPANT, now.minusSeconds(89))
        assertTrue(point.visibleAt(now))
        assertFalse(point.copy(timestamp = now.minusSeconds(90)).visibleAt(now))
        assertFalse(point.copy(timestamp = null).visibleAt(now))
        assertFalse(point.copy(timestamp = now.plusSeconds(60)).visibleAt(now))
    }

    @Test fun dogPositionsOutsideBoundaryRemainVisibleButBecomeStale() {
        val point = HuntingMapPoint(54.0, 10.0, "Hund", "", MapPointKind.DOG, now.minusSeconds(121))
        assertTrue(point.visibleAt(now))
        assertTrue(point.staleAt(now))
        assertFalse(point.copy(timestamp = now.minusSeconds(119)).staleAt(now))
        assertTrue(point.copy(timestamp = null).staleAt(now))
    }

    @Test fun expiredMarkersAndInvalidCoordinatesAreHidden() {
        val point = HuntingMapPoint(51.0, 7.0, "Anschuss", "", MapPointKind.FACILITY, expiresAt = now)
        assertFalse(point.visibleAt(now))
        assertTrue(point.copy(expiresAt = now.plusSeconds(1)).visibleAt(now))
        assertFalse(point.copy(latitude = 91.0, expiresAt = null).visibleAt(now))
    }

    @Test fun participantRpcUsesItsActualColumnNamesAndSafeFallbacks() {
        val names = participantNames(JSONArray("""[
            {"participant_user_id":"owner","display_name":"Anna","participant_email":"anna@example.org"},
            {"participant_user_id":"guest","display_name":null,"participant_email":"gast@example.org"},
            {"participant_user_id":"editor","display_name":"","participant_email":null}
        ]"""))
        assertEquals("Anna", names["owner"])
        assertEquals("gast@example.org", names["guest"])
        assertEquals("Teilnehmer", names["editor"])
    }

    @Test fun participantRolesMapToPwaRoleIconsAndUnknownRolesDefaultToMember() {
        val roles = participantRoles(JSONArray("""
        [
            {"participant_user_id":"owner","participant_role":"owner"},
            {"participant_user_id":"editor","participant_role":"editor"},
            {"participant_user_id":"guest","participant_role":"guest"},
            {"participant_user_id":"other","participant_role":"unexpected"}
        ]
        """))
        assertEquals(HuntingParticipantRole.OWNER, roles["owner"])
        assertEquals(HuntingParticipantRole.EDITOR, roles["editor"])
        assertEquals(HuntingParticipantRole.GUEST, roles["guest"])
        assertEquals(HuntingParticipantRole.GUEST, roles["other"])
        assertEquals("location-owner.svg", participantMarkerIconAsset(roles.getValue("owner")))
        assertEquals("location-editor.svg", participantMarkerIconAsset(roles.getValue("editor")))
        assertEquals("location-member.svg", participantMarkerIconAsset(roles.getValue("guest")))
        assertEquals("location-dog.svg", dogLocationIconAsset(false))
        assertEquals("location-dog-stale.svg", dogLocationIconAsset(true))
    }

    @Test fun ownDisplayNameWriteMatchesPwaUpsertContract() {
        val userId = "c76274b7-e21a-440e-ad0d-c51c6f6345e0"
        val body = displayNameWriteBody(userId, " Anna Beispiel ")
        assertEquals(userId, body.getString("user_id"))
        assertEquals("Anna Beispiel", body.getString("display_name"))
        assertTrue(body.has("updated_at"))
        assertEquals(JSONObject.NULL, displayNameWriteBody(userId, "  ").opt("display_name"))
        assertThrows(IllegalArgumentException::class.java) { displayNameWriteBody(userId, "a".repeat(61)) }
        assertThrows(IllegalArgumentException::class.java) { displayNameWriteBody("invalid", "Anna") }
    }

    @Test fun profileInvitationRequestMatchesPwaRpcRolesAndEmailNormalization() {
        val profileId = "c76274b7-e21a-440e-ad0d-c51c6f6345e0"
        val guest = inviteMemberRequestBody(profileId, " Guest@Example.org ", HuntingProfileMemberRole.GUEST)
        assertEquals(profileId, guest.getString("p_profile_id"))
        assertEquals("guest@example.org", guest.getString("p_email"))
        assertEquals("guest", guest.getString("p_role"))
        assertEquals("editor", inviteMemberRequestBody(profileId, "editor@example.org",
            HuntingProfileMemberRole.EDITOR).getString("p_role"))
        assertThrows(IllegalArgumentException::class.java) {
            inviteMemberRequestBody("invalid", "guest@example.org", HuntingProfileMemberRole.GUEST)
        }
        assertThrows(IllegalArgumentException::class.java) {
            inviteMemberRequestBody(profileId, "invalid email", HuntingProfileMemberRole.GUEST)
        }
        assertThrows(IllegalArgumentException::class.java) {
            inviteMemberRequestBody(profileId, "guest@example", HuntingProfileMemberRole.GUEST)
        }
    }

    @Test fun onlyProfileOwnerCanDeleteAProfile() {
        val profile = HuntingProfile("revier", "Testrevier", "owner-id", false, null)
        assertTrue(canDeleteHuntingProfile(profile, "owner-id"))
        assertFalse(canDeleteHuntingProfile(profile, "editor-id"))
        assertFalse(canDeleteHuntingProfile(profile, ""))
    }

    @Test fun guestLinkTokenUrlAndFragmentMatchPwaContract() {
        val token = createGuestLinkToken()
        assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(token))
        assertEquals("https://wierichs.github.io/Jagdkarte/#gast=$token", guestLinkUrl(token))
        assertEquals(token, guestLinkTokenFromFragment("gast=$token"))
        assertEquals(token, guestLinkTokenFromFragment("other=value&gast=$token"))
        assertNull(guestLinkTokenFromFragment("gast=invalid"))
        assertNull(guestLinkTokenFromFragment("other=$token"))
    }

    @Test fun guestLinkTargetRequiresValidProfileAndMarkerIds() {
        val profileId = "c76274b7-e21a-440e-ad0d-c51c6f6345e0"
        val markerId = "c76274b7-e21a-440e-ad0d-c51c6f6345e1"
        assertEquals(GuestLinkTarget(profileId, markerId), guestLinkTargetFromJson(JSONObject()
            .put("profile_id", profileId).put("marker_id", markerId)))
        assertThrows(IllegalArgumentException::class.java) {
            guestLinkTargetFromJson(JSONObject().put("profile_id", profileId).put("marker_id", "bad"))
        }
    }

    @Test fun sharedProfileOwnerEmailsMapToPwaStyleSelectionLabels() {
        val sharedId = "c76274b7-e21a-440e-ad0d-c51c6f6345e0"
        val ownerEmails = ownerEmailsByProfileId(JSONArray("""[
            {"profile_id":"$sharedId","owner_email":" owner@example.org "},
            {"profile_id":"missing","owner_email":null}
        ]"""))
        val shared = HuntingProfile(sharedId, "Waldrevier", "12345678-1234-4234-8234-123456789012", false, null,
            ownerEmails[sharedId])
        val missingEmail = shared.copy(ownerEmail = null)
        val own = shared.copy(ownerId = "current-user", ownerEmail = null)
        assertEquals("Waldrevier (Geteilt) @owner@example.org", profileSelectionLabel(shared, "current-user"))
        assertEquals("Waldrevier (Geteilt) @12345678", profileSelectionLabel(missingEmail, "current-user"))
        assertEquals("Waldrevier (Eigen)", profileSelectionLabel(own, "current-user"))
    }
}