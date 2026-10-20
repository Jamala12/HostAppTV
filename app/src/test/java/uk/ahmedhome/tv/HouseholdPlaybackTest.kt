package uk.ahmedhome.tv

import org.junit.Assert.*
import org.junit.Test
import uk.ahmedhome.tv.core.Redactor
import uk.ahmedhome.tv.playback.SubtitlePolicy

class HouseholdPlaybackTest {
    @Test fun englishSubtitlesFollowActualAudioLanguage() {
        assertFalse(SubtitlePolicy.wantsEnglish("foreign", listOf("en-GB")))
        assertFalse(SubtitlePolicy.wantsEnglish("foreign", listOf("eng")))
        assertTrue(SubtitlePolicy.wantsEnglish("foreign", listOf("ko")))
        assertTrue(SubtitlePolicy.wantsEnglish("foreign", listOf("zh-Hans")))
        assertFalse(SubtitlePolicy.wantsEnglish("foreign", listOf(null, "und")))
        assertTrue(SubtitlePolicy.wantsEnglish("always", emptyList()))
        assertFalse(SubtitlePolicy.wantsEnglish("off", listOf("ko")))
    }

    @Test fun authorizationTokensAreRedactedWithoutRegisteringTheToken() {
        assertEquals("Authorization: Bearer ***", Redactor.redact("Authorization: Bearer synthetic.token-private"))
        assertEquals("authorization: bearer ***", Redactor.redact("authorization: bearer other-synthetic-token"))
    }
}
