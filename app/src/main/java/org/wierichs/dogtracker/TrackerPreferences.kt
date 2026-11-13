package org.wierichs.dogtracker

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import org.wierichs.dogtracker.jagdkarte.JWD_EXPORT_MARKER_TYPE_LABELS
import org.wierichs.dogtracker.jagdkarte.defaultJwdMarkerTypeMapping

internal data class HuntingMapCamera(val latitude: Double, val longitude: Double, val zoom: Double)

internal object TrackerPreferences {
    private const val FILE_NAME = "tracker_settings"
    private const val KEY_INTERVAL_SECONDS = "interval_seconds"
    private const val KEY_MARKER_ICON_SIZE = "marker_icon_size"
    private const val KEY_HUNTING_MAP_STYLE = "hunting_map_style"
    private const val KEY_JWD_MARKER_TYPE_MAPPING = "jwd_marker_type_mapping"
    private const val KEY_HIDDEN_HUNTING_PROFILES = "hidden_hunting_profiles"
    private const val KEY_IS_TRACKING = "is_tracking"
    private const val KEY_STATUS = "status"
    private const val KEY_LOCATION_UPLOAD_RETRY_AFTER = "location_upload_retry_after"
    private const val KEY_RELAY_DEVICE_ID = "relay_device_id"
    private const val KEY_RELAY_ENCRYPTION_KEY_ID = "relay_encryption_key_id"
    private const val KEY_RELAY_ENCRYPTION_PUBLIC_KEY = "relay_encryption_public_key"
    private const val KEY_RELAY_ENABLED = "relay_enabled"
    private const val KEY_LAST_TAB_TRACKER = "last_tab_tracker"
    private const val KEY_LAST_PROFILE_PREFIX = "last_profile:"
    private const val KEY_LOCATION_SHARE_PREFIX = "location_share:"
    private const val KEY_MAP_CAMERA_PREFIX = "map_camera:"
    private const val KEY_MAP_FULL_SCREEN = "map_full_screen"
    private const val KEY_PERMISSION_INTRODUCTION = "permission_introduction"
    private const val KEY_HUNTING_FUNCTIONS_CONSENT = "hunting_functions_consent"
    private const val KEY_AUTOMATIC_HUNTING_CONSENT = "automatic_hunting_consent"
    private const val KEY_PARTICIPATION_PREFIX = "hunting_participation:"
    val ENDPOINT = BackendConfiguration.dogLocationEndpoint
    val RELAY_ENDPOINT = BackendConfiguration.dogRelayEndpoint

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun endpoint(@Suppress("UNUSED_PARAMETER") context: Context): String = ENDPOINT

    fun intervalSeconds(context: Context): Long =
        preferences(context).getLong(KEY_INTERVAL_SECONDS, 15L)

    fun isTracking(context: Context): Boolean =
        preferences(context).getBoolean(KEY_IS_TRACKING, false)

    fun status(context: Context): String =
        preferences(context).getString(KEY_STATUS, "Noch nicht gestartet.") ?: "Noch nicht gestartet."

    fun markerIconSize(context: Context): String = preferences(context)
        .getString(KEY_MARKER_ICON_SIZE, "standard")
        ?.takeIf { it in MARKER_ICON_SIZES } ?: "standard"

    fun setMarkerIconSize(context: Context, size: String) {
        require(size in MARKER_ICON_SIZES)
        preferences(context).edit { putString(KEY_MARKER_ICON_SIZE, size) }
    }

    fun huntingMapStyle(context: Context): String = preferences(context).getString(KEY_HUNTING_MAP_STYLE, "OPEN_STREET_MAP")
        ?.let { if (it == "SATELLITE") "AERIAL" else it } ?: "OPEN_STREET_MAP"

    fun setHuntingMapStyle(context: Context, style: String) {
        require(style in setOf("OPEN_STREET_MAP", "AERIAL", "OFFLINE"))
        preferences(context).edit { putString(KEY_HUNTING_MAP_STYLE, style) }
    }

