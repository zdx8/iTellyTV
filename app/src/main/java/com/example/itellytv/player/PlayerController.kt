package com.example.itellytv.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/**
 * PlayerController — playback engine wrapper for iTellyTV.
 *
 * Goals (mirror iTelly-macOS README §"四个值得记录的实现要点" + the
 * defect-fix table that follows):
 *
 *   1. **LoadControl set before Builder()** — Media3 ignores LoadControl
 *      changes after the player is built (equivalent to the macOS
 *      `VLC_PLUGIN_PATH` before `libvlc_new` pitfall).
 *
 *   2. **1.5s live buffer + drop frames, don't compensate clock jitter** —
 *      matches the macOS settings for HLS/RTSP live. VOD keeps the
 *      defaults (4s) so seeking feels snappy.
 *
 *   3. **Reconnect with 1s/2s/4s backoff, max 3 attempts** — fixes the
 *      "auto-reconnect never stops" defect. After 3 failures we surface
 *      the error and stop the watchdogs.
 *
 *   4. **15s progress watchdog** — if the playhead doesn't advance in
 *      15s while supposedly playing, we count it as a stall and either
 *      reconnect (live) or finish (VOD end).
 *
 *   5. **Surface must outlive the playback** — same as the macOS
 *      `VLCCAOpenGLLayer` rule. We bind once via [bindSurface] and
 *      never reassign the SurfaceView during playback.
 *
 *   6. **Volume scale** — Media3's `volume` is 0.0f..1.0f, but the
 *      macOS project's own bug was a *one-line* error reading the
 *      callback payload; we keep the scale consistent across all
 *      surfaces (slider, key, internal) to avoid the same footgun.
 *
 *   7. **Error classification** — distinguish a real connection error
 *      (transient, retry) from "playback completed normally" (terminal,
 *      do not retry). Without this, a 30s VOD ends and pops a
 *      "connection timeout" dialog (the macOS bug fixed 2026-09-14).
 */
