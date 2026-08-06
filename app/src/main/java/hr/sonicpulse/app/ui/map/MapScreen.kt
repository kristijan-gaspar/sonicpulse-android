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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import java.util.UUID
import hr.sonicpulse.app.ui.components.FilterChipRow
import hr.sonicpulse.app.ui.components.FilterChipRowHorizontalContentPadding
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

// --- Bottom-edge layout for the map's native ornaments (logo/attribution) and the Compose
// overlays that share that corner (current-location FAB, snackbar). ---
//
// org.maplibre.compose's OrnamentOptions exposes exactly one shared `padding: PaddingValues` for
// every ornament, regardless of alignment (confirmed against the pinned maplibre-compose 0.13.0
// sources — there is no per-corner padding override). Giving that single padding a large bottom
// value to keep the bottom-end attribution clear of the FAB — as the previous version did via
// CurrentLocationFabClearance — also pushes the unrelated bottom-*start* logo up by the same
// amount, even though the logo and the FAB are in different corners and never collide. The fix is
// the other direction: keep the shared ornament padding small (both logo and attribution sit close
// to the true bottom edge), and instead give the FAB — a plain Compose composable we fully
// control — enough of its own bottom padding to clear the attribution button.

/** Small, uniform edge inset for every enabled MapLibre ornament (logo, attribution, compass). */
private val MapOrnamentEdgeInset = Spacing.sm

/** MapLibre's native attribution/logo row has no Compose-measurable size — the compose wrapper
 * exposes no callback or handle for it (see OrnamentOptions, above). This is a deliberately
 * generous estimate of its on-screen height (a small icon button plus MapLibre's own default
 * internal margins — see `maplibre_four_dp`/`maplibre_eight_dp` in the native SDK's resources),
 * used only to keep other Compose overlays comfortably clear of it. */
private val AttributionApproxHeight = 32.dp

/** Bottom padding for the current-location FAB (bottom-end, same corner as the attribution
 * button): [MapOrnamentEdgeInset] to reach the attribution's own top edge, plus its estimated
 * height, plus a small visual gap. */
private val CurrentLocationFabBottomPadding = MapOrnamentEdgeInset + AttributionApproxHeight + Spacing.sm

/** Bottom padding for the snackbar host: clear of the FAB itself (56dp, the standard
 * [androidx.compose.material3.FloatingActionButton] size) sitting above the attribution row, plus
 * a small gap — so a shown snackbar never covers the logo, the attribution button or the FAB. */
