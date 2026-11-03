package local.watchremote

import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Offline Android-runtime regressions. Never reads the user's auth or calls Spotify. */
class ValidationInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        var count = 0
        try {
            val testStore = AuthStore(targetContext, "regression-only-vault")
            fun auth() = JSONObject().put("client_id", "a".repeat(32)).put("access_token", "fake-test-access")
                .put("refresh_token", "fake-test-refresh").put("expires_at", System.currentTimeMillis() + 3600000)
            fun make(vararg responses: Fake): SpotifyClient {
                val queue = java.util.ArrayDeque(responses.toList())
                return SpotifyClient(testStore, retryPause = {}) { check(queue.isNotEmpty()); queue.removeFirst() }.apply { provision(auth()) }
            }
            fun test(block: () -> Unit) { block(); count++ }
            test {
                val client = make(Fake(204, ""))
                check(client.request("GET", "/me/player") == null)
            }
            test {
                val client = make(Fake(200, "opaque-success"), Fake(200, "\"opaque-success\""))
                check(client.request("PUT", "/me/player/pause") == null)
                check(client.request("POST", "/me/player/next") == null)
            }
            test {
                val client = make(Fake(200, "opaque-malformed-read"))
                try { client.request("GET", "/me/player"); error("must reject malformed read") }
                catch (e: ApiFailure) { check(!e.userText.contains("opaque")); check(e.userText.contains("格式")) }
            }
            test {
                val client = make(Fake(403, "{\"error\":{\"reason\":\"RESTRICTION_VIOLATED\"}}"))
                try { client.request("PUT", "/me/player/pause"); error("must reject 403") }
                catch (e: ApiFailure) { check(!e.userText.contains("Premium")); check(e.reason == "RESTRICTION_VIOLATED") }
            }
            test {
                val client = make(Fake(429, "", "10"))
                repeat(2) { try { client.request("GET", "/me/player"); error("must reject limit") } catch (e: ApiFailure) { check(e.code == 429) } }
            }
            test {
                for (method in listOf("GET", "POST")) {
                    val client = if (method == "GET") make(Fake(502, "<html>temporary upstream error</html>"), Fake(502, "<html>temporary upstream error</html>"))
                        else make(Fake(502, "<html>temporary upstream error</html>"))
                    try { client.request(method, "/me/player"); error("must reject 502") }
                    catch (e: ApiFailure) { check(e.code == 502); check(!e.userText.contains("html")); check(!e.userText.contains("Premium")) }
                }
            }
            test {
                val client = make(Fake(401, ""), Fake(200, "{\"access_token\":\"new-fake\",\"expires_in\":3600}"), Fake(200, "{}"))
                check(client.request("GET", "/me/player") != null)
                check(testStore.load()!!.getString("refresh_token") == "fake-test-refresh")
            }
            test {
                testStore.save(auth()); val reloaded = AuthStore(targetContext, "regression-only-vault").load()!!
                check(reloaded.getString("access_token") == "fake-test-access")
            }
            test {
                val p = Playback.parse(JSONObject("""{"device":{"id":"device","name":"test","volume_percent":null,"supports_volume":false},"is_playing":true,"progress_ms":1000,"item":{"name":"long title","duration_ms":120000,"uri":"spotify:track:abc","artists":[{"name":"artist"}],"album":{"images":[]}},"actions":{"disallows":{"skipping_next":true}}}"""))
                check(p.volume == null && !p.volumeSupported)
                check(!p.allows("skipping_next") && p.allows("pausing"))
                check(p.positionNow(p.sampled + 2000) == 3000)
                check(p.positionNow(p.sampled + 200000) == 120000)
            }
            test {
                val p = Playback.parse(null)
                check(p.deviceId == null && !p.allows("pausing"))
            }
            test {
                val client = make(Fake(502, "upstream"), Fake(200, "{\"recovered\":true}"))
                check(client.request("GET", "/me/player")!!.getBoolean("recovered"))
            }
            test {
                val client = make(Fake(0, "", failure = java.net.SocketTimeoutException()), Fake(200, "{}"))
                check(client.request("GET", "/me/player") != null)
            }
            test {
                var calls = 0
                val client = SpotifyClient(testStore, retryPause = {}) { calls++; Fake(0, "", failure = java.net.SocketTimeoutException()) }.apply { provision(auth()) }
                try { client.request("POST", "/me/player/next"); error("must report ambiguous command") }
                catch (_: java.net.SocketTimeoutException) { check(calls == 1) }
            }
            test {
                var calls = 0
                val client = SpotifyClient(testStore, retryPause = {}) { calls++; Fake(0, "", failure = javax.net.ssl.SSLPeerUnverifiedException("test")) }.apply { provision(auth()) }
                try { client.request("GET", "/me/player"); error("must reject unverified peer") }
                catch (_: javax.net.ssl.SSLPeerUnverifiedException) { check(calls == 1) }
            }
            test {
                var calls = 0
                lateinit var client: SpotifyClient
                client = SpotifyClient(testStore, retryPause = {}) { calls++; Fake(200, "{}", onResponse = { client.cancelRead() }) }.apply { provision(auth()) }
                try { client.request("GET", "/me/player"); error("must cancel read") }
                catch (_: ReadCancelled) { check(calls == 1) }
            }
            testStore.clear()
            result.putString("stream", "Offline Android regression checks passed: $count\n")
            finish(-1, result)
        } catch (e: Throwable) {
            result.putString("stream", "Offline regression failed after $count: ${e.javaClass.simpleName}\n${e.stackTrace.take(5).joinToString("\n")}\n")
            finish(0, result)
        }
    }
    private class Fake(private val code: Int, private val data: String, private val retry: String? = null, private val failure: java.io.IOException? = null, private val onResponse: (() -> Unit)? = null) : HttpURLConnection(URL("https://example.invalid")) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode(): Int { failure?.let { throw it }; onResponse?.invoke(); return code }
        override fun getHeaderField(name: String?): String? = if (name == "Retry-After") retry else null
        override fun getInputStream() = ByteArrayInputStream(data.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(data.toByteArray())
        override fun getOutputStream() = ByteArrayOutputStream()
    }
}
