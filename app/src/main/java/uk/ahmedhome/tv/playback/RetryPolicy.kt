package uk.ahmedhome.tv.playback

enum class FailureClass { TRANSIENT, AUTH, UNAVAILABLE, UNSUPPORTED, CLEARTEXT, BEHIND_LIVE_WINDOW }

sealed interface RetryDecision {
    data class Retry(val delayMs: Long, val attempt: Int) : RetryDecision
    data class GiveUp(val failure: FailureClass) : RetryDecision
}

/**
 * At most [maxRetries] automatic retries with backoff, all within a [windowMs] recovery window that starts
 * at the first failure. Auth / unavailable / unsupported failures need user action and are never retried.
 * Sustained healthy playback ([healthyResetMs]) resets the budget.
 */
class RetryPolicy(
    private val maxRetries: Int = 3,
    private val windowMs: Long = 30_000,
    private val backoffMs: List<Long> = listOf(1_000, 3_000, 6_000),
    private val healthyResetMs: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var attempts = 0
    private var windowStart = -1L
    private var healthySince = -1L

    fun onFailure(failure: FailureClass): RetryDecision {
        if (failure != FailureClass.TRANSIENT && failure != FailureClass.BEHIND_LIVE_WINDOW) return RetryDecision.GiveUp(failure)
        val now = clock()
        healthySince = -1L
        if (windowStart < 0) windowStart = now
        if (attempts >= maxRetries) return RetryDecision.GiveUp(FailureClass.TRANSIENT)
        val delay = if (failure == FailureClass.BEHIND_LIVE_WINDOW) 0L else backoffMs[attempts.coerceAtMost(backoffMs.lastIndex)]
        if (now + delay - windowStart > windowMs) return RetryDecision.GiveUp(FailureClass.TRANSIENT)
        attempts++
        return RetryDecision.Retry(delay, attempts)
    }

    /** Call while frames are playing; resets the budget after a sustained healthy period. */
    fun onHealthy() {
        val now = clock()
        if (healthySince < 0) healthySince = now
        if (attempts > 0 && now - healthySince >= healthyResetMs) reset()
    }

    fun reset() { attempts = 0; windowStart = -1L; healthySince = -1L }
    val attemptsUsed: Int get() = attempts
}

/** Maps Media3 PlaybackException error codes (kept as ints for JVM tests) and HTTP status to a failure class. */
object FailureClassifier {
    fun classify(errorCode: Int, httpStatus: Int?): FailureClass {
        if (httpStatus != null) return when (httpStatus) {
            401, 403 -> FailureClass.AUTH
            404, 410 -> FailureClass.UNAVAILABLE
            else -> if (httpStatus in 500..599 || httpStatus == 429 || httpStatus == 408) FailureClass.TRANSIENT else FailureClass.UNAVAILABLE
        }
        return when (errorCode) {
            1002 -> FailureClass.BEHIND_LIVE_WINDOW // ERROR_CODE_BEHIND_LIVE_WINDOW
            2007 -> FailureClass.CLEARTEXT // ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED
            2005, 2006 -> FailureClass.UNAVAILABLE // FILE_NOT_FOUND, NO_PERMISSION
            3003, 3004 -> FailureClass.UNSUPPORTED // container / manifest unsupported
            // 3001/3002 (malformed data) can come from an interrupted live segment: bounded retry.
            in 4001..4005 -> FailureClass.UNSUPPORTED // DECODER_* / DECODING_FORMAT_*
            in 5001..5002 -> FailureClass.UNSUPPORTED // AUDIO_TRACK_*
            in 6000..6008 -> FailureClass.UNSUPPORTED // DRM
            else -> FailureClass.TRANSIENT // network, timeouts, unspecified IO, remote errors
        }
    }
}
