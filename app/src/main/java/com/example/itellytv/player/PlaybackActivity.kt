package com.example.itellytv.player

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.example.itellytv.R

/**
 * PlaybackActivity — fullscreen ExoPlayer host, mediated by [PlayerController].
 *
 * What this activity does:
 *   1. Build the PlayerController with our tuning (1.5s live buffer,
 *      drop frames, 1s/2s/4s reconnect, 15s stall watchdog)
 *   2. Bind the SurfaceView to the player
 *   3. Manage a list of channels and the current index
 *   4. Show a left-side channel drawer via [ChannelDrawer] when the
 *      user presses D-pad LEFT, hide on BACK or after 3s of no input
 *   5. D-pad UP/DOWN move the selection AND immediately switch
 *      playback to the newly highlighted channel
 *   6. Display position / state / error overlays
 *
 * Input handling:
 *   - LEFT               → toggle the drawer (show / hide)
 *   - RIGHT / UP / DOWN  → drawer must be visible; moves selection
 *                           and immediately plays the new channel
 *   - DPAD_CENTER / ENTER → play/pause
 *   - BACK              → close drawer if open, else finish()
 */
class PlaybackActivity : FragmentActivity(), ChannelDrawer.Callbacks {

    private lateinit var controller: PlayerController
    private lateinit var surfaceView: SurfaceView
    private lateinit var errorView: TextView
    private lateinit var stateView: TextView
    private lateinit var debugView: TextView
    private lateinit var positionView: TextView
    private lateinit var drawer: ChannelDrawer
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var startedAtMs: Long = 0

    /** Full channel list for drawer navigation. */
    private var allChannels: List<com.example.itellytv.data.model.ChannelEntity> = emptyList()
    private var currentIndex: Int = -1

    /**
     * Counts consecutive terminal errors so we can stop trying to
     * "auto-advance to next channel" once every channel has failed.
     * Reset to 0 on every successful channel switch (in
     * [onChannelSelected]) so a single bad stream doesn't poison the
     * counter forever.
     */
    private var consecutiveFailures: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_playback)

        // Android 15 (API 35) is edge-to-edge by default. The video
        // SurfaceView must paint under the system bars (we want a true
        // full-screen video), but the debug / state / position strips
        // should not get cut off. We push the system-bar insets onto
        // the root FrameLayout as padding so the video frames the
        // entire screen and the overlay strips stay readable.
        com.example.itellytv.ui.EdgeToEdgeInsets.apply(
            root = findViewById(R.id.root),
            applyTop = true,
            applyBottom = true,
            applyLeft = true,
            applyRight = true
        )

        surfaceView = findViewById(R.id.surface)
        errorView   = findViewById(R.id.error_view)
        stateView   = findViewById(R.id.state_view)
        debugView   = findViewById(R.id.debug_view)
        positionView = findViewById(R.id.position_view)
        drawer      = ChannelDrawer(findViewById(R.id.root), this)
        drawer.setup()

        controller = PlayerController(this, callback)
        controller.prepare()
        controller.bindSurface(surfaceView)

        val streamUrl   = intent.getStringExtra(EXTRA_STREAM_URL)
        val streamTitle = intent.getStringExtra(EXTRA_STREAM_TITLE) ?: "iTellyTV"
        val optionsJson = intent.getStringExtra(EXTRA_STREAM_OPTIONS_JSON)

        // Multi-channel mode: MainActivity passes the whole list and
        // an index. Single-channel mode: a single URL (used by tests
        // and by direct intent). We always set up allChannels so the
        // drawer is populated regardless.
        //
        // On API 33+, getParcelableArrayListExtra(name, Class) is
        // the documented call. The old one-arg form is deprecated
        // and on some Android TV builds returns null even when the
        // intent *does* carry the list.
        allChannels = readChannelList(intent)
        currentIndex = intent.getIntExtra(EXTRA_CHANNEL_INDEX, -1)
        Log.i(TAG, "PlaybackActivity received ${allChannels.size} channels, currentIndex=$currentIndex")

        if (allChannels.isNotEmpty() && currentIndex in allChannels.indices) {
            val ch = allChannels[currentIndex]
            debugView.text = "URL:  ${ch.url}\nName: ${ch.displayName}"
            startedAtMs = System.currentTimeMillis()
            controller.play(ch.url, ch.displayName, ChannelOptions.fromJson(ch.optionsJson))
            drawer.submit(allChannels, currentIndex)
        } else if (!streamUrl.isNullOrBlank()) {
            val options = ChannelOptions.fromJson(optionsJson)
            debugView.text = "URL:  $streamUrl\nName: $streamTitle"
            if (!options.isEmpty()) debugView.append("\nOptions: ${options.pairs}")
            startedAtMs = System.currentTimeMillis()
            controller.play(streamUrl, streamTitle, options)
            // No drawer population in single-channel mode.
        } else {
            showError("[INPUT] no stream URL provided")
            return
        }
    }

    /**
     * Single source of truth for what the UI shows.
     */
    private val callback = object : PlayerController.Callbacks {
        override fun onPlaybackStateChanged(state: PlayerController.PlaybackState) {
            runOnUiThread {
                stateView.text = "state: ${state.name.lowercase()}"
                if (state == PlayerController.PlaybackState.READY) {
                    errorView.visibility = View.GONE
                }
                Log.i(TAG, "onPlaybackStateChanged: $state")
            }
        }

        override fun onError(error: PlayerController.PlayerError, terminal: Boolean) {
            runOnUiThread {
                val prefix = if (terminal) "[ERROR]" else "[WARN]"
                showError("$prefix ${error.code}\n${error.message}")
                // If the error is terminal AND the drawer is closed
                // (i.e. the user isn't actively browsing), advance
                // to the next channel so they don't get stuck on a
                // dead stream. The PlaybackController has already
                // burned its 3 reconnect attempts; jumping to the
                // next channel is the next recovery step.
                if (terminal && !drawer.isShown) {
                    // Track consecutive failures. If every channel
                    // has failed in a row, the IPTV server is
                    // probably down — stop looping and show a
                    // permanent "all channels failed" message.
                    consecutiveFailures += 1
                    if (consecutiveFailures >= allChannels.size) {
                        showError("✗ All ${allChannels.size} channels failed.\n" +
                            "Subscription server may be down.")
                        return@runOnUiThread
                    }
                    mainHandler.postDelayed({
                        if (allChannels.isNotEmpty() && !drawer.isShown) {
                            drawer.moveBy(+1)
                        }
                    }, 1_500L)
                }
            }
        }

        override fun onPositionChanged(positionMs: Long, durationMs: Long, isLive: Boolean) {
            runOnUiThread {
                val posSec = positionMs / 1000
                val durSec = durationMs / 1000
                val liveTag = if (isLive) "  (live)" else ""
                positionView.text = if (durationMs > 0) {
                    "%d:%02d / %d:%02d%s".format(posSec / 60, posSec % 60, durSec / 60, durSec % 60, liveTag)
                } else {
                    "%d:%02d%s".format(posSec / 60, posSec % 60, liveTag)
                }
            }
        }
    }

    override fun onChannelSelected(channel: com.example.itellytv.data.model.ChannelEntity, position: Int) {
        currentIndex = position
        // Reset the failure counter — the user just made a fresh
        // selection, give it a clean slate.
        consecutiveFailures = 0
        debugView.text = "URL:  ${channel.url}\nName: ${channel.displayName}"
        startedAtMs = System.currentTimeMillis()
        controller.play(channel.url, channel.displayName, ChannelOptions.fromJson(channel.optionsJson))
        // Keep the drawer visible — user is still browsing.
        drawer.bumpActivity()
    }

    private fun showError(message: String) {
        errorView.text = message
        errorView.visibility = View.VISIBLE
    }

    /**
     * D-pad map:
     *   - LEFT          → toggle drawer (without switching channel)
     *   - UP / DOWN     → IMMEDIATELY switch to previous / next channel
     *                     AND make the drawer visible (so the user
     *                     can see what's playing). The drawer's
     *                     currentIndex updates to match.
     *   - DPAD_CENTER / ENTER → play/pause
     *   - RIGHT         → switch to next channel (same as DOWN)
     *   - MEDIA_REWIND / FFW → ±10s
     *   - BACK → close drawer if open, else finish()
     */
    /**
     * Intercept keys at the lowest level we can. Some Android TV
     * builds and some Chinese OEM remotes only emit ACTION_UP for
     * DPAD_CENTER / ENTER, and a few of them go through
     * `dispatchKeyEvent` but not `onKeyDown` (because the focused
     * child swallows them). We handle both and log when we do.
     */
    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event != null && event.action == KeyEvent.ACTION_DOWN) {
            val handled = handleKey(event.keyCode)
            if (handled) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val handled = handleKey(keyCode)
        if (handled) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun handleKey(keyCode: Int): Boolean {
        // Any key press counts as activity: keep the auto-hide
        // timer fresh. We do this *before* the show/hide logic so
        // the drawer stays visible for 3s after each press.
        drawer.bumpActivity()

        // Back is special — it has different semantics depending on
        // drawer state. We handle it first.
        if (keyCode == KeyEvent.KEYCODE_BACK) return handleBackKey()
        // Media keys are always available (drawer-open or closed).
        if (handleMediaKey(keyCode)) return true
        // Channel navigation. Split for readability.
        return handleNavigationKey(keyCode)
    }

    /**
     * Channel-navigation keys (UP / DOWN / LEFT / RIGHT / OK). The
     * drawer's open/closed state changes the semantics:
     *   - drawer CLOSED: UP/DOWN/RIGHT immediately switch channel;
     *     LEFT summons the drawer; OK toggles play/pause.
     *   - drawer OPEN: UP/DOWN move the *browse cursor*; OK commits
     *     the focused row; LEFT closes.
     */
    private fun handleNavigationKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_L2 -> {
                if (drawer.isShown) drawer.hide() else drawer.show()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (allChannels.isEmpty()) return true
                if (drawer.isShown) drawer.browseBy(-1) else drawer.moveBy(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (allChannels.isEmpty()) return true
                if (drawer.isShown) drawer.browseBy(+1) else drawer.moveBy(+1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_A -> {
                if (drawer.isShown) {
                    val pos = drawer.focusedIndex()
                    val ch = drawer.focusedChannel()
                    if (ch != null && pos != drawer.playingIndex) {
                        onChannelSelected(ch, pos)
                    }
                    drawer.hide()
                    return true
                }
                controller.togglePlayPause()
                return true
            }
        }
        return false
    }

    private fun handleMediaKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                controller.togglePlayPause(); true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                controller.seekBy(-PlayerConfig.SEEK_STEP_MS); true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                controller.seekBy(+PlayerConfig.SEEK_STEP_MS); true
            }
            else -> false
        }
    }

    private fun handleBackKey(): Boolean {
        if (drawer.isShown) { drawer.hide(); return true }
        finish()
        return true
    }

    override fun onStart() {
        super.onStart()
        // Resume playback when the activity comes back to the
        // foreground. We restore the user's previous playWhenReady
        // preference rather than always starting; if the user had
        // paused before, ExoPlayer stays paused.
        controller.resume()
    }

    override fun onStop() {
        super.onStop()
        // Going off-screen (Home button, another activity on top)
        // pauses playback. This is the right behaviour for a media
        // app: don't burn bandwidth in the background.
        // (We used to do this in onPause, but onPause fires for
        // transient interruptions like dialogs and IME that
        // shouldn't pause playback.)
        controller.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.release()
        val elapsed = (System.currentTimeMillis() - startedAtMs) / 1000
        Log.i(TAG, "PlaybackActivity onDestroy after ${elapsed}s")
    }

    companion object {
        const val EXTRA_STREAM_URL = "com.example.itellytv.STREAM_URL"
        const val EXTRA_STREAM_TITLE = "com.example.itellytv.STREAM_TITLE"
        const val EXTRA_STREAM_OPTIONS_JSON = "com.example.itellytv.STREAM_OPTIONS_JSON"

        /** Parcelable list of channels for drawer navigation. */
        const val EXTRA_CHANNEL_LIST = "com.example.itellytv.CHANNEL_LIST"
        /** Index into the list for the channel to start playing. */
        const val EXTRA_CHANNEL_INDEX = "com.example.itellytv.CHANNEL_INDEX"

        private const val TAG = "iTellyTV.Playback"

        /**
         * Robust read of the channel list. Tries the typed API-33
         * call first, falls back to the deprecated form for older
         * devices.
         */
        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        private fun readChannelList(
            intent: android.content.Intent
        ): List<com.example.itellytv.data.model.ChannelEntity> {
            // API 33+: typed Class<T> form.
            val typed = intent.getParcelableArrayListExtra(
                EXTRA_CHANNEL_LIST,
                com.example.itellytv.data.model.ChannelEntity::class.java
            )
            if (typed != null) return typed
            // Older API: deprecated but still works.
            val legacy = intent.getParcelableArrayListExtra<android.os.Parcelable>(
                EXTRA_CHANNEL_LIST
            ) ?: return emptyList()
            return legacy.filterIsInstance<com.example.itellytv.data.model.ChannelEntity>()
        }
    }
}
