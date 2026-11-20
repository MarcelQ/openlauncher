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
 *    `registerOrganizer(int windowingMode)` (this fork RE-KEYS Q's displayId
 *    signature to windowingMode — dex-verified from the locals table of both
 *    the client method and the server `registerTaskOrganizer`) and
 *    ITaskOrganizer callbacks that receive the task leash SurfaceControl
 *    directly, and `WindowContainerTransaction` (setBounds / setWindowingMode /
 *    reparent).
 *  - SERVER-SIDE routing (same capture, services.jar): organizers for one
 *    windowing mode live in a LinkedList (multiple registrations ALLOWED, only
 *    a warning is logged), but each task binds to `peekLast()` — the LAST
 *    registered organizer — at bind time. Hence concurrent sessions must not
 *    pile onto TaskOrganizerTaskEmbedder blindly: the engine gives the first
 *    session that engine and additional sessions VirtualDisplayTaskEmbedder,
 *    whose private VirtualDisplay (createVirtualDisplay + setLaunchDisplayId +
 *    registerTaskStackListener — dex-verified) can never collide AND keeps its
 *    task RESUMED even unfocused (visible stack on its own display — the
 *    native equivalent of mini-aa's "prevent the embedded app from stopping").
 * All of it is reached by reflection (absent from the compile SDK's
 * android.jar) — engine in TaskEmbedEngine.kt, device-verified only.
 *
 * Framework-free here (like GridLayout.kt / PipFreeform.kt) so the decision
 * logic is JVM-unit-testable.
 */

/** Bench force-attempt log tag (see engineUsageAllowed). Keep it short:
 *  Android rejects persist properties with more than 4 dots, so
 *  `persist.log.tag.d.emblembed` is NOT settable — `d.embed` is. */
const val EMBED_FORCE_TAG = "d.embed"

/**
 * Engine attempt gate. Normally the ROM's embedding engines only engage at
 * system uid (their signature permissions are never granted otherwise). For
 * BENCH DIAGNOSTICS the attempt can be forced ON regardless of uid with
 * `setprop persist.log.tag.<EMBED_FORCE_TAG> I` — read via Log.isLoggable,
 * the platform's supported log-level-tag mechanism: the attempt then runs on
 * any ROM and its honest failure lands in the Diagnostics ring instead of
 * the quiet gate-only line — on the unit that means "permission denied"
 * verdicts WITHOUT re-signing, on any AVD it exercises the ctor probe +
 * session bookkeeping. Default (unset) = the honest uid gate. Never force
 * this on a user device: it defeats the never-auto-launch guarantee for
 * normal builds.
 */
fun engineUsageAllowed(systemUid: Boolean, forcedByLogLevel: Boolean): Boolean =
    systemUid || forcedByLogLevel

/**
 * Engine constructor selection (round 9, the multi-embed fix, dex-verified):
 * this ROM's VirtualDisplayTaskEmbedder has NO (Context, Host) ctor — only
 * <init>(Context, TaskEmbedder$Host, ZZZ)V, the booleans stored in order as
 * mSingleTaskInstance, mUsePublicVirtualDisplay, mUseTrustedDisplay. Calling
 * getConstructor(Context, Host) threw NoSuchMethodException on EVERY
 * additional card, and the old chain then fell through to RE-registering the
 * windowing-mode organizer whose peekLast() binding stole card 1's task
 * ("it doesn't know which widget to render the right app"). Selection is
 * pure here so the rule is JVM-tested; the engine instantiates with the flags
 * read off the ROM's own onInitialize bytecode (singleTask=true → one task
 * locked per card display + RESUMED/FOCUSED there; private VD (no
 * FLAG_PUBLIC +0x1) so no app can discover card displays; trusted (
 * FLAG_TRUSTED +0x400) — required for reparentDisplayContent + input).
 * Returns null when neither shape exists (e.g. the Host interface itself is
 * absent on the running framework — API 31+ AOSP).
 */
fun selectEmbedderCtor(
    ctors: List<java.lang.reflect.Constructor<*>>, hostItf: Class<*>
): java.lang.reflect.Constructor<*>? {
    ctors.firstOrNull { c ->
        c.parameterTypes.size == 2 && c.parameterTypes[0] == android.content.Context::class.java &&
        c.parameterTypes[1] == hostItf
    }?.let { return it }
    return ctors.firstOrNull { c ->
        c.parameterTypes.size == 5 && c.parameterTypes[0] == android.content.Context::class.java &&
        c.parameterTypes[1] == hostItf &&
        c.parameterTypes.drop(2).all { it == java.lang.Boolean.TYPE }
    }
}

/** Ordered embed strategies. First success wins and keeps the card live. */
enum class EmbedMode { TASK_EMBEDDER, TASK_ORGANIZER, FALLBACK_LAUNCH }

/**
 * Embed chain for one (re)launch:
 *  - TASK_EMBEDDER is the primary: the ROM ships the framework's own
 *    TaskEmbedder host (surface creation, task creation, input forwarding all
 *    handled framework-side). It needs API 29+ (initialize() takes a
 *    SurfaceControl, which is public API 29+) and a fully on-screen card rect.
 *  - TASK_ORGANIZER (the ROM's VirtualDisplayTaskEmbedder) is the fallback
 *    engine: renders the task on a PRIVATE VirtualDisplay mirrored into the
 *    host surface (guaranteed render; its task also stays RESUMED while
 *    unfocused, input depends on the ROM's tap-exclude/key-event path).
 *  - FALLBACK_LAUNCH is always last: the card then behaves exactly like the
 *    APP OVERLAY card (freeform-over-launcher chain, fullscreen last).
 *  - On a non-system build (systemUid == false) the signature permissions are
 *    never granted, so we skip straight to the fallback and keep logs honest.
 *  - [vdFirst] is the chain for ADDITIONAL concurrent sessions while an
 *    organizer-engine session is already live: the session takes its own
 *    private VirtualDisplay engine, and when that fails it goes STRAIGHT to
 *    FALLBACK_LAUNCH. It must never retry the TaskOrganizer engine: registering
 *    a second organizer for the embed windowing mode re-binds the FIRST card's
 *    task to the new organizer (server LinkedList.peekLast, dex-verified) —
 *    round-8 bench: the second card rendered nothing and card 1 went blind.
 *    (Round-8 root cause: VirtualDisplayTaskEmbedder has NO 2-arg ctor on this
 *    ROM — only (Context, Host, ZZZ) — so the VD attempt threw NoSuchMethod and
 *    the old chain fell through to re-registering the organizer. Ctor probe
 *    fixed in TaskEmbedEngine; the chain hardening lives here.)
 */
fun embedChain(
    systemUid: Boolean, sdkInt: Int, hasValidBounds: Boolean, vdFirst: Boolean = false
): List<EmbedMode> {
    if (!systemUid || sdkInt < 29 || !hasValidBounds) return listOf(EmbedMode.FALLBACK_LAUNCH)
    return if (vdFirst)
        listOf(EmbedMode.TASK_ORGANIZER, EmbedMode.FALLBACK_LAUNCH)
    else
        listOf(EmbedMode.TASK_EMBEDDER, EmbedMode.TASK_ORGANIZER, EmbedMode.FALLBACK_LAUNCH)
}

/**
 * Gate evaluation for the chain above. `canManageActivities` mirrors
 * MANAGE_ACTIVITY_STACKS (this API 30 ROM — TaskEmbedder/TaskOrganizer's
 * requirement, renamed MANAGE_ACTIVITY_TASKS on API 31+) being granted; that
 * is also what lifts hidden-API gating for the reflection the engine needs.
 */
fun embedEnginesAllowed(systemUid: Boolean, sdkInt: Int, canManageActivities: Boolean): Boolean =
    systemUid && sdkInt >= 29 && canManageActivities

/**
 * The embed rect must be FULLY on screen: a card on another home-screen page
 * is laid out off-screen (pager offset), and embedding to those coords puts
 * the task on a virtual location the user never sees. Cards embed only while
 * their page is the visible one; leaving the page stops the session.
 */
fun embedRectOnScreen(b: FreeformBounds, screenW: Int, screenH: Int): Boolean =
    b.isValid && b.left >= 0 && b.top >= 0 && b.right <= screenW && b.bottom <= screenH

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
 * Intent flags for EVERY embed launch (both live engines): a NEW task whose
 * leash the engine then reparents/mirrors. MULTIPLE_TASK mirrors PipWidget's
 * rationale + mini-aa ground truth (their TaskView startApp always adds
 * 0x10008000 = NEW_TASK|MULTIPLE_TASK): an already-running app opens its own
 * embed instance instead of RESUMING its old (fullscreen/hidden) task — a
 * resumed old task never fires onTaskCreated, which is why post-occlusion
 * re-embeds left the card black on the unit. Values are
 * Intent.FLAG_ACTIVITY_NEW_TASK (0x10000000) | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
 * (0x8000) inlined so this file stays framework-free and JVM-unit-testable.
 */
const val EMBED_LAUNCH_FLAGS: Int = 0x10000000 or 0x00008000

/**
 * Resume-watchdog gate (mini-aa pattern, EmbeddedAppViewKt TaskViewContainer):
 * their ON_RESUME observer re-launches the embedded app into the still-alive
 * TaskView whenever the launcher was paused >30 s (and recreates the whole
 * activity after >4 h). We watch for ON_PAUSE/ON_RESUME too but act sooner —
 * our revive ladder starts with a NON-destructive WCT reorder+show and only
 * escalates to purge+relaunch, so a short flicker threshold suffices.
 * [idleMs] = time between ON_PAUSE and ON_RESUME; below the threshold treat
 * as window-recycling noise (recents peek etc.) and do nothing.
 */
const val EMBED_REVIVE_IDLE_MS: Long = 1000L
fun embedReviveOnResume(idleMs: Long, sessionLive: Boolean): Boolean =
    sessionLive && idleMs >= EMBED_REVIVE_IDLE_MS

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
    // 12: a full engine attempt writes ~4 entries (gate, constructed,
    // launched/failed, outcome) and the bench iterates over two engines —
    // 6 was too small for round-3 forensics.
    private const val MAX_ENTRIES = 12
    private val entries = ArrayDeque<String>()

    fun record(entry: String) = synchronized(this) {
        entries.addLast(entry)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    fun snapshot(): List<String> = synchronized(this) { entries.toList() }
}
