package hr.sonicpulse.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hr.sonicpulse.app.R
import hr.sonicpulse.app.domain.model.AppLanguage
import hr.sonicpulse.app.domain.model.ThemeMode
import hr.sonicpulse.app.ui.components.AppCard
import hr.sonicpulse.app.ui.components.SectionHeader
import hr.sonicpulse.app.ui.components.SettingsRow
import hr.sonicpulse.app.ui.theme.AppShapes
import hr.sonicpulse.app.ui.theme.MonospaceValueStyle
import hr.sonicpulse.app.ui.theme.SemanticColors
import hr.sonicpulse.app.ui.theme.Spacing
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Refreshes permission status only — mirrors MonitoringScreen/MapScreen's ON_RESUME pattern,
    // but unlike those screens this never starts monitoring, requests a permission or moves the
    // map: an ordinary resume (e.g. switching apps) must be a pure, side-effect-free re-read.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissionStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        SettingsContent(
            uiState = uiState,
            modifier = Modifier.padding(innerPadding),
            onThemeSelect = viewModel::setThemeMode,
            onLanguageSelect = viewModel::setLanguage,
            onOpenAppSettings = { openAppSettingsSafely(context) },
            onOpenLocationSettings = { openLocationSettingsSafely(context) },
            onCopyDeviceId = { id ->
                clipboardManager.setText(AnnotatedString(id))
                scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.settings_device_id_copied)) }
            }
        )
    }
}

private fun openAppSettingsSafely(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    )
    try {
        context.startActivity(intent)
    } catch (e: android.content.ActivityNotFoundException) {
        // No activity can handle this on the current device/emulator image — nothing else to do.
    }
}

private fun openLocationSettingsSafely(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    } catch (e: android.content.ActivityNotFoundException) {
        // No activity can handle this on the current device/emulator image — nothing else to do.
    }
}

@Composable
internal fun SettingsContent(
    uiState: SettingsUiState,
    onThemeSelect: (ThemeMode) -> Unit,
    onLanguageSelect: (AppLanguage) -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onCopyDeviceId: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var openDialog by remember { mutableStateOf<AboutDialog?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl)
    ) {
        AppearanceSection(uiState.themeMode, uiState.language, onThemeSelect, onLanguageSelect)
        NotificationsSection()
        PermissionsSection(
            microphoneGranted = uiState.microphonePermissionGranted,
            locationPermissionGranted = uiState.locationPermissionGranted,
            preciseLocationGranted = uiState.preciseLocationPermissionGranted,
            locationServicesEnabled = uiState.locationServicesEnabled,
            onOpenAppSettings = onOpenAppSettings,
            onOpenLocationSettings = onOpenLocationSettings
        )
        DeviceIdSection(uiState.installationId, onCopyDeviceId)
        AboutSection(
            versionName = uiState.versionName,
            onTermsClick = { openDialog = AboutDialog.Terms },
            onPrivacyClick = { openDialog = AboutDialog.Privacy }
        )
    }

    when (openDialog) {
        AboutDialog.Terms -> SettingsInfoDialog(
            title = stringResource(R.string.settings_label_terms),
            content = stringResource(R.string.settings_terms_content),
            onDismiss = { openDialog = null }
        )
        AboutDialog.Privacy -> SettingsInfoDialog(
            title = stringResource(R.string.settings_label_privacy),
            content = stringResource(R.string.settings_privacy_content),
            onDismiss = { openDialog = null }
        )
        null -> Unit
    }
}

private enum class AboutDialog { Terms, Privacy }

// --- Section A: Appearance ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSection(
    themeMode: ThemeMode,
    language: AppLanguage,
    onThemeSelect: (ThemeMode) -> Unit,
    onLanguageSelect: (AppLanguage) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader(stringResource(R.string.settings_section_appearance))
        AppCard {
            AppearanceLabelRow(Icons.Filled.DarkMode, stringResource(R.string.settings_label_theme))
            Spacer(modifier = Modifier.height(Spacing.sm))
            ThemeModeSegmentedControl(themeMode, onThemeSelect)
            Spacer(modifier = Modifier.height(Spacing.lg))
            AppearanceLabelRow(Icons.Filled.Language, stringResource(R.string.settings_label_language))
            Spacer(modifier = Modifier.height(Spacing.sm))
            LanguageSegmentedControl(language, onLanguageSelect)
        }
    }
}

