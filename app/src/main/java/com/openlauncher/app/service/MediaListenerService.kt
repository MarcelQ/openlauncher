package com.openlauncher.app.service

import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
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
        override fun onQueueTitleChanged(title: CharSequence?) = refreshNowPlaying()
        override fun onQueueChanged(queue: List<MediaSession.QueueItem?>?) =
            refreshNowPlaying()
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
    // (re-armed whenever the target's own session comes back). Sends a media PLAY
    // key exactly like a play button would — no activity launch, no focus steal.
    private var wakeAttempts = 0
    private var lastWakeElapsedMs = 0L

    /**
     * [sessionPkgs] are the packages that currently hold a media session. The wake
     * fires when the TARGET app holds none of them — not merely when the system has
     * no sessions at all: on head units the stock radio/MCU app keeps a MediaSession
     * alive nearly permanently, which used to suppress the wake forever.
     */
    private fun maybeAutoWake(sessionPkgs: List<String>) {
        val pkg = autoConnectPackage ?: return
        if (sessionPkgs.any { it == pkg }) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeElapsedMs < WAKE_COOLDOWN_MS) return
        if (wakeAttempts >= MAX_WAKE_ATTEMPTS) return
        wakeAttempts++
        lastWakeElapsedMs = now
        Log.i(TAG, "auto-wake attempt $wakeAttempts/$MAX_WAKE_ATTEMPTS: no session for $pkg; " +
                "live sessions: ${if (sessionPkgs.isEmpty()) "none" else sessionPkgs.joinToString()}")
        val whenMs = SystemClock.uptimeMillis()
        val down = KeyEvent(whenMs, whenMs, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0)
        val up = KeyEvent(whenMs, whenMs, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0)
        // 1) Targeted broadcast so only the chosen app's MediaButtonReceiver gets the
        //    key. FLAG_INCLUDE_STOPPED_PACKAGES is essential on real head units: right
        //    after boot (or a force-stop) the app is in the "stopped" state and the
        //    framework drops broadcasts to it without this flag — sendBroadcast
        //    never throws for that, the key is just silently discarded, which is why
        //    an exception-only fallback never helped.
        try {
            for (ev in arrayOf(down, up)) {
                val intent = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg)
                intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                intent.putExtra(Intent.EXTRA_KEY_EVENT, ev)
                sendBroadcast(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "targeted media-key broadcast failed: $e")
        }
        // 2) Always also route the key through the system dispatcher (no permission
        //    needed). It reaches sessions and the system's media-button fallback
        //    receiver, covering apps whose manifest receiver gates plain broadcasts.
        try {
            val am = getSystemService(AUDIO_SERVICE) as? AudioManager
            if (am != null) {
                am.dispatchMediaKeyEvent(down)
                am.dispatchMediaKeyEvent(up)
            }
        } catch (e: Exception) {
            Log.w(TAG, "dispatchMediaKeyEvent failed: $e")
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
            maybeAutoWake(sessionPkgs = emptyList())
            return
        }
        // Re-arm the waker only when the TARGET app itself is back, not for any
        // unrelated session (the head unit's stock radio always holds one).
        val sessionPkgs = sessions.mapNotNull { it.packageName }
        if (autoConnectPackage == null || sessionPkgs.any { it == autoConnectPackage })
            wakeAttempts = 0

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

        // The target may still be missing even though OTHER apps hold sessions
        // (e.g. the stock radio app) — maybeAutoWake() filters on the package list.
        maybeAutoWake(sessionPkgs)
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
        val prev = _nowPlaying.value

        // Offline queue support: MediaController.getQueue() is served by the OS
        // from the source app's session — no internet involved. Whether an app
        // fills it is up to that app (Spotify does for the track it is playing).
        val queue = try {
            controller.queue.orEmpty().mapNotNull { item ->
                val d = item?.description ?: return@mapNotNull null
                com.openlauncher.app.model.NowPlayingQueueItem(
                    id       = item.queueId,
                    title    = d.title?.toString() ?: "?",
                    subtitle = (d.subtitle ?: d.description)?.toString() ?: ""
                )
            }
        } catch (_: Exception) { emptyList() }
        @Suppress("DEPRECATION")
        val activeQueueItemId = controller.playbackState?.activeQueueItemId ?: -1L
        if (queue.size != (prev?.queue?.size ?: -1))
            Log.i(TAG, "queue from ${controller.packageName}: ${queue.size} items, " +
                    "activeId=$activeQueueItemId")

        // Shuffle support: the OS media-session API has NO shuffle control, so
        // the only honest way to offer it is the action the app itself
        // advertises (PlaybackState.getCustomActions — read from the session,
        // no internet, nothing invented).
        val customActions = try {
            controller.playbackState?.customActions.orEmpty().mapNotNull { a ->
                a?.action?.let {
                    com.openlauncher.app.model.NowPlayingCustomAction(
                        action = it,
                        title  = a.name?.toString() ?: "",
                        extras = a.extras
                    )
                }
            }
        } catch (_: Exception) { emptyList() }
        if (customActions.map { "${it.action}|${it.title}" } !=
            (prev?.customActions?.map { "${it.action}|${it.title}" } ?: emptyList<String>())) {
            val shuffleIdx = com.openlauncher.app.model.findShuffleActionIndex(customActions)
            val found = customActions.getOrNull(shuffleIdx)
            Log.i(TAG, "custom actions from ${controller.packageName}: " +
                    (if (customActions.isEmpty()) "none"
                     else customActions.joinToString { "${it.action} (${it.title})" }) +
                    "; shuffle=" + (found?.let { "${it.action} -> active=${com.openlauncher.app.model.shuffleStateFromTitle(it.title) ?: "unknown"}" }
                        ?: "not exposed"))
        }

        // Skip redundant emissions: metadata bitmaps parcel into fresh instances on
        // every read, so a plain data-class compare would never match and every
        // notification would force a recomposition + art redraw.
        if (prev != null &&
            prev.controller?.sessionToken == controller.sessionToken &&
            prev.title == title && prev.artist == artist &&
            prev.isPlaying == isPlaying && prev.artUri == artUri &&
            prev.queue == queue && prev.activeQueueItemId == activeQueueItemId &&
            prev.customActions.map { "${it.action}|${it.title}" } ==
                customActions.map { "${it.action}|${it.title}" } &&
            (prev.albumArt != null) == (art != null)
        ) return

        _nowPlaying.value = NowPlayingState(
            title            = title,
            artist           = artist,
            albumArt         = art,
            artUri           = artUri,
            isPlaying        = isPlaying,
            controller       = controller,
            queue            = queue,
            activeQueueItemId = activeQueueItemId,
            customActions    = customActions
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
