package hr.sonicpulse.app.ui.map

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hr.sonicpulse.app.R
import hr.sonicpulse.app.domain.model.Hotspot
import hr.sonicpulse.app.ui.components.FilterChipRow
import hr.sonicpulse.app.ui.permissions.PermissionDecisionEvaluator
import hr.sonicpulse.app.ui.theme.AppShapes
import hr.sonicpulse.app.ui.theme.MonospaceValueStyle
import hr.sonicpulse.app.ui.theme.SemanticColors
import hr.sonicpulse.app.ui.theme.Spacing
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
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
import org.maplibre.compose.map.OrnamentOptions
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

/** A value between 10 and 15 s is acceptable per the plan; 15 s chosen and used consistently. */
private const val LOCATION_FIX_TIMEOUT_MILLIS = 15_000L

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
        onRetry = viewModel::retry,
        hasRequestedPermissionBefore = viewModel::hasRequestedPermissionBefore,
        markPermissionRequested = viewModel::markPermissionRequested
    )
}

@Composable
internal fun MapContent(
    uiState: MapUiState,
    onSelectRange: (HotspotTimeRange) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    hasRequestedPermissionBefore: suspend (String) -> Boolean,
    markPermissionRequested: suspend (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedHotspot by remember { mutableStateOf<Hotspot?>(null) }

    // --- Map (style/tile) render state — independent of hotspot backend state (§4/§16). Retry
    // recreates the map instance (via mapInstanceKey); onMapLoadFinished/onMapLoadFailed verify
    // they belong to the current instance generation before mutating anything. ---
    var mapInstanceKey by remember { mutableIntStateOf(0) }
    val renderCoordinator = remember { MapRenderCoordinator() }
    var renderVersion by remember { mutableIntStateOf(0) }

    val cameraState = rememberCameraState(firstPosition = DefaultCameraPosition)

    // Fits the camera to the successfully committed dataset exactly once per dataset. Keyed
    // directly on uiState.hotspots — MapViewModel only ever replaces that list on a *successful*
    // load, so a failed request (which republishes the same list reference) or ordinary
    // recomposition never re-triggers this, and manual panning is never fought.
    LaunchedEffect(uiState.hotspots) {
        when (val target = HotspotCamera.targetFor(uiState.hotspots)) {
            HotspotCameraTarget.KeepCurrent -> Unit
            is HotspotCameraTarget.Center -> cameraState.animateTo(
                CameraPosition(target = Position(longitude = target.longitude, latitude = target.latitude), zoom = target.zoom)
            )
            is HotspotCameraTarget.Bounds -> cameraState.animateTo(boundingBox = target.boundingBox, padding = PaddingValues(48.dp))
        }
    }

    // --- Current-location button state — map-screen-local only; never touches the app's
    // singleton DefaultLocationProvider (that provider has monitoring-session start/stop
    // semantics this screen must not interfere with). Permission is requested only on tap. ---
    var locationEnabled by remember { mutableStateOf(false) }
    var locationRequestedBeforeSnapshot by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val locatingCoordinator = remember { LocatingCoordinator() }
    var locatingVersion by remember { mutableIntStateOf(0) }
    var currentLocatingGeneration by remember { mutableLongStateOf(0L) }
    val isLocating = locatingVersion.let { locatingCoordinator.attempt.active }

    // Tracks whether Settings was opened specifically from this screen's location flow — only
    // this may trigger a resume re-check; an ordinary app resume must not (§5.4).
    var openedAppSettingsForLocation by rememberSaveable { mutableStateOf(false) }
    var openedLocationSettingsFromMap by rememberSaveable { mutableStateOf(false) }

    fun beginLocatingAttempt() {
        locationEnabled = true
        currentLocatingGeneration = locatingCoordinator.start()
        locatingVersion++
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val activity = context.findActivity()
        val fineLocation = PermissionDecisionEvaluator.evaluate(
            granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true,
            shouldShowRationale = activity?.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) == true,
            requestedBefore = locationRequestedBeforeSnapshot[Manifest.permission.ACCESS_FINE_LOCATION] == true
        )
        val coarseLocation = PermissionDecisionEvaluator.evaluate(
            granted = results[Manifest.permission.ACCESS_COARSE_LOCATION] == true,
            shouldShowRationale = activity?.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION) == true,
            requestedBefore = locationRequestedBeforeSnapshot[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        )
        scope.launch { results.keys.forEach { markPermissionRequested(it) } }

        when (MapLocationPermissionEvaluator.evaluate(fineLocation, coarseLocation)) {
            MapLocationPermissionOutcome.Granted -> beginLocatingAttempt()
            MapLocationPermissionOutcome.Denied -> scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.map_location_denied))
            }
            MapLocationPermissionOutcome.PermanentlyDenied -> scope.launch {
                openedAppSettingsForLocation = true
                val result = snackbarHostState.showSnackbar(
                    message = context.getString(R.string.permission_permanently_denied_message),
                    actionLabel = context.getString(R.string.action_open_settings),
                    duration = SnackbarDuration.Long
                )
                if (result == SnackbarResult.ActionPerformed) {
                    openAppSettings(context)
                } else {
                    openedAppSettingsForLocation = false
                }
            }
        }
    }

    // §5.1: location-services state is checked on every press, before any locationEnabled
    // shortcut — a permission granted earlier does not mean services are still on right now.
    fun onCurrentLocationClick() {
        if (!isLocationServicesEnabled(context)) {
            scope.launch {
                openedLocationSettingsFromMap = true
                val result = snackbarHostState.showSnackbar(
                    message = context.getString(R.string.map_location_services_disabled),
                    actionLabel = context.getString(R.string.action_open_location_settings),
                    duration = SnackbarDuration.Long
                )
                if (result == SnackbarResult.ActionPerformed) {
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } else {
                    openedLocationSettingsFromMap = false
                }
            }
            return
        }

        val fineGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (fineGranted || coarseGranted) {
            beginLocatingAttempt()
            return
        }

        // §5.2: no unsafe `context as Activity` — if no Activity can be resolved, fail gracefully
        // rather than crash or begin locating with nothing actually requested.
        if (context.findActivity() == null) {
            return
        }
        scope.launch {
            val permissions = mapLocationPermissions()
            locationRequestedBeforeSnapshot = permissions.associateWith { hasRequestedPermissionBefore(it) }
            locationPermissionLauncher.launch(permissions)
        }
    }

    // §5.3: the locating attempt races a location result against a fixed timeout — whichever
    // completes the current generation first wins; the loser is then stale and does nothing.
    // LaunchedEffect's own key-based cancellation is what guarantees "leaving composition cancels
    // the active timeout job" and "a new attempt invalidates the previous attempt's timeout".
    LaunchedEffect(currentLocatingGeneration) {
        if (currentLocatingGeneration == 0L) return@LaunchedEffect
        val generation = currentLocatingGeneration
        delay(LOCATION_FIX_TIMEOUT_MILLIS)
        if (locatingCoordinator.complete(generation)) {
            locatingVersion++
            snackbarHostState.showSnackbar(context.getString(R.string.map_location_unavailable))
        }
    }

    // §5.4: only re-check location state on resume if Settings was opened from this screen's own
    // flow, and even then never auto-begin locating or animate the camera — only a later explicit
    // button press may do that.
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOpenedAppSettings = rememberUpdatedState(openedAppSettingsForLocation)
    val currentOpenedLocationSettings = rememberUpdatedState(openedLocationSettingsFromMap)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            if (currentOpenedAppSettings.value) {
                openedAppSettingsForLocation = false
                val fineGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
                val coarseGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
                if (fineGranted || coarseGranted) {
                    locationEnabled = true
                }
            }
            if (currentOpenedLocationSettings.value) {
                // isLocationServicesEnabled() is re-checked fresh on the next explicit button
                // press (§5.1) — nothing else needs updating here.
                openedLocationSettingsFromMap = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // --- Ornaments: explicit alignment + padding measured from the screen's own controls, rather
    // than relying on default/undocumented MapLibre ornament positions (§1). Logo + attribution
    // sit bottom-start (opposite the bottom-end FAB); the scale bar joins them there, clear of the
    // top filter/legend area; the compass sits top-end, pushed below the measured top-controls
    // height so it never sits under the filter row. ---
    var topControlsHeightPx by remember { mutableIntStateOf(0) }
    val topControlsHeightDp = with(density) { topControlsHeightPx.toDp() }
    val ornamentOptions = remember(topControlsHeightDp) {
        OrnamentOptions(
            padding = PaddingValues(start = Spacing.sm, top = topControlsHeightDp + Spacing.sm, end = Spacing.sm, bottom = Spacing.lg),
            isLogoEnabled = true,
            logoAlignment = Alignment.BottomStart,
            isAttributionEnabled = true,
            attributionAlignment = Alignment.BottomStart,
            isCompassEnabled = true,
            compassAlignment = Alignment.TopEnd,
            isScaleBarEnabled = true,
            scaleBarAlignment = Alignment.BottomStart
        )
    }

    val polygonsByBucket = remember(uiState.hotspots) { bucketPolygons(uiState.hotspots) }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        Box(modifier = modifier.fillMaxSize().padding(innerPadding)) {
            key(mapInstanceKey) {
                val instanceGeneration = remember(mapInstanceKey) { renderCoordinator.newInstance() }
                MaplibreMap(
                    modifier = Modifier.fillMaxSize(),
                    baseStyle = BaseStyle.Uri(MAP_STYLE_URL),
                    cameraState = cameraState,
                    options = MapOptions(ornamentOptions = ornamentOptions),
                    onMapClick = { position: Position, _ ->
                        val hit = HotspotHitSelector.select(position.latitude, position.longitude, uiState.hotspots)
                        if (hit != null) {
                            selectedHotspot = hit
                            ClickResult.Consume
                        } else {
                            ClickResult.Pass
                        }
                    },
                    onMapLoadFailed = { reason ->
                        renderCoordinator.onLoadFailed(instanceGeneration, reason)
                        renderVersion++
                    },
                    onMapLoadFinished = {
                        renderCoordinator.onLoadFinished(instanceGeneration)
                        renderVersion++
                    }
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
                        LaunchedEffect(location, currentLocatingGeneration) {
                            if (location != null && locatingCoordinator.complete(currentLocatingGeneration)) {
                                locatingVersion++
                                cameraState.animateTo(CameraPosition(target = location.position.value, zoom = 15.0))
                            }
                        }
                    }
                }
            }

            // §4 visual priority: map-render failure > map-render loading > hotspot states >
            // normal controls — each tier is exclusive of the ones below it, not merely drawn on
            // top of them. While the map itself hasn't finished loading, no hotspot-state overlay
            // or normal control is composed at all (not just hidden), so nothing can be
            // misleadingly interactive over a full-screen map failure or a bare loading map.
            val currentRenderState = renderVersion.let { renderCoordinator.state }
            when (currentRenderState) {
                is MapRenderState.Failed -> MapLoadErrorOverlay(onRetry = {
                    mapInstanceKey++
                })
                MapRenderState.Loading -> MapLoadingIndicator()
                MapRenderState.Loaded -> HotspotControlsAndOverlays(
                    uiState = uiState,
                    onSelectRange = onSelectRange,
                    onRefresh = onRefresh,
                    onRetry = onRetry,
                    onCurrentLocationClick = ::onCurrentLocationClick,
                    isLocating = isLocating,
                    onTopControlsMeasured = { topControlsHeightPx = it }
                )
            }
        }
    }

    selectedHotspot?.let { hotspot ->
        HotspotDetailBottomSheet(hotspot = hotspot, onDismiss = { selectedHotspot = null })
    }
}

