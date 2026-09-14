package com.example.itellytv.data.repository

import com.example.itellytv.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * SubscriptionRefresher — wraps [ChannelRepository.loadRemote] with
 * a "show stale + retry in the background" policy.
 *
 * State machine:
 *
 *                  ┌──────────┐
 *        initial → │  IDLE    │ ←────  user clicks Reload
 *                  └────┬─────┘
 *                       │
 *                       ▼
 *                  ┌──────────┐
 *                  │ LOADING  │ ────► success → IDLE  (channels flow emits)
 *                  └────┬─────┘
 *                       │ fail
 *                       ▼
 *                  ┌────────────┐
 *                  │ RETRYING   │ (5s → 15s → 30s, 3 attempts)
 *                  └────┬───────┘
 *                       │ all attempts failed
 *                       ▼
 *                  ┌──────────┐
 *                  │  FAILED  │ (channels flow continues emitting stale data)
 *                  └──────────┘
 *
 * The UI binds to [state] for the status line and to
 * [ChannelRepository.observeChannels] for the actual list — so when
 * we fall back to a stale playlist, the list keeps showing it.
 */
class SubscriptionRefresher(
    private val repository: ChannelRepository,
    private val scope: CoroutineScope
) {

    sealed class State {
        data object Idle : State()
        data class Loading(val message: String) : State()
        data class Retrying(val attempt: Int, val nextRetryMs: Long) : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val backoffsMs = longArrayOf(5_000L, 15_000L, 30_000L)
    private val maxAttempts = backoffsMs.size
    private var currentJob: Job? = null

    /**
     * Start a fresh load. Cancels any pending retry chain.
     */
    fun refresh(url: String = AppConfig.DEFAULT_SUBSCRIPTION_URL) {
        currentJob?.cancel()
        currentJob = scope.launch {
            attemptWithRetry(url)
        }
    }

    private suspend fun attemptWithRetry(url: String) {
        _state.value = State.Loading("Loading $url …")
        var attempt = 0
        while (true) {
            val result = repository.loadRemote(url)
            if (result.isSuccess) {
                _state.value = State.Idle
                return
            }
            val err = result.exceptionOrNull()?.message ?: "unknown error"
            if (attempt >= maxAttempts) {
                _state.value = State.Failed("✗ $err (gave up after $maxAttempts attempts)")
                return
            }
            val delay = backoffsMs[attempt]
            attempt += 1
            _state.value = State.Retrying(attempt, delay)
            delay(delay)
        }
    }

    fun cancel() {
        currentJob?.cancel()
        _state.value = State.Idle
    }
}
