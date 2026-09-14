package com.example.itellytv.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.itellytv.AppConfig
import com.example.itellytv.CrashLog
import com.example.itellytv.R
import com.example.itellytv.data.model.ChannelEntity
import com.example.itellytv.data.repository.ChannelRepository
import com.example.itellytv.data.repository.SubscriptionRefresher
import com.example.itellytv.player.PlaybackActivity
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * MainActivity — the home screen, M3-onwards.
 *
 * Layout (top to bottom):
 *   ┌─────────────────────────────────────────────────────┐
 *   │  iTellyTV                          [⟳  Reload]      │  ← header
 *   │  ─────────────────────────────────────────────────  │
 *   │  ⏳  Loading / ✓ N channels / ⚠ Retrying / ✗ Failed │  ← status line
 *   │                                                     │
 *   │   ▸  Channel 1                                      │
 *   │      Channel 2                                      │  ← ListView
 *   │      …                                              │
 *   │                                                     │
 *   │  #123  (number buffer indicator, 3s timeout)        │  ← number buffer
 *   └─────────────────────────────────────────────────────┘
 *
 * Inputs:
 *   - D-pad UP/DOWN            → move focus through the list
 *   - D-pad OK / ENTER         → play the focused channel
 *   - Long-press OK on a row   → context menu (Play / Toggle favorite)
 *   - Number keys 0-9          → accumulate into a buffer; on each
 *                               press, jump to that 1-based index
 *   - Back                     → exit app
 *
 * Network:
 *   - On launch, kicks off [SubscriptionRefresher]; the DB is
 *     consulted first so we always show *something* even if the
 *     network is dead.
 */
class MainActivity : FragmentActivity() {

    private lateinit var repository: ChannelRepository
    private lateinit var refresher: SubscriptionRefresher
    private lateinit var statusView: TextView
    private lateinit var listView: ListView
    private lateinit var reloadButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var header: TextView
    private lateinit var numberBufferView: TextView

    private lateinit var channelAdapter: ChannelRowAdapter
    private var currentChannels: List<ChannelEntity> = emptyList()
    private var currentSubscriptionUrl: String? = null
    private val numberBuffer = ChannelIndexBuffer()
    private var hasAutoPlayedFirstChannel = false
    private var cameFromPlayback = false

    /**
     * Catch-all handler for uncaught exceptions inside our coroutines.
     * Without this, a Room migration mismatch or NPE inside a flow
     * collector crashes the app silently. We log to the standard tag
     * and surface a non-fatal status to the user.
     *
     * We also guard against touching the view tree after the activity
     * is finishing or destroyed — Room can re-emit on its background
     * executor and we'd otherwise leak a reference to the old view
     * hierarchy (the bug that lints `Leaking this` in coroutines).
     */
    private val coroutineErrorHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Coroutine error", throwable)
        if (isFinishing || isDestroyed) return@CoroutineExceptionHandler
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            statusView.text = "✗ ${throwable.javaClass.simpleName}: ${throwable.message ?: "?"}"
            statusView.setTextColor(ColorExt.statusError(this))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Wrap the whole setup in try/catch so that a crash during
        // inflation (e.g. a missing layout attribute, a buggy OEM
        // resource overlay) doesn't kill the app silently. We
        // degrade to a plain TextView showing the error so the
        // user — and our crash handler — can still see what
        // happened.
        try {
            setContentView(R.layout.activity_main)
        } catch (t: Throwable) {
            showFatalError("setContentView failed", t)
            return
        }

