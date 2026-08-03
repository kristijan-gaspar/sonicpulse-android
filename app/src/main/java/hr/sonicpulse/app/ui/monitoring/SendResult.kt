package hr.sonicpulse.app.ui.monitoring

/**
 * Display-shaped submission outcome for one detection (design spec §5.1G), preserving the
 * user-visible distinctions from [hr.sonicpulse.app.domain.model.SubmissionStatus] — never a
 * 1:1 mirror of every [hr.sonicpulse.app.domain.model.SubmissionFailureReason] variant (that
 * would leak protocol/diagnostic detail into the UI), but not collapsed to one generic failure
 * either.
 */
enum class SendResult {
    Sending,
    Sent,
    FailedNoLocation,
    FailedNetwork,
    FailedServerConfig,
    FailedOther
}
