package hr.sonicpulse.app.ui.map

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/** `LocalContext.current` inside a Composable is not guaranteed to be an `Activity` directly — it
 * can be wrapped in one or more `ContextWrapper`s (themed contexts, test harnesses, some
 * third-party integrations). An unconditional `context as Activity` throws `ClassCastException` in
 * that case; this walks the wrapper chain instead and returns `null` if no `Activity` is found,
 * bounded so a malformed/cyclical wrapper chain can never hang or stack-overflow. */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    var stepsRemaining = 20
    while (current != null && stepsRemaining > 0) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext
        stepsRemaining--
    }
    return null
}
