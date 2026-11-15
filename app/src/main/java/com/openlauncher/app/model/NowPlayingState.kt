package com.openlauncher.app.model

import android.graphics.Bitmap
import android.media.session.MediaController

data class NowPlayingState(
    val title: String,
    val artist: String,
    val albumArt: Bitmap?,
    // Full-resolution artwork URI when the source app provides one —
    // preferred over the (often downscaled) metadata bitmap
    val artUri: String? = null,
    val isPlaying: Boolean,
    val controller: MediaController?,
    // Upcoming/current tracks the source app exposes as its playback queue
    // (MediaController.getQueue — OS-level, works fully offline; whether an
    // app fills it is up to that app). Empty when the app exposes no queue.
    val queue: List<NowPlayingQueueItem> = emptyList(),
    // Queue id of the currently playing item when the source reports one
    // (PlaybackState.activeQueueItemId, deprecated on newer APIs — may be -1)
    val activeQueueItemId: Long = -1L,
    // Extra playback actions the source app exposes on top of the standard
    // transport controls (captured from PlaybackState.getCustomActions(),
    // offline). The launcher uses these for the shuffle toggle — the OS
    // media-session API itself has no shuffle control at all.
    val customActions: List<NowPlayingCustomAction> = emptyList()
)

/** One entry of the source app's playback queue (queue track list). */
data class NowPlayingQueueItem(
    val id: Long,
    val title: String,
    val subtitle: String
)

/**
 * One extra playback action the source app advertises (e.g. a shuffle
 * toggle). Mirrors the framework PlaybackState.CustomAction as plain data
 * so the matching logic below stays framework-free and JVM-testable.
 */
data class NowPlayingCustomAction(
    val action: String,
    val title: String,
    val extras: android.os.Bundle? = null
)

/**
 * Index of the first custom action that is a shuffle toggle, or -1 when the
 * source exposes none. Prefer the human-readable title (apps name it
 * "Shuffle"/"Shuffle off"), fall back to the action id. Matching is
 * substring-based and case-insensitive — action ids look like
 * "com.spotify.action.TOGGLE_SHUFFLE".
 */
internal fun findShuffleActionIndex(actions: List<NowPlayingCustomAction>): Int {
    val byTitle = actions.indexOfFirst { it.title.contains("shuffle", ignoreCase = true) }
    if (byTitle >= 0) return byTitle
    return actions.indexOfFirst { it.action.contains("shuffle", ignoreCase = true) }
}
