package com.openlauncher.app.ui.widget

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Region
import android.os.Process
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.openlauncher.app.ui.widget.EmbedMode.*
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * Reflective embedding engine behind the EMBED_APP card.
 *
 * GROUND TRUTH = the dev's SPD unit's own framework.jar (API 30, dumped from
 * the 2026-11-18 ROM capture). Everything below was signature-verified there;
 * newer-AOSP shapes (ActivityView, LaunchCookie, SurfaceControlViewHost) DO
 * NOT exist on this ROM and are deliberately not used:
 *
 *   android.window.TaskEmbedder (abstract, @hide)
 *     ├─ TaskOrganizerTaskEmbedder  ← engine 1: registers a TaskOrganizer,
 *     │    receives the task leash SurfaceControl in
 *     │    ITaskOrganizer.onTaskAppeared(RunningTaskInfo, SurfaceControl) and
 *     │    embeds it under the Host view's own surface. Framework-side input
 *     │    forwarding via Host.getWindow()/getTapExcludeRegion.
 *     └─ VirtualDisplayTaskEmbedder  ← engine 2: renders the task on a
 *          VirtualDisplay mirrored into the host surface (guaranteed render,
 *          input depends on tap-exclude working; last engine resort).
 *   Both share: prepareActivityOptions(ActivityOptions), startActivity(...),
 *   start()/stop()/release(), isInitialized(), resize(w,h)-equivalents, and a
 *   TaskEmbedder$Host implemented here as a dynamic Proxy over the card view.
 *
 *   android.window.TaskOrganizer.registerOrganizer(int windowingMode) (this
 *     fork re-keys Q's displayId signature; server keeps a LinkedList per mode
 *     and binds each new task to peekLast() — dex-verified) + manual leash
 *     reparent via reflectively-discovered SurfaceControl$Transaction, then
 *     FALLBACK_LAUNCH (PipWidget chain) so the card is never dead.
 *
 * Hidden-API gating is BLOCKED-flagged in the ROM dex, but uid 1000 callers
 * are exempt — which is why this only engages for the platform-signed
 * aospDebug build re-signed with the vendor platform key (see docs
 * float-window-research.md §F). Normal builds log the gate and fall back.
 *
 * MULTI-SESSION: several EMBED cards can embed DIFFERENT apps concurrently
 * (per-card assignment, AppSettings.embedAppPackages). The engine keys
 * sessions by card token. Only one session runs the TaskOrganizer engine at a
 * time (server peekLast() routing would otherwise cross-wire task→card
 * binding); additional sessions get their own VirtualDisplayTaskEmbedder —
 * a private VirtualDisplay per card, which also keeps each embedded app
 * RESUMED while unfocused (the native answer to "the other one stops
 * rendering when I focus this one" — mini-aa needs a keep-alive toggle for
 * that; the ROM's display stack gives it for free).
 * The register→launch sequence stays atomic per session: initialize()
 * registers the organizer and startActivity() must follow before any other
 * session registers (peekLast!), and initializeAndLaunch is ONE synchronous
 * method called from surfaceCreated — all such callbacks run on the main
 * thread, so two sessions can never interleave mid-sequence.
 *
 * Device-verified only; decision helpers in TaskEmbed.kt are JVM-tested.
 */
object TaskEmbedEngine {

    private const val TAG = "TaskEmbed"
    private const val TE_HOST_CLASS  = "android.window.TaskEmbedder"
    private const val TE_IMPL_CLASS  = "android.window.TaskOrganizerTaskEmbedder"
    private const val TE_VD_CLASS    = "android.window.VirtualDisplayTaskEmbedder"
    private const val TE_HOST_ITF    = "android.window.TaskEmbedder\$Host"
    private const val TE_LISTENER    = "android.window.TaskEmbedder\$Listener"
    private const val TASK_ORG_CLASS = "android.window.TaskOrganizer"

    class Session(
        var token: Int,
        val packageName: String,
        val bounds: FreeformBounds,
        var mode: EmbedMode,
        val embedder: Any?,
        val hostProxy: Any?,
        val container: ViewGroup?,
        var organizer: Any? = null,
        // true only while WE release the embedder (teardown): the Listener's
        // onReleased must not flip a session we are intentionally closing.
        var releasedByUs: Boolean = false,
        // Relaunch material (round-7 revive path, mini-aa pattern): everything
        // initializeAndLaunch needs, kept on the session so a re-initialized
        // host surface or an ON_RESUME watchdog can re-launch WITHOUT tearing
        // the embedder down (their TaskView stays alive across relaunches too).
        var activity: Activity? = null,
        var anchor: SurfaceView? = null,
        var baseCls: Class<*>? = null,
        var launchedOnce: Boolean = false,
        // Framework task id from Listener.onTaskCreated — used to purge the
        // task on teardown (mini-aa removeCurrentTask) so a later re-embed is
        // never shadowed by a stale task.
        var taskId: Int = -1,
        // Card's outcome callback (liveMode dot updates) kept so revive
        // failures can flip the card honest — never a silent dead surface.
        var onOutcome: ((EmbedMode) -> Unit)? = null
    )

    // All live embed sessions, keyed by card token (insertion-ordered for
    // deterministic forensics). Multiple cards = multiple concurrent embeds.
    private val sessions = LinkedHashMap<Int, Session>()
    private var tokenSeq = 0

    /** Re-launch material on the session (see Session) + the card's outcome
     *  callback so a revive can flip the dot even outside the embed chain. */
    private fun Session.attachRelaunchMaterial(activity: Activity, anchor: SurfaceView, base: Class<*>, onOutcome: (EmbedMode) -> Unit) {
        this.activity = activity; this.anchor = anchor; this.baseCls = base; this.onOutcome = onOutcome
    }

    fun isLiveFor(token: Int, packageName: String): Boolean = synchronized(this) {
        sessions[token]?.let { it.packageName == packageName && it.mode != FALLBACK_LAUNCH } == true
    }

    fun stop(token: Int) = synchronized(this) {
        sessions[token]?.let { teardown(it) }
    }

    fun nextToken(): Int = synchronized(this) { ++tokenSeq }

    /** (Re)embed [packageName] into card [container] under a caller-claimed token.
     *  [onOutcome] fires later if an engine that reported success then fails on
     *  its async surface step — the widget uses it to flip to tap-to-open. */
    fun embedWithToken(
        activity: Activity, container: FrameLayout, packageName: String,
        bounds: FreeformBounds, token: Int, onOutcome: (EmbedMode) -> Unit = {}
    ): EmbedMode {
        val granted = embedPermissions(activity)
        val systemUid = Process.myUid() == 1000
        val manage = granted.contains(PERM_MANAGE_ACTIVITY_TASKS) || granted.contains(PERM_MANAGE_ACTIVITY_STACKS)
        val sdk = android.os.Build.VERSION.SDK_INT
        val missing = embedMissingPermissions(sdk, granted)
        Log.i(TAG, "embed pkg=$packageName sdk=$sdk uid=${Process.myUid()} missing=$missing bounds=$bounds")
        TaskEmbedLog.record("${timeStamp()} $packageName :: gate uid=${Process.myUid()} sdk=$sdk missing=$missing")

        // Caller-claimed token wins; make the engine's own sequence skip past it
        // so future claims never collide.
        synchronized(this) { if (token > tokenSeq) tokenSeq = token }

        // Reuse when the SAME card container re-claims a token (double-fired
        // effects / bounds recompute). Tearing down and re-creating a live
        // TaskEmbedder for that would flicker the embedded task on the bench.
        // A RECREATED container (new FrameLayout) is deliberately NOT reused:
        // the engine's Host geometry + input window must track the live view.
        var vdFirst = false
        synchronized(this) {
            // Purge sessions whose card container left the window: composable
            // recreation during grid boot leaves old containers behind, and a
            // live engine on a DEAD container both renders into nothing and
            // keeps holding an organizer registration (peekLast!) that newer
            // launches would bind to.
            sessions.values
                .filter { it.container != null && !it.container.isAttachedToWindow }
                .toList()
                .forEach { s ->
                    TaskEmbedLog.record("${timeStamp()} ${s.packageName} :: purged (card container detached)")
                    teardown(s)
                }

            val prev = sessions.values.find {
                it.embedder != null && it.mode != FALLBACK_LAUNCH &&
                it.packageName == packageName && it.bounds == bounds && it.container === container
            }
            if (prev != null) {
                prev.token = token
                Log.i(TAG, "reusing ${prev.mode} session for $packageName (token $token)")
                return prev.mode
            }

            // Same CARD re-embedding: tear down only THIS card's old session —
            // other cards' embeds keep running untouched (multi-session).
            sessions[token]?.let { teardown(it) }

            // One session on the windowing-mode organizer at a time (server
            // binds each new task to the LAST registered organizer): extra
            // concurrent sessions prefer their own private VirtualDisplay.
            vdFirst = sessions.values.any { it.mode == TASK_EMBEDDER }
            if (vdFirst)
                TaskEmbedLog.record("${timeStamp()} $packageName :: organizer engine busy — VirtualDisplay engine first (multi-embed)")
        }

        for (mode in embedChain(systemUid, sdk, bounds.isValid && embedEnginesAllowed(systemUid, sdk, manage), vdFirst)) {
            try {
                when (mode) {
                    TASK_EMBEDDER    -> return startViaTaskEmbedder(activity, container, packageName, bounds, token, TE_IMPL_CLASS, onOutcome)
                    TASK_ORGANIZER   -> return startViaTaskEmbedder(activity, container, packageName, bounds, token, TE_VD_CLASS, onOutcome)
                    FALLBACK_LAUNCH  -> {
                        // NO auto-launch: the card shows its icon + "TAP TO OPEN
                        // (OVERLAY MODE)" and the widget's click handler runs
                        // PipFreeform's chain on demand. Launching here would
                        // fire on every (re)composition that re-claims a token.
                        TaskEmbedLog.record("${timeStamp()} $packageName :: FALLBACK_LAUNCH (tap to open)")
                        Log.i(TAG, "FALLBACK_LAUNCH for $packageName (no auto-launch; tap opens)")
                        synchronized(this) { sessions[token] = Session(token, packageName, bounds, FALLBACK_LAUNCH, null, null, container) }
                        return FALLBACK_LAUNCH
                    }
                }
            } catch (e: Exception) {
                TaskEmbedLog.record("${timeStamp()} $packageName :: $mode failed(${e.javaClass.simpleName}: ${e.message})")
                Log.w(TAG, "$mode failed for $packageName", e)
            }
        }
        return FALLBACK_LAUNCH
    }

    // ── engines 1+2: the ROM's own TaskEmbedder implementations ──────────────
    private fun startViaTaskEmbedder(
        activity: Activity, container: FrameLayout, pkg: String, bounds: FreeformBounds,
        token: Int, implClass: String, onAsyncFail: (EmbedMode) -> Unit
    ): EmbedMode {
        val hostCls  = Class.forName(TE_HOST_ITF)
        val baseCls  = Class.forName(TE_HOST_CLASS)
        val implCls  = Class.forName(implClass)

        // Host over the card container: the framework asks us for geometry,
        // the window token (input forwarding) and posts back to our UI thread.
        val hostHandler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "getWidth"                    -> container.width
                "getHeight"                   -> container.height
                "getPositionInWindow"         -> IntArray(2).also { container.getLocationInWindow(it) }.let { Point(it[0], it[1]) }
                "getScreenBounds"             -> Rect(container.boundsOnScreenSafe())
                // task bounds == card screen rect (setLaunchBounds), so the
                // screen->task transform is a pure translation by -origin.
                // Identity (round-3 guess) made VDTE mirror geometry wrong.
                "getScreenToTaskMatrix"       -> {
                    val loc = IntArray(2).also { container.getLocationOnScreen(it) }
                    Matrix().apply { setTranslate(-loc[0].toFloat(), -loc[1].toFloat()) }
                }
                "canReceivePointerEvents"     -> container.isShown && container.width > 0
                // Input contract (dex-verified): TaskEmbedder.updateLocationAnd
                // TapExcludeRegion pushes THIS region to
                // IWindowSession.updateTapExcludeRegion(hostWindow, region) —
                // the launcher window then REFUSES touches inside it and they
                // fall to the embedded task's window. An EMPTY region (round-3
                // value) meant the launcher ate every touch on the card = the
                // "touch doesn't work" bench complaint. Region coords are
                // window-relative: card rect inside the launcher window.
                "getTapExcludeRegion"         -> {
                    val pos = IntArray(2).also { container.getLocationInWindow(it) }
                    Region(pos[0], pos[1], pos[0] + container.width, pos[1] + container.height)
                }
                "getWindow"                   -> hostWindow(container)
                "post"                        -> container.post(args?.getOrNull(0) as? Runnable)
                "onTaskBackgroundColorChanged" -> { TaskEmbedLog.record("${timeStamp()} :: task bg changed"); null }
                "toString"                    -> "OpenLauncherTaskEmbedHost"
                "hashCode"                    -> System.identityHashCode(proxy)
                "equals"                      -> proxy === args?.getOrNull(0)
                else                          -> { Log.d(TAG, "Host.${method.name}(${args?.size ?: 0}) unhandled"); null }
            }
        }
        val hostProxy = Proxy.newProxyInstance(hostCls.classLoader, arrayOf(hostCls), hostHandler)

        val embedder = implCls.getConstructor(Context::class.java, hostCls).newInstance(activity, hostProxy)

        // Listener: surface state + task identity into the log ring.
        val listenerCls = Class.forName(TE_LISTENER)
        val listener = Proxy.newProxyInstance(listenerCls.classLoader, arrayOf(listenerCls),
            InvocationHandler { _, m, a ->
                when (m.name) {
                    "onInitialized"     -> { TaskEmbedLog.record("${timeStamp()} $pkg :: ${implCls.simpleName} onInitialized"); null }
                    "onReleased" -> {
                        // The embedded task died (app quit / ROM killed it /
                        // back at task root released it): flip THIS card back
                        // to tap-to-open so it is never a dead black surface.
                        // Releases from our own teardown are expected and skip.
                        val unexpected = synchronized(this@TaskEmbedEngine) {
                            sessions[token]?.takeIf { !it.releasedByUs }
                                ?.also { it.mode = FALLBACK_LAUNCH }
                        }
                        if (unexpected != null) {
                            TaskEmbedLog.record("${timeStamp()} $pkg :: task released by app/ROM — card back to tap-to-open")
                            onAsyncFail(FALLBACK_LAUNCH)
                        } else {
                            TaskEmbedLog.record("${timeStamp()} $pkg :: ${implCls.simpleName} onReleased (our teardown)")
                        }
                        null
                    }
                    "onTaskCreated"     -> {
                        TaskEmbedLog.record("${timeStamp()} $pkg :: task created id=${a?.getOrNull(0)} comp=${a?.getOrNull(1)}")
                        // Remember the framework task id: teardown purges it
                        // (mini-aa removeCurrentTask) so a stale fullscreen
                        // task can never shadow a later re-embed attempt.
                        (a?.getOrNull(0) as? Int)?.let { tid ->
                            synchronized(this@TaskEmbedEngine) { sessions[token]?.taskId = tid }
                        }
                        // Task exists NOW: push the card geometry through the
                        // ROM's own bounds path so the task is card-sized from
                        // the first frame (launch bounds are ignored by this
                        // server; resizeTask base impl is a stub — see resize()).
                        resize(token, container.width, container.height)
                        null
                    }
                    "onBackPressedOnTaskRoot" -> { TaskEmbedLog.record("${timeStamp()} $pkg :: back-press at task root"); null }
                    else -> { TaskEmbedLog.record("${timeStamp()} $pkg :: listener.${m.name}"); null }
                }
            })
        baseCls.getMethod("setListener", listenerCls).invoke(embedder, listener)

        // Anchor SurfaceView: the embedder's initialize() needs ITS surface,
        // which Android creates asynchronously — so initialize + start +
        // launch run from surfaceCreated, not synchronously here (calling
        // start() before initialize was the bench bug: the engine chain
        // "succeeded" without a host surface and the card fell back).
        // setZOrderMediaOverlay: WITHOUT it a SurfaceView sits BELOW the app
        // window (Android default) and the launcher's opaque card background
        // hides its hole — round-3 bench: no host surface ever reached
        // surfaceCreated on the SPD unit; this is the prime suspect plus the
        // probe below settles it either way.
        val anchor = SurfaceView(activity)
        try { anchor.setZOrderMediaOverlay(true) } catch (_: Exception) {}
        container.addView(anchor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Compose's AndroidViewHolder lays the container out ONLY during
        // Compose's own layout passes; an addView() from here does not
        // re-trigger one (1.7.0 source: holder.onLayout -> view.layout).
        // Observed on the unit round-3+: anchor stayed 0x0 forever, and
        // SurfaceView.layout() is exactly what calls updateSurface() — no
        // layout, no surface, no initialize. So drive one measure/layout
        // pass over the container ourselves: with PFLAG_FORCE_LAYOUT set
        // by addView, View.layout() runs onLayout -> anchor gets its size
        // -> SurfaceView.layout -> updateSurface -> surfaceCreated.
        forceLayout(container)
        container.post {
            Log.i(TAG, "anchor probe: container=${container.width}x${container.height} attached=${container.isAttachedToWindow} anchor=${anchor.width}x${anchor.height} shown=${anchor.isShown}")
            TaskEmbedLog.record("${timeStamp()} $pkg :: anchor ${anchor.width}x${anchor.height} attached=${container.isAttachedToWindow}")
            if (anchor.width == 0 || !anchor.isShown) {
                // Forensics: which ancestor in the view chain hides/blocks it?
                val sb = StringBuilder("anchor chain: ")
                var v: View? = anchor
                while (v != null) {
                    sb.append("${v.javaClass.simpleName}(vis=${v.visibility} ${v.width}x${v.height}) < ")
                    v = v.parent as? View
                }
                Log.i(TAG, sb.toString())
                anchor.postDelayed({
                    Log.i(TAG, "anchor late probe: ${anchor.width}x${anchor.height} shown=${anchor.isShown} surfaceValid=${anchor.holder.surface?.isValid}")
                }, 800)
            }
        }

        val mode = if (implCls.name == TE_IMPL_CLASS) TASK_EMBEDDER else TASK_ORGANIZER
        synchronized(this) {
            sessions[token] = Session(token, pkg, bounds, mode, embedder, hostProxy, container)
                .also { it.attachRelaunchMaterial(activity, anchor, baseCls, onAsyncFail) }
        }

        // The surface may already be valid by the time we register (forceLayout
        // above can complete it synchronously on this ROM) — then the callback
        // never fires, so kick the same path directly. `fired` keeps the two
        // entry points from double-initializing.
        var fired = false
        fun tryInit(fromCallback: Boolean) {
            if (fired) return
            fired = true
            try {
                initializeAndLaunch(activity, baseCls, embedder, anchor, pkg, bounds, implCls.name == TE_IMPL_CLASS)
                synchronized(this@TaskEmbedEngine) { sessions[token]?.launchedOnce = true }
            } catch (e: Exception) {
                TaskEmbedLog.record("${timeStamp()} $pkg :: ${implCls.simpleName} host-surface init failed(${e.javaClass.simpleName}: ${e.message})")
                Log.w(TAG, "host surface init failed for $pkg (fromCallback=$fromCallback)", e)
                // Degrade THIS card to the tap-to-open overlay state so it
                // is never a dead surface.
                synchronized(this@TaskEmbedEngine) { sessions[token]?.mode = FALLBACK_LAUNCH }
                onAsyncFail(FALLBACK_LAUNCH)
            }
        }

        anchor.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                TaskEmbedLog.record("${timeStamp()} $pkg :: host surface created")
                Log.i(TAG, "host surface created pkg=$pkg")
                if (!fired) { tryInit(true); return }
                // RE-created surface (round-5 unit log: `host surface destroyed`
                // when the launcher went occluded behind Settings, then created
                // again on return). The embedder is still alive but its host
                // SurfaceControl is stale — the old engine ignored this and the
                // card stayed black forever ("hard to get it to render again").
                // mini-aa's answer to the same failure was relaunch-on-resume;
                // ours is stronger: re-initialize the SAME embedder against the
                // NEW surface, then purge + relaunch the task so the ROM hands
                // us a fresh leash parented under it.
                revive(token, "surface re-created", forceReinitialize = true)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                TaskEmbedLog.record("${timeStamp()} $pkg :: host surface changed ${width}x$height")
                Log.i(TAG, "host surface changed pkg=$pkg ${width}x$height")
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                TaskEmbedLog.record("${timeStamp()} $pkg :: host surface destroyed")
                Log.i(TAG, "host surface destroyed pkg=$pkg")
            }
        })
        if (runCatching { anchor.holder.surface?.isValid == true }.getOrDefault(false)) {
            Log.i(TAG, "host surface already valid at register pkg=$pkg")
            tryInit(false)
        }

        TaskEmbedLog.record("${timeStamp()} $pkg :: ${implCls.simpleName} constructed; waiting for host surface")
        Log.i(TAG, "${implCls.simpleName} waiting for host surface pkg=$pkg bounds=$bounds")
        return mode
    }

    /** Reflect a method regardless of Java access (protected members of the
     *  ROM's TaskEmbedder are invisible to getMethod — dex shows
     *  prepareActivityOptions is protected). Walks the declaring hierarchy. */
    private fun findMethod(c: Class<*>, name: String, vararg args: Class<*>): java.lang.reflect.Method {
        var k: Class<*>? = c
        while (k != null) {
            try {
                val m = k.getDeclaredMethod(name, *args)
                m.isAccessible = true
                return m
            } catch (_: NoSuchMethodException) { k = k.superclass }
        }
        throw NoSuchMethodException("${c.name}.$name${if (args.isEmpty()) "()" else args.map { it.simpleName }.joinToString("(", "[", "]")}")
    }

    /** Surface-driven activation (dex-verified API 30 SPD shape):
     * initialize(SurfaceControl) -> isInitialized/start() ->
     * prepareActivityOptions -> startActivity(PendingIntent, Intent, ActivityOptions).
     * SurfaceView.getSurfaceControl is @hide (uid-1000 exempt); the
     * SurfaceControl class itself is public API 29+, which is why the engine
     * gate in TaskEmbed.kt starts at 29.
     */
    private fun initializeAndLaunch(
        activity: Activity, baseCls: Class<*>, embedder: Any, anchor: SurfaceView,
        pkg: String, bounds: FreeformBounds, cardLaunchBounds: Boolean
    ) {
        val sc = SurfaceView::class.java.getMethod("getSurfaceControl").invoke(anchor)
            ?: throw IllegalStateException("no host surface control")
        val scCls = Class.forName("android.view.SurfaceControl")
        val init = findMethod(baseCls, "initialize", scCls).invoke(embedder, sc) as? Boolean ?: false
        if (!init) throw IllegalStateException("initialize(surface) returned false")
        val isInit = findMethod(baseCls, "isInitialized").invoke(embedder) as? Boolean ?: false
        if (!isInit) findMethod(baseCls, "start").invoke(embedder)

        val intent = launchIntentFor(activity.packageManager, pkg)
            ?: throw IllegalStateException("no launch intent")
        // NEW_TASK|MULTIPLE_TASK (mini-aa ground truth: their startApp always
        // adds 0x10008000): an already-running app then opens a FRESH embed
        // task instead of RESUMING its old (fullscreen/hidden) one — a resumed
        // old task never fires onTaskCreated, which is why re-embeds after an
        // occlusion used to leave the card black. Purge + these flags together
        // make every embed launch deterministic.
        intent.addFlags(EMBED_LAUNCH_FLAGS)
        val opts = ActivityOptions.makeBasic()
        // Sizing ground truth (dex + bench round 5): TOTE.onTaskAppeared is ONLY
        // reparent+show+apply — the ROM never bounds/positions the task itself.
        // Without launch bounds the multi-window task is created fullscreen
        // (mBounds 0,0-1280,720) which misaligns visual vs window coords (touch
        // lands offset) and wastes surface area. Card rect in SCREEN coords:
        // after the leash reparent under our anchor layer, task-local (0,0)
        // lands at the card origin and window frames match the visual.
        // (VirtualDisplayTaskEmbedder NOT included: its task lives on a private
        // display whose geometry its own prepareActivityOptions controls.)
        if (cardLaunchBounds) opts.setLaunchBounds(Rect(bounds.left, bounds.top, bounds.right, bounds.bottom))
        val prepared = findMethod(baseCls, "prepareActivityOptions", ActivityOptions::class.java).invoke(embedder, opts)
            as? ActivityOptions ?: throw IllegalStateException("prepareActivityOptions returned null")
        val pi = android.app.PendingIntent.getActivity(
            activity, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            findMethod(baseCls, "startActivity", android.app.PendingIntent::class.java, Intent::class.java, ActivityOptions::class.java)
                .invoke(embedder, pi, intent, prepared)
        } catch (nsme: NoSuchMethodException) {
            findMethod(baseCls, "startActivity", android.app.PendingIntent::class.java).invoke(embedder, pi)
        }
        TaskEmbedLog.record("${timeStamp()} $pkg :: embed launched (host surface ready)")
        Log.i(TAG, "embed launched pkg=$pkg")
    }

    /** Card resized while an embed is live — or task just appeared.
     *  ROM ground truth (round-6 dex read): base resizeTask(int,int) is a
     *  STUB (return-void) — the vendor's actual sizing path is
     *  TaskOrganizerTaskEmbedder.notifyBoundsChanged(): reads
     *  Host.getScreenBounds(), offsetTo(0,0), WCT.setBounds(taskToken, size)
     *  + applyTransaction. So task SIZE = host container size, origin (0,0)
     *  (keeps the reparented leash visually under our anchor layer). The
     *  VirtualDisplay engine has a real resizeTask(II) — try both, best effort. */
    fun resize(token: Int, width: Int, height: Int) {
        val s = synchronized(this) { sessions[token] } ?: return
        val e = s.embedder ?: return
        try { findMethod(e.javaClass, "notifyBoundsChanged").invoke(e) }
        catch (ex: NoSuchMethodException) { Log.d(TAG, "notifyBoundsChanged absent on ${e.javaClass.simpleName}") }
        catch (ex: Exception) { Log.w(TAG, "notifyBoundsChanged: ${ex.javaClass.simpleName}: ${ex.cause ?: ex.message}") }
        try { findMethod(e.javaClass, "resizeTask", java.lang.Integer.TYPE, java.lang.Integer.TYPE)
                .invoke(e, width, height) }
        catch (ex: Exception) { Log.w(TAG, "resizeTask(${width}x$height): ${ex.javaClass.simpleName}: ${ex.message}") }
    }

    /**
     * Revive a LIVE session whose rendering died underneath us — the two
     * unit-observed cases:
     *  a) host SurfaceView surface destroyed+re-created (launcher occluded by
     *     another app/Settings: round-5 log `host surface destroyed`), embedder
     *     still alive but its host SurfaceControl stale, card black forever;
     *  b) launcher resumed after a pause while the session still claims live
     *     (widget's lifecycle watchdog) — mini-aa does exactly this (their
     *     ON_RESUME observer: resetForResume() + startApp() relaunch into the
     *     still-alive TaskView; only >4 h idle escalates to activity.recreate).
     * Escalation ladder (cheap → brutal):
     *  1. WCT re-assert: reorder(mTaskToken, onTop=true) + setHidden(false) via
     *     WindowOrganizer.applyTransaction (dex-verified verbs; mTaskToken is
     *     the WindowContainerToken field TOTE keeps from onTaskAppeared). If the
     *     task only got hidden behind the occluder, this alone restores render.
     *  2. purgeTask + embedder release + initialize(new SurfaceControl) +
     *     relaunch — same task-kill+relaunch semantics mini-aa uses, but
     *     without tearing down the card composable (no embed-token churn).
     *  3. Failure → session flips to FALLBACK_LAUNCH and the card's outcome
     *     callback paints the honest amber tap-to-open dot.
     */
    fun revive(token: Int, reason: String, forceReinitialize: Boolean = false) {
        val s = synchronized(this) { sessions[token] } ?: return
        if (s.mode == FALLBACK_LAUNCH || s.embedder == null || s.activity == null ||
            s.anchor == null || s.baseCls == null) return
        val act = s.activity!!; val anchor = s.anchor!!; val base = s.baseCls!!; val e = s.embedder!!
        TaskEmbedLog.record("${timeStamp()} ${s.packageName} :: revive (${reason}, mode=${s.mode})")
        Log.i(TAG, "revive token=$token pkg=${s.packageName} reason=$reason mode=${s.mode}")

        // 1. WCT re-assert (only meaningful for the organizer engine: its task
        //    lives on the DEFAULT display where a fullscreen window can push it
        //    invisible; the VirtualDisplay engine's private display can't).
        //    If our host surface is still ALIVE, the task merely got hidden
        //    behind the occluder — reorder+show alone restores rendering, and we
        //    never kill a working app (mini-aa always relaunches; we try not to).
        val surfaceAlive = runCatching { anchor.holder.surface?.isValid == true }.getOrDefault(false)
        if (!forceReinitialize && s.mode == TASK_EMBEDDER && surfaceAlive && reassertTask(e, s.packageName)) {
            TaskEmbedLog.record("${timeStamp()} ${s.packageName} :: revive done — re-assert only (surface alive)")
            Log.i(TAG, "revive: re-assert only (no relaunch) pkg=${s.packageName}")
            return
        }

        // 2. Full re-embed into the SAME embedder: purge task, release the
        //    stale engine state, re-initialize against the live surface, relaunch.
        purgeTask(s)
        try { findMethod(e.javaClass, "release").invoke(e) }
        catch (ex: Exception) { Log.w(TAG, "revive release (ignored): ${ex.javaClass.simpleName}: ${ex.cause ?: ex.message}") }
        try {
            initializeAndLaunch(act, base, e, anchor, s.packageName, s.bounds, s.mode == TASK_EMBEDDER)
            synchronized(this) { s.launchedOnce = true }
            TaskEmbedLog.record("${timeStamp()} ${s.packageName} :: revive relaunch OK")
        } catch (ex: Exception) {
            TaskEmbedLog.record("${timeStamp()} ${s.packageName} :: revive failed(${ex.javaClass.simpleName}: ${ex.cause ?: ex.message})")
            Log.w(TAG, "revive relaunch failed for ${s.packageName}", ex)
            synchronized(this) { s.mode = FALLBACK_LAUNCH }
            s.onOutcome?.invoke(FALLBACK_LAUNCH)
        }
    }

    /** WindowContainerToken the organizer embedder keeps from onTaskAppeared
     *  (dex: TOTE field mTaskToken : Landroid/window/WindowContainerToken;). */
    private fun taskTokenOf(e: Any): Any? {
        var k: Class<*>? = e.javaClass
        while (k != null) {
            try {
                val f = k.getDeclaredField("mTaskToken")
                f.isAccessible = true
                return f.get(e)
            } catch (_: NoSuchFieldException) { k = k.superclass }
        }
        return null
    }

    /** WCT reorder(token,true)+setHidden(token,false) -> applyTransaction.
     *  Dex-verified this fork's WCT carries reorder(WindowContainerToken,Z) and
     *  setHidden(...), and WindowOrganizer.applyTransaction exists. Best-effort:
     *  any miss logs and returns false (caller escalates). */
    private fun reassertTask(e: Any, pkg: String): Boolean = try {
        val wctCls = Class.forName("android.window.WindowContainerTransaction")
        val tok = taskTokenOf(e) ?: return false
        val wct = wctCls.getConstructor().newInstance()
        findMethod(wctCls, "reorder", Class.forName("android.window.WindowContainerToken"), java.lang.Boolean.TYPE)
            .invoke(wct, tok, true)
        runCatching {
            findMethod(wctCls, "setHidden", Class.forName("android.window.WindowContainerToken"), java.lang.Boolean.TYPE)
                .invoke(wct, tok, false)
        }
        val woCls = Class.forName("android.window.WindowOrganizer")
        woCls.getMethod("applyTransaction", wctCls).invoke(null, wct)
        TaskEmbedLog.record("${timeStamp()} $pkg :: WCT reorder+setHidden(false) applied")
        true
    } catch (ex: Exception) {
        Log.w(TAG, "reassertTask: ${ex.javaClass.simpleName}: ${ex.cause ?: ex.message}")
        false
    }

    /** Kill the framework task (mini-aa removeCurrentTask): guarantees a
     *  stale/hidden task can never shadow the next embed launch (launch would
     *  otherwise just RESUME the old fullscreen task and the embedder would
     *  never see a fresh leash = the black-card symptom). */
    private fun purgeTask(s: Session) {
        if (s.taskId < 0) return
        try {
            val am = s.activity!!.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            ActivityManager_removeTask(am, s.taskId)
            TaskEmbedLog.record("${timeStamp()} ${s.packageName} :: purged task id=${s.taskId}")
            Log.i(TAG, "purged task ${s.taskId} pkg=${s.packageName}")
        } catch (ex: Exception) {
            Log.w(TAG, "removeTask(${s.taskId}): ${ex.javaClass.simpleName}: ${ex.cause ?: ex.message}")
        }
        s.taskId = -1
    }

    /** ActivityManager.removeTask is @hide (uid-1000 exempt); reflect it once. */
    private var removeTaskMethod: java.lang.reflect.Method? = null
    private fun ActivityManager_removeTask(am: android.app.ActivityManager, taskId: Int) {
        val m = removeTaskMethod ?: android.app.ActivityManager::class.java
            .getMethod("removeTask", java.lang.Integer.TYPE).also { removeTaskMethod = it }
        m.invoke(am, taskId)
    }

    /** Force a measure/layout pass over a Compose-hosted container so freshly
     *  added children get laid out (Compose's interop holder does not react
     *  to the child requestLayout — see call-site comment). Uses the
     *  container's CURRENT on-screen size, i.e. exactly what Compose gave it. */
    private fun forceLayout(c: ViewGroup) {
        val w = c.width; val h = c.height
        if (w <= 0 || h <= 0) {
            Log.w(TAG, "forceLayout skipped: container not sized yet (${w}x$h)")
            return
        }
        try {
            c.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            c.layout(c.left, c.top, c.left + w, c.top + h)
        } catch (e: Exception) {
            Log.w(TAG, "forceLayout failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** IWindow for the Host — via ViewRootImpl (hidden) on the card's window.
     *  Round-4 unit verdict: this SPD fork has NO ViewRootImpl.getWindow()
     *  getter; fall back to the mWindow field, then to WindowManagerGlobal's
     *  root-window array (index 0 = our launcher window). */
    private fun hostWindow(container: View): Any? {
        return try {
            val vriCls = Class.forName("android.view.ViewRootImpl")
            val getVri = View::class.java.getMethod("getViewRootImpl")
            val vri = getVri.invoke(container.rootView) ?: return null
            try { return vriCls.getMethod("getWindow").invoke(vri) } catch (_: Exception) {}
            var k: Class<*>? = vriCls
            var fieldFound = false
            while (k != null) {
                try {
                    val f = k.getDeclaredField("mWindow")
                    f.isAccessible = true
                    fieldFound = true
                    val w = f.get(vri)
                    if (w != null) { Log.i(TAG, "hostWindow via $k.name.mWindow"); return w }
                    break
                } catch (_: NoSuchFieldException) {}
                k = k.superclass
            }
            if (!fieldFound) Log.w(TAG, "ViewRootImpl.mWindow field absent on this ROM")
            val wmg = Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getInstance").invoke(null)
            val f = wmg.javaClass.getDeclaredField("mWindows").apply { isAccessible = true }
            (f.get(wmg) as? Array<*>)?.firstOrNull().also {
                Log.i(TAG, "hostWindow via WindowManagerGlobal.mWindows[0]: ${it?.javaClass?.name ?: "null"}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "hostWindow unavailable: ${e.javaClass.simpleName}")
            null
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun teardown(s: Session) {
        Log.i(TAG, "tearing down ${s.mode} session token=${s.token} pkg=${s.packageName}")
        s.releasedByUs = true
        // Purge the task FIRST (mini-aa removeCurrentTask): a task orphaned by
        // a failed release() would linger hidden and shadow the next embed
        // launch (launch would resume the stale task; no leash, no render).
        try { purgeTask(s) } catch (_: Exception) {}
        s.embedder?.let { e ->
            // stop and release in SEPARATE try blocks: round-3 bench showed a
            // throwing stop() (embedder never initialized — session died before
            // its host surface) silently skipping release(), which unregisters
            // the organizer AND kills the launched task. A never-initialized
            // embedder has nothing to stop; release() must still run.
            // findMethod: these may be protected on the ROM class (getMethod
            // only sees public) — observed on the unit for stop/release chain.
            try { findMethod(e.javaClass, "stop").invoke(e) }
            catch (ex: Exception) { Log.w(TAG, "embedder stop (ignored): ${ex.javaClass.simpleName}: ${ex.cause ?: ex.message}") }
            try { findMethod(e.javaClass, "release").invoke(e) }
            catch (ex: Exception) { Log.w(TAG, "embedder release: ${ex.javaClass.simpleName}: ${ex.cause ?: ex.message}") }
        }
        try { s.container?.removeAllViews() } catch (_: Exception) {}
        sessions.remove(s.token)
    }

    /** Card-adjacent relaunch: engine stop + re-embed happens via the widget. */
    private fun embedPermissions(context: Context): Set<String> {
        val pm = context.packageManager
        return listOf(PERM_ACTIVITY_EMBEDDING, PERM_MANAGE_ACTIVITY_TASKS, PERM_MANAGE_ACTIVITY_STACKS)
            .filter { pm.checkPermission(it, context.packageName) == PackageManager.PERMISSION_GRANTED }
            .toSet()
    }

    /** boundsOnScreen can throw off-window views; be defensive (old ROMs). */
    private fun View.boundsOnScreenSafe(): android.graphics.Rect = try {
        val loc = IntArray(2); getLocationOnScreen(loc)
        android.graphics.Rect(loc[0], loc[1], loc[0] + width, loc[1] + height)
    } catch (_: Exception) { android.graphics.Rect(0, 0, width, height) }

    private val launchLogTimeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    private fun timeStamp(): String = launchLogTimeFormat.format(java.util.Date())
}

/** Shared launch-intent lookup (same shape as PipWidget's, without its flags). */
internal fun launchIntentFor(pm: PackageManager, packageName: String): Intent? =
    pm.getLaunchIntentForPackage(packageName)
        ?: pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).setPackage(packageName), 0)
            .firstOrNull()?.activityInfo?.let { ai ->
                Intent(Intent.ACTION_MAIN).apply { setClassName(ai.packageName, ai.name) }
            }