@Composable
private fun AppearanceLabelRow(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeModeSegmentedControl(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val options = ThemeMode.entries
    val darkLabel = stringResource(R.string.theme_mode_dark)
    val lightLabel = stringResource(R.string.theme_mode_light)
    val systemLabel = stringResource(R.string.theme_mode_system)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = {
                    Text(
                        when (mode) {
                            ThemeMode.Dark -> darkLabel
                            ThemeMode.Light -> lightLabel
                            ThemeMode.System -> systemLabel
                        }
                    )
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSegmentedControl(selected: AppLanguage, onSelect: (AppLanguage) -> Unit) {
    val options = AppLanguage.entries
    val hrLabel = stringResource(R.string.language_option_hr)
    val enLabel = stringResource(R.string.language_option_en)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, language ->
            SegmentedButton(
                selected = selected == language,
                onClick = { onSelect(language) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = { Text(if (language == AppLanguage.Croatian) hrLabel else enLabel) }
            )
        }
    }
}

// --- Section B: Notifications ---

@Composable
private fun NotificationsSection() {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader(stringResource(R.string.settings_section_notifications))
        AppCard {
            SettingsRow(
                icon = Icons.Filled.Notifications,
                title = stringResource(R.string.settings_monitoring_notification_title),
                description = stringResource(R.string.settings_monitoring_notification_description),
                trailing = {
                    Text(
                        text = stringResource(R.string.settings_always_enabled),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            )
        }
    }
}

// --- Section C: Permissions ---

@Composable
private fun PermissionsSection(
    microphoneGranted: Boolean,
    locationPermissionGranted: Boolean,
    preciseLocationGranted: Boolean,
    locationServicesEnabled: Boolean,
    onOpenAppSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader(stringResource(R.string.settings_section_permissions))
        AppCard {
            PermissionRow(Icons.Filled.Mic, stringResource(R.string.label_microphone), microphoneGranted, onOpenAppSettings)
            PermissionRow(
                Icons.Filled.LocationOn,
                stringResource(R.string.settings_label_location_permission),
                locationPermissionGranted,
                onOpenAppSettings
            )
            PermissionRow(
                Icons.Filled.GpsFixed,
                stringResource(R.string.settings_label_precise_location),
                preciseLocationGranted,
                onOpenAppSettings
            )
            LocationServicesRow(locationServicesEnabled, onOpenLocationSettings)
        }
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, label: String, granted: Boolean, onOpenAppSettings: () -> Unit) {
    SettingsRow(
        icon = icon,
        title = label,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                DeviceStatusBadge(
                    isOk = granted,
                    okTextRes = R.string.permission_status_granted,
                    notOkTextRes = R.string.permission_status_denied
                )
                if (!granted) {
                    TextButton(onClick = onOpenAppSettings) { Text(stringResource(R.string.settings_action_open)) }
                }
            }
        }
    )
}

/** Android's device-level Location Services toggle — distinct from location *permission* above:
 * both can be on/off independently of each other, and this row's Open action goes to Android's
 * location settings, never the app's own settings page. */
@Composable
private fun LocationServicesRow(enabled: Boolean, onOpenLocationSettings: () -> Unit) {
    SettingsRow(
        icon = Icons.Filled.MyLocation,
        title = stringResource(R.string.settings_label_location_services),
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                DeviceStatusBadge(
                    isOk = enabled,
                    okTextRes = R.string.location_services_status_enabled,
                    notOkTextRes = R.string.location_services_status_disabled
                )
                if (!enabled) {
                    TextButton(onClick = onOpenLocationSettings) { Text(stringResource(R.string.settings_action_open)) }
                }
            }
        }
    )
}

/** Shared visual shape for both a permission's granted/denied state and Location Services'
 * enabled/disabled state — the two are worded differently ([okTextRes]/[notOkTextRes]) since
 * "Granted"/"Denied" does not fit a device-level service toggle. */
@Composable
private fun DeviceStatusBadge(isOk: Boolean, okTextRes: Int, notOkTextRes: Int) {
    val color = if (isOk) SemanticColors.Success else SemanticColors.Warning
    val background = if (isOk) SemanticColors.SuccessBg else SemanticColors.WarningBg
    val text = stringResource(if (isOk) okTextRes else notOkTextRes)
    Surface(shape = AppShapes.ChipOrBadge, color = background) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 3.dp)
        )
    }
}

// --- Section E: Device ID ---

@Composable
private fun DeviceIdSection(installationId: String?, onCopyDeviceId: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader(stringResource(R.string.settings_section_device_id))
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Icon(Icons.Filled.Fingerprint, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_device_id_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    SelectionContainer {
                        Text(text = installationId.orEmpty(), style = MonospaceValueStyle, fontSize = 13.sp)
                    }
                }
                if (installationId != null) {
                    IconButton(onClick = { onCopyDeviceId(installationId) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.action_copy))
                    }
                }
            }
        }
    }
}

// --- Section F: About ---

@Composable
private fun AboutSection(versionName: String, onTermsClick: () -> Unit, onPrivacyClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader(stringResource(R.string.settings_section_about))
        AppCard {
            SettingsRow(
                icon = Icons.Filled.Info,
                title = stringResource(R.string.settings_label_version),
                trailing = {
                    Text(
                        text = versionName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            )
            SettingsRow(
                icon = Icons.Filled.Description,
                title = stringResource(R.string.settings_label_terms),
                onClick = onTermsClick,
                trailing = { ChevronIcon() }
            )
            SettingsRow(
                icon = Icons.Filled.Shield,
                title = stringResource(R.string.settings_label_privacy),
                onClick = onPrivacyClick,
                trailing = { ChevronIcon() }
            )
        }
    }
}

@Composable
private fun ChevronIcon() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    )
}

@Composable
private fun SettingsInfoDialog(title: String, content: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text(content, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
    )
}
