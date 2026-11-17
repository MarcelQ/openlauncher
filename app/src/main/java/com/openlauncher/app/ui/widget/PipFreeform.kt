package com.openlauncher.app.ui.widget

/**
 * Pure decision helpers behind the PIP_APP "app overlay" card.
 *
 * The card launches the assigned app as a freeform window sized to the card's
 * on-screen rect, layered over the launcher. Framework-free (like GridLayout.kt)
 * so the decision logic is JVM-unit-testable; the Android-side launch chain that
 * consumes these lives in PipWidget.kt.
 */

/** Screen-space rect the freeform window should open at (plain ints, no android.graphics). */
data class FreeformBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val isValid: Boolean get() = right > left && bottom > top
}

/** Ordered launch strategies for the overlay. The launcher walks this list and
 *  stops at the first attempt that doesn't throw — same chain shape as the
 *  well-known freeform head-unit launchers: reflection first (cleanest), raw
 *  ActivityOptions bundle key second (survives hidden-API method gating on some
 *  ROMs), plain fullscreen last (always works, just not as an overlay). */
enum class PipLaunchMode { FREEFORM_REFLECTION, FREEFORM_BUNDLE, FULLSCREEN }

/**
 * Launch plan for one tap. Freeform attempts only make sense when the card
 * currently knows its own on-screen rect; without it there is nothing sensible
 * to size the window to, so the tap degrades to a normal launch.
 */
fun pipLaunchPlan(hasValidBounds: Boolean): List<PipLaunchMode> =
    if (hasValidBounds) {
        listOf(PipLaunchMode.FREEFORM_REFLECTION, PipLaunchMode.FREEFORM_BUNDLE, PipLaunchMode.FULLSCREEN)
    } else {
        listOf(PipLaunchMode.FULLSCREEN)
    }

/** Card rect in screen coordinates: launcher-window offset + card offset-in-window + size. */
fun freeformBoundsOnScreen(rootX: Int, rootY: Int, cardX: Int, cardY: Int, width: Int, height: Int): FreeformBounds =
    FreeformBounds(
        left   = rootX + cardX,
        top    = rootY + cardY,
        right  = rootX + cardX + width,
        bottom = rootY + cardY + height
    )

/** AOSP WINDOWING_MODE_FREEFORM (ActivityOptions.WINDOWING_MODE_FREEFORM, @hide). */
const val WINDOWING_MODE_FREEFORM = 5

/** The raw key ActivityOptions.toBundle() carries the windowing mode under;
 *  stuffing it directly bypasses the hidden setter when reflection is gated. */
const val KEY_LAUNCH_WINDOWING_MODE = "android:activity.windowingMode"

/**
 * Last few APP OVERLAY launch outcomes (newest last). Process-lifetime memory
 * only — nothing persisted. Surfaces in the Settings ▸ Diagnostics report so a
 * head-unit screenshot answers "which attempt threw what" without adb logcat.
 */
object PipLaunchLog {
    private const val MAX_ENTRIES = 6
    private val entries = ArrayDeque<String>()

    fun record(entry: String) = synchronized(this) {
        entries.addLast(entry)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    fun snapshot(): List<String> = synchronized(this) { entries.toList() }
}