        try {
            setupMainScreen()
        } catch (t: Throwable) {
            showFatalError("setupMainScreen failed", t)
        }
    }

    private fun setupMainScreen() {
        repository = ChannelRepository(applicationContext)
        refresher = SubscriptionRefresher(repository, lifecycleScope)

        statusView = findViewById(R.id.status)
        listView = findViewById(R.id.channel_list)
        reloadButton = findViewById(R.id.reload_button)
        progress = findViewById(R.id.progress)
        header = findViewById(R.id.header)
        numberBufferView = findViewById(R.id.number_buffer)

        // Android 15 (API 35) is edge-to-edge by default. Without
        // this padding, the channel-list header would render under
        // the system status bar (the row with the clock / signal
        // strength on TVs). We only need the top inset — there's no
        // soft-nav bar on a TV remote, and we want the channel list
        // to extend to the very bottom of the screen.
        EdgeToEdgeInsets.apply(
            root = findViewById(android.R.id.content),
            applyTop = true,
            applyBottom = false,
            applyLeft = true,
            applyRight = true
        )

        header.text = getString(R.string.app_name)
        reloadButton.setOnClickListener { refreshSubscription() }
        // Long-press the Reload button → prompt for a new URL. Useful
        // on the bench: you can swap the subscription URL without
        // rebuilding the app. Mirrors the iTelly-macOS "Subscribe to
        // URL" flow, just hidden behind a long-press.
        reloadButton.setOnLongClickListener {
            promptForUrl()
            true
        }

        channelAdapter = ChannelRowAdapter(this)
        listView.adapter = channelAdapter

        // Click on a row → play
        listView.setOnItemClickListener { _, _, position, _ ->
            val channel = currentChannels.getOrNull(position) ?: return@setOnItemClickListener
            playChannel(channel)
        }

        // Long-press on a row → context menu (Play / Toggle favorite)
        listView.setOnItemLongClickListener { _, view, position, _ ->
            val channel = currentChannels.getOrNull(position) ?: return@setOnItemLongClickListener false
            ChannelContextMenu.show(
                anchor = view,
                channel = channel,
                onToggleFavorite = { id, newState -> toggleFavorite(id, newState) },
                onPlay = { ch -> playChannel(ch) }
            )
            true
        }

        observeRefresherState()

        // Focus on the list so D-pad UP/DOWN work immediately. If the
        // list is empty, fall back to the Reload button.
        lifecycleScope.launch(coroutineErrorHandler) {
            val playlists = repository.observePlaylists().first()
            if (playlists.isEmpty()) {
                refreshSubscription()
            }
        }
    }

    /**
     * Last-resort error display. We swap the whole view tree for a
     * single TextView containing the throwable's message and stack.
     * This runs on the main thread so the user sees it immediately
     * and we don't have to worry about coroutine contexts.
     */
    private fun showFatalError(stage: String, t: Throwable) {
        Log.e(TAG, "$stage: ${t.javaClass.simpleName}: ${t.message}", t)
        try {
            // Write to a file in the app's external files dir so we
            // can pull it via `adb pull` without root.
            CrashLog.write(applicationContext, stage, t)
        } catch (_: Throwable) {
            // Even file IO failed — fine, logcat still has the trace.
        }
        val text = buildString {
            append("⚠ iTellyTV failed to start\n\n")
            append("Stage: $stage\n")
            append("Error: ${t.javaClass.name}\n")
            append("Message: ${t.message ?: "(none)"}\n\n")
            append("Full trace written to:\n")
            append(CrashLog.filePath(applicationContext))
            append("\n\nPull it with:\n")
            append("adb pull ")
            append(CrashLog.filePath(applicationContext))
            append(" ~/Desktop/")
        }
        val tv = TextView(this).apply {
            setText(text)
            setTextColor(ColorExt.statusError(this@MainActivity))
            setBackgroundColor(ColorExt.surfaceDark(this@MainActivity))
            setPadding(48, 48, 48, 48)
            textSize = 14f
        }
        setContentView(tv)
    }

    override fun onResume() {
        super.onResume()
        // If we're coming back from PlaybackActivity, the user is
        // now choosing — clear the auto-play flag so the next time
        // channels arrive we render the list instead of jumping
        // straight back into the player.
        if (cameFromPlayback) {
            hasAutoPlayedFirstChannel = false
            cameFromPlayback = false
        }
        // After returning from PlaybackActivity, re-anchor the list
        // focus so D-pad is immediately usable.
        if (currentChannels.isNotEmpty()) {
            listView.requestFocus()
        }
    }

    /**
     * One single key handler covers:
     *   - D-pad OK / ENTER on a row → play
     *   - Number keys 0-9 → buffer & jump
     *   - Long-press OK / ENTER on a row → handled by setOnItemLongClickListener
     *   - Back → exit (default)
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 0-9 digit keys: build the index buffer and jump.
        val digit = digitFromKeyCode(keyCode)
        if (digit != null) {
            handleDigit(digit)
            return true
        }

        // OK / ENTER on the list (when a row is selected) plays it.
        // (The ListView itself fires onItemClick on OK by default; this
        // branch is just a safety net in case focus is on the
        // Reload button.)
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER) {
            val pos = listView.selectedItemPosition
            val channel = currentChannels.getOrNull(pos)
            if (channel != null) {
                playChannel(channel)
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun digitFromKeyCode(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> 0
        KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> 1
        KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> 2
        KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> 3
        KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> 4
        KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> 5
        KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> 6
        KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> 7
        KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> 8
        KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> 9
        else -> null
    }

    private fun handleDigit(digit: Int) {
        if (currentChannels.isEmpty()) return
        val buffer = numberBuffer.onDigit(digit) ?: return
        val target = buffer.toIntOrNull() ?: return
        if (target < 1) return
        val pos = target - 1   // 1-based → 0-based
        if (pos >= currentChannels.size) {
            // Out of range — show a brief hint and don't move
            flashNumberBuffer("#$buffer (out of range)")
            return
        }
        listView.setSelection(pos)
        // Move focus to the list so the selection is visible.
        listView.requestFocus()
        flashNumberBuffer(buffer)
    }

    private fun flashNumberBuffer(text: String) {
        numberBufferView.text = "#$text"
        numberBufferView.visibility = View.VISIBLE
        numberBufferView.removeCallbacks(hideNumberBuffer)
        numberBufferView.postDelayed(hideNumberBuffer, NUMBER_BUFFER_DISPLAY_MS)
    }

    private val hideNumberBuffer = Runnable {
        numberBufferView.visibility = View.GONE
        numberBuffer.clear()
    }

    // ---------- Network / data ----------

    private fun refreshSubscription() {
        refresher.refresh(currentSubscriptionUrl ?: AppConfig.DEFAULT_SUBSCRIPTION_URL)
    }

    /**
     * Show an input dialog so the user can paste a different M3U URL.
     * The new URL is kept only for the rest of the session; the
     * default [AppConfig.DEFAULT_SUBSCRIPTION_URL] is what a fresh
     * install will use.
     */
    private fun promptForUrl() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://example.com/playlist.m3u"
            setText(currentSubscriptionUrl ?: AppConfig.DEFAULT_SUBSCRIPTION_URL)
            setSelection(text.length)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Load subscription URL")
            .setView(input)
            .setPositiveButton("Load") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    currentSubscriptionUrl = url
                    refresher.refresh(url)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun observeRefresherState() {
        lifecycleScope.launch(coroutineErrorHandler) {
            refresher.state.collect { state ->
                when (state) {
                    is SubscriptionRefresher.State.Idle -> {
                        progress.visibility = View.GONE
                    }
                    is SubscriptionRefresher.State.Loading -> {
                        progress.visibility = View.VISIBLE
                        statusView.text = state.message
                        statusView.setTextColor(ColorExt.statusMuted(this@MainActivity))
                    }
                    is SubscriptionRefresher.State.Retrying -> {
                        progress.visibility = View.VISIBLE
                        statusView.text =
                            "⚠ Retrying (${state.attempt}/3) in ${state.nextRetryMs / 1000}s …"
                        statusView.setTextColor(ColorExt.statusWarn(this@MainActivity))
                    }
                    is SubscriptionRefresher.State.Failed -> {
                        progress.visibility = View.GONE
                        statusView.text = state.message
                        statusView.setTextColor(ColorExt.statusError(this@MainActivity))
                    }
                }
            }
        }
        // Always observe the channel list (whether or not the network
        // is alive) so we have something to show after a failed reload.
        lifecycleScope.launch(coroutineErrorHandler) {
            repository.observePlaylists().collect { playlists ->
                val first = playlists.firstOrNull() ?: return@collect
                repository.observeChannels(first.id).collect { channels ->
                    renderChannels(channels, first.name)
                }
            }
        }
    }

    private fun renderChannels(channels: List<ChannelEntity>, playlistName: String) {
        // Don't clobber a "Retrying…" / "Failed" status message
        // while the network is in the middle of doing something.
        // Also: if the activity is going away, don't touch the view
        // tree (Room can re-emit from its IO executor; touching the
        // old views would leak the activity reference).
        if (isFinishing || isDestroyed) return
        currentChannels = channels
        if (channels.isEmpty()) {
            showError("No channels in $playlistName")
            return
        }

        // Auto-play path: when we have a list AND haven't auto-played
        // yet, jump straight into the player WITHOUT rendering the
        // list. This avoids the visual "flash" of the list before
        // the activity transition. The list is still kept in
        // currentChannels so the player activity can receive it
        // via Intent extras.
        if (!hasAutoPlayedFirstChannel) {
            hasAutoPlayedFirstChannel = true
            playChannel(channels.first())
            return
        }

        // Manual path: from here the user has already been to the
        // player at least once and is now back on the list (e.g.
        // pressed Back). Render the list normally.
        channelAdapter.submit(channels)
        listView.visibility = View.VISIBLE
        if (refresher.state.value is SubscriptionRefresher.State.Idle) {
            statusView.text = "${channels.size} channels · $playlistName"
            statusView.setTextColor(ColorExt.statusOk(this@MainActivity))
        }
        progress.visibility = View.GONE
        if (!listView.hasFocus() && currentFocus == null) {
            listView.requestFocus()
            listView.setSelection(0)
        }
    }

    private fun toggleFavorite(channelId: Long, newState: Boolean) {
        lifecycleScope.launch(coroutineErrorHandler) {
            repository.setFavorite(channelId, newState)
            // Force the adapter to re-render the row so the star
            // indicator updates.
            val list = repository.observePlaylists().first().firstOrNull()
                ?.let { pl -> repository.observeChannels(pl.id).first() }
                ?: return@launch
            channelAdapter.submit(list)
        }
    }

    // ---------- Playback ----------

    private fun playChannel(channel: ChannelEntity) {
        val intent = Intent(this, PlaybackActivity::class.java).apply {
            putExtra(PlaybackActivity.EXTRA_STREAM_URL, channel.url)
            putExtra(PlaybackActivity.EXTRA_STREAM_TITLE, channel.displayName)
            putExtra(PlaybackActivity.EXTRA_STREAM_OPTIONS_JSON, channel.optionsJson)
            // Pass the full channel list so the playback activity can
            // show the left-side drawer for up/down navigation.
            putParcelableArrayListExtra(
                PlaybackActivity.EXTRA_CHANNEL_LIST,
                ArrayList(currentChannels)
            )
            val idx = currentChannels.indexOf(channel)
            putExtra(PlaybackActivity.EXTRA_CHANNEL_INDEX, idx)
        }
        // Mark that the next onResume came from the player, so we
        // re-render the list normally instead of auto-playing again.
        cameFromPlayback = true
        startActivity(intent)
    }

    // ---------- Display helpers ----------

    private fun showError(message: String) {
        statusView.text = message
        statusView.setTextColor(ColorExt.statusError(this))
        progress.visibility = View.GONE
    }

    companion object {
        private const val TAG = "iTellyTV.Main"
        private const val NUMBER_BUFFER_DISPLAY_MS = 1_500L
    }
}
