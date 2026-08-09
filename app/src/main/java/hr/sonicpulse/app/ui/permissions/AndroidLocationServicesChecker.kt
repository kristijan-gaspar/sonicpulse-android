package hr.sonicpulse.app.ui.permissions

import android.content.Context
import android.location.LocationManager
import androidx.core.location.LocationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class AndroidLocationServicesChecker @Inject constructor(
    @ApplicationContext private val context: Context
) : LocationServicesChecker {
    override fun isEnabled(): Boolean {
        val locationManager = context.getSystemService(LocationManager::class.java) ?: return false
        return LocationManagerCompat.isLocationEnabled(locationManager)
    }
}
