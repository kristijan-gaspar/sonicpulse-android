package hr.sonicpulse.app.domain.model

/** Why a detection was not submitted or was rejected (plan §2.9's outcome table). */
enum class SubmissionFailureReason {
    NO_LOCATION,
    STALE_LOCATION,
    INACCURATE_LOCATION,
    NETWORK_ERROR,
    BAD_REQUEST,
    UNAUTHORIZED,
    RATE_LIMITED,
    CLIENT_ERROR,
    SERVER_ERROR
}
