package org.hostapptv.player

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.hostapptv.player.data.xtream.CatalogKind
import org.hostapptv.player.data.xtream.ProviderConfig
import org.hostapptv.player.data.xtream.XLiveStream
import org.hostapptv.player.data.xtream.XtreamClient
import org.hostapptv.player.data.xtream.XtreamException

/** Contract tests against synthetic provider responses (no real provider data). */
class XtreamClientTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun client(user: String = "u", pass: String = "p") =
        XtreamClient(http, ProviderConfig(server.url("/").toString(), user, pass, allowCleartext = true))

    private fun json(body: String, code: Int = 200) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    @Test fun `authenticates and reads account facts`() = runBlocking {
        json("""{"user_info":{"auth":1,"status":"Active","exp_date":"1800000000","max_connections":"2","active_cons":0,"allowed_output_formats":["m3u8","ts"]},"server_info":{}}""")
        val info = client().authenticate()
        assertTrue(info.active); assertEquals(1800000000L, info.expiresAtEpochSec); assertEquals(2, info.maxConnections)
        assertEquals(listOf("m3u8", "ts"), info.outputFormats)
        val req = server.takeRequest()
        assertEquals("/player_api.php?username=u&password=p", req.path)
    }

    @Test fun `auth zero and http 401 are unauthorized`() = runBlocking {
        json("""{"user_info":{"auth":0}}""")
        try { client().authenticate(); fail() } catch (e: XtreamException.Unauthorized) {}
        json("nope", 401)
        try { client().authenticate(); fail() } catch (e: XtreamException.Unauthorized) {}
    }

    @Test fun `malformed json is reported, not thrown raw`() = runBlocking {
        json("""{"user_info": {"auth": 1, "status": """)
        try { client().authenticate(); fail() } catch (e: XtreamException.Malformed) {}
        json("<html>gateway</html>")
        try { client().liveStreams { }; fail() } catch (e: XtreamException.Malformed) {}
    }

    @Test fun `server errors map to http exception`() = runBlocking {
        json("", 503)
        try { client().categories(CatalogKind.LIVE); fail() } catch (e: XtreamException.Http) { assertEquals(503, e.code) }
    }

    @Test fun `tolerates mixed field types and skips unusable rows`() = runBlocking {
        json("""[
          {"num":"1","name":"One","stream_id":"10","stream_icon":"","epg_channel_id":null,"category_id":2,"tv_archive":"1","extra":{"x":[1,2]}},
          {"num":2,"name":null,"stream_id":11,"tv_archive":false},
          {"name":"No id"},
          "garbage",
          {"num":3.0,"name":"Three","stream_id":12.0,"category_id":"5"}
        ]""")
        val got = mutableListOf<XLiveStream>()
        val n = client().liveStreams { got += it }
        assertEquals(3, n)
        assertEquals(listOf(10L, 11L, 12L), got.map { it.streamId })
        assertEquals("Channel 11", got[1].name)
        assertNull(got[0].icon); assertEquals("2", got[0].categoryId); assertTrue(got[0].tvArchive)
    }

    @Test fun `empty catalogue returned as object is zero items`() = runBlocking {
        json("{}")
        assertEquals(0, client().movies { fail("no batch expected") })
    }

    @Test fun `error objects are not successful empty catalogues`() = runBlocking {
        for (body in listOf("""{"user_info":{"auth":0}}""", """{"error":"try again"}""")) {
            json(body)
            try { client().movies { fail("No batch expected") }; fail("Error envelope was accepted") }
            catch (_: XtreamException.Malformed) { }
        }
    }

    @Test fun `streams a large catalogue in bounded batches`() = runBlocking {
        val total = 100_000
        val body = Buffer().apply {
            writeUtf8("[")
            for (i in 1..total) { if (i > 1) writeUtf8(","); writeUtf8("""{"num":$i,"name":"Synthetic channel $i","stream_id":$i,"category_id":"${i % 40}"}""") }
            writeUtf8("]")
        }
        server.enqueue(MockResponse().setBody(body))
        var batches = 0; var maxBatch = 0; var count = 0
        val started = System.nanoTime()
        val n = client().liveStreams(batchSize = 500) { b -> batches++; maxBatch = maxOf(maxBatch, b.size); count += b.size }
        val ms = (System.nanoTime() - started) / 1_000_000
        println("JVM parse of $total synthetic live rows: ${ms} ms in $batches batches (host JVM, not device)")
        assertEquals(total, n); assertEquals(total, count); assertEquals(500, maxBatch); assertEquals(200, batches)
    }

    @Test fun `series info accepts episodes keyed by season or as nested arrays`() = runBlocking {
        json("""{"seasons":[{"season_number":1},{"season_number":2}],"info":{"plot":"P","genre":"G"},
          "episodes":{"2":[{"id":"21","episode_num":1,"title":"B1","container_extension":"mkv","info":{"duration":"00:42:10"}}],
                      "1":[{"id":"12","episode_num":2,"title":"A2"},{"id":"11","episode_num":"1","title":"A1","info":{"duration_secs":"1500"}}]}}""")
        val a = client().seriesInfo(7)
        assertEquals(listOf(11L, 12L, 21L), a.episodes.map { it.id })
        assertEquals(1500L, a.episodes[0].durationSecs); assertEquals(2530L, a.episodes[2].durationSecs)
        assertEquals("mkv", a.episodes[2].extension); assertEquals(listOf(1, 2), a.seasons)
        json("""{"episodes":[[{"id":1,"season":3,"episode_num":1,"title":"X"}]],"info":[]}""")
        val b = client().seriesInfo(8)
        assertEquals(3, b.episodes.single().season); assertNull(b.plot)
    }

    @Test fun `vod info and short epg`() = runBlocking {
        json("""{"info":{"plot":"Story","duration_secs":5400,"backdrop_path":["http://img/b.jpg"],"genre":"Drama"},"movie_data":{"container_extension":"mkv"}}""")
        val v = client().vodInfo(5)
        assertEquals("Story", v.plot); assertEquals(5400L, v.durationSecs); assertEquals("http://img/b.jpg", v.backdrop); assertEquals("mkv", v.extension)
        // "News at Ten" in base64, and a plain title that is not valid base64 text
        json("""{"epg_listings":[{"title":"TmV3cyBhdCBUZW4=","start_timestamp":"1700000000","stop_timestamp":"1700001800"},{"title":"Plain title!"}]}""")
        val e = client().shortEpg(5)
        assertEquals(listOf("News at Ten", "Plain title!"), e.map { it.title }); assertEquals(1700000000L, e[0].startEpochSec)
    }

    @Test fun `normalizes server addresses`() {
        assertEquals("http://host:8080/", XtreamClient.normalizeBaseUrl("host:8080").toString())
        assertEquals("https://host/", XtreamClient.normalizeBaseUrl("https://host/player_api.php?username=x").toString())
        assertEquals("http://host:25461/sub/", XtreamClient.normalizeBaseUrl(" http://host:25461/sub ").toString())
        assertNull(XtreamClient.normalizeBaseUrl("   "))
    }

    @Test fun `builds escaped stream urls without persisting them`() {
        val c = XtreamClient(http, ProviderConfig("http://h:80", "a b", "p/w", true))
        assertEquals("http://h/live/a%20b/p%2Fw/5.ts", c.liveUrl(5, "ts"))
        assertEquals("http://h/movie/a%20b/p%2Fw/6.mkv", c.movieUrl(6, "mkv"))
        assertEquals("http://h/series/a%20b/p%2Fw/7.mp4", c.episodeUrl(7, null))
    }

    @Test fun `refuses plain http unless allowed`() = runBlocking {
        val c = XtreamClient(http, ProviderConfig(server.url("/").toString(), "u", "p", allowCleartext = false))
        try { c.authenticate(); fail() } catch (e: XtreamException.CleartextRefused) {}
        assertEquals(0, server.requestCount)
    }

    @Test fun `durations parse`() {
        assertEquals(3723L, XtreamClient.parseDuration("01:02:03")); assertEquals(125L, XtreamClient.parseDuration("2:05"))
        assertNull(XtreamClient.parseDuration("n/a")); assertFalse(XtreamClient.decodeMaybeBase64("") != null)
    }
}
