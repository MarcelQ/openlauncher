package com.openlauncher.app.service

import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.KeyEvent
import com.openlauncher.app.model.NowPlayingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MediaListenerService : NotificationListenerService() {

    private var activeController: MediaController? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refreshNowPlaying()
        override fun onMetadataChanged(metadata: MediaMetadata?)   = refreshNowPlaying()
        override fun onSessionDestroyed()                          = refreshNowPlaying()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        isConnected.value = true
        refreshNowPlaying()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        isConnected.value = false
        clearController()
        _nowPlaying.value = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?)  { refreshNowPlaying() }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { refreshNowPlaying() }

    override fun onDestroy() {
        instance = null
        clearController()
        // Clear the static flow so the UI doesn't keep showing a dead session
        // (and pinning its album-art bitmap) after the service is killed
        _nowPlaying.value = null
        super.onDestroy()
    }

    private fun clearController() {
        activeController?.unregisterCallback(controllerCallback)
        activeController = null
    }

    // ── Auto-wake (steering-wheel PLAY key without the steering wheel) ───────────
    // refreshNowPlaying() runs on EVERY notification posted/removed system-wide, so
    // the wake attempt must stay heavily throttled: cooldown + a hard attempt cap
    // (re-armed whenever a session comes back). Sends a media PLAY key exactly like
    // a play button would — no activity launch, no focus steal.
    private var wakeAttempts = 0
    private var lastWakeElapsedMs = 0L

    private fun maybeAutoWake() {
        val pkg = autoConnectPackage ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeElapsedMs < WAKE_COOLDOWN_MS) return
        if (wakeAttempts >= MAX_WAKE_ATTEMPTS) return
        wakeAttempts++
        lastWakeElapsedMs = now
        Log.i(TAG, "auto-wake attempt $wakeAttempts/$MAX_WAKE_ATTEMPTS: media PLAY key -> $pkg")
        val whenMs = SystemClock.uptimeMillis()
        val down = KeyEvent(whenMs, whenMs, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0)
        val up = KeyEvent(whenMs, whenMs, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0)
        // Preferred: targeted broadcast so only the chosen app's MediaButtonReceiver
        // gets the key. If the receiver's permission gate rejects our send, fall
        // back to the system media-key dispatcher (routes to the active session).
        try {
            for (ev in arrayOf(down, up)) {
                val intent = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg)
                intent.putExtra(Intent.EXTRA_KEY_EVENT, ev)
                sendBroadcast(intent)
            }
        } catch (_: Exception) {
            val am = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
            am.dispatchMediaKeyEvent(down)
            am.dispatchMediaKeyEvent(up)
        }
    }

    private fun refreshNowPlaying() {
        val msm = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        val sessions: List<MediaController> = try {
            msm.getActiveSessions(ComponentName(this, MediaListenerService::class.java))
        } catch (_: SecurityException) {
            emptyList()
        }

        val active = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()

        if (active == null) {
            clearController()
            _nowPlaying.value = null
            maybeAutoWake()
            return
        }
        wakeAttempts = 0 // a live session exists again — re-arm the waker

        // getActiveSessions returns NEW MediaController instances on every call —
        // compare session tokens, not references, or we churn callbacks and
        // recompose the UI on every notification system-wide.
        if (active.sessionToken != activeController?.sessionToken) {
            clearController()
            activeController = active
            active.registerCallback(
                controllerCallback,
                android.os.Handler(android.os.Looper.getMainLooper())
            )
        }

        updateFromController(activeController)
    }

    private fun updateFromController(controller: MediaController?) {
        if (controller == null) { _nowPlaying.value = null; return }
        val meta = controller.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: "Unknown"
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        // Prefer the largest bitmap the source provides; many apps put a
        // downscaled image in ALBUM_ART and the full one in ART (or vice versa)
        val art = try {
            listOfNotNull(
                meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART),
                meta?.getBitmap(MediaMetadata.METADATA_KEY_ART),
                meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ).maxByOrNull { it.width * it.height }
        } catch (_: Exception) { null }
        val artUri = meta?.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        val isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING

        // Skip redundant emissions: metadata bitmaps parcel into fresh instances on
        // every read, so a plain data-class compare would never match and every
        // notification would force a recomposition + art redraw.
        val prev = _nowPlaying.value
        if (prev != null &&
            prev.controller?.sessionToken == controller.sessionToken &&
            prev.title == title && prev.artist == artist &&
            prev.isPlaying == isPlaying && prev.artUri == artUri &&
            (prev.albumArt != null) == (art != null)
        ) return

        _nowPlaying.value = NowPlayingState(
            title      = title,
            artist     = artist,
            albumArt   = art,
            artUri     = artUri,
            isPlaying  = isPlaying,
            controller = controller
        )
    }

    companion object {
        private const val TAG = "MediaAutoConnect"
        private const val WAKE_COOLDOWN_MS = 20_000L
        private const val MAX_WAKE_ATTEMPTS = 4

        /** The media app the auto-wake targets (visible via QUERY_ALL_PACKAGES + <queries>). */
        const val SPOTIFY_PACKAGE = "com.spotify.music"

        /** Package to re-wake when no session is alive; null disables auto-wake.
         *  Set by LauncherViewModel from the spotifyAutoConnect setting. */
        @Volatile var autoConnectPackage: String? = null

        private val _nowPlaying = MutableStateFlow<NowPlayingState?>(null)
        val nowPlaying: StateFlow<NowPlayingState?> = _nowPlaying
        val isConnected = MutableStateFlow(false)

        @Volatile private var instance: MediaListenerService? = null
        fun requestRefresh() { instance?.refreshNowPlaying() }
    }
}
