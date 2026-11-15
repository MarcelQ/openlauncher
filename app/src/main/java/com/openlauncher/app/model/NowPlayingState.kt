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
    val subtitle: String,
    // Per-track thumbnail exactly as the source app attached it to its own
    // queue entry (MediaDescription icon). Best-effort: many apps attach
    // none, then both stay null and the row shows a placeholder.
    // artBitmap is deliberately excluded from equals()/hashCode() below —
    // apps hand out a fresh Bitmap on every read, so including it would
    // make every poll look like the queue changed.
    val artBitmap: Bitmap? = null,
    val artUri: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NowPlayingQueueItem) return false
        return id == other.id && title == other.title &&
            subtitle == other.subtitle && artUri == other.artUri
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + subtitle.hashCode()
        result = 31 * result + (artUri?.hashCode() ?: 0)
        return result
    }
}

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
 * "Shuffle"/"Shuffle off"/"Play in random order"), fall back to the action
 * id. Matching is substring-based and case-insensitive — action ids look
 * like "com.spotify.action.TOGGLE_SHUFFLE".
 */
internal fun findShuffleActionIndex(actions: List<NowPlayingCustomAction>): Int {
    val byTitle = actions.indexOfFirst {
        it.title.contains("shuffle", ignoreCase = true) ||
            it.title.contains("random", ignoreCase = true)
    }
    if (byTitle >= 0) return byTitle
    return actions.indexOfFirst {
        it.action.contains("shuffle", ignoreCase = true) ||
            it.action.contains("random", ignoreCase = true)
    }
}

/**
 * Current shuffle state encoded in the action's own title, when the app
 * bothers to carry it ("Shuffle: off", "Shuffle On"). Returns null when the
 * title says nothing about the state — the caller then falls back to
 * mirroring its own last toggle.
 */
internal fun shuffleStateFromTitle(title: String): Boolean? {
    val t = title.lowercase()
    return when {
        Regex("shuffle\\W*off").containsMatchIn(t) -> false
        Regex("shuffle\\W*on").containsMatchIn(t)  -> true
        Regex("random\\W*off").containsMatchIn(t)  -> false
        Regex("random\\W*on").containsMatchIn(t)   -> true
        else -> null
    }
}
