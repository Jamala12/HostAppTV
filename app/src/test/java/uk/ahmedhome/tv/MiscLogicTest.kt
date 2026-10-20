package uk.ahmedhome.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uk.ahmedhome.tv.data.account.AccountStore
import uk.ahmedhome.tv.data.db.ProgressEntity
import uk.ahmedhome.tv.data.xtream.ProviderConfig
import uk.ahmedhome.tv.data.xtream.XEpisode
import uk.ahmedhome.tv.ui.details.nextUp

class AccountStoreCodecTest {
    @Test fun `round trips credentials with separators`() {
        val c = ProviderConfig("http://h:8080", "user\nname", "p\\a\nss", true)
        assertEquals(c, AccountStore.decode(AccountStore.encode(c)))
    }
    @Test fun `account id is stable, non secret and password independent`() {
        val a = AccountStore.accountId(ProviderConfig("http://h:8080", "alice", "one", false))
        assertEquals(a, AccountStore.accountId(ProviderConfig("http://H:8080/", "alice", "two", false)))
        assertNotEquals(a, AccountStore.accountId(ProviderConfig("http://h:8080", "bob", "one", false)))
        assertFalse(a.contains("alice")); assertEquals(16, a.length)
    }
    @Test fun `config toString hides secrets`() {
        assertFalse(ProviderConfig("http://h", "alice", "s3cret", false).toString().contains("s3cret"))
    }
}

class NextUpTest {
    private val eps = (1..3).map { XEpisode(it.toLong(), 1, it, "E$it", "mp4", 60, null, null) }
    private fun p(id: Long, finished: Boolean, at: Long) = ProgressEntity("a", "episode", id, null, 1, 1, id.toInt(), "S", null, null, "mp4", 10, 60_000, at, finished)

    @Test fun `starts at first episode`() { assertEquals(1L, nextUp(eps, emptyList())!!.first.id) }
    @Test fun `resumes unfinished episode`() { assertEquals(2L, nextUp(eps, listOf(p(1, true, 1), p(2, false, 2)))!!.first.id) }
    @Test fun `moves past finished episode`() { assertEquals(3L, nextUp(eps, listOf(p(2, true, 5)))!!.first.id) }
    @Test fun `empty series has nothing`() { assertNull(nextUp(emptyList(), emptyList())) }
}
