package com.example.itellytv.player

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.itellytv.R

/**
 * PlaybackService — a [MediaSessionService] that owns the [ExoPlayer]
 * and exposes it to the system as a MediaSession.
 *
 * Why we need this: on Android TV the system wants to know which
 * app is currently playing audio so it can show media controls on the
 * lock screen, in Quick Settings, and in the TV's media notifications.
 * Without a MediaSession the user can't pause playback from the TV's
 * remote after the app goes into the background.
 *
 * Lifecycle:
 *   - onCreate()      builds the ExoPlayer + MediaSession
 *   - onGetSession()  called by Media3 framework when an external
 *                      controller (system UI, Wear, Auto) wants a
 *                      session handle
 *   - onDestroy()     releases the player
 *
 * Note: the MainActivity and PlaybackActivity we have today own
 * their own ExoPlayer instances via [PlayerController]. To wire this
 * service in we'd refactor so playback lives in the service. That is
 * tracked as a follow-up — for 1.1.0 we just provide the service
 * and the manifest entry, so the system can find us.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .build()
        player = exo
        // The PendingIntent fires when the user taps the notification.
        // For now we send them back to MainActivity — they'll see
        // the home screen, which is the closest thing to a "now
        // playing" surface until the proper controller lands.
        val pendingIntent: PendingIntent? = packageManager
            .getLaunchIntentForPackage(packageName)
            ?.let { intent ->
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }
        mediaSession = MediaSession.Builder(this, exo)
            .setSessionActivity(pendingIntent!!)
            .build()
        Log.i(TAG, "PlaybackService created")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * Foreground-service notification. Required on Android 8+ so
     * the OS lets us keep playing audio in the background. We don't
     * actually start the foreground here yet — the real player
     * state lives in [PlayerController] which is owned by the
     * activity. This notification is a placeholder so the manifest
     * entry is justified and the system has a slot to upgrade us
     * later.
     */
    override fun onUpdateNotification(session: MediaSession) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.app_banner)
            .setContentTitle("iTellyTV")
            .setContentText("Media session active")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "PlaybackService destroyed")
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null  // not used; MediaSessionService handles its own binding

    companion object {
        private const val TAG = "iTellyTV.PlaybackSvc"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "itellytv_playback"
    }
}
