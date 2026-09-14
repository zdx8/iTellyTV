package com.example.itellytv.player

/**
 * iTellyTV playback tuning constants.
 *
 * These mirror the values iTelly-macOS ships with after the 2026-09-14
 * defect-fix pass. Keep them here (not inlined) so a `--diagnose`
 * self-test can dump them, and so users can override via settings.
 */
object PlayerConfig {

    // ---- Live tuning (HLS/RTSP live) ----
    /** Minimum buffer (ms) before the first frame can be drawn. */
    const val LIVE_MIN_BUFFER_MS = 1_500L

    /** Maximum buffer (ms) before ExoPlayer stops refilling. */
    const val LIVE_MAX_BUFFER_MS = 5_000L

    /**
     * ms of buffer required to *start* playback on a live feed. Live
     * is a continuous stream so we don't need much; 500ms gets the
     * first frame out quickly. (Previously aliased to
     * [LIVE_BUFFER_FOR_REBUFFER_MS], which was a bug: that value
     * is the *rebuffer* threshold, which on a live feed should be
     * longer than the start threshold so we don't go into a rebuffer
     * loop during normal playback.)
     */
    const val LIVE_BUFFER_FOR_PLAYBACK_MS = 500L

    /** ms of buffer required to resume after underrun (live). */
    const val LIVE_BUFFER_FOR_REBUFFER_MS = 1_000L

    /** Prefer dropping frames over pausing playback on underrun. */
    const val LIVE_DROP_FRAMES_ON_UNDERRUN = true

    // ---- VOD (mp4 / progressive) ----
    const val VOD_MIN_BUFFER_MS = 4_000L
    const val VOD_MAX_BUFFER_MS = 20_000L
    const val VOD_BUFFER_FOR_PLAYBACK_MS = 1_500L
    const val VOD_BUFFER_FOR_REBUFFER_MS = 2_000L

    // ---- Reconnect (the "fixed" defect from iTelly-macOS README) ----
    /** 1s / 2s / 4s exponential backoff. */
    val RECONNECT_BACKOFFS_MS = longArrayOf(1_000L, 2_000L, 4_000L)

    /** After 3 attempts the player surfaces a terminal error. */
    const val MAX_RECONNECT_ATTEMPTS = 3

    // ---- Watchdog ----
    /** If the playhead doesn't advance in 15s while supposedly
     *  playing, we treat it as a stall and either reconnect (live)
     *  or finish (VOD end). */
    const val STALL_WATCHDOG_MS = 15_000L

    /** Polling interval for the watchdog. */
    const val PROGRESS_POLL_MS = 1_000L

    // ---- Network ----
    const val HTTP_CONNECT_TIMEOUT_MS = 8_000
    const val HTTP_READ_TIMEOUT_MS = 8_000

    /**
     * Default User-Agent. The actual value is built in [userAgent] so
     * the version name stays in sync with [BuildConfig].
     */
    private const val USER_AGENT_PREFIX = "iTellyTV/"

    /**
     * The full User-Agent string. Composed lazily at first use; the
     * `versionName` from `BuildConfig` is appended so the UA tracks
     * release versioning.
     */
    val userAgent: String
        get() = USER_AGENT_PREFIX + com.example.itellytv.BuildConfig.VERSION_NAME + " (Android TV 13)"

    // ---- UI ----
    /** D-pad LEFT / RIGHT seek step (ms). 10s matches iTelly-macOS. */
    const val SEEK_STEP_MS = 10_000L
}
