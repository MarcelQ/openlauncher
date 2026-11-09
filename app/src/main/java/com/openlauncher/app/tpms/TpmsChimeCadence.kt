package com.openlauncher.app.tpms

/**
 * Timing rules for the audible low-pressure warning — pure logic, no Android, so the cadence
 * can be unit tested on the JVM (see `app\src\test\java\com\openlauncher\app\tpms\`).
 *
 * Deliberately separate from both the audio plumbing ([TpmsChimePlayer]) and the card: the
 * visual warning reacts the moment a wheel drops below the limit, while the chime is a second,
 * gentler layer that only has to remind the driver often enough that they do not ignore it.
 *
 * Rules (all injectable, all in milliseconds):
 *  - [firstDelayMs]: a warning must be continuous for this long before the first chime, so a
 *    sensor that reports one bad frame while the engine is cranking never makes noise;
 *  - [repeatMs]: while the warning continues, chime at this gap;
 *  - [tiredAfterMs] / [tiredRepeatMs]: after the warning has been sounding for this long the
 *    gap widens — on a long trip with a slow leak the driver has been told, and a chime every
 *    30 s for two hours stops being information and becomes noise;
 *  - the condition clearing (or the sound being switched off) resets the state, so the next
 *    genuine drop is announced after the short first delay again.
 *
 * Not thread-safe: call it from one coroutine (the ViewModel's warning loop).
 */
class TpmsChimeCadence(
    private val firstDelayMs: Long = 4_000L,
    private val repeatMs: Long = 30_000L,
    private val tiredAfterMs: Long = 5 * 60_000L,
    private val tiredRepeatMs: Long = 2 * 60_000L,
) {
    /** -1 = not currently alarming. */
    private var alarmingSinceMs = -1L

    /** When the next chime is allowed; -1 = nothing pending. */
    private var dueAtMs = -1L

    /** True when the warning is currently sounding (drives "stop the sound" decisions). */
    val alarming: Boolean get() = alarmingSinceMs >= 0L

    /**
     * Call on a steady tick with a monotonic clock ([android.os.SystemClock.elapsedRealtime],
     * i.e. wall-clock jumps cannot skip or repeat a chime).
     *
     * @param alarming whether any wheel is being warned about right now
     * @return true exactly once for each scheduled chime
     */
    fun shouldChime(alarming: Boolean, nowMs: Long): Boolean {
        if (!alarming) {
            reset()
            return false
        }
        if (alarmingSinceMs < 0L) {
            alarmingSinceMs = nowMs
            dueAtMs = nowMs + firstDelayMs
            return false
        }
        if (nowMs < dueAtMs) return false
        val gap = if (nowMs - alarmingSinceMs >= tiredAfterMs) tiredRepeatMs else repeatMs
        dueAtMs = nowMs + gap
        return true
    }

    /** Forget the current warning (settings changed, card removed, condition cleared). */
    fun reset() {
        alarmingSinceMs = -1L
        dueAtMs = -1L
    }
}
