package com.openlauncher.app.ui.widget

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.util.Log
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.openlauncher.app.ui.widget.PipLaunchMode.*

/**
 * "PIP" app-overlay card: launches the assigned app as a freeform window sized
 * to this card's on-screen rect, layered over the launcher — the freeform
 * chain, no platform signing required. (The old platform-signed ActivityView
 * in-card embedding path is gone; it only worked on ROMs accepting the AOSP
 * test platform key.)
 *
 * Launch chain and bounds decision are in PipFreeform.kt (JVM-tested). Every
 * attempt logs under the `PipFreeform` tag — absence of an "OK" line means the
 * card never launched, and each failed attempt names its reason.
 */
@Composable
fun PipWidget(
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
            Text(
                "ASSIGN APP",
                color         = dimColor,
                fontSize      = 8.sp,
                letterSpacing = 1.5.sp,
                textAlign     = TextAlign.Center
            )
        }
        return
    }

    var screenBounds by remember { mutableStateOf<FreeformBounds?>(null) }

    val pm    = context.packageManager
    val icon  = remember(packageName) {
        runCatching { pm.getApplicationIcon(packageName).toBitmap(96, 96) }.getOrNull()
    }
    val label = remember(packageName) {
        runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
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
                    rootX  = rootPos[0], rootY = rootPos[1],
                    cardX  = winPos.x.toInt(), cardY = winPos.y.toInt(),
                    width  = coords.size.width, height = coords.size.height
                )
            }
            .then(if (!isEditing) Modifier.clickable {
                launchOverLauncher(context, packageName, screenBounds)
            } else Modifier)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (icon != null) {
                Image(
                    painter            = BitmapPainter(icon.asImageBitmap()),
                    contentDescription = label,
                    modifier           = Modifier.size(48.dp)
                )
            } else {
                Icon(Icons.Default.PlayArrow, null, tint = accent, modifier = Modifier.size(48.dp))
            }
            Text(
                text          = label.uppercase(),
                color         = dimColor,
                fontSize      = 8.sp,
                letterSpacing = 1.5.sp,
                textAlign     = TextAlign.Center,
                maxLines      = 1
            )
            Text(
                text          = "TAP TO OPEN",
                color         = accent.copy(alpha = 0.6f),
                fontSize      = 7.sp,
                letterSpacing = 1.sp,
                textAlign     = TextAlign.Center
            )
        }
    }
}

private const val TAG = "PipFreeform"

/**
 * Walks [pipLaunchPlan] and starts [packageName] with the first strategy that
 * doesn't throw. Returns the mode that started it, or null when nothing could.
 *
 * Honest caveat: some ROMs accept a freeform request without throwing and then
 * silently open the app fullscreen — that also counts as "OK" here; the visual
 * result (and `adb shell dumpsys window windows | findstr WindowingMode`) is
 * the real verdict.
 */
private fun launchOverLauncher(context: Context, packageName: String, bounds: FreeformBounds?): PipLaunchMode? {
    val pm = context.packageManager
    val intent = pm.getLaunchIntentForPackage(packageName)
        ?: pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).setPackage(packageName), 0
        ).firstOrNull()?.activityInfo?.let { ai ->
            Intent(Intent.ACTION_MAIN).apply { setClassName(ai.packageName, ai.name) }
        }
    if (intent == null) {
        Log.w(TAG, "no launch intent for $packageName")
        return null
    }
    // MULTIPLE_TASK so an already-running app opens a second freeform instance
    // over the launcher instead of just raising its existing fullscreen task.
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

    for (mode in pipLaunchPlan(bounds?.isValid == true)) {
        try {
            when (mode) {
                FREEFORM_REFLECTION -> {
                    val opts = ActivityOptions.makeBasic()
                    // @hide @UnsupportedAppUsage — throws NoSuchMethodException where
                    // the platform enforces hidden-API gating; the BUNDLE mode below
                    // writes the same value through the public bundle instead.
                    ActivityOptions::class.java
                        .getMethod("setLaunchWindowingMode", java.lang.Integer.TYPE)
                        .invoke(opts, WINDOWING_MODE_FREEFORM)
                    applyLaunchBounds(opts, bounds!!)
                    context.startActivity(intent, opts.toBundle())
                }
                FREEFORM_BUNDLE -> {
                    val opts = ActivityOptions.makeBasic()
                    applyLaunchBounds(opts, bounds!!)
                    val bundle = opts.toBundle()
                    bundle.putInt(KEY_LAUNCH_WINDOWING_MODE, WINDOWING_MODE_FREEFORM)
                    context.startActivity(intent, bundle)
                }
                FULLSCREEN -> context.startActivity(intent)
            }
            Log.i(TAG, "$mode OK pkg=$packageName bounds=$bounds")
            return mode
        } catch (e: Exception) {
            Log.w(TAG, "$mode failed for $packageName: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
    return null
}

/** ActivityOptions.setLaunchBounds is public since API 24; minSdk 21 guards it. */
private fun applyLaunchBounds(opts: ActivityOptions, bounds: FreeformBounds) {
    if (Build.VERSION.SDK_INT >= 24) {
        opts.setLaunchBounds(Rect(bounds.left, bounds.top, bounds.right, bounds.bottom))
    }
}