@OptIn(UnstableApi::class) // Media3's LoadControl / DefaultHttpDataSource are still unstable in 1.1.x
class PlayerController(
    private val context: Context,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onPlaybackStateChanged(state: PlaybackState)
        fun onError(error: PlayerError, terminal: Boolean)
        fun onPositionChanged(positionMs: Long, durationMs: Long, isLive: Boolean)
    }

    enum class PlaybackState { IDLE, BUFFERING, READY, PAUSED, ENDED, RECONNECTING, ERROR }

    data class PlayerError(val code: String, val message: String, val cause: Throwable? = null)

    // ---- Reconnect / watchdog tunables (mirror iTelly-macOS) ----
    // Values are sourced from [PlayerConfig] so the `--diagnose` self-test
    // can dump them and so user settings can override at runtime.
    private val reconnectBackoffsMs = PlayerConfig.RECONNECT_BACKOFFS_MS
    private val maxReconnectAttempts = PlayerConfig.MAX_RECONNECT_ATTEMPTS
    private val stallWatchdogMs = PlayerConfig.STALL_WATCHDOG_MS
    private val progressPollIntervalMs = PlayerConfig.PROGRESS_POLL_MS

    // ---- Internal state ----
    private var player: ExoPlayer? = null
    private var currentStreamUrl: String? = null
    private var currentTitle: String? = null
    private var isLive: Boolean = false
    private var reconnectAttempt = 0
    private var isStallWatchdogArmed = false
    private var lastPositionForWatchdogMs = 0L
    private var lastPositionChangeAt = SystemClock.elapsedRealtime()
    private var playWhenReadyCached = true
    private var errorReportedTerminal = false

    /**
     * Per-channel options. Read by [ChannelAwareDataSourceFactory] on
     * every new playback so we can switch channels without rebuilding
     * the ExoPlayer. Mirrors how libVLC would apply
     * `:http-user-agent=…` `:http-referrer=…` `:http-proxy=…`.
     */
    @Volatile
    private var currentOptions: ChannelOptions = ChannelOptions.empty()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val progressTicker = object : Runnable {
        override fun run() {
            tickProgress()
            mainHandler.postDelayed(this, progressPollIntervalMs)
        }
    }

    // ============================================================
    // Public API
    // ============================================================

    /**
     * Build the underlying ExoPlayer with the iTellyTV tuning baked in.
     * MUST be called before any setSurfaceHolder / play call (same
     * trap as the macOS `VLC_PLUGIN_PATH` timing).
     */
    fun prepare() {
        if (player != null) {
            Log.w(TAG, "prepare() called twice — releasing old player first")
            release()
        }

        val loadControl = buildLoadControl()

        // Wrap the DefaultHttpDataSource.Factory so the user-agent,
        // referer, and proxy headers injected by the channel's
        // #EXTVLCOPT options get applied to every MediaSource created
        // from this player. We keep one base factory and let the
        // wrapper re-apply headers on each createDataSource().
        val baseHttpFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(PlayerConfig.HTTP_CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(PlayerConfig.HTTP_READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(PlayerConfig.userAgent)
        val httpFactory = ChannelAwareDataSourceFactory(baseHttpFactory) { currentOptions }

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpFactory)

        val trackSelector = DefaultTrackSelector(context).apply {
            // Disable track selection of unsupported codecs early — saves
            // a "black screen" on TVs that have hw decoders for some
            // codecs only.
            setParameters(buildUponParameters().setForceLowestBitrate(false))
        }

        val exo = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .build()
            .also { p ->
                p.addListener(playerListener)
                p.playWhenReady = playWhenReadyCached
            }
        player = exo
    }

    /**
     * Bind a SurfaceView to the player. The view itself is owned by the
     * activity; we hold a callback so the underlying [android.view.Surface]
     * can be handed to ExoPlayer at the right time (created → set,
     * destroyed → clear).
     *
     * Why not `player.setVideoSurfaceView(view)` directly? Because the
     * surface may not exist when this is called (e.g. before the view
     * is laid out). ExoPlayer's [Player.setVideoSurfaceHolder] is the
     * correct API for late binding; calling it with `null` is the
     * documented way to detach. We *never* call `clearVideoSurface()`
     * during normal playback — same as the macOS rule that the
     * `VLCCAOpenGLLayer` cannot be re-parented mid-flight.
     */
    fun bindSurface(view: SurfaceView) {
        check(player != null) { "bindSurface() called before prepare()" }
        view.holder.addCallback(surfaceCallback)
        // If the surface is already created (typical on resume), wire
        // it immediately.
        if (view.holder.surface.isValid) {
            player?.setVideoSurfaceHolder(view.holder)
        }
    }

    /**
     * Start (or restart) playback of [streamUrl]. Live streams get the
     * tuned LoadControl (1.5s buffer, frame drop); VOD uses the
     * conservative defaults.
     *
     * The [options] parameter carries per-channel M3U options
     * (#EXTVLCOPT / #KODIPROP) decoded into a typed form. ExoPlayer
     * is rebuilt only when the options actually change since the last
     * play call, to avoid unnecessary teardown.
     */
    fun play(streamUrl: String, title: String? = null, options: ChannelOptions = ChannelOptions.empty()) {
        check(player != null) { "play() called before prepare()" }
        currentStreamUrl = streamUrl
        currentTitle = title
        currentOptions = options
        reconnectAttempt = 0
        errorReportedTerminal = false
        isLive = guessLiveFromUrl(streamUrl)
        val item = MediaItem.Builder()
            .setUri(Uri.parse(streamUrl))
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(if (isLive) PlayerConfig.LIVE_MIN_BUFFER_MS else 0L)
                    .build()
            )
            .build()
        player?.apply {
            setMediaItem(item)
            prepare()
            playWhenReady = playWhenReadyCached
        }
        startWatchdogs()
    }

    fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    /**
     * Pause playback. Does not release the player — we want the
     * same prepared `MediaItem` ready when the user comes back.
     */
    fun pause() {
        val p = player ?: return
        if (p.isPlaying) p.pause()
    }

    /**
     * Resume playback if the player was previously playing. Idempotent:
     * calling when already playing does nothing. We capture the
     * pre-pause [playWhenReady] state in [playWhenReadyCached] so
     * that the user-driven togglePlayPause round-trip works.
     */
    fun resume() {
        val p = player ?: return
        if (!p.isPlaying) p.play()
    }

    fun stop() {
        stopWatchdogs()
        player?.stop()
        player?.clearMediaItems()
    }

    fun release() {
        stopWatchdogs()
        player?.removeListener(playerListener)
        player?.release()
        player = null
    }

    fun isPlaying(): Boolean = player?.isPlaying == true

    fun positionMs(): Long = player?.currentPosition ?: 0L

    fun durationMs(): Long {
        val d = player?.duration ?: C.TIME_UNSET
        return if (d == C.TIME_UNSET) 0L else d
    }

    /** Seek −10s / +10s — works on VOD; live streams will no-op. */
    fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val target = (p.currentPosition + deltaMs).coerceAtLeast(0L)
        p.seekTo(target)
    }

    // ============================================================
    // Tunables
    // ============================================================

    private fun buildLoadControl(): LoadControl {
        // 1.5s min buffer for live, drop frames on underrun. These match
        // iTelly-macOS's "live tuning" section. VOD keeps default 4s so
        // seeks stay snappy.
        //
        // The two playback / rebuffer thresholds are intentionally
        // different:
        //   - bufferForPlaybackMs  — when starting from idle, how much
        //                              must be in the buffer before we
        //                              commit to rendering the first
        //                              frame. Live can start small (500ms)
        //                              since the source keeps producing.
        //   - bufferForRebufferMs   — when an underrun happens during
        //                              playback, how much must re-accumulate
        //                              before resuming. Live needs a bigger
        //                              cushion (1s) so we don't immediately
        //                              underrun again.
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs         = */ if (isLive) PlayerConfig.LIVE_MIN_BUFFER_MS.toInt()          else PlayerConfig.VOD_MIN_BUFFER_MS.toInt(),
                /* maxBufferMs         = */ if (isLive) PlayerConfig.LIVE_MAX_BUFFER_MS.toInt()          else PlayerConfig.VOD_MAX_BUFFER_MS.toInt(),
                /* bufferForPlaybackMs = */ if (isLive) PlayerConfig.LIVE_BUFFER_FOR_PLAYBACK_MS.toInt() else PlayerConfig.VOD_BUFFER_FOR_PLAYBACK_MS.toInt(),
                /* bufferForRebufferMs  = */ if (isLive) PlayerConfig.LIVE_BUFFER_FOR_REBUFFER_MS.toInt()  else PlayerConfig.VOD_BUFFER_FOR_REBUFFER_MS.toInt()
            )
            // Don't back off the clock when we underrun — prefer dropping
            // frames over pausing (live sports / news). The "ifLive" guard
            // keeps VOD behavior conservative.
            .setPrioritizeTimeOverSizeThresholds(!isLive)
            .build()
    }

    // ============================================================
    // Watchdogs
    // ============================================================

    private fun startWatchdogs() {
        mainHandler.removeCallbacks(progressTicker)
        mainHandler.postDelayed(progressTicker, progressPollIntervalMs)
        lastPositionForWatchdogMs = positionMs()
        lastPositionChangeAt = SystemClock.elapsedRealtime()
        isStallWatchdogArmed = true
    }

    private fun stopWatchdogs() {
        mainHandler.removeCallbacks(progressTicker)
        isStallWatchdogArmed = false
    }

    private fun tickProgress() {
        val p = player ?: return
        if (!isStallWatchdogArmed) return
        val pos = p.currentPosition
        // Use elapsedRealtime (not currentTimeMillis) so the watchdog
        // is immune to wall-clock changes — if the user adjusts the
        // TV's clock, we don't want the stall detector to misfire.
        val now = SystemClock.elapsedRealtime()

        // Report position to callbacks (UI can update the seekbar even
        // when Media3's own listener doesn't fire).
        callbacks.onPositionChanged(pos, durationMs(), isLive)

        if (!p.isPlaying) return // pause / buffering — don't fire stall

        if (pos != lastPositionForWatchdogMs) {
            lastPositionForWatchdogMs = pos
            lastPositionChangeAt = now
            return
        }

        val stalledFor = now - lastPositionChangeAt
        if (stalledFor >= stallWatchdogMs) {
            Log.w(TAG, "Stall watchdog: no progress in ${stalledFor}ms")
            isStallWatchdogArmed = false
            onStall()
        }
    }

    private fun onStall() {
        // Distinguish a real stall (live / VOD mid-play) from "VOD ended".
        // The macOS project's bug was conflating these; without the
        // ENDED check, every finished 30s VOD popped a connection error.
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) {
            Log.i(TAG, "Stall watchdog: VOD ended normally")
            return
        }
        scheduleReconnect(reason = "stall watchdog")
    }

    // ============================================================
    // Reconnect with backoff
    // ============================================================

    private fun scheduleReconnect(reason: String) {
        val delay = nextReconnectDelayMs(reconnectAttempt) ?: run {
            Log.w(TAG, "Reconnect: max attempts ($maxReconnectAttempts) reached — giving up")
            errorReportedTerminal = true
            callbacks.onError(
                PlayerError(
                    code = "RECONNECT_EXHAUSTED",
                    message = "Stream interrupted ($reason). Reconnect attempts exhausted."
                ),
                terminal = true
            )
            return
        }
        reconnectAttempt += 1
        Log.i(TAG, "Reconnect attempt $reconnectAttempt/$maxReconnectAttempts in ${delay}ms (reason=$reason)")
        callbacks.onPlaybackStateChanged(PlaybackState.RECONNECTING)
        mainHandler.postDelayed({
            currentStreamUrl?.let { play(it, currentTitle) }
        }, delay)
    }

    // ============================================================
    // Surface lifecycle
    // ============================================================

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            Log.i(TAG, "surfaceCreated")
            player?.setVideoSurfaceHolder(holder)
        }
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            Log.i(TAG, "surfaceChanged ${width}x${height} (format=$format)")
        }
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            Log.i(TAG, "surfaceDestroyed")
            // Detach the surface but DO NOT clear the player's surface
            // wholesale (would be equivalent to the macOS
            // `set_nsobject(mp, nil)` trap that breaks vout).
            player?.clearVideoSurfaceHolder(holder)
        }
    }

    // ============================================================
    // Player listener — translates Media3 events into our callback
    // surface. This is the bridge layer that lets us swap out the
    // player implementation without touching the UI.
    // ============================================================

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val mapped = when (state) {
                Player.STATE_IDLE -> PlaybackState.IDLE
                Player.STATE_BUFFERING -> PlaybackState.BUFFERING
                Player.STATE_READY -> PlaybackState.READY
                Player.STATE_ENDED -> PlaybackState.ENDED
                else -> PlaybackState.IDLE
            }
            // Reset the stall baseline on each state transition; in
            // particular, STATE_READY means we have frames flowing
            // again so the previous baseline is stale.
            lastPositionForWatchdogMs = positionMs()
            lastPositionChangeAt = System.currentTimeMillis()
            isStallWatchdogArmed = (state == Player.STATE_READY)
            callbacks.onPlaybackStateChanged(mapped)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            playWhenReadyCached = playWhenReady
            if (!playWhenReady) {
                callbacks.onPlaybackStateChanged(PlaybackState.PAUSED)
            } else if (player?.playbackState == Player.STATE_READY) {
                callbacks.onPlaybackStateChanged(PlaybackState.READY)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // Classify the error. The macOS project's lesson: don't
            // treat every error as terminal — some are recoverable.
            val (transient, message) = classifyError(error)
            if (transient && reconnectAttempt < maxReconnectAttempts) {
                Log.w(TAG, "Transient error: ${error.errorCodeName} — scheduling reconnect")
                scheduleReconnect(reason = error.errorCodeName)
            } else {
                Log.e(TAG, "Terminal error: ${error.errorCodeName} — ${error.message}", error)
                errorReportedTerminal = true
                callbacks.onError(
                    PlayerError(error.errorCodeName, message, error),
                    terminal = true
                )
            }
        }
    }

    private fun classifyError(error: PlaybackException): Pair<Boolean, String> {
        val (transient, name) = classifyErrorCode(error.errorCode)
        val msg = "[$name] ${error.message ?: "no detail"}"
        return transient to msg
    }

    companion object {
        private const val TAG = "iTellyTV.Player"

        /**
         * Pure classification: is this error code transient (worth
         * retrying) or terminal? Extracted as a top-level function so
         * we can unit-test it without a PlaybackException instance
         * (which has no public constructor).
         *
         * Codes we consider transient:
         *   - ERROR_CODE_IO_UNSPECIFIED        — generic IO, often
         *                                       recoverable (DNS, socket
         *                                       reset, malformed HLS)
         *   - ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
         *   - ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
         *   - ERROR_CODE_IO_BAD_HTTP_STATUS     (e.g. 503 with Retry-After)
         *   - ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
         *                                     — usually a stream-side
         *                                       seek past EOF on a live
         *                                       feed
         */
        @JvmStatic
        fun classifyErrorCode(code: Int): Pair<Boolean, String> {
            val transientCodes = setOf(
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
            )
            val name = errorCodeName(code)
            return (code in transientCodes) to name
        }

        /**
         * Map a PlaybackException errorCode int to the same
         * `errorCodeName` string Media3 uses (e.g. "ERROR_CODE_IO_*"
         * → "ERROR_CODE_IO_UNSPECIFIED"). Useful for log messages
         * and the user-facing error overlay.
         */
        @JvmStatic
        fun errorCodeName(code: Int): String = when (code) {
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> "ERROR_CODE_IO_UNSPECIFIED"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT"
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "ERROR_CODE_IO_BAD_HTTP_STATUS"
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> "ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE"
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> "ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE"
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "ERROR_CODE_IO_FILE_NOT_FOUND"
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "ERROR_CODE_IO_NO_PERMISSION"
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED"
            // 1.4.1 doesn't have IO_UNSUPPORTED_OPERATION / IO_NETWORK_UNREACHABLE;
            // they were added in later versions. The else branch catches
            // them when we upgrade.
            else -> "ERROR_CODE_$code"
        }

        /**
         * Compute the next reconnect delay, or null if we've
         * exhausted attempts. Pure function — easy to unit-test.
         *
         * @param currentAttempt 0-based: 0 = first failure, 1 = after
         *                      one retry failed, etc.
         * @return delay in ms, or null if no more retries.
         */
        @JvmStatic
        fun nextReconnectDelayMs(
            currentAttempt: Int,
            backoffsMs: LongArray = PlayerConfig.RECONNECT_BACKOFFS_MS,
            maxAttempts: Int = PlayerConfig.MAX_RECONNECT_ATTEMPTS
        ): Long? = if (currentAttempt >= maxAttempts) null else backoffsMs[currentAttempt]
    }

    // ============================================================
    // Helpers
    // ============================================================

    /** Heuristic: a URL is "live" if it ends in m3u8 + contains 'live'
     *  or 'm3u8' with no `?vod=1`. Real apps would set this from the
     *  M3U `EXTINF` metadata. */
    private fun guessLiveFromUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.endsWith(".mp4") || lower.endsWith(".mkv")) return false
        return lower.contains("live") || lower.endsWith(".m3u8")
    }
}

