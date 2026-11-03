package local.watchremote

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import android.os.SystemClock
import java.io.IOException

internal class ApiFailure(val userText: String, val code: Int = 0, val reason: String = "") : IOException(userText)
internal class ReadCancelled : IOException()
internal data class Playback(
    val deviceId: String? = null, val deviceName: String = "选择播放设备", val playing: Boolean = false,
    val title: String = "没有活跃播放", val artist: String = "请在手机或电脑打开 Spotify", val uri: String = "",
    val cover: String = "", val duration: Int = 0, val position: Int = 0, val sampled: Long = SystemClock.elapsedRealtime(),
    val volume: Int? = null, val volumeSupported: Boolean = false, val restricted: Boolean = false,
    val shuffle: Boolean = false, val repeat: String = "off", val disallowed: Set<String> = emptySet()
) {
    fun positionNow(now: Long = SystemClock.elapsedRealtime()): Int =
        (position.toLong() + if (playing) (now - sampled).coerceAtLeast(0) else 0L).coerceIn(0, duration.toLong()).toInt()
    fun allows(action: String) = deviceId != null && !restricted && action !in disallowed
    companion object {
        fun parse(value: JSONObject?): Playback {
            if (value == null) return Playback()
            val device = value.optJSONObject("device") ?: JSONObject()
            val item = value.optJSONObject("item")
            val artists = item?.optJSONArray("artists") ?: JSONArray()
            val album = item?.optJSONObject("album")
            val images = album?.optJSONArray("images") ?: item?.optJSONArray("images")
            val cover = (0 until (images?.length() ?: 0)).mapNotNull { images?.optJSONObject(it) }
                .sortedBy { kotlin.math.abs(it.optInt("width", 160) - 160) }.firstOrNull()?.optString("url") ?: ""
            val flags = value.optJSONObject("actions")?.optJSONObject("disallows") ?: JSONObject()
            val disallowed = flags.keys().asSequence().filter { flags.optBoolean(it) }.toSet()
            return Playback(device.optString("id").takeIf { it.isNotBlank() && it != "null" }, device.optString("name", "选择播放设备"),
                value.optBoolean("is_playing"), item?.optString("name") ?: "当前内容不可显示",
                (0 until artists.length()).mapNotNull { artists.optJSONObject(it)?.optString("name") }.joinToString(" / ")
                    .ifEmpty { item?.optJSONObject("show")?.optString("name") ?: "Spotify" },
                item?.optString("uri") ?: "", cover, item?.optInt("duration_ms") ?: 0, value.optInt("progress_ms"),
                SystemClock.elapsedRealtime(), if (device.isNull("volume_percent")) null else device.optInt("volume_percent"),
                device.optBoolean("supports_volume", false), device.optBoolean("is_restricted"),
                value.optBoolean("shuffle_state"), value.optString("repeat_state", "off"), disallowed)
        }
    }
}

