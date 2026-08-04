package hr.sonicpulse.app.ui.map

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.location.LocationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hr.sonicpulse.app.R
import hr.sonicpulse.app.domain.model.Hotspot
import hr.sonicpulse.app.ui.permissions.PermissionDecisionEvaluator
import hr.sonicpulse.app.ui.permissions.SinglePermissionDecision
import hr.sonicpulse.app.ui.theme.AppShapes
import hr.sonicpulse.app.ui.theme.MonospaceValueStyle
import hr.sonicpulse.app.ui.theme.SemanticColors
import hr.sonicpulse.app.ui.theme.Spacing
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.gms.rememberFusedLocationProvider
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.location.LocationPuck
import org.maplibre.compose.location.rememberUserLocationState
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Position

/** Documented, officially working OpenFreeMap style (verified during the branch spike). A dark
 * variant exists in OpenFreeMap's own style picker, but its exact style URL could not be verified
 * as actually resolving in this environment — using an unverified URL was explicitly out of scope,
 * so the app's dark theme currently renders the same Liberty style light map. */
private const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

/** Roughly the geographic center of Croatia, at a country-level zoom — the default camera before
 * any hotspot data or user-location action changes it. */
private val DefaultCameraPosition = CameraPosition(target = Position(longitude = 16.5, latitude = 45.3), zoom = 6.6)

@Composable
fun MapScreen(viewModel: MapViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // The one and only screen-entry trigger, mirroring DetectionsScreen — MapViewModel never
    // auto-loads. NavHost disposes/recomposes this destination on each visit, so leaving and
    // returning to the Map tab always re-fetches, even though the ViewModel instance itself can
    // survive the round trip via saved nav-graph state.
    LaunchedEffect(Unit) {
        viewModel.onScreenEntered()
    }

    MapContent(
        uiState = uiState,
        onSelectRange = viewModel::selectRange,
        onRefresh = viewModel::refresh,
        hasRequestedPermissionBefore = viewModel::hasRequestedPermissionBefore,
        markPermissionRequested = viewModel::markPermissionRequested
    )
}

