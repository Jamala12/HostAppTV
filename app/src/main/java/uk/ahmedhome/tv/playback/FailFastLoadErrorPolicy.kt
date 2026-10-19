package uk.ahmedhome.tv.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Refused or missing streams (401/403/404/410) fail immediately so the viewer gets an actionable message;
 * other load errors keep Media3's short internal retries, bounded overall by [RetryPolicy].
 */
@OptIn(UnstableApi::class)
class FailFastLoadErrorPolicy : DefaultLoadErrorHandlingPolicy(/* minimumLoadableRetryCount = */ 2) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val e = loadErrorInfo.exception
        if (e is HttpDataSource.InvalidResponseCodeException && e.responseCode in FATAL) return C.TIME_UNSET
        return super.getRetryDelayMsFor(loadErrorInfo)
    }

    private companion object { val FATAL = setOf(401, 403, 404, 410) }
}
