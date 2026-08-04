package hr.sonicpulse.app.service.notification

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** One short one-shot vibration per local detection. Any failure — no vibrator hardware, a denied
 * VIBRATE permission on some OEM skin, or any other [Exception] from the platform API — is
 * swallowed rather than thrown, so it can never take monitoring down with it. */
class AndroidDetectionVibrator @Inject constructor(
    @ApplicationContext private val context: Context
) : DetectionVibrator {

    override fun vibrateOnce() {
        try {
            val vibrator = obtainVibrator() ?: return
            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(VibrationEffect.createOneShot(VIBRATION_DURATION_MILLIS, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            Log.w(TAG, "Detection vibration failed")
        }
    }

    private fun obtainVibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private companion object {
        const val TAG = "DetectionVibrator"
        const val VIBRATION_DURATION_MILLIS = 200L
    }
}