/** Everything drawn once the map itself has not (fully) failed: top filter/legend controls,
 * hotspot loading/error/empty overlays and the current-location FAB. Split out so the map-render
 * failure branch above can omit all of it structurally, not just visually. */
@Composable
private fun HotspotControlsAndOverlays(
    uiState: MapUiState,
    onSelectRange: (HotspotTimeRange) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onCurrentLocationClick: () -> Unit,
    isLocating: Boolean,
    onTopControlsMeasured: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(Spacing.lg).onSizeChanged { onTopControlsMeasured(it.height) }
    ) {
        TimeRangeRow(
            committedRange = uiState.committedRange,
            pendingRange = uiState.pendingRange,
            isInitialLoading = uiState.isInitialLoading,
            isLoadingSubsequent = uiState.isLoadingSubsequent,
            onSelectRange = onSelectRange,
            onRefresh = onRefresh
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        MapLegend()
        if (uiState.subsequentError) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            SubsequentErrorBanner(onRetry = onRetry)
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

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        FloatingActionButton(onClick = onCurrentLocationClick, modifier = Modifier.padding(Spacing.lg)) {
            if (isLocating) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.MyLocation, contentDescription = stringResource(R.string.action_current_location))
            }
        }
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
    isInitialLoading: Boolean,
    isLoadingSubsequent: Boolean,
    onSelectRange: (HotspotTimeRange) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selected = pendingRange ?: committedRange
    val requestActive = isInitialLoading || pendingRange != null || isLoadingSubsequent

    val last24hLabel = stringResource(R.string.range_last_24_hours)
    val last3daysLabel = stringResource(R.string.range_last_3_days)
    val last7daysLabel = stringResource(R.string.range_last_7_days)

    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FilterChipRow(
            options = HotspotTimeRange.entries,
            selected = selected,
            onSelect = onSelectRange,
            label = { range ->
                when (range) {
                    HotspotTimeRange.Last24Hours -> last24hLabel
                    HotspotTimeRange.Last3Days -> last3daysLabel
                    HotspotTimeRange.Last7Days -> last7daysLabel
                }
            },
            enabled = { !requestActive },
            loading = { it == pendingRange },
            modifier = Modifier.weight(1f)
        )
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
private fun MapLoadingIndicator(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
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
                DetailCell(label = stringResource(R.string.detail_label_devices), value = hotspot.deviceCount.toString(), modifier = Modifier.weight(1f))
                DetailCell(label = stringResource(R.string.detail_label_radius), value = radiusText, modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(Spacing.sm))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                DetailCell(label = stringResource(R.string.detail_label_confidence), value = confidenceText, modifier = Modifier.weight(1f))
                DetailCell(label = stringResource(R.string.detail_label_time_span), value = timeSpanText, modifier = Modifier.weight(1f))
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
