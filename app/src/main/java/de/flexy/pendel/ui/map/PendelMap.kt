package de.flexy.pendel.ui.map

import android.graphics.PointF
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.flexy.pendel.core.geo.LatLon
import de.flexy.pendel.ui.theme.RoutePalette
import de.flexy.pendel.ui.theme.WaitRamp
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

data class MapLine(val id: String, val points: List<LatLon>, val colorIndex: Int, val opacity: Float)

/** A point marker; [value] drives size/color (wait seconds for intersections, duration for stops). */
data class MapMarker(val id: Long, val lat: Double, val lon: Double, val value: Double, val kind: String)

data class MapLayers(
    val lines: List<MapLine> = emptyList(),
    val markers: List<MapMarker> = emptyList(),
    val intersections: List<MapMarker> = emptyList(),
    /** Wait events as heatmap points (value = seconds). */
    val heat: List<MapMarker> = emptyList(),
    val showLines: Boolean = true,
    val showMarkers: Boolean = true,
    val showIntersections: Boolean = true,
    val showHeat: Boolean = false,
)

private const val SRC_LINES = "pendel-lines"
private const val SRC_MARKERS = "pendel-markers"
private const val SRC_INTER = "pendel-intersections"
private const val SRC_HEAT = "pendel-heat"
const val LAYER_INTER = "pendel-intersections-layer"
private const val LAYER_LINES = "pendel-lines-layer"
private const val LAYER_MARKERS = "pendel-markers-layer"
private const val LAYER_HEAT = "pendel-heat-layer"

private fun Color.hex(): String = String.format("#%06X", 0xFFFFFF and toArgb())

/**
 * MapLibre map (OpenFreeMap vector tiles by default, no API key) with GeoJSON layers for routes,
 * stops, intersections and a wait-time heatmap. Only the visible map area is requested from the
 * tile server; tracks are rendered locally.
 */
@Composable
fun PendelMap(
    layers: MapLayers,
    styleUrlLight: String,
    styleUrlDark: String,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    onIntersectionClick: ((Long) -> Unit)? = null,
) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val density = LocalDensity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val styleUrl = if (dark) styleUrlDark else styleUrlLight
    val clickHandler by rememberUpdatedState(onIntersectionClick)

    val mapView = remember {
        MapView(context, MapLibreMapOptions.createFromAttributes(context, null).textureMode(true)).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var fittedKey by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        // offline-first: if the online style cannot be loaded, fall back to a plain local style
        // so recorded tracks are still visible without network
        mapView.addOnDidFailLoadingMapListener {
            val m = map ?: return@addOnDidFailLoadingMapListener
            if (style == null) {
                m.setStyle(Style.Builder().fromJson(offlineStyle(dark))) { s ->
                    setupLayers(s, dark)
                    style = s
                }
            }
        }
        mapView.getMapAsync { m ->
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            if (!interactive) m.uiSettings.setAllGesturesEnabled(false)
            m.addOnMapClickListener { latLng ->
                val handler = clickHandler ?: return@addOnMapClickListener false
                val pt: PointF = m.projection.toScreenLocation(latLng)
                val r = 24f
                val features = m.queryRenderedFeatures(android.graphics.RectF(pt.x - r, pt.y - r, pt.x + r, pt.y + r), LAYER_INTER)
                val id = features.firstOrNull()?.getNumberProperty("id")?.toLong() ?: return@addOnMapClickListener false
                handler(id)
                true
            }
            map = m
        }
    }

    LaunchedEffect(map, styleUrl) {
        val m = map ?: return@LaunchedEffect
        style = null
        m.setStyle(Style.Builder().fromUri(styleUrl)) { s ->
            setupLayers(s, dark)
            style = s
        }
    }

    LaunchedEffect(style, layers, dark) {
        val s = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        updateSources(s, layers, dark)
        // fit camera once per distinct data set
        val pts = layers.lines.flatMap { it.points } + layers.markers.map { LatLon(it.lat, it.lon) }
        val key = "${pts.size}-${pts.firstOrNull()}-${pts.lastOrNull()}"
        if (pts.isNotEmpty() && key != fittedKey) {
            fittedKey = key
            val padding = with(density) { 32f * this.density }.toInt()
            if (pts.size == 1 || pts.all { it == pts.first() }) {
                m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(pts[0].lat, pts[0].lon), 15.0))
            } else {
                val b = LatLngBounds.Builder()
                pts.forEach { b.include(LatLng(it.lat, it.lon)) }
                runCatching { m.moveCamera(CameraUpdateFactory.newLatLngBounds(b.build(), padding)) }
            }
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun offlineStyle(dark: Boolean): String {
    val bg = if (dark) "#1A1A19" else "#F2F1EE"
    return """{"version":8,"sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"$bg"}}]}"""
}

