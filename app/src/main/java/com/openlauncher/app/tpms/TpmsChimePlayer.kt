package com.openlauncher.app.tpms

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.util.Log
import com.openlauncher.app.R

// Same log tag as the rest of the TPMS feature (TpmsRepository, TpmsUsbSource).
private const val TAG = "TpmsUsb"

/**
 * Plays the low-pressure warning chime (short, low-latency [SoundPool] playback rather than a
 * [android.media.MediaPlayer], because the chime is a 1 s asset that may be replayed while the
 * radio, navigation prompts and a phone call are all active).
 *
 * The asset `res/raw/tpms_alarm_chime.wav` is generated from scratch (four soft bell partials,
 * B5/F#5, ~1.16 s) — no vendor or third-party samples are involved.
 *
 * Stream choice: the chime is tagged `USAGE_ALARM` + `CONTENT_TYPE_SONIFICATION`, i.e. the
 * vehicle-warning path rather than the media path. That keeps it audible when the radio is
 * paused or the media volume is down, and the audio policy ducks media for sonification by
 * itself. The trade-off, which has NOT been verified on the target head units: a unit with the
 * alarm/notification stream muted plays nothing. [play] therefore logs the alarm stream volume
 * under the `TpmsUsb` tag, so that case is visible from adb instead of looking like a silent bug.
 *
 * Threading: [play], [stop] and [release] are meant for the main thread (the ViewModel's
 * warning loop runs on `Dispatchers.Main`).
 */
class TpmsChimePlayer(private val context: Context) {

    private var pool: SoundPool? = null
    private var soundId = 0
    private var streamId = 0
    private var pendingPlay = false
    private var released = false

    /** Lazily builds the pool and starts loading; plays as soon as the asset is ready. */
    fun play() {
        if (released) return
        val p = ensurePool() ?: return
        if (soundId == 0) return                       // load failed, already logged
        if (p.play(soundId, 1f, 1f, 1, 0, 1f).also { streamId = it } != 0) return
        // Not loaded yet: play it the moment the loader finishes.
        pendingPlay = true
    }

    /** Stops a chime that is still sounding (condition cleared, sound switched off). */
    fun stop() {
        pendingPlay = false
        val p = pool ?: return
        if (streamId != 0) {
            runCatching { p.stop(streamId) }
            streamId = 0
        }
    }

    fun release() {
        released = true
        pool?.let { p -> runCatching { p.release() } }
        pool = null
        soundId = 0
        streamId = 0
        pendingPlay = false
    }

    private fun ensurePool(): SoundPool? {
        pool?.let { return it }
        val am = context.getSystemService(AudioManager::class.java)
        // Diagnostics only: which volume the warning path currently has on this unit.
        runCatching {
            am?.let {
                val vol = it.getStreamVolume(AudioManager.STREAM_ALARM)
                val max = it.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                Log.i(TAG, "chime: alarm stream $vol/$max" +
                    if (vol == 0) " — MUTED, the chime will not be heard; raise the alarm volume" else "")
            }
        }
        val p = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        p.setOnLoadCompleteListener { _, _, status ->
            if (status != 0) {
                Log.w(TAG, "chime: could not load tpms_alarm_chime (status $status)")
            } else if (pendingPlay) {
                pendingPlay = false
                if (!released) streamId = p.play(soundId, 1f, 1f, 1, 0, 1f)
            }
        }
        soundId = runCatching { p.load(context, R.raw.tpms_alarm_chime, 1) }.getOrDefault(0)
        if (soundId == 0) Log.w(TAG, "chime: raw resource could not be queued for loading")
        pool = p
        return p
    }
}
