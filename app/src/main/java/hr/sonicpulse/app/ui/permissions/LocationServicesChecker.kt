package hr.sonicpulse.app.ui.permissions

/** Thin abstraction over Android's Location Services (GPS/network provider) enabled state,
 * mirroring [PermissionChecker] — keeps permission-gated view models (e.g. `SettingsViewModel`)
 * plain-JVM testable via a fake, without needing a real Android Context/LocationManager. */
interface LocationServicesChecker {
    fun isEnabled(): Boolean
}
