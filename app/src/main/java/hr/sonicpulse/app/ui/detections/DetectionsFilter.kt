package hr.sonicpulse.app.ui.detections

/** Applied client-side over already-loaded pages only — selecting one never triggers downloading
 * the entire device history, and pagination stays available while a restrictive filter is active. */
enum class DetectionsFilter { All, Today, Grouped, Ungrouped }
