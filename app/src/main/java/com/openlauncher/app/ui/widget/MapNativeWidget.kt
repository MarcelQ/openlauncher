package com.openlauncher.app.ui.widget

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.openlauncher.app.BuildConfig
import com.openlauncher.app.util.LocationData
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * MAP_NATIVE card (branch exp-mapcard): an interactive osmdroid map rendered
 * INSIDE the launcher card — the "embed our own content, hand off navigation"
 * direction from docs/float-window-research.md. Tiles come from
 * tile.openstreetmap.org (no API key, Apache-2 osmdroid). NAVIGATE performs a
 * standard `geo:` handoff to Google Maps/Waze/etc. — the launch-don't-embed
 * model the vendor's own SystemUI float sidebar uses.
 *
 * All in-card controls are native Android Views layered over the MapView in
 * ONE FrameLayout: the classic AndroidView compositing path draws the view
 * subtree over sibling Compose content, so Compose overlay Text/Buttons placed
 * above an AndroidView render mis-placed (screenshot bug 2026-11-18 00:47).
 * Keeping the whole card body one view subtree avoids the z-order issue on
 * every API level (the interop fix needs API 29+, our floor is 21).
 *
 * Offline-first: osmdroid caches tiles; without network the card keeps the
 * last view/cache and degrades to blank tiles, never a crash. While the grid
 * is in edit mode the MapView is not instantiated at all (no tile traffic
 * during edits, no touch fights with the resize handles).
 */
private class MapCardViews(
    val root: FrameLayout,
    val map: MapView,
    val status: TextView,
)

@Composable
fun MapNativeWidget(
    location: LocationData?,
    accent: Color,
    isDayMode: Boolean,
    isEditing: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val textColor = if (isDayMode) 0xFF666666.toInt() else 0xFF888888.toInt()

    if (isEditing) {
        AndroidView(
            modifier = modifier,
            factory = {
                TextView(appContext).apply {
                    text = "MAP\nlive when editing is off"
                    setTextColor(textColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    gravity = Gravity.CENTER
                }
            },
        )
        return
    }

    // Idempotent per-process osmdroid config load (User-Agent is an OSM tile
    // etiquette requirement, not an optional nicety).
    val configured = remember {
        Configuration.getInstance().load(
            appContext,
            appContext.getSharedPreferences("osmdroid_prefs", 0),
        )
        Configuration.getInstance().userAgentValue =
            MapCardLogic.osmUserAgent(BuildConfig.VERSION_NAME)
        true
    }

    // Latest fix; native click handlers close over this state holder.
    val latestFix = remember { mutableStateOf<LocationData?>(null) }
    latestFix.value = location

    val views = remember(configured) {
        val map = MapView(appContext).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setUseDataConnection(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(MapCardLogic.initialZoomFor(null))
        }
        val root = FrameLayout(appContext)
        root.addView(map, FrameLayout.LayoutParams(-1, -1))

        val status = TextView(appContext).apply {
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            text = "NO GPS FIX"
        }
        root.addView(
            status,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                setMargins(16, 12, 16, 12)
            },
        )

        val chips = LinearLayout(appContext).apply { orientation = LinearLayout.HORIZONTAL }
        // Plain TextViews, NOT Button widgets: the launcher Activity uses a
        // plain DeviceDefault theme where AppCompat/Material Buttons crash.
        val mkChip: (String, () -> Unit) -> TextView = { label, onClick ->
            TextView(appContext).apply {
                text = label
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                typeface = Typeface.MONOSPACE
                setPadding(16, 10, 16, 10)
                setBackgroundResource(android.R.drawable.btn_default)
                isClickable = true
                setOnClickListener { onClick() }
            }
        }
        chips.addView(mkChip("RECENTRE") {
            latestFix.value?.let { loc ->
                map.controller.setZoom(MapCardLogic.initialZoomFor(loc.accuracy))
                map.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
                map.invalidate()
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(8, 0, 0, 0) })
        chips.addView(mkChip("NAVIGATE") {
            val loc = latestFix.value
            if (loc == null) {
                Toast.makeText(appContext, "No GPS fix yet", Toast.LENGTH_SHORT).show()
            } else {
                val uri = Uri.parse(MapCardLogic.geoHandoffUri(loc.latitude, loc.longitude))
                try {
                    appContext.startActivity(
                        Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(appContext, "No maps app can handle this location", Toast.LENGTH_SHORT).show()
                }
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(8, 0, 0, 0) })
        root.addView(
            chips,
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, 8, 4)
            },
        )

        MapCardViews(root, map, status)
    }

    val positionMarker = remember(views.map) {
        Marker(views.map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(appContext, android.R.drawable.presence_online)
        }
    }

    // First fix frames the map; later fixes only move the marker + status
    // line, so the user's own pan/zoom survives driving.
    var framedFirstFix by remember { mutableStateOf(false) }
    LaunchedEffect(location?.latitude, location?.longitude) {
        val loc = location ?: return@LaunchedEffect
        views.status.text = MapCardLogic.formatLatLon(loc.latitude, loc.longitude)
        val pt = GeoPoint(loc.latitude, loc.longitude)
        if (!views.map.overlays.contains(positionMarker)) views.map.overlays.add(positionMarker)
        positionMarker.setPosition(pt)
        if (!framedFirstFix) {
            framedFirstFix = true
            views.map.controller.setZoom(MapCardLogic.initialZoomFor(loc.accuracy))
            views.map.controller.setCenter(pt)
        }
        views.map.invalidate()
    }

    AndroidView(
        modifier = modifier,
        factory = { views.root },
        // Card removed / page swapped away: stop the tile fetch threads.
        onRelease = { _ -> views.map.setUseDataConnection(false) },
    )
}
