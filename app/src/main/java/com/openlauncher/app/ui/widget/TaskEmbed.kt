package com.openlauncher.app.ui.widget

/**
 * Pure decision helpers behind the EMBED_APP "app embed" card — the card that
 * renders the assigned app's LIVE window inside the card and resizes it with
 * the card grid footprint.
 *
 * Unlike PIP_APP (which only launches a freeform window OVER the launcher),
 * real in-card rendering requires the system task-embedding path.
 *
 * GROUND TRUTH FOR THE DEV'S SPD UNIT (API 30, dumped 2026-11-19 from the
 * captured ROM's own framework.jar — do NOT code against newer-AOSP guesses):
 *  - NO android.service.voice.ActivityView, NO LaunchCookie, NO
 *    SurfaceControlViewHost on this ROM.
 *  - PRESENT (all @hide, hiddenapi-BLOCKED, but uid-1000 callers are exempt
 *    from the gating): `android.window.TaskEmbedder` — the vendor-relevant
 *    legacy embedding host (the ActivityView-era engine: prepareActivityOptions
 *    + startActivity + Host interface), `android.window.TaskOrganizer` with
 *    `registerOrganizer(int displayId)` and ITaskOrganizer callbacks that
 *    receive the task leash SurfaceControl directly, and
 *    `WindowContainerTransaction` (setBounds / setWindowingMode / reparent).
 * All of it is reached by reflection (absent from the compile SDK's
 * android.jar) — engine in TaskEmbedEngine.kt, device-verified only.
 *
 * Framework-free here (like GridLayout.kt / PipFreeform.kt) so the decision
 * logic is JVM-unit-testable.
 */

/** Ordered embed strategies. First success wins and keeps the card live. */
enum class EmbedMode { TASK_EMBEDDER, TASK_ORGANIZER, FALLBACK_LAUNCH }

/**
 * Embed chain for one (re)launch:
 *  - TASK_EMBEDDER is the primary: the ROM ships the framework's own
 *    TaskEmbedder host (surface creation, task creation, input forwarding all
 *    handled framework-side). It needs API 26+ and a valid card rect.
 *  - TASK_ORGANIZER is the fallback engine: register TaskOrganizer on the
 *    default display, launch normally, reparent the leash the callback hands
 *    us. Rendering proven by this shape; input routing is the open question,
 *    so it ranks below the embedder.
 *  - FALLBACK_LAUNCH is always last: the card then behaves exactly like the
 *    APP OVERLAY card (freeform-over-launcher chain, fullscreen last).
 *  - On a non-system build (systemUid == false) the signature permissions are
 *    never granted, so we skip straight to the fallback and keep logs honest.
 */
fun embedChain(systemUid: Boolean, sdkInt: Int, hasValidBounds: Boolean): List<EmbedMode> {
    if (!systemUid || sdkInt < 26 || !hasValidBounds) return listOf(EmbedMode.FALLBACK_LAUNCH)
    return listOf(EmbedMode.TASK_EMBEDDER, EmbedMode.TASK_ORGANIZER, EmbedMode.FALLBACK_LAUNCH)
}

/**
 * Gate evaluation for the chain above. `canManageActivities` mirrors
 * MANAGE_ACTIVITY_STACKS (this API 30 ROM — TaskEmbedder/TaskOrganizer's
 * requirement, renamed MANAGE_ACTIVITY_TASKS on API 31+) being granted; that
 * is also what lifts hidden-API gating for the reflection the engine needs.
 */
fun embedEnginesAllowed(systemUid: Boolean, sdkInt: Int, canManageActivities: Boolean): Boolean =
    systemUid && sdkInt >= 26 && canManageActivities

/** Signature-level permissions the embed engines require (granted only at system uid). */
const val PERM_ACTIVITY_EMBEDDING     = "android.permission.ACTIVITY_EMBEDDING"
const val PERM_MANAGE_ACTIVITY_STACKS = "android.permission.MANAGE_ACTIVITY_STACKS"
const val PERM_MANAGE_ACTIVITY_TASKS  = "android.permission.MANAGE_ACTIVITY_TASKS"

/** Permission the current embed chain still needs but the process lacks. */
fun embedMissingPermissions(sdkInt: Int, granted: Set<String>): List<String> {
    val required =
        if (sdkInt >= 31) setOf(PERM_ACTIVITY_EMBEDDING, PERM_MANAGE_ACTIVITY_TASKS)
        else              setOf(PERM_ACTIVITY_EMBEDDING, PERM_MANAGE_ACTIVITY_STACKS)
    return required.filter { it !in granted }
}

/**
 * Intent flags for the plain launch the TASK_ORGANIZER route uses: a NEW task
 * whose leash we then reparent. MULTIPLE_TASK mirrors PipWidget's rationale —
 * an already-running app opens its own embed instance instead of being raised
 * fullscreen. Values are Intent.FLAG_ACTIVITY_NEW_TASK (0x10000000) |
 * Intent.FLAG_ACTIVITY_MULTIPLE_TASK (0x8000) inlined so this file stays
 * framework-free and JVM-unit-testable. (The TASK_EMBEDDER route does NOT use
 * these: the embedder creates its own root task and launches into it.)
 */
const val EMBED_LAUNCH_FLAGS: Int = 0x10000000 or 0x00008000

/**
 * Fallback decision for a tap when no embed engine is live: null = do nothing
 * (edit mode / no app); otherwise walk PipFreeform's proven chain. The card
 * must never be a dead tap, so a valid-assignment tap always launches.
 */
fun embedFallbackTap(isEditing: Boolean, hasApp: Boolean): PipLaunchMode? {
    if (isEditing || !hasApp) return null
    return PipLaunchMode.FREEFORM_REFLECTION
}

/**
 * Last few EMBED card engine attempts (newest last). Same pattern as
 * PipLaunchLog: process-lifetime only, surfaced in Settings ▸ Diagnostics so
 * a head-unit report answers "which engine threw what" without adb attached.
 */
object TaskEmbedLog {
    private const val MAX_ENTRIES = 6
    private val entries = ArrayDeque<String>()

    fun record(entry: String) = synchronized(this) {
        entries.addLast(entry)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    fun snapshot(): List<String> = synchronized(this) { entries.toList() }
}
