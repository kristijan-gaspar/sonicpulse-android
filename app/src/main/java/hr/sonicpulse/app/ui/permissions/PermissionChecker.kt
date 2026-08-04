package hr.sonicpulse.app.ui.permissions

/** Thin abstraction over `ContextCompat.checkSelfPermission` so permission-gated view models (e.g.
 * `SettingsViewModel`) stay plain-JVM testable via a fake, without needing a real Android Context. */
interface PermissionChecker {
    fun isGranted(permission: String): Boolean
}