@Composable
internal fun MapContent(
    uiState: MapUiState,
    onSelectRange: (HotspotTimeRange) -> Unit,
    onRefresh: () -> Unit,
    hasRequestedPermissionBefore: suspend (String) -> Boolean,
    markPermissionRequested: suspend (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedHotspot by remember { mutableStateOf<Hotspot?>(null) }

    // --- Map (style/tile) load state — independent of hotspot data load state (§16). ---
    var mapLoadFailedReason by remember { mutableStateOf<String?>(null) }
    var mapLoadFinished by remember { mutableStateOf(false) }
    var mapInstanceKey by remember { mutableIntStateOf(0) }

    val cameraState = rememberCameraState(firstPosition = DefaultCameraPosition)

    val polygonsByBucket = remember(uiState.hotspots) { bucketPolygons(uiState.hotspots) }

    // Fits the camera to the successfully committed dataset exactly once per dataset — keyed on
    // the hotspots list itself, so it never re-runs on ordinary recomposition or while the user is
    // manually panning (panning doesn't change uiState.hotspots).
    LaunchedEffect(uiState.hotspots) {
        val allPolygons = polygonsByBucket.values.flatten()
        val bounds = HotspotBounds.compute(allPolygons) ?: return@LaunchedEffect
        cameraState.animateTo(boundingBox = bounds, padding = PaddingValues(48.dp))
    }

    // --- Current-location button state — map-screen-local only; never touches the app's
    // singleton DefaultLocationProvider (that provider has monitoring-session start/stop
    // semantics this screen must not interfere with). Permission is requested only on tap. ---
    var locationEnabled by remember { mutableStateOf(false) }
    var locationRequestedBeforeSnapshot by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var isLocating by remember { mutableStateOf(false) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val fineLocation = PermissionDecisionEvaluator.evaluate(
            granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true,
            shouldShowRationale = activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION),
            requestedBefore = locationRequestedBeforeSnapshot[Manifest.permission.ACCESS_FINE_LOCATION] == true
        )
        val coarseLocation = PermissionDecisionEvaluator.evaluate(
            granted = results[Manifest.permission.ACCESS_COARSE_LOCATION] == true,
            shouldShowRationale = activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION),
            requestedBefore = locationRequestedBeforeSnapshot[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        )
        scope.launch { results.keys.forEach { markPermissionRequested(it) } }

        when (MapLocationPermissionEvaluator.evaluate(fineLocation, coarseLocation)) {
            MapLocationPermissionOutcome.Granted -> {
                isLocating = true
                locationEnabled = true
            }
            MapLocationPermissionOutcome.Denied -> scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.map_location_denied))
            }
            MapLocationPermissionOutcome.PermanentlyDenied -> scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = context.getString(R.string.permission_permanently_denied_message),
                    actionLabel = context.getString(R.string.action_open_settings),
                    duration = SnackbarDuration.Long
                )
                if (result == SnackbarResult.ActionPerformed) openAppSettings(context)
            }
        }
    }

    fun onLocationButtonClick() {
        if (locationEnabled) {
            isLocating = true
            return
        }
        if (!isLocationServicesEnabled(context)) {
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = context.getString(R.string.map_location_services_disabled),
                    actionLabel = context.getString(R.string.action_open_location_settings),
                    duration = SnackbarDuration.Long
                )
                if (result == SnackbarResult.ActionPerformed) {
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            }
            return
        }
        scope.launch {
            val permissions = mapLocationPermissions()
            locationRequestedBeforeSnapshot = permissions.associateWith { hasRequestedPermissionBefore(it) }
            locationPermissionLauncher.launch(permissions)
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        Box(modifier = modifier.fillMaxSize().padding(innerPadding)) {
            key(mapInstanceKey) {
                MaplibreMap(
                    modifier = Modifier.fillMaxSize(),
                    baseStyle = BaseStyle.Uri(MAP_STYLE_URL),
                    cameraState = cameraState,
                    options = MapOptions(),
                    onMapClick = { position: Position, _ ->
                        val hit = HotspotHitSelector.select(position.latitude, position.longitude, uiState.hotspots)
                        if (hit != null) {
                            selectedHotspot = hit
                            ClickResult.Consume
                        } else {
                            ClickResult.Pass
                        }
                    },
                    onMapLoadFailed = { reason -> mapLoadFailedReason = reason ?: "" },
                    onMapLoadFinished = { mapLoadFinished = true }
                ) {
                    polygonsByBucket.forEach { (bucket, polygons) ->
                        if (polygons.isEmpty()) return@forEach
                        val source = rememberGeoJsonSource(data = GeoJsonData.JsonString(HotspotGeoJson.featureCollection(polygons)))
                        FillLayer(
                            id = "hotspots-fill-${bucket.name}",
                            source = source,
                            color = const(bucket.color),
                            opacity = const(0.25f)
                        )
                        LineLayer(
                            id = "hotspots-outline-${bucket.name}",
                            source = source,
                            color = const(bucket.color),
                            width = const(2.dp)
                        )
                    }

                    if (locationEnabled) {
                        val locationProvider = rememberFusedLocationProvider()
                        val userLocationState = rememberUserLocationState(locationProvider = locationProvider)
                        val location = userLocationState.location
                        if (location != null) {
                            LocationPuck(idPrefix = "map-user-location", location = location, cameraState = cameraState)
                        }
                        LaunchedEffect(location, isLocating) {
                            if (isLocating && location != null) {
                                isLocating = false
                                cameraState.animateTo(CameraPosition(target = location.position.value, zoom = 15.0))
                            }
                        }
                    }
                }
            }

            if (mapLoadFailedReason != null) {
                MapLoadErrorOverlay(onRetry = {
                    mapLoadFailedReason = null
                    mapLoadFinished = false
                    mapInstanceKey++
                })
            }

            Column(modifier = Modifier.fillMaxWidth().padding(Spacing.lg)) {
                TimeRangeRow(
                    committedRange = uiState.committedRange,
                    pendingRange = uiState.pendingRange,
                    isLoadingSubsequent = uiState.isLoadingSubsequent,
                    onSelectRange = onSelectRange,
                    onRefresh = onRefresh
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                MapLegend()
                if (uiState.subsequentError) {
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    SubsequentErrorBanner(onRetry = onRefresh)
                }
            }

            if (uiState.isInitialLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (uiState.initialError) {
                InitialDataErrorOverlay(onRetry = onRefresh)
            } else if (uiState.hotspots.isEmpty()) {
                EmptyHotspotsOverlay()
            }

            FloatingActionButton(
                onClick = { onLocationButtonClick() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.lg)
            ) {
                if (isLocating) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.MyLocation, contentDescription = stringResource(R.string.action_current_location))
                }
            }
        }
    }

    selectedHotspot?.let { hotspot ->
        HotspotDetailBottomSheet(hotspot = hotspot, onDismiss = { selectedHotspot = null })
    }
}

private fun isLocationServicesEnabled(context: Context): Boolean {
    val locationManager = context.getSystemService(LocationManager::class.java) ?: return false
    return LocationManagerCompat.isLocationEnabled(locationManager)
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    )
}

/** The 3 device-count buckets that decide polygon color (§10) — color depends only on
 * [Hotspot.deviceCount], never [Hotspot.confidence]. */
private enum class DeviceCountBucket(val color: Color) {
    Two(SemanticColors.Yellow),
    Three(SemanticColors.Warning),
    FourOrMore(SemanticColors.Danger)
}

private fun bucketFor(deviceCount: Int): DeviceCountBucket = when {
    deviceCount <= 2 -> DeviceCountBucket.Two
    deviceCount == 3 -> DeviceCountBucket.Three
    else -> DeviceCountBucket.FourOrMore
}

/** One [org.maplibre.compose.sources.GeoJsonSource] per bucket (at most 3 total) rather than one
 * per hotspot or one shared source with data-driven filter expressions — the simplest of the two
 * options §10 allows, and avoids depending on the expression DSL's feature-property filtering
 * surface for something 3 static layers already solve directly. */
