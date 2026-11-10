package org.wierichs.dogtracker

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import org.wierichs.dogtracker.jagdkarte.JWD_EXPORT_MARKER_TYPE_LABELS
import org.wierichs.dogtracker.jagdkarte.defaultJwdMarkerTypeMapping

internal object TrackerPreferences {
    private const val FILE_NAME = "tracker_settings"
    private const val KEY_INTERVAL_SECONDS = "interval_seconds"
    private const val KEY_MARKER_ICON_SIZE = "marker_icon_size"
    private const val KEY_HUNTING_MAP_STYLE = "hunting_map_style"
    private const val KEY_JWD_MARKER_TYPE_MAPPING = "jwd_marker_type_mapping"
    private const val KEY_HIDDEN_HUNTING_PROFILES = "hidden_hunting_profiles"
    private const val KEY_IS_TRACKING = "is_tracking"
    private const val KEY_STATUS = "status"
    private const val KEY_RELAY_DEVICE_ID = "relay_device_id"
    private const val KEY_RELAY_ENCRYPTION_KEY_ID = "relay_encryption_key_id"
    private const val KEY_RELAY_ENCRYPTION_PUBLIC_KEY = "relay_encryption_public_key"
    private const val KEY_RELAY_ENABLED = "relay_enabled"
    val ENDPOINT = BackendConfiguration.dogLocationEndpoint
    val RELAY_ENDPOINT = BackendConfiguration.dogRelayEndpoint

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun endpoint(@Suppress("UNUSED_PARAMETER") context: Context): String = ENDPOINT

    fun intervalSeconds(context: Context): Long =
        preferences(context).getLong(KEY_INTERVAL_SECONDS, 60L)

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
        require(userId.isNotBlank())
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

    private val MARKER_ICON_SIZES = setOf("small", "standard", "large")
}