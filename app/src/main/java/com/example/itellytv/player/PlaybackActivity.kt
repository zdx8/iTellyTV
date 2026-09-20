package com.example.itellytv.player

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
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

    /**
     * Whether playback was running when the activity last went to the
     * background. Used by [onStart] so we only resume what the user
     * actually had playing.
     */
    private var wasPlayingBeforeStop = false

    /**
     * The pending 1.5s "auto-advance to the next channel" task. Held
     * so it can be cancelled when the user picks a channel manually or
     * the activity is destroyed — otherwise a stale advance would fire
     * on top of the user's choice.
     */
    private val autoAdvanceRunnable = Runnable {
        if (allChannels.isNotEmpty() && !drawer.isShown && !isFinishing && !isDestroyed) {
            drawer.moveBy(+1)
        }
    }

    /**
     * Set while a centre-key ACTION_DOWN has been handled, so the
     * matching ACTION_UP is passed through instead of being treated as
     * "this remote only sends UP" and firing the action a second time.
     */
    private var centerKeyDownSeen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_playback)

        // Android 15 (API 35) is edge-to-edge by default. We want the
        // video to paint under the system bars (true full-screen), so
        // the insets are applied to the three overlay strips — NOT to
        // the root, which would inset the SurfaceView and letterbox
        // the picture.
        surfaceView = findViewById(R.id.surface)
        errorView   = findViewById(R.id.error_view)
        stateView   = findViewById(R.id.state_view)
        debugView   = findViewById(R.id.debug_view)
        positionView = findViewById(R.id.position_view)

        com.example.itellytv.ui.EdgeToEdgeInsets.apply(
            root = debugView, applyTop = true,
            applyBottom = false, applyLeft = true, applyRight = false
        )
        com.example.itellytv.ui.EdgeToEdgeInsets.apply(
            root = stateView, applyTop = true,
            applyBottom = false, applyLeft = false, applyRight = true
        )
        com.example.itellytv.ui.EdgeToEdgeInsets.apply(
            root = positionView, applyTop = false,
            applyBottom = true, applyLeft = true, applyRight = false
        )

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
            return
        }

        if (streamUrl.isNullOrBlank()) {
            showError("[INPUT] no stream URL provided")
            return
        }

        // Single-channel mode: a bare URL, no channel list, so there is
        // nothing to populate the drawer with.
        val options = ChannelOptions.fromJson(optionsJson)
        debugView.text = "URL:  $streamUrl\nName: $streamTitle"
        if (!options.isEmpty()) {
            debugView.append("\nOptions: ${options.pairs}")
        }
        startedAtMs = System.currentTimeMillis()
        controller.play(streamUrl, streamTitle, options)
    }

    /**
     * Single source of truth for what the UI shows.
     */
    private val callback = object : PlayerController.Callbacks {
        override fun onPlaybackStateChanged(state: PlayerController.PlaybackState) {
            runOnUiThread {
                stateView.text = "state: ${state.name.lowercase()}"
                Log.i(TAG, "onPlaybackStateChanged: $state")
                // A channel that reaches READY is a working channel, so
                // the failure streak is reset here rather than in
                // onChannelSelected — the latter also runs for the
                // auto-advance itself, which meant the counter never got
                // past 1 and the "all channels failed" give-up never
                // triggered.
                if (state == PlayerController.PlaybackState.READY) {
                    errorView.visibility = View.GONE
                    consecutiveFailures = 0
                }
            }
        }

        override fun onError(error: PlayerController.PlayerError, terminal: Boolean) {
            runOnUiThread {
                val prefix = if (terminal) "[ERROR]" else "[WARN]"
                showError("$prefix ${error.code}\n${error.message}")
                // If the error is terminal AND the drawer is closed
                // (i.e. the user isn't actively browsing), advance to
                // the next channel so they don't get stuck on a dead
                // stream.
                if (!terminal || drawer.isShown) return@runOnUiThread

                consecutiveFailures += 1
                val total = allChannels.size
                if (total > 0 && consecutiveFailures >= total) {
                    // Every channel failed in a row — the source is
                    // almost certainly down. Stop looping.
                    showError(
                        "✗ All $total channels failed.\n" +
                            "Subscription server may be down."
                    )
                    return@runOnUiThread
                }
                mainHandler.removeCallbacks(autoAdvanceRunnable)
                mainHandler.postDelayed(autoAdvanceRunnable, 1_500L)
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
        // The user (or the auto-advance) picked a channel — drop any
        // other pending auto-advance so we don't skip past it.
        mainHandler.removeCallbacks(autoAdvanceRunnable)
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
     * Intercept keys at the lowest level we can.
     *
     * Why here and not in [onKeyDown]: the focused child (the
     * SurfaceView, the drawer's ListView) can swallow a key before the
     * Activity ever sees it, and several OEM TV remotes only deliver the
     * centre key as ACTION_UP with no matching ACTION_DOWN. Handling the
     * key at the dispatch boundary makes all of those cases behave the
     * same — this is the fix for the "OK does nothing" reports.
     *
     * Repeat handling: holding UP/DOWN should machine-gun through
     * channels and holding FFW/RW should keep seeking, so those keys
     * honour key-repeat. Every other key ignores repeats — otherwise
     * holding LEFT would strobe the drawer and holding OK would toggle
     * play/pause as fast as the remote repeats.
     *
     * `@SuppressLint("RestrictedApi")`: `dispatchKeyEvent` is inherited
     * through `androidx.core.app.ComponentActivity`, which is
     * `@RestrictTo(LIBRARY_GROUP_PREFIX)`, so lint flags any override as
     * reaching into a restricted API. The method we actually override
     * and call through to is the public framework
     * `android.app.Activity.dispatchKeyEvent`, so there is no
     * stability risk — this is the documented false positive for that
     * detector.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return super.dispatchKeyEvent(event)

        val isCenterKey = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_A ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val repeatAllowed = event.repeatCount == 0 || isRepeatableKey(event.keyCode)
                if (repeatAllowed && handleKey(event.keyCode)) {
                    centerKeyDownSeen = isCenterKey
                    return true
                }
            }
            KeyEvent.ACTION_UP -> {
                // Fallback for remotes that never send ACTION_DOWN for
                // the centre key. Only acts when the matching DOWN was
                // not already handled, so a well-behaved remote that
                // sends both does not toggle twice.
                val actOnUp = isCenterKey && !centerKeyDownSeen
                centerKeyDownSeen = false
                if (actOnUp && handleKey(event.keyCode)) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Keys where holding the button should auto-repeat. */
    private fun isRepeatableKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_MEDIA_REWIND -> true
        else -> false
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
        // Only resume if we were actually playing when we went to the
        // background. The old code called resume() unconditionally,
        // which silently un-paused a stream the user had paused with
        // the OK key.
        if (wasPlayingBeforeStop) {
            controller.resume()
        }
    }

    override fun onStop() {
        // Remember whether the user had playback running before we
        // lose the foreground, then pause.
        wasPlayingBeforeStop = controller.isPlaying()
        controller.pause()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(autoAdvanceRunnable)
        drawer.release()
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
         * Robust read of the channel list.
         *
         * `getParcelableArrayListExtra(String, Class)` was added in
         * **API 33**. Calling it unconditionally — as the previous
         * version did — throws `NoSuchMethodError` on Android 6..12,
         * i.e. exactly the devices our `minSdk = 23` claims to
         * support. We only take that path when the platform actually
         * has it and fall back to the deprecated-but-universal
         * one-arg form everywhere else.
         */
        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        private fun readChannelList(
            intent: android.content.Intent
        ): List<com.example.itellytv.data.model.ChannelEntity> {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                val typed = intent.getParcelableArrayListExtra(
                    EXTRA_CHANNEL_LIST,
                    com.example.itellytv.data.model.ChannelEntity::class.java
                )
                if (typed != null) return typed
            }
            val legacy = intent.getParcelableArrayListExtra<android.os.Parcelable>(
                EXTRA_CHANNEL_LIST
            ) ?: return emptyList()
            return legacy.filterIsInstance<com.example.itellytv.data.model.ChannelEntity>()
        }
    }
}
