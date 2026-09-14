package com.example.itellytv

/**
 * iTellyTV — application-level constants.
 *
 * One place for the "magic strings" so we can grep them all when
 * a URL changes. The previous code spread `DEFAULT_TEST_STREAM`
 * across MainActivity and PlaybackActivity; this consolidates.
 */
object AppConfig {

    /**
     * Default IPTV subscription URL.
     *
     * iTellyTV for Android is intended to run on a TV in the user's
     * home network; the IPTV server at [DEFAULT_SUBSCRIPTION_URL]
     * is a private-network box reachable only from the local Wi-Fi.
     *
     * If the user wants to use a different provider, they tap
     * "Reload" on the home screen — the new URL replaces this one
     * for the rest of the session.
     */
    const val DEFAULT_SUBSCRIPTION_URL =
        "http://10.0.0.51:1905/interface.m3u?profile=keren"

    /**
     * HTTP timeouts for subscription refresh (separate from the
     * per-stream HTTP timeouts in PlayerConfig).
     */
    const val SUBSCRIPTION_CONNECT_TIMEOUT_S = 10L
    const val SUBSCRIPTION_READ_TIMEOUT_S = 20L

    /**
     * Public test stream — used only for the M1 "Play test stream"
     * button. Disabled in the production home (M3+).
     */
    const val MUX_PUBLIC_TEST_HLS =
        "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
}
