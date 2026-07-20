package hr.sonicpulse.app.data.audio

sealed interface AudioCaptureError {
    data object UnsupportedConfiguration : AudioCaptureError
    data object PermissionDenied : AudioCaptureError
    data class ReadFailure(val errorCode: Int) : AudioCaptureError
}
