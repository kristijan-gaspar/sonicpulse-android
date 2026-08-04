package hr.sonicpulse.app.ui.permissions

class FakePermissionChecker(private val granted: MutableSet<String> = mutableSetOf()) : PermissionChecker {

    override fun isGranted(permission: String): Boolean = permission in granted

    fun setGranted(permission: String, isGranted: Boolean) {
        if (isGranted) granted += permission else granted -= permission
    }
}
