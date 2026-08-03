package hr.sonicpulse.app.ui.monitoring

/** Collapses [hr.sonicpulse.app.domain.model.SubmissionStatus] into the 3 banner states of design spec §5.1G. */
enum class SendResult {
    Sending,
    Sent,
    Failed
}
