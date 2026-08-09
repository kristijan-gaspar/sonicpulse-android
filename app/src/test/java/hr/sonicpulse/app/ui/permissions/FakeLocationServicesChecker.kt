package hr.sonicpulse.app.ui.permissions

class FakeLocationServicesChecker(private var enabled: Boolean = false) : LocationServicesChecker {

    override fun isEnabled(): Boolean = enabled

    fun setEnabled(isEnabled: Boolean) {
        enabled = isEnabled
    }
}
