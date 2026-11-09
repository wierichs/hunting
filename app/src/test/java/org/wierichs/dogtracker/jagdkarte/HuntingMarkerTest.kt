package org.wierichs.dogtracker.jagdkarte

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HuntingMarkerTest {
    private val profile = HuntingProfile("revier", "Testrevier", "owner", false, JSONObject("""{
        "type":"Polygon","coordinates":[
            [[6,50],[8,50],[8,52],[6,52],[6,50]],
            [[6.5,50.5],[6.5,51],[7,51],[7,50.5],[6.5,50.5]]
        ]
    }"""))
    private val inside = MarkerCoordinates(51.5, 7.5)
    private val outside = MarkerCoordinates(54.0, 10.0)
    private val hole = MarkerCoordinates(50.75, 6.75)

    private fun draft(type: HuntingMarkerType) = HuntingMarkerDraft(type, " Test ", " Notiz ", inside)

    @Test fun markerCatalogMatchesPwaIdsLabelsAndAssets() {
        assertEquals(listOf("hochsitz", "bock", "erdsitz", "salzlecke", "huette", "leiter", "kanzel", "wagen",
            "kirrung", "falle", "wildkamera", "anschuss", "wildsichtung", "standplatz", "sonstiges"),
            HuntingMarkerType.entries.map { it.id })
        assertEquals("Jagdhu\u00fctte", HuntingMarkerType.HUETTE.label)
        assertEquals("drueckjagdbock.svg", HuntingMarkerType.BOCK.iconAsset)
        assertEquals(HuntingMarkerType.SONSTIGES, HuntingMarkerType.fromId("unbekannt"))
    }

    @Test fun markerIconSizesMatchPwaScalesAndDefaultToStandard() {
        assertEquals(listOf(0.8f, 1f, 1.25f), HuntingMarkerSize.entries.map { it.scale })
        assertEquals(HuntingMarkerSize.STANDARD, HuntingMarkerSize.fromId(null))
        assertEquals(HuntingMarkerSize.STANDARD, HuntingMarkerSize.fromId("invalid"))
    }

    @Test fun guestsCanOnlyCreateAnschussAndWildsichtung() {
        assertEquals(setOf("anschuss", "wildsichtung"),
            HuntingMarkerType.entries.filter { it.canCreate(false) }.map { it.id }.toSet())
        assertTrue(HuntingMarkerType.entries.all { it.canCreate(true) })
        assertThrows(IllegalStateException::class.java) {
            markerWriteRequest(profile, false, "guest", draft(HuntingMarkerType.HOCHSITZ), inside)
        }
        assertThrows(IllegalStateException::class.java) {
            markerWriteRequest(profile, false, "guest", draft(HuntingMarkerType.STANDPLATZ), null)
        }
    }

    @Test fun temporaryReportsUseRpcWithSeparateTargetAndGpsCoordinates() {
        val target = MarkerCoordinates(51.6, 7.6)
        for (type in listOf(HuntingMarkerType.ANSCHUSS, HuntingMarkerType.WILDSICHTUNG)) {
            val request = markerWriteRequest(profile, false, "guest", draft(type).copy(coordinates = target), inside)
            assertEquals("rest/v1/rpc/create_temporary_hunting_marker", request.path)
            assertEquals(type.id, request.body.getString("p_type_id"))
            assertEquals(51.6, request.body.getDouble("p_latitude"), 0.0)
            assertEquals(7.6, request.body.getDouble("p_longitude"), 0.0)
            assertEquals(51.5, request.body.getDouble("p_user_latitude"), 0.0)
            assertEquals(7.5, request.body.getDouble("p_user_longitude"), 0.0)
            assertFalse(request.body.has("expires_at"))
        }
    }

    @Test fun reportsRequireGpsInsideTheBoundaryEvenForEditors() {
        for (canEdit in listOf(false, true)) {
            for (position in listOf(null, outside, hole)) {
                assertThrows(IllegalStateException::class.java) {
                    markerWriteRequest(profile, canEdit, "member", draft(HuntingMarkerType.ANSCHUSS), position)
                }
            }
        }
    }

    @Test fun standplatzUsesRpcWithoutGpsAndWithoutDependingOnHuntingMode() {
        val request = markerWriteRequest(profile, true, "editor", draft(HuntingMarkerType.STANDPLATZ), null)
        assertEquals("rest/v1/rpc/create_temporary_hunting_marker", request.path)
        assertTrue(request.body.isNull("p_user_latitude"))
        assertTrue(request.body.isNull("p_user_longitude"))
    }

    @Test fun permanentFacilitiesUseInsertAndNeverSupplyExpiry() {
        val request = markerWriteRequest(profile, true, "editor", draft(HuntingMarkerType.HOCHSITZ), null)
        assertEquals("rest/v1/hunting_markers", request.path)
        assertEquals("editor", request.body.getString("created_by"))
        assertEquals("Test", request.body.getString("title"))
        assertEquals("Notiz", request.body.getString("notes"))
        assertEquals(51.5, request.body.getDouble("latitude"), 0.0)
        assertEquals(7.5, request.body.getDouble("longitude"), 0.0)
        assertFalse(request.body.has("expires_at"))
        assertFalse(request.body.has("p_user_latitude"))
    }

    @Test fun missingBoundaryOutsideTargetsHolesAndInvalidCoordinatesFailClosed() {
        for (position in listOf(outside, hole, MarkerCoordinates(Double.NaN, 7.5))) {
            assertThrows(IllegalArgumentException::class.java) {
                markerWriteRequest(profile, true, "owner", draft(HuntingMarkerType.HOCHSITZ).copy(coordinates = position), null)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            markerWriteRequest(profile.copy(boundary = null), true, "owner", draft(HuntingMarkerType.HOCHSITZ), null)
        }
    }

    @Test fun fieldLimitsAndDefaultTitleMatchServerContract() {
        val draft = draft(HuntingMarkerType.HOCHSITZ)
        assertEquals("Hochsitz", markerWriteRequest(profile, true, "owner", draft.copy(title = "  "), null).body.getString("title"))
        markerWriteRequest(profile, true, "owner", draft.copy(title = "a".repeat(80), notes = "a".repeat(300)), null)
        assertThrows(IllegalArgumentException::class.java) {
            markerWriteRequest(profile, true, "owner", draft.copy(title = "a".repeat(81)), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            markerWriteRequest(profile, true, "owner", draft.copy(notes = "a".repeat(301)), null)
        }
    }

    @Test fun markerUpdatesAreOwnerEditorOnlyAndPreserveTypeAndCoordinates() {
        val markerId = "c76274b7-e21a-440e-ad0d-c51c6f6345e0"
        val request = markerUpdateRequest(profile, true, markerId, " Neuer Sitz ", " Neue Notiz ")
        assertEquals("rest/v1/hunting_markers?id=eq.$markerId&profile_id=eq.${profile.id}", request.path)
        assertEquals("Neuer Sitz", request.body.getString("title"))
        assertEquals("Neue Notiz", request.body.getString("notes"))
        assertEquals(2, request.body.length())
        assertThrows(IllegalStateException::class.java) {
            markerUpdateRequest(profile, false, markerId, "Titel", "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            markerUpdateRequest(profile, true, "not-a-uuid", "Titel", "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            markerUpdateRequest(profile, true, markerId, " ", "")
        }
    }

    @Test fun editorsCanDeleteAnyMarkerAndGuestsOnlyTheirOwnTemporaryMarkers() {
        val markerId = "c76274b7-e21a-440e-ad0d-c51c6f6345e0"
        val ownTemporary = HuntingMapPoint(51.5, 7.5, "Anschuss", "", MapPointKind.FACILITY,
            markerId = markerId, markerTypeId = "anschuss", createdBy = "guest")
        assertEquals("rest/v1/hunting_markers?id=eq.$markerId&profile_id=eq.${profile.id}",
            markerDeletePath(profile, true, "editor", ownTemporary.copy(createdBy = "another-member")))
        assertEquals("rest/v1/hunting_markers?id=eq.$markerId&profile_id=eq.${profile.id}",
            markerDeletePath(profile, false, "guest", ownTemporary))
        assertThrows(IllegalStateException::class.java) {
            markerDeletePath(profile, false, "other-guest", ownTemporary)
        }
        for (markerType in listOf("hochsitz", "sonstiges")) {
            assertThrows(IllegalStateException::class.java) {
                markerDeletePath(profile, false, "guest", ownTemporary.copy(markerTypeId = markerType))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            markerDeletePath(profile, true, "editor", ownTemporary.copy(markerId = "invalid"))
        }
    }

    @Test fun markerMoveUsesAuthorizedRpcAndRequiresCurrentLocationInsideBoundary() {
        val markerId = "c76274b7-e21a-440e-ad0d-c51c6f6345e0"
        val request = markerMoveRequest(profile, true, markerId, inside)
        assertEquals("rest/v1/rpc/move_hunting_marker_to_location", request.path)
        assertEquals(markerId, request.body.getString("p_marker_id"))
        assertEquals(inside.latitude, request.body.getDouble("p_latitude"), 0.0)
        assertEquals(inside.longitude, request.body.getDouble("p_longitude"), 0.0)
        assertThrows(IllegalStateException::class.java) {
            markerMoveRequest(profile, false, markerId, inside)
        }
        for (coordinates in listOf(outside, hole, MarkerCoordinates(Double.NaN, 7.5))) {
            assertThrows(IllegalArgumentException::class.java) {
                markerMoveRequest(profile, true, markerId, coordinates)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            markerMoveRequest(profile, true, "invalid", inside)
        }
    }
}