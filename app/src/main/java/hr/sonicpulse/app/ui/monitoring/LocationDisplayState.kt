package hr.sonicpulse.app.ui.monitoring

/** Drives the Lokacija mini-card (design spec §5.1F). */
enum class LocationDisplayState {
    Gps,
    Searching,
    PreciseRequired,
    Unavailable
}