    fun jwdMarkerTypeMappings(context: Context): Map<String, String> {
        val defaults = defaultJwdMarkerTypeMapping()
        val unsupportedIds = defaults.keys - JWD_EXPORT_MARKER_TYPE_LABELS.keys
        val saved = runCatching {
            JSONObject(preferences(context).getString(KEY_JWD_MARKER_TYPE_MAPPING, "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        return defaults.mapValues { (typeId, defaultTarget) ->
            if (typeId !in unsupportedIds) defaultTarget
            else saved.optString(typeId).takeIf { it in JWD_EXPORT_MARKER_TYPE_LABELS } ?: defaultTarget
        }
    }

    fun setJwdMarkerTypeMapping(context: Context, typeId: String, exportTypeId: String) {
        val defaults = defaultJwdMarkerTypeMapping()
        require(typeId in defaults && typeId !in JWD_EXPORT_MARKER_TYPE_LABELS) {
            "Dieser Markertyp hat keine JWD-Zuordnungseinstellung."
        }
        require(exportTypeId in JWD_EXPORT_MARKER_TYPE_LABELS) { "Ungueltiger JWD-Markertyp." }
        val mapping = runCatching {
            JSONObject(preferences(context).getString(KEY_JWD_MARKER_TYPE_MAPPING, "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        if (exportTypeId == defaults[typeId]) mapping.remove(typeId) else mapping.put(typeId, exportTypeId)
        preferences(context).edit { putString(KEY_JWD_MARKER_TYPE_MAPPING, mapping.toString()) }
    }

    fun hiddenHuntingProfileIds(context: Context, userId: String): Set<String> {
        if (userId.isBlank()) return emptySet()
        return preferences(context).getStringSet("$KEY_HIDDEN_HUNTING_PROFILES:$userId", emptySet())?.toSet()
            ?: emptySet()
    }

    fun setHuntingProfileHidden(context: Context, userId: String, profileId: String, hidden: Boolean) {
        require(userId.isNotBlank() && profileId.isNotBlank())
        val key = "$KEY_HIDDEN_HUNTING_PROFILES:$userId"
        val hiddenIds = preferences(context).getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (hidden) hiddenIds.add(profileId) else hiddenIds.remove(profileId)
        preferences(context).edit {
            if (hiddenIds.isEmpty()) remove(key) else putStringSet(key, hiddenIds)
        }
    }

    fun deviceToken(context: Context): String? = DeviceTokenStorage.read(context)

    fun saveDeviceToken(context: Context, token: String) {
        if (deviceToken(context) != token) {
            preferences(context).edit { remove(KEY_RELAY_DEVICE_ID) }
        }
        DeviceTokenStorage.write(context, token)
    }

    fun relayDeviceId(context: Context): String? =
        preferences(context).getString(KEY_RELAY_DEVICE_ID, null)

    fun relayEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_RELAY_ENABLED, false)

    fun lastTabIsTracker(context: Context): Boolean =
        preferences(context).getBoolean(KEY_LAST_TAB_TRACKER, false)

    fun permissionIntroductionCompleted(context: Context): Boolean =
        !requiresAutomaticHuntingConsent(context) && preferences(context).getBoolean(KEY_PERMISSION_INTRODUCTION,
            preferences(context).contains(KEY_LAST_TAB_TRACKER))

    fun completePermissionIntroduction(context: Context) {
        preferences(context).edit { putBoolean(KEY_PERMISSION_INTRODUCTION, true) }
    }

    fun huntingFunctionsConsented(context: Context): Boolean =
        preferences(context).getBoolean(KEY_HUNTING_FUNCTIONS_CONSENT, false) &&
            preferences(context).getBoolean(KEY_AUTOMATIC_HUNTING_CONSENT, false)

    fun requiresAutomaticHuntingConsent(context: Context): Boolean =
        preferences(context).getBoolean(KEY_HUNTING_FUNCTIONS_CONSENT, false) &&
            !preferences(context).getBoolean(KEY_AUTOMATIC_HUNTING_CONSENT, false)

    fun setHuntingFunctionsConsent(context: Context, enabled: Boolean) {
        preferences(context).edit {
            putBoolean(KEY_HUNTING_FUNCTIONS_CONSENT, enabled)
            putBoolean(KEY_AUTOMATIC_HUNTING_CONSENT, enabled)
            putBoolean(KEY_RELAY_ENABLED, enabled)
        }
    }

    fun huntingParticipationConsented(context: Context, userId: String, profileId: String): Boolean {
        if (userId.isBlank() || profileId.isBlank()) return false
        return preferences(context).getBoolean("$KEY_PARTICIPATION_PREFIX$userId:$profileId", false)
    }

    fun setHuntingParticipationConsent(context: Context, userId: String, profileId: String, enabled: Boolean) {
        require(userId.isNotBlank() && profileId.isNotBlank())
        preferences(context).edit { putBoolean("$KEY_PARTICIPATION_PREFIX$userId:$profileId", enabled) }
    }

    fun setLastTabIsTracker(context: Context, selected: Boolean) {
        preferences(context).edit { putBoolean(KEY_LAST_TAB_TRACKER, selected) }
    }

    fun lastHuntingProfileId(context: Context, userId: String): String? {
        if (userId.isBlank()) return null
        return preferences(context).getString("$KEY_LAST_PROFILE_PREFIX$userId", null)
    }

    fun setLastHuntingProfileId(context: Context, userId: String, profileId: String?) {
        if (userId.isBlank()) return
        preferences(context).edit {
            val key = "$KEY_LAST_PROFILE_PREFIX$userId"
            if (profileId.isNullOrBlank()) remove(key) else putString(key, profileId)
        }
    }

    fun locationShareConsented(context: Context, userId: String, profileId: String): Boolean {
        if (userId.isBlank() || profileId.isBlank()) return false
        return preferences(context).getBoolean("$KEY_LOCATION_SHARE_PREFIX$userId:$profileId", false)
    }

    fun setLocationShareConsent(context: Context, userId: String, profileId: String, enabled: Boolean) {
        require(userId.isNotBlank() && profileId.isNotBlank())
        preferences(context).edit {
            putBoolean("$KEY_LOCATION_SHARE_PREFIX$userId:$profileId", enabled)
        }
    }

    fun mapCamera(context: Context, userId: String, profileId: String): HuntingMapCamera? {
        if (profileId.isBlank()) return null
        val accountId = userId.ifBlank { "anonymous" }
        val json = runCatching {
            JSONObject(preferences(context).getString("$KEY_MAP_CAMERA_PREFIX$accountId:$profileId", null) ?: return null)
        }.getOrNull() ?: return null
        val latitude = json.optDouble("latitude", Double.NaN)
        val longitude = json.optDouble("longitude", Double.NaN)
        val zoom = json.optDouble("zoom", Double.NaN)
        return HuntingMapCamera(latitude, longitude, zoom).takeIf {
            latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0 &&
                zoom.isFinite() && zoom in 3.0..20.0
        }
    }

    fun setMapCamera(context: Context, userId: String, profileId: String, camera: HuntingMapCamera) {
        require(profileId.isNotBlank() && camera.latitude.isFinite() && camera.latitude in -90.0..90.0 &&
            camera.longitude.isFinite() && camera.longitude in -180.0..180.0 && camera.zoom.isFinite() && camera.zoom in 3.0..20.0)
        val accountId = userId.ifBlank { "anonymous" }
        preferences(context).edit {
            putString("$KEY_MAP_CAMERA_PREFIX$accountId:$profileId", JSONObject()
                .put("latitude", camera.latitude).put("longitude", camera.longitude).put("zoom", camera.zoom).toString())
        }
    }

    fun mapFullScreen(context: Context): Boolean = preferences(context).getBoolean(KEY_MAP_FULL_SCREEN, false)

    fun setMapFullScreen(context: Context, enabled: Boolean) {
        preferences(context).edit { putBoolean(KEY_MAP_FULL_SCREEN, enabled) }
    }

    fun setRelayEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit { putBoolean(KEY_RELAY_ENABLED, enabled) }
    }

    fun saveRelayDeviceId(context: Context, deviceId: String) {
        preferences(context).edit { putString(KEY_RELAY_DEVICE_ID, deviceId) }
    }

    fun relayEncryptionKeyId(context: Context): String? =
        preferences(context).getString(KEY_RELAY_ENCRYPTION_KEY_ID, null)

    fun relayEncryptionPublicKey(context: Context): String? =
        preferences(context).getString(KEY_RELAY_ENCRYPTION_PUBLIC_KEY, null)

    fun saveRelayEncryptionKey(context: Context, keyId: String, publicKey: String) {
        preferences(context).edit {
            putString(KEY_RELAY_ENCRYPTION_KEY_ID, keyId)
            putString(KEY_RELAY_ENCRYPTION_PUBLIC_KEY, publicKey)
        }
    }

    fun saveConfiguration(context: Context, intervalSeconds: Long) {
        preferences(context).edit {
            putLong(KEY_INTERVAL_SECONDS, intervalSeconds)
        }
    }

    fun setTracking(context: Context, isTracking: Boolean) {
        preferences(context).edit { putBoolean(KEY_IS_TRACKING, isTracking) }
    }

    fun setStatus(context: Context, status: String) {
        preferences(context).edit { putString(KEY_STATUS, status) }
    }

    fun locationUploadRetryAfter(context: Context): Long? =
        preferences(context).getLong(KEY_LOCATION_UPLOAD_RETRY_AFTER, -1L).takeIf { it >= 0L }

    fun setLocationUploadRetryAfter(context: Context, retryAfterAtEpochMillis: Long): Boolean {
        require(retryAfterAtEpochMillis >= 0L)
        return preferences(context).edit()
            .putLong(KEY_LOCATION_UPLOAD_RETRY_AFTER, retryAfterAtEpochMillis)
            .commit()
    }

    fun clearLocationUploadRetryAfter(context: Context): Boolean =
        preferences(context).edit().remove(KEY_LOCATION_UPLOAD_RETRY_AFTER).commit()

    private val MARKER_ICON_SIZES = setOf("small", "standard", "large")
}