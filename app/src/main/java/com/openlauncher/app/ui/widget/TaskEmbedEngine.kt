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
 *   android.window.TaskOrganizer.registerOrganizer(int displayId) + manual
 *     leash reparent via reflectively-discovered SurfaceControl$Transaction
 *     ← engine 3 (fully manual fallback), then FALLBACK_LAUNCH (PipWidget
 *     chain) so the card is never dead.
 *
 * Hidden-API gating is BLOCKED-flagged in the ROM dex, but uid 1000 callers
 * are exempt — which is why this only engages for the platform-signed
 * aospDebug build re-signed with the vendor platform key (see docs
 * float-window-research.md §F). Normal builds log the gate and fall back.
 *
 * One live session process-wide (prototype parity with the single
 * pip/embed app-package settings): most recent claimer wins.
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
        var releasedByUs: Boolean = false
    )

    private var session: Session? = null
    private var tokenSeq = 0

    fun isLiveFor(token: Int, packageName: String): Boolean = synchronized(this) {
        session?.let { it.token == token && it.packageName == packageName && it.mode != FALLBACK_LAUNCH } == true
    }

    fun stop(token: Int) = synchronized(this) {
        if (session?.token == token) teardown(session!!)
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
        synchronized(this) {
            val prev = session
            if (prev != null && prev.embedder != null && prev.mode != FALLBACK_LAUNCH &&
                prev.packageName == packageName && prev.bounds == bounds && prev.container === container) {
                prev.token = token
                Log.i(TAG, "reusing ${prev.mode} session for $packageName (token $token)")
                return prev.mode
            }
            session?.let { teardown(it) }
        }

        for (mode in embedChain(systemUid, sdk, bounds.isValid && embedEnginesAllowed(systemUid, sdk, manage))) {
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
                        synchronized(this) { session = Session(token, packageName, bounds, FALLBACK_LAUNCH, null, null, container) }
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
                "getScreenToTaskMatrix"       -> Matrix()
                "canReceivePointerEvents"     -> container.isShown && container.width > 0
                "getTapExcludeRegion"         -> Region()
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
                            session?.takeIf { it.token == token && !it.releasedByUs }
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
                    "onTaskCreated"     -> { TaskEmbedLog.record("${timeStamp()} $pkg :: task created id=${a?.getOrNull(0)} comp=${a?.getOrNull(1)}"); null }
                    "onBackPressedOnTaskRoot" -> { TaskEmbedLog.record("${timeStamp()} $pkg :: back-press at task root"); null }
                    else -> null
                }
            })
        baseCls.getMethod("setListener", listenerCls).invoke(embedder, listener)

        // Anchor SurfaceView: the embedder's initialize() needs ITS surface,
        // which Android creates asynchronously — so initialize + start +
        // launch run from surfaceCreated, not synchronously here (calling
        // start() before initialize was the bench bug: the engine chain
        // "succeeded" without a host surface and the card fell back).
        val anchor = SurfaceView(activity)
        container.addView(anchor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val mode = if (implCls.name == TE_IMPL_CLASS) TASK_EMBEDDER else TASK_ORGANIZER
        synchronized(this) { session = Session(token, pkg, bounds, mode, embedder, hostProxy, container) }

        anchor.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                try {
                    initializeAndLaunch(activity, baseCls, embedder, anchor, pkg)
                } catch (e: Exception) {
                    TaskEmbedLog.record("${timeStamp()} $pkg :: ${implCls.simpleName} host-surface init failed(${e.javaClass.simpleName}: ${e.message})")
                    Log.w(TAG, "host surface init failed for $pkg", e)
                    // Degrade THIS card to the tap-to-open overlay state so it
                    // is never a dead surface.
                    synchronized(this@TaskEmbedEngine) { session?.takeIf { it.token == token }?.mode = FALLBACK_LAUNCH }
                    onAsyncFail(FALLBACK_LAUNCH)
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                TaskEmbedLog.record("${timeStamp()} $pkg :: host surface destroyed")
            }
        })

        TaskEmbedLog.record("${timeStamp()} $pkg :: ${implCls.simpleName} constructed; waiting for host surface")
        Log.i(TAG, "${implCls.simpleName} waiting for host surface pkg=$pkg bounds=$bounds")
        return mode
    }

    /**
     * Surface-driven activation (dex-verified API 30 SPD shape):
     * initialize(SurfaceControl) -> isInitialized/start() ->
     * prepareActivityOptions -> startActivity(PendingIntent, Intent, ActivityOptions).
     * SurfaceView.getSurfaceControl is @hide (uid-1000 exempt); the
     * SurfaceControl class itself is public API 29+, which is why the engine
     * gate in TaskEmbed.kt starts at 29.
     */
    private fun initializeAndLaunch(activity: Activity, baseCls: Class<*>, embedder: Any, anchor: SurfaceView, pkg: String) {
        val sc = SurfaceView::class.java.getMethod("getSurfaceControl").invoke(anchor)
            ?: throw IllegalStateException("no host surface control")
        val scCls = Class.forName("android.view.SurfaceControl")
        val init = baseCls.getMethod("initialize", scCls).invoke(embedder, sc) as? Boolean ?: false
        if (!init) throw IllegalStateException("initialize(surface) returned false")
        val isInit = baseCls.getMethod("isInitialized").invoke(embedder) as? Boolean ?: false
        if (!isInit) baseCls.getMethod("start").invoke(embedder)

        val intent = launchIntentFor(activity.packageManager, pkg)
            ?: throw IllegalStateException("no launch intent")
        val opts = ActivityOptions.makeBasic()
        val prepared = baseCls.getMethod("prepareActivityOptions", ActivityOptions::class.java).invoke(embedder, opts)
        val pi = android.app.PendingIntent.getActivity(
            activity, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            baseCls.getMethod("startActivity", android.app.PendingIntent::class.java, Intent::class.java, ActivityOptions::class.java)
                .invoke(embedder, pi, intent, prepared)
        } catch (nsme: NoSuchMethodException) {
            baseCls.getMethod("startActivity", android.app.PendingIntent::class.java).invoke(embedder, pi)
        }
        TaskEmbedLog.record("${timeStamp()} $pkg :: embed launched (host surface ready)")
        Log.i(TAG, "embed launched pkg=$pkg")
    }

    /** Card resized while an embed is live: ask the framework to resize the task. */
    fun resize(token: Int, width: Int, height: Int) {
        val s = synchronized(this) { session?.takeIf { it.token == token } } ?: return
        val e = s.embedder ?: return
        try {
            e.javaClass.getMethod("resizeTask", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(e, width, height)
        } catch (ex: Exception) {
            Log.w(TAG, "resizeTask(${width}x$height): ${ex.javaClass.simpleName}: ${ex.message}")
        }
    }

    /** IWindow for the Host — via ViewRootImpl (hidden) on the card's window. */
    private fun hostWindow(container: View): Any? {
        return try {
            val vriCls = Class.forName("android.view.ViewRootImpl")
            val getVri = View::class.java.getMethod("getViewRootImpl")
            val vri = getVri.invoke(container.rootView)
            vri?.let { vriCls.getMethod("getWindow").invoke(it) }
        } catch (e: Exception) {
            Log.w(TAG, "hostWindow unavailable: ${e.javaClass.simpleName}")
            null
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun teardown(s: Session) {
        s.releasedByUs = true
        try {
            s.embedder?.let { e ->
                e.javaClass.getMethod("stop").invoke(e)
                e.javaClass.getMethod("release").invoke(e)
            }
        } catch (e: Exception) { Log.w(TAG, "embedder teardown: ${e.javaClass.simpleName}: ${e.message}") }
        try { s.container?.removeAllViews() } catch (_: Exception) {}
        if (session === s) session = null
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
