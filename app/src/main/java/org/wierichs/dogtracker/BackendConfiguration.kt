package org.wierichs.dogtracker

internal object BackendConfiguration {
    val baseUrl: String = BuildConfig.SUPABASE_URL.trimEnd('/')
    val publicKey: String = BuildConfig.SUPABASE_PUBLIC_KEY
    val dogLocationEndpoint: String = "$baseUrl/functions/v1/dog-location"
    val dogRelayEndpoint: String = "$baseUrl/functions/v1/dog-location-relay"
    val relayEndpoint: String = "$baseUrl/functions/v1/location-relay"
}