package hr.sonicpulse.app.ui.detections

/**
 * Three genuinely different situations that must never share one generic "nothing here" view:
 * a device with no history at all is not the same as a restrictive filter matching nothing in
 * what's loaded so far, which is itself not the same as "no matches yet, but more backend pages
 * might have some" — that last case must still let the user trigger another page load.
 */
sealed interface DetectionsEmptyState {
    data object NoDetectionsAtAll : DetectionsEmptyState
    data object NoMatchesForFilter : DetectionsEmptyState
    data object NoCurrentMatchesMorePagesAvailable : DetectionsEmptyState
}