private val SnackbarBottomPadding = CurrentLocationFabBottomPadding + 56.dp + Spacing.sm

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

    val locationDeniedMessage =
        stringResource(R.string.map_location_denied)

    val permissionPermanentlyDeniedMessage =
        stringResource(R.string.permission_permanently_denied_message)

    val openSettingsLabel =
        stringResource(R.string.action_open_settings)

    val locationServicesDisabledMessage =
        stringResource(R.string.map_location_services_disabled)

    val openLocationSettingsLabel =
        stringResource(R.string.action_open_location_settings)

    val locationUnavailableMessage =
        stringResource(R.string.map_location_unavailable)

    var selectedHotspotId by remember { mutableStateOf<UUID?>(null) }

    LaunchedEffect(uiState.hotspots, selectedHotspotId) {
        selectedHotspotId = retainedSelectedHotspotId(
            hotspots = uiState.hotspots,
            selectedId = selectedHotspotId
        )
    }

    // --- Map (style/tile) render state — independent of hotspot backend state (§4/§16). Retry
    // recreates the map instance (via mapInstanceKey); onMapLoadFinished/onMapLoadFailed verify
    // they belong to the current instance generation before mutating anything. ---
    var mapInstanceKey by remember { mutableIntStateOf(0) }
    val renderCoordinator = remember { MapRenderCoordinator() }
    var renderVersion by remember { mutableIntStateOf(0) }

    val cameraState = rememberCameraState(firstPosition = DefaultCameraPosition)

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

    /** Location services are unavailable (checked fresh on every button press and again on resume
     * from Android's location settings): the map-local provider must not stay enabled, and any
     * attempt currently waiting on a fix must stop immediately rather than linger until its own
     * 15 s timeout fires later for no reason. */
    fun disableLocationDueToServicesUnavailable() {
        locationEnabled = false
        locatingCoordinator.cancel()
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
                snackbarHostState.showSnackbar(locationDeniedMessage)
            }
            MapLocationPermissionOutcome.PermanentlyDenied -> scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = permissionPermanentlyDeniedMessage,
                    actionLabel = openSettingsLabel,
                    duration = SnackbarDuration.Long
                )
                if (result == SnackbarResult.ActionPerformed) {
                    openedAppSettingsForLocation = true
                    openAppSettings(context)
                }
            }
        }
    }

    // §5.1: location-services state is checked on every press, before any locationEnabled
    // shortcut — a permission granted earlier does not mean services are still on right now.
    fun onCurrentLocationClick() {
        if (!isLocationServicesEnabled(context)) {
            disableLocationDueToServicesUnavailable()
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = locationServicesDisabledMessage,
                    actionLabel = openLocationSettingsLabel,
                    duration = SnackbarDuration.Long
                )
                if (result == SnackbarResult.ActionPerformed) {
                    openedLocationSettingsFromMap = true
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
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
            snackbarHostState.showSnackbar(locationUnavailableMessage)
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
                // Neither auto-starts locating nor animates the camera — only updates whether the
                // map-local location provider/puck may run at all; a later explicit Current
                // Location press performs the actual attempt.
                locationEnabled = fineGranted || coarseGranted
            }
            if (currentOpenedLocationSettings.value) {
                openedLocationSettingsFromMap = false
                // If services are still off, apply the same cleanup as an explicit button press
                // would (disable the provider, cancel any lingering attempt) — but never start a
                // new attempt or move the camera just because services are now back on; that only
                // happens from a later explicit Current Location press.
                if (!isLocationServicesEnabled(context)) {
                    disableLocationDueToServicesUnavailable()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // --- Ornaments: explicit alignment + padding measured from the screen's own controls, rather
    // than relying on default/undocumented MapLibre ornament positions (§1/§3). Every enabled
    // ornament gets its own corner so none of them overlap each other: logo bottom-start,
    // attribution bottom-end, compass top-end. The scale bar is disabled entirely — it's not
    // needed for SonicPulse and visually competed with the filter/legend overlays for the same
    // top-start corner. Top padding clears the measured top filter/legend controls (only the
    // compass is top-aligned now, but the same clearance still applies to it). Bottom padding is
    // only the small shared MapOrnamentEdgeInset — see the comment on that constant above: the FAB
    // clears the attribution button with its own padding instead, so the logo (which never
    // overlaps the FAB) is no longer pushed up unnecessarily high. ---
    var topControlsHeightPx by remember { mutableIntStateOf(0) }
    val topControlsHeightDp = with(density) { topControlsHeightPx.toDp() }
    val ornamentOptions = remember(topControlsHeightDp) {
        OrnamentOptions(
            padding = PaddingValues(
                start = MapOrnamentEdgeInset,
                top = topControlsHeightDp + Spacing.sm,
                end = MapOrnamentEdgeInset,
                bottom = MapOrnamentEdgeInset
            ),
            isLogoEnabled = true,
            logoAlignment = Alignment.BottomStart,
            isAttributionEnabled = true,
            attributionAlignment = Alignment.BottomEnd,
            isCompassEnabled = true,
            compassAlignment = Alignment.TopEnd,
            isScaleBarEnabled = false
        )
    }

    val polygonsByBucket = remember(uiState.hotspots) { bucketPolygons(uiState.hotspots) }

    // No Scaffold here on purpose: the app-level Scaffold in SonicPulseApp already reserves the
    // area between the top bar and the bottom navigation bar, and a nested Scaffold's own default
    // window-insets handling would re-reserve part of that same space, shrinking the map viewport
    // for no reason (§3) — filters/legend/FAB/snackbar are plain overlays inside this Box instead.
    Box(modifier = modifier.fillMaxSize()) {
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
                        selectedHotspotId = hit.id
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

        // Cleared of the logo/attribution row and the current-location FAB — see
        // SnackbarBottomPadding above — so a shown snackbar never covers any of them.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = SnackbarBottomPadding)
        )
    }

    // Re-derived from the latest uiState.hotspots on every recomposition — see selectedHotspotId's
    // KDoc above. null here (id set but no longer present in the list) simply closes the sheet.
    val selectedHotspot = selectedHotspotFrom(uiState.hotspots, selectedHotspotId)
    selectedHotspot?.let { hotspot ->
        HotspotDetailBottomSheet(hotspot = hotspot, onDismiss = { selectedHotspotId = null })
    }
}

/** Everything drawn once the map itself has not (fully) failed: top filter/legend controls,
 * hotspot loading/error/empty overlays and the current-location FAB. Split out so the map-render
 * failure branch above can omit all of it structurally, not just visually. */
/** While the very first load has failed, this composes *only* the dedicated initial-error state —
 * no filters, refresh, legend, empty state or FAB — so there is exactly one way to recover
 * (its own Retry) and nothing else is reachable or even present in the composition. */
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
    when {
        uiState.initialError -> {
            InitialDataErrorOverlay(onRetry = onRefresh, serverConfigurationError = uiState.initialErrorServerConfiguration)
        }
        else -> {
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
                // Aligns the legend's left edge with the first filter chip's left edge, not the
                // Column's own edge: FilterChipRow's LazyRow has its own internal
                // FilterChipRowHorizontalContentPadding before the first chip, which this
                // Column's plain start padding (Spacing.lg, applied above) doesn't account for.
                MapLegend(modifier = Modifier.padding(start = FilterChipRowHorizontalContentPadding))
                if (uiState.subsequentError) {
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    SubsequentErrorBanner(onRetry = onRetry, serverConfigurationError = uiState.subsequentErrorServerConfiguration)
                }
            }

            if (uiState.isInitialLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (uiState.hotspots.isEmpty()) {
                EmptyHotspotsOverlay()
            }

            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                val currentLocationLabel = stringResource(R.string.action_current_location)
                // end/bottom are the only paddings that affect a BottomEnd-aligned child's final
                // position; bottom clears the attribution button below it (CurrentLocationFabBottomPadding).
                FloatingActionButton(
                    onClick = onCurrentLocationClick,
                    modifier = Modifier.padding(end = Spacing.lg, bottom = CurrentLocationFabBottomPadding)
                ) {
                    if (isLocating) {
                        // Semantics kept identical to the Icon it replaces — without this, a screen
                        // reader announces this button with no name at all while it is locating.
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp).semantics { contentDescription = currentLocationLabel },
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Filled.MyLocation, contentDescription = currentLocationLabel)
                    }
                }
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

/**
 * Re-derives the selected hotspot from the currently loaded [hotspots] by id, rather than trusting
 * a captured object from the moment it was tapped — so an open detail sheet always reflects the
 * latest data for that hotspot, and closes on its own (returning null) once the hotspot is no
 * longer present (removed by a refresh or filtered out by a range change), instead of continuing
 * to display a stale snapshot. `selectedId == null` (nothing selected) also returns null.
 */
internal fun selectedHotspotFrom(hotspots: List<Hotspot>, selectedId: UUID?): Hotspot? =
    selectedId?.let { id -> hotspots.find { it.id == id } }

internal fun retainedSelectedHotspotId(
    hotspots: List<Hotspot>,
    selectedId: UUID?
): UUID? =
    selectedId?.takeIf { id ->
        hotspots.any { hotspot -> hotspot.id == id }
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
        val refreshLabel = stringResource(R.string.action_refresh)
        IconButton(onClick = onRefresh, enabled = !requestActive) {
            if (isLoadingSubsequent && pendingRange == null) {
                // Semantics kept identical to the Icon it replaces — without this, a screen reader
                // announces this button with no name at all while it is loading.
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp).semantics { contentDescription = refreshLabel },
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = refreshLabel)
            }
        }
    }
}

