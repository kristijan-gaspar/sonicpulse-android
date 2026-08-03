package hr.sonicpulse.app.ui.monitoring

/**
 * The Monitoring screen's own display phase — named to avoid colliding with the repository-level
 * [hr.sonicpulse.app.repository.MonitoringState].
 */
enum class MonitoringPhase {
    Idle,
    AcquiringLocation,
    Listening,
    PreciseLocationRequired
}
