package com.radius.shared.core

/**
 * Immutable configuration handed to [RadiusCore] at construction time by the platform layer.
 *
 * PART OF THE SHARED CONTRACT. Adding a field here changes the API both clients compile against.
 *
 * Deliberately absent, and must stay absent:
 *  - anything location-shaped: latitude, longitude, bearing, heading, geofence, map tile source.
 *    Safety invariant 1. `cityGeohash5` is the ONLY coarse-location concept in the product and it
 *    lives on server-issued profile data, not in client config.
 *  - any stable device identifier. Safety invariant 4.
 *  - secrets. Keys live in Keystore / Keychain and are never passed through here as raw material.
 */
public class RadiusConfig(
    /** Base URL of the Radius API. Certificate pinning is enforced by the platform HTTP stack. */
    public val apiBaseUrl: String,
    /**
     * SHA-256 SPKI pins for [apiBaseUrl], base64. Empty list is only legal in a debug build and
     * the platform layer is responsible for refusing to ship an unpinned release.
     */
    public val certificatePinsBase64: List<String>,
    /** Build channel. Gates debug-only affordances; never gates a safety invariant. */
    public val channel: Channel,
) {
    public enum class Channel {
        DEBUG,
        INTERNAL,
        RELEASE,
    }
}