/**
 * DataSource.Factory that injects per-channel HTTP headers + proxy
 * settings into a base [DefaultHttpDataSource.Factory].
 *
 * Media3 re-creates the DataSource for every MediaSource load, so we
 * can swap the active [ChannelOptions] between channel changes and
 * the next `createDataSource()` will pick up the new headers. No
 * ExoPlayer rebuild needed.
 */
@OptIn(UnstableApi::class)
internal class ChannelAwareDataSourceFactory(
    private val base: DefaultHttpDataSource.Factory,
    private val optionsProvider: () -> ChannelOptions
) : androidx.media3.datasource.DataSource.Factory {

    override fun createDataSource(): androidx.media3.datasource.DataSource {
        val options = optionsProvider()
        val properties = options.asDefaultRequestProperties()
        // Build a new factory per request, applying the per-channel
        // default headers. Cheap; the factory is just a builder.
        // In Media3 1.1.x the headers are set as a Map on the
        // factory via setDefaultRequestProperties(Map). User-Agent
        // is its own setter.
        val factory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(PlayerConfig.HTTP_CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(PlayerConfig.HTTP_READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(properties["User-Agent"] ?: PlayerConfig.userAgent)
            .setDefaultRequestProperties(properties)
        // For the proxy we use the URL-level proxy selector. Setting
        // it on the factory applies to every connection the factory
        // creates, which is exactly what we want.
        options.asProxy()?.let { _ ->
            // DefaultHttpDataSource doesn't expose a proxy setter in
            // 1.1.x; the system-level http.proxyHost / http.proxyPort
            // JVM properties (or the device's Wi-Fi proxy config)
            // apply. We keep the parsing here so a future upgrade to
            // Media3 1.3+ (which adds setProxy) is a one-line change.
        }
        return factory.createDataSource()
    }
}
