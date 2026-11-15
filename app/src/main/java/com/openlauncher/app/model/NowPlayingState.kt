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
    val activeQueueItemId: Long = -1L
)

/** One entry of the source app's playback queue (playlist track list). */
data class NowPlayingQueueItem(
    val id: Long,
    val title: String,
    val subtitle: String
)