internal class SpotifyClient(private val store: AuthStore, private val retryPause: (Long) -> Unit = { Thread.sleep(it) }, private val connection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    // This client is confined to the activity's single network worker.
    var auth: JSONObject? = null
        private set
    var blockedUntil = 0L
        private set
    private val readEpoch = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var activeRead: HttpURLConnection? = null
    fun cancelRead() { readEpoch.incrementAndGet(); try { activeRead?.disconnect() } catch (_: Exception) {} }
    fun load() { auth = store.load() }
    fun provision(value: JSONObject) { store.save(value); auth = value }
    fun logout() { store.clear(); auth = null }
    fun authorized() = auth != null
    private fun token(): String {
        val saved = auth ?: throw ApiFailure("请先在设置中完成电脑配对")
        if (saved.optLong("expires_at") > System.currentTimeMillis() + 60000) return saved.getString("access_token")
        val conn = connection(URL("https://accounts.spotify.com/api/token"))
        try {
            conn.requestMethod = "POST"; conn.doOutput = true; conn.connectTimeout = 4000; conn.readTimeout = 6000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val form = "grant_type=refresh_token&refresh_token=${enc(saved.getString("refresh_token"))}&client_id=${enc(saved.getString("client_id"))}"
            conn.outputStream.use { it.write(form.toByteArray()) }
            if (conn.responseCode == 429) { setLimit(conn); throw ApiFailure("授权服务繁忙，请稍后重试", 429) }
            if (conn.responseCode in 500..599) throw ApiFailure("授权服务暂时异常，请稍后重试", conn.responseCode)
            if (conn.responseCode != 200) throw ApiFailure("授权刷新失败，请重新配对", conn.responseCode)
            val fresh = try { JSONObject(conn.inputStream.bufferedReader().use { it.readText() }) }
                catch (_: Exception) { throw ApiFailure("授权服务返回异常，请稍后重试") }
            saved.put("access_token", fresh.getString("access_token"))
                .put("expires_at", System.currentTimeMillis() + fresh.getLong("expires_in") * 1000)
            if (fresh.has("refresh_token")) saved.put("refresh_token", fresh.getString("refresh_token"))
            store.save(saved)
            return saved.getString("access_token")
        } finally { conn.disconnect() }
    }
    private fun setLimit(c: HttpURLConnection) {
        blockedUntil = SystemClock.elapsedRealtime() + RetryPolicy.retryAfterMillis(c.getHeaderField("Retry-After"), System.currentTimeMillis())
    }
    fun request(method: String, path: String, body: JSONObject? = null): JSONObject? {
        val epoch = readEpoch.get()
        for (attempt in 0..1) {
            try {
                if (method == "GET" && epoch != readEpoch.get()) throw ReadCancelled()
                return requestOnce(method, path, body, epoch)
            } catch (e: IOException) {
                if (method == "GET" && (epoch != readEpoch.get() || e is ReadCancelled)) throw ReadCancelled()
                val safe = when (e) {
                    is ApiFailure -> RetryPolicy.mayRetryRead(e.code)
                    is javax.net.ssl.SSLHandshakeException, is javax.net.ssl.SSLPeerUnverifiedException -> false
                    else -> true
                }
                if (method != "GET" || attempt != 0 || !safe) throw e
                retryPause(java.util.concurrent.ThreadLocalRandom.current().nextLong(250, 501))
            }
        }
        throw ApiFailure("读取暂未完成，请重试")
    }
    private fun requestOnce(method: String, path: String, body: JSONObject?, epoch: Long): JSONObject? {
        if (!path.startsWith("/") || path.startsWith("//")) throw ApiFailure("请求地址无效")
        if (SystemClock.elapsedRealtime() < blockedUntil) throw ApiFailure("Spotify 请求过于频繁，等待后再试", 429)
        val bearer = token()
        val conn = connection(URL("https://api.spotify.com/v1$path"))
        try {
            if (method == "GET") { activeRead = conn; if (epoch != readEpoch.get()) throw ReadCancelled() }
            conn.connectTimeout = 4000; conn.readTimeout = if (method == "GET") 4000 else 6000
            conn.requestMethod = method; conn.instanceFollowRedirects = false; conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $bearer")
            if (method == "PUT" || body != null) {
                conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write((body?.toString() ?: "").toByteArray()) }
            }
            val code = conn.responseCode
            if (method == "GET" && epoch != readEpoch.get()) throw ReadCancelled()
            if (code == 429) { setLimit(conn); throw ApiFailure("Spotify 请求过于频繁，已暂停请求", code) }
            if (code == 401) { auth?.put("expires_at", 0); throw ApiFailure("授权需刷新，请再试一次；重复出现时重新配对", code) }
            if (code in 500..599) throw ApiFailure(ControlPolicy.serverFailure(code, method), code)
            if (code !in 200..299) {
                val error = try { JSONObject(conn.errorStream?.bufferedReader()?.use { it.readText().take(8192) } ?: "{}").optJSONObject("error") } catch (_: Exception) { null }
                val reason = error?.optString("reason") ?: ""
                val text = when (code) {
                    403 -> ControlPolicy.forbidden(reason)
                    404 -> "设备或内容不可用，请在手机或电脑打开 Spotify"
                    else -> "Spotify 请求失败（$code），请刷新重试"
                }
                throw ApiFailure(text, code, reason)
            }
            if (code == 204 || method != "GET") return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            if (method == "GET" && epoch != readEpoch.get()) throw ReadCancelled()
            if (text.isBlank()) throw ApiFailure("Spotify 返回空数据，请刷新重试")
            return try { JSONObject(text) } catch (_: Exception) { throw ApiFailure("Spotify 返回数据格式异常，请刷新重试") }
        } finally { if (activeRead === conn) activeRead = null; conn.disconnect() }
    }
    fun playback() = Playback.parse(request("GET", "/me/player"))
    companion object { fun enc(text: String): String = URLEncoder.encode(text, "UTF-8") }
}