private fun bucketPolygons(hotspots: List<Hotspot>): Map<DeviceCountBucket, List<HotspotPolygon>> =
    DeviceCountBucket.entries.associateWith { bucket ->
        hotspots.filter { bucketFor(it.deviceCount) == bucket }.map {
            HotspotGeometry.polygonFor(
                hotspotId = it.id,
                centerLatitude = it.latitude,
                centerLongitude = it.longitude,
                radiusMeters = it.radiusMeters,
                deviceCount = it.deviceCount,
                confidence = it.confidence
            )
        }
    }

@Composable
private fun TimeRangeRow(
    committedRange: HotspotTimeRange,
    pendingRange: HotspotTimeRange?,
    isLoadingSubsequent: Boolean,
    onSelectRange: (HotspotTimeRange) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selected = pendingRange ?: committedRange
    val requestActive = pendingRange != null || isLoadingSubsequent

    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            HotspotTimeRange.entries.forEach { range ->
                RangeChip(
                    range = range,
                    isSelected = range == selected,
                    isLoading = range == pendingRange,
                    enabled = !requestActive,
                    onClick = { onSelectRange(range) }
                )
            }
        }
        IconButton(onClick = onRefresh, enabled = !requestActive) {
            if (isLoadingSubsequent && pendingRange == null) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        }
    }
}

@Composable
private fun RangeChip(
    range: HotspotTimeRange,
    isSelected: Boolean,
    isLoading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = AppShapes.Pill,
        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        onClick = onClick,
        enabled = enabled
    ) {
        Row(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(Spacing.xs))
            }
            Text(
                text = stringResource(range.labelRes),
                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun MapLegend(modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = AppShapes.Card, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)) {
        Row(modifier = Modifier.padding(Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            LegendEntry(SemanticColors.Yellow, stringResource(R.string.map_legend_2_devices))
            LegendEntry(SemanticColors.Warning, stringResource(R.string.map_legend_3_devices))
            LegendEntry(SemanticColors.Danger, stringResource(R.string.map_legend_4_plus_devices))
        }
    }
}

@Composable
private fun LegendEntry(color: Color, label: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
        Spacer(modifier = Modifier.width(Spacing.xs))
        Text(text = label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun MapLoadErrorOverlay(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = stringResource(R.string.map_error_load), style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(Spacing.md))
            Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

@Composable
private fun InitialDataErrorOverlay(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(shape = AppShapes.Card, color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(Spacing.lg), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = stringResource(R.string.map_error_data_initial), style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(Spacing.md))
                Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}

@Composable
private fun EmptyHotspotsOverlay(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(shape = AppShapes.Card, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)) {
            Text(
                text = stringResource(R.string.map_empty_no_hotspots),
                modifier = Modifier.padding(Spacing.lg),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun SubsequentErrorBanner(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), shape = AppShapes.Card, color = SemanticColors.WarningBg) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = stringResource(R.string.map_error_data_refresh), style = MaterialTheme.typography.bodySmall, color = SemanticColors.Warning)
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HotspotDetailBottomSheet(hotspot: Hotspot, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    val timeSpan = remember(hotspot.id) { hotspotTimeSpanFor(hotspot.firstReceivedAtUtc, hotspot.lastReceivedAtUtc) }
    val timeSpanText = when (timeSpan) {
        is HotspotTimeSpan.Seconds -> stringResource(R.string.duration_seconds, timeSpan.seconds)
        is HotspotTimeSpan.MinutesSeconds -> stringResource(R.string.duration_minutes_seconds, timeSpan.minutes, timeSpan.seconds)
        is HotspotTimeSpan.HoursMinutes -> stringResource(R.string.duration_hours_minutes, timeSpan.hours, timeSpan.minutes)
    }
    val radiusText = stringResource(R.string.unit_meters_short, hotspot.radiusMeters.roundToInt())
    val confidenceText = stringResource(R.string.unit_percent, hotspot.confidence)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = AppShapes.BottomSheet) {
        Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md).fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(text = stringResource(R.string.hotspot_detail_title), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                }
            }
            Spacer(modifier = Modifier.height(Spacing.md))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                DetailCell(label = stringResource(R.string.detail_label_devices), value = hotspot.deviceCount.toString(), modifier = Modifier)
                DetailCell(label = stringResource(R.string.detail_label_radius), value = radiusText, modifier = Modifier)
            }
            Spacer(modifier = Modifier.height(Spacing.sm))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                DetailCell(label = stringResource(R.string.detail_label_confidence), value = confidenceText, modifier = Modifier)
                DetailCell(label = stringResource(R.string.detail_label_time_span), value = timeSpanText, modifier = Modifier)
            }
        }
    }
}

@Composable
private fun DetailCell(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = AppShapes.ChipOrBadge, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Text(text = value, style = MonospaceValueStyle.copy(fontWeight = FontWeight.Bold))
        }
    }
}