/** A compact "Devices  ●2  ●3  ●4+" chip — not a full-width banner. `wrapContentWidth()`, not
 * `fillMaxWidth()`, so it hugs its own content and sits at the top-start side of the map (the
 * default alignment for a non-fillMaxWidth child inside the top controls' Start-aligned Column).
 * It still sits inside a FlowRow so the title + 3 short entries wrap onto a second line rather
 * than clip if they ever don't fit the available width (narrow screen, larger system font). */
@Composable
private fun MapLegend(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.wrapContentWidth(),
        shape = AppShapes.Card,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
    ) {
        FlowRow(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs).wrapContentWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text(
                text = stringResource(R.string.map_legend_title),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            LegendEntry(SemanticColors.Yellow, stringResource(R.string.map_legend_2_devices))
            LegendEntry(SemanticColors.Warning, stringResource(R.string.map_legend_3_devices))
            LegendEntry(SemanticColors.Danger, stringResource(R.string.map_legend_4_plus_devices))
        }
    }
}

/** One "●2"-style entry — [count] is just the number (or "4+"), the shared [R.string.map_legend_title]
 * supplies "Devices"/"Uređaji" once for the whole legend rather than repeating it per entry. */
@Composable
private fun LegendEntry(color: Color, count: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = count, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
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
private fun InitialDataErrorOverlay(onRetry: () -> Unit, modifier: Modifier = Modifier, serverConfigurationError: Boolean = false) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(shape = AppShapes.Card, color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(Spacing.lg), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(
                        if (serverConfigurationError) R.string.error_server_configuration else R.string.map_error_data_initial
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
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
private fun SubsequentErrorBanner(onRetry: () -> Unit, modifier: Modifier = Modifier, serverConfigurationError: Boolean = false) {
    Surface(modifier = modifier.fillMaxWidth(), shape = AppShapes.Card, color = SemanticColors.WarningBg) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    if (serverConfigurationError) R.string.error_server_configuration else R.string.map_error_data_refresh
                ),
                style = MaterialTheme.typography.bodySmall,
                color = SemanticColors.Warning
            )
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