private fun setupLayers(s: Style, dark: Boolean) {
    s.addSource(GeoJsonSource(SRC_HEAT, FeatureCollection.fromFeatures(emptyList<Feature>())))
    s.addSource(GeoJsonSource(SRC_LINES, FeatureCollection.fromFeatures(emptyList<Feature>())))
    s.addSource(GeoJsonSource(SRC_MARKERS, FeatureCollection.fromFeatures(emptyList<Feature>())))
    s.addSource(GeoJsonSource(SRC_INTER, FeatureCollection.fromFeatures(emptyList<Feature>())))

    // Wait-time "heat": blurred circles instead of a MapLibre HeatmapLayer. The heatmap layer needs
    // an offscreen half-float render target and crashed on some GPUs when switched on.
    s.addLayer(
        CircleLayer(LAYER_HEAT, SRC_HEAT).withProperties(
            PropertyFactory.circleRadius(Expression.get("r")),
            PropertyFactory.circleColor(Expression.get("color")),
            PropertyFactory.circleBlur(1f),
            PropertyFactory.circleOpacity(0.7f),
            PropertyFactory.visibility(Property.NONE),
        ),
    )
    s.addLayer(
        LineLayer(LAYER_LINES, SRC_LINES).withProperties(
            PropertyFactory.lineColor(Expression.get("color")),
            PropertyFactory.lineOpacity(Expression.get("opacity")),
            PropertyFactory.lineWidth(4f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    val surface = if (dark) "#1A1A19" else "#FFFFFF"
    s.addLayer(
        CircleLayer(LAYER_MARKERS, SRC_MARKERS).withProperties(
            PropertyFactory.circleRadius(Expression.get("r")),
            PropertyFactory.circleColor(Expression.get("color")),
            PropertyFactory.circleStrokeColor(surface),
            PropertyFactory.circleStrokeWidth(1.5f),
        ),
    )
    s.addLayer(
        CircleLayer(LAYER_INTER, SRC_INTER).withProperties(
            PropertyFactory.circleRadius(Expression.get("r")),
            PropertyFactory.circleColor(Expression.get("color")),
            PropertyFactory.circleStrokeColor(surface),
            PropertyFactory.circleStrokeWidth(2f),
        ),
    )
}

private fun updateSources(s: Style, l: MapLayers, dark: Boolean) {
    val lines = if (!l.showLines) emptyList() else l.lines.filter { it.points.size >= 2 }.map { line ->
        Feature.fromGeometry(LineString.fromLngLats(line.points.map { Point.fromLngLat(it.lon, it.lat) })).apply {
            addStringProperty("color", RoutePalette.color(line.colorIndex, dark).hex())
            addNumberProperty("opacity", line.opacity)
        }
    }
    s.getSourceAs<GeoJsonSource>(SRC_LINES)?.setGeoJson(FeatureCollection.fromFeatures(lines))

    val markers = if (!l.showMarkers) emptyList() else l.markers.map { mk ->
        Feature.fromGeometry(Point.fromLngLat(mk.lon, mk.lat)).apply {
            addNumberProperty("id", mk.id)
            addNumberProperty("r", (3.0 + kotlin.math.sqrt(mk.value.coerceAtLeast(0.0).takeIf { it.isFinite() } ?: 0.0) * 0.6).coerceAtMost(11.0))
            addStringProperty("color", WaitRamp.at(0.6, dark).hex())
        }
    }
    s.getSourceAs<GeoJsonSource>(SRC_MARKERS)?.setGeoJson(FeatureCollection.fromFeatures(markers))

    val maxWait = l.intersections.maxOfOrNull { it.value }?.takeIf { it > 0 && it.isFinite() } ?: 1.0
    val inter = if (!l.showIntersections) emptyList() else l.intersections.map { x ->
        val t = (x.value / maxWait).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
        Feature.fromGeometry(Point.fromLngLat(x.lon, x.lat)).apply {
            addNumberProperty("id", x.id)
            addNumberProperty("r", 6.0 + 8.0 * kotlin.math.sqrt(t))
            addStringProperty("color", WaitRamp.at(0.25 + 0.75 * t, dark).hex())
        }
    }
    s.getSourceAs<GeoJsonSource>(SRC_INTER)?.setGeoJson(FeatureCollection.fromFeatures(inter))

    val heatPts = l.heat.filter { it.value.isFinite() && it.value > 0 && it.lat.isFinite() && it.lon.isFinite() }
    val maxHeat = heatPts.maxOfOrNull { it.value } ?: 1.0
    val heat = heatPts.map { h ->
        val w = (h.value / maxHeat).coerceIn(0.0, 1.0)
        Feature.fromGeometry(Point.fromLngLat(h.lon, h.lat)).apply {
            addNumberProperty("r", 10.0 + 22.0 * kotlin.math.sqrt(w))
            addStringProperty("color", WaitRamp.at(0.3 + 0.7 * w, dark).hex())
        }
    }
    s.getSourceAs<GeoJsonSource>(SRC_HEAT)?.setGeoJson(FeatureCollection.fromFeatures(heat))
    s.getLayer(LAYER_HEAT)?.setProperties(PropertyFactory.visibility(if (l.showHeat) Property.VISIBLE else Property.NONE))
}
