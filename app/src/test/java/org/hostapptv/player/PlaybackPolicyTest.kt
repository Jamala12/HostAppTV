package org.hostapptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.hostapptv.player.playback.FailureClass
import org.hostapptv.player.playback.FailureClassifier
import org.hostapptv.player.playback.PlaybackController
import org.hostapptv.player.playback.RetryDecision
import org.hostapptv.player.playback.RetryPolicy

class RetryPolicyTest {
    private var now = 0L
    private fun policy() = RetryPolicy(clock = { now })

    @Test fun `transient failures keep retrying while the recovery window is open`() {
        val p = policy()
        val delays = (1..3).map { (p.onFailure(FailureClass.TRANSIENT) as RetryDecision.Retry).also { now += it.delayMs + 2_000 }.delayMs }
        assertEquals(listOf(500L, 1_000L, 2_000L), delays)
        assertTrue(p.onFailure(FailureClass.TRANSIENT) is RetryDecision.Retry)
        now = 15_000
        assertEquals(RetryDecision.GiveUp(FailureClass.TRANSIENT), p.onFailure(FailureClass.TRANSIENT))
    }

    @Test fun `recovery window bounds total retry time`() {
        val p = policy()
        assertTrue(p.onFailure(FailureClass.TRANSIENT) is RetryDecision.Retry)
        now += 15_000 // pending connections consume the same deadline as retry delays
        assertEquals(RetryDecision.GiveUp(FailureClass.TRANSIENT), p.onFailure(FailureClass.TRANSIENT))
    }

    @Test fun `actionable failures never auto retry`() {
        listOf(FailureClass.AUTH, FailureClass.UNAVAILABLE, FailureClass.UNSUPPORTED, FailureClass.CLEARTEXT).forEach {
            assertEquals(RetryDecision.GiveUp(it), policy().onFailure(it))
        }
    }

    @Test fun `sustained healthy playback restores the budget`() {
        val p = policy()
        p.onFailure(FailureClass.TRANSIENT)
        p.onHealthy(); now += 3_000; p.onHealthy()
        assertEquals(0, p.attemptsUsed)
        assertTrue(p.onFailure(FailureClass.TRANSIENT) is RetryDecision.Retry)
    }

    @Test fun `buffering consumes the budget before a retry is attempted`() {
        val p = policy()
        p.beginRecovery(); now = 12_000
        assertEquals(3_000L, p.remainingMs)
        p.onFailure(FailureClass.TRANSIENT)
        assertEquals(3_000L, p.remainingMs)
        now = 15_000
        assertEquals(0L, p.remainingMs)
        assertEquals(RetryDecision.GiveUp(FailureClass.TRANSIENT), p.onFailure(FailureClass.TRANSIENT))
    }

    @Test fun `brief readiness does not restart a failing recovery window`() {
        val p = policy()
        p.beginRecovery(); now = 5_000; p.onHealthy(); now = 6_000
        p.onFailure(FailureClass.TRANSIENT)
        assertEquals(9_000L, p.remainingMs)
    }

    @Test fun `behind live window rejoins immediately`() {
        assertEquals(RetryDecision.Retry(0, 1), policy().onFailure(FailureClass.BEHIND_LIVE_WINDOW))
    }

    @Test fun `on demand keeps three retries with the original backoff`() {
        val p = RetryPolicy.onDemand { now }
        val delays = (1..3).map { (p.onFailure(FailureClass.TRANSIENT) as RetryDecision.Retry).delayMs }
        assertEquals(listOf(1_000L, 3_000L, 6_000L), delays)
        assertEquals(RetryDecision.GiveUp(FailureClass.TRANSIENT), p.onFailure(FailureClass.TRANSIENT))
    }

    @Test fun `on demand failure window allows more than fifteen seconds but refuses a late retry`() {
        val p = RetryPolicy.onDemand { now }
        p.onFailure(FailureClass.TRANSIENT)
        now = 16_000
        assertEquals(RetryDecision.Retry(3_000, 2), p.onFailure(FailureClass.TRANSIENT))
        now = 25_000
        assertEquals(RetryDecision.GiveUp(FailureClass.TRANSIENT), p.onFailure(FailureClass.TRANSIENT))
    }

    @Test fun `on demand restores its retry budget after a minute of healthy playback`() {
        val p = RetryPolicy.onDemand { now }
        p.onFailure(FailureClass.TRANSIENT)
        p.onHealthy(); now = 3_000; p.onHealthy()
        assertEquals(1, p.attemptsUsed)
        now = 60_000; p.onHealthy()
        assertEquals(0, p.attemptsUsed)
    }
}

class FailureClassifierTest {
    @Test fun `http status decides first`() {
        assertEquals(FailureClass.AUTH, FailureClassifier.classify(2004, 401))
        assertEquals(FailureClass.AUTH, FailureClassifier.classify(2004, 403))
        assertEquals(FailureClass.UNAVAILABLE, FailureClassifier.classify(2004, 404))
        assertEquals(FailureClass.TRANSIENT, FailureClassifier.classify(2004, 503))
        assertEquals(FailureClass.TRANSIENT, FailureClassifier.classify(2004, 429))
    }
    @Test fun `media3 error codes`() {
        assertEquals(FailureClass.TRANSIENT, FailureClassifier.classify(2001, null)) // connection failed
        assertEquals(FailureClass.TRANSIENT, FailureClassifier.classify(2002, null)) // timeout
        assertEquals(FailureClass.BEHIND_LIVE_WINDOW, FailureClassifier.classify(1002, null))
        assertEquals(FailureClass.CLEARTEXT, FailureClassifier.classify(2007, null))
        assertEquals(FailureClass.UNSUPPORTED, FailureClassifier.classify(4003, null))
        assertEquals(FailureClass.UNSUPPORTED, FailureClassifier.classify(3003, null))
        assertEquals(FailureClass.TRANSIENT, FailureClassifier.classify(3001, null)) // damaged live segment: bounded retry
    }
    @Test fun `software decoder detection`() {
        assertTrue(PlaybackController.isSoftwareDecoder("c2.android.hevc.decoder"))
        assertTrue(PlaybackController.isSoftwareDecoder("OMX.google.h264.decoder"))
        assertEquals(false, PlaybackController.isSoftwareDecoder("OMX.MTK.VIDEO.DECODER.HEVC"))
        assertEquals(false, PlaybackController.isSoftwareDecoder("c2.amlogic.hevc.decoder"))
    }
}
