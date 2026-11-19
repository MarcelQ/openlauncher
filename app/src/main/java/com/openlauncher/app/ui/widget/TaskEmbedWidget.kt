package com.openlauncher.app.ui.widget

import android.app.Activity
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import com.openlauncher.app.ui.widget.EmbedMode.FALLBACK_LAUNCH

/**
 * "APP EMBED" card: renders the assigned app's LIVE window INSIDE the card and
 * resizes it with the card footprint — the in-card embedding path (engine +
 * decision helpers in TaskEmbedEngine.kt / TaskEmbed.kt, ROM-verified against
 * the dev's SPD unit's framework.jar).
 *
 * Requires the platform-signed build running at system uid (aospDebug re-signed
 * with the vendor platform key); everywhere else it degrades to the APP
 * OVERLAY behaviour (freeform-over-launcher chain on tap) and says so in the
 * Diagnostics log ring (TaskEmbedLog) — never a dead card.
 *
 * Status caveat: engine outcomes are logged, but "task actually visible +
 * touches forwarded" is bench-verdict territory (dumpsys window + eyes), same
 * honest rule as PipFreeform's ROM-downgrade caveat.
 */
@Composable
fun TaskEmbedWidget(
    packageName: String,
    accent: Color,
    isDayMode: Boolean,
    isEditing: Boolean,
    onAssign: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tileBg   = if (isDayMode) Color(0xFFFFFFFF) else Color(0xFF0B0B0B)
    val dimColor = if (isDayMode) Color(0xFF888888) else Color(0xFF555555)
    val context  = LocalContext.current
    val rootView = LocalView.current

    if (packageName.isEmpty()) {
        Column(
            modifier            = modifier.background(tileBg)
                .then(if (!isEditing) Modifier.clickable(onClick = onAssign) else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Add, null, tint = accent.copy(alpha = 0.6f), modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(6.dp))
            Text("ASSIGN APP", color = dimColor, fontSize = 8.sp, letterSpacing = 1.5.sp, textAlign = TextAlign.Center)
        }
        return
    }

    var screenBounds by remember { mutableStateOf<FreeformBounds?>(null) }
    val container = remember { FrameLayout(context) }
    val dm        = context.resources.displayMetrics
    var embedToken by remember { mutableIntStateOf(0) }
    var liveMode   by remember { mutableStateOf<EmbedMode?>(null) }

    // (Re)start embedding on assignment change; stop when cleared or editing.
    // The delay is a settle window: during launcher boot the card composable
    // is recreated several times in a burst (grid + settings flows both emit),
    // and each recreation would otherwise drive a full embed/teardown cycle
    // (round-3 bench: ~8 per 160 ms on the unit). A cancelled LaunchedEffect
    // never reaches embed — only the final, settled one does.
    LaunchedEffect(packageName, isEditing) {
        if (isEditing || packageName.isEmpty()) {
            if (embedToken != 0) { TaskEmbedEngine.stop(embedToken); embedToken = 0 }
            liveMode = null
        } else {
            kotlinx.coroutines.delay(350)
            val act = context as? Activity
            val b   = screenBounds
            if (act != null && b != null && embedRectOnScreen(b, dm.widthPixels, dm.heightPixels)) {
                if (embedToken != 0) TaskEmbedEngine.stop(embedToken)
                embedToken = TaskEmbedEngine.nextToken()
                liveMode   = TaskEmbedEngine.embedWithToken(act, container, packageName, b, embedToken) { mode -> liveMode = mode }
            }
        }
    }

    // Bounds move: page switches take the card off screen (stop the session —
    // embedding to pager-offset coords would render the task nowhere visible)
    // and bring it back (embed fresh). An IN-PLACE card resize resizes the
    // live task via the framework instead of relaunching it.
    LaunchedEffect(screenBounds, packageName, isEditing) {
        val act = context as? Activity
        val b   = screenBounds
        if (isEditing || packageName.isEmpty() || act == null || b == null) return@LaunchedEffect
        if (!embedRectOnScreen(b, dm.widthPixels, dm.heightPixels)) {
            // Grace window before tearing down: an embedded task taking
            // fullscreen focus relayouts the launcher grid for a frame or two
            // (round-5 unit log: card oscillated 1189px <-> 1264px wide, each
            // transient off-screen blip stopped the session -> destroy/re-embed
            // loop forever). A real page swipe keeps the card off screen, so
            // the re-check still tears down then.
            kotlinx.coroutines.delay(400)
            val b2 = screenBounds ?: return@LaunchedEffect
            if (!embedRectOnScreen(b2, dm.widthPixels, dm.heightPixels)) {
                if (embedToken != 0) { TaskEmbedEngine.stop(embedToken); embedToken = 0; liveMode = null }
            }
        } else if (embedToken == 0) {
            // Same settle window as the assignment effect: boot recompositions
            // move the card by ~1 px per pass; embed only once bounds are calm.
            kotlinx.coroutines.delay(350)
            val b2 = screenBounds ?: return@LaunchedEffect
            if (embedToken != 0 || !embedRectOnScreen(b2, dm.widthPixels, dm.heightPixels)) return@LaunchedEffect
            embedToken = TaskEmbedEngine.nextToken()
            liveMode   = TaskEmbedEngine.embedWithToken(act, container, packageName, b2, embedToken) { mode -> liveMode = mode }
        } else if (liveMode == EmbedMode.TASK_EMBEDDER || liveMode == EmbedMode.TASK_ORGANIZER) {
            TaskEmbedEngine.resize(embedToken, b.right - b.left, b.bottom - b.top)
        }
    }

    // Stop the session when THIS card leaves composition (page swipe-out,
    // card delete, app exit). MUST be keyed on Unit, not embedToken:
    // a token re-key runs onDispose AFTER embedToken was reassigned, and
    // since embedToken is a State-backed property the lambda reads the NEW
    // token — round-3/4 bench proved that self-destructed every session
    // ~30 ms after embed (stop→purge→relaunch churn, surface never born).
    // Session replacement itself is handled by the effects' explicit stop()
    // of the OLD token before claiming a new one.
    DisposableEffect(Unit) {
        onDispose {
            val t = embedToken
            if (t != 0) {
                android.util.Log.i("TaskEmbed", "card disposed: stop token=$t")
                TaskEmbedEngine.stop(t); embedToken = 0
            }
        }
    }

    val pm    = context.packageManager
    val icon  = remember(packageName) { runCatching { pm.getApplicationIcon(packageName).toBitmap(96, 96) }.getOrNull() }
    val label = remember(packageName) {
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }.getOrDefault(packageName)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(tileBg)
            .onGloballyPositioned { coords ->
                val rootPos = IntArray(2)
                rootView.getLocationOnScreen(rootPos)
                val winPos = coords.positionInWindow()
                screenBounds = freeformBoundsOnScreen(
                    rootX = rootPos[0], rootY = rootPos[1],
                    cardX = winPos.x.toInt(), cardY = winPos.y.toInt(),
                    width = coords.size.width, height = coords.size.height
                )
            }
            .then(if (liveMode == FALLBACK_LAUNCH) Modifier.clickable {
                val mode = embedFallbackTap(isEditing = false, hasApp = true)
                if (mode != null) {
                    android.util.Log.i("TaskEmbed", "card tapped -> fallback freeform launch $packageName")
                    launchOverLauncher(context, packageName, screenBounds)
                }
            } else Modifier)
    ) {
        // Live engines render into this container (the framework attaches the
        // task surface / anchor SurfaceView into it). Fallback keeps it empty.
        AndroidView(
            factory  = { container },
            modifier = Modifier.fillMaxSize()
        )

        if (liveMode == null || liveMode == FALLBACK_LAUNCH) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (icon != null) {
                    Image(painter = BitmapPainter(icon.asImageBitmap()), contentDescription = label, modifier = Modifier.size(48.dp))
                } else {
                    Icon(Icons.Default.PlayArrow, null, tint = accent, modifier = Modifier.size(48.dp))
                }
                Text(
                    text = label.uppercase(), color = dimColor, fontSize = 8.sp,
                    letterSpacing = 1.5.sp, textAlign = TextAlign.Center, maxLines = 1
                )
                Text(
                    text = if (liveMode == FALLBACK_LAUNCH) "TAP TO OPEN (OVERLAY MODE)" else "STARTING EMBED…",
                    color = accent.copy(alpha = 0.6f), fontSize = 7.sp, letterSpacing = 1.sp, textAlign = TextAlign.Center
                )
            }
        }

        // Link-state glyph (same visual language as the TPMS footer glyph):
        // a real in-card embed vs the overlay fallback must never be a guess.
        if (packageName.isNotEmpty() && liveMode != null && !isEditing) {
            val mode = liveMode
            val dotColor = when (mode) {
                EmbedMode.TASK_EMBEDDER, EmbedMode.TASK_ORGANIZER -> Color(0xFF33C14A) // live surface
                FALLBACK_LAUNCH                                   -> Color(0xFFC9A227) // overlay tap-open
                null                                              -> Color(0xFF777777)
            }
            val dotDesc = when (mode) {
                EmbedMode.TASK_EMBEDDER  -> "Embed: live in-card surface (TaskEmbedder)"
                EmbedMode.TASK_ORGANIZER -> "Embed: live in-card surface (VirtualDisplay)"
                FALLBACK_LAUNCH          -> "Embed: overlay fallback, tap opens the app"
                null                     -> "Embed: starting"
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(9.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
                    .semantics { contentDescription = dotDesc }
            )
        }
    }
}
