package local.watchremote
import android.util.Base64
import org.json.JSONObject
import java.net.*
import java.security.*
import java.security.spec.MGF1ParameterSpec
import javax.crypto.*
import javax.crypto.spec.*
private fun ByteArray.b64() = Base64.encodeToString(this, Base64.NO_WRAP)
private fun String.un64() = Base64.decode(this, Base64.DEFAULT)
internal class PairServer(private val receive: (JSONObject) -> Unit) {
    val code = (10000000 + SecureRandom().nextInt(90000000)).toString()
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private var server: ServerSocket? = null
    @Volatile private var alive = true
    fun close() { alive = false; try { server?.close() } catch (_: Exception) {} }
    fun start() {
        server = ServerSocket(18765, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1000 }
        Thread {
            val deadline = System.currentTimeMillis() + 300000
            var failures = 0
            while (alive && System.currentTimeMillis() < deadline && failures < 5) {
                try { server!!.accept().use { socket ->
                    socket.soTimeout = 4000
                    val reader = socket.getInputStream().bufferedReader()
                    val request = reader.readLine() ?: return@use
                    val headers = mutableMapOf<String, String>()
                    var count = 0
                    while (true) {
                        val line = reader.readLine() ?: return@use
                        if (line.isEmpty()) break
                        check(++count <= 32 && line.length <= 4096)
                        val p = line.indexOf(':'); if (p > 0) headers[line.substring(0, p).lowercase()] = line.substring(p + 1).trim()
                    }
                    fun reply(status: String, data: JSONObject) {
                        val bytes = data.toString().toByteArray()
                        socket.getOutputStream().apply { write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush() }
                    }
                    if (!MessageDigest.isEqual((headers["authorization"] ?: "").toByteArray(), "Bearer $code".toByteArray())) {
                        failures++; reply("403 Forbidden", JSONObject().put("error", "pairing code")); return@use
                    }
                    if (request == "GET /session HTTP/1.1") {
                        reply("200 OK", JSONObject().put("public_key", keys.public.encoded.b64())); return@use
                    }
                    check(request == "POST /provision HTTP/1.1")
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    check(length in 1..32768)
                    val data = CharArray(length); var read = 0
                    while (read < length) { val n = reader.read(data, read, length - read); check(n > 0); read += n }
                    val envelope = JSONObject(String(data))
                    val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding")
                    rsa.init(Cipher.DECRYPT_MODE, keys.private, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
                    val aes = Cipher.getInstance("AES/GCM/NoPadding")
                    aes.init(Cipher.DECRYPT_MODE, SecretKeySpec(rsa.doFinal(envelope.getString("key").un64()), "AES"), GCMParameterSpec(128, envelope.getString("iv").un64()))
                    val value = JSONObject(String(aes.doFinal(envelope.getString("data").un64()), Charsets.UTF_8))
                    check(value.getString("client_id").matches(Regex("[a-fA-F0-9]{32}")))
                    check(value.getString("access_token").isNotBlank() && value.getString("refresh_token").isNotBlank())
                    receive(value); reply("200 OK", JSONObject().put("ok", true)); close()
                } } catch (_: SocketTimeoutException) {} catch (_: Exception) { if (alive) failures++ }
            }
            close()
        }.apply { name = "ShortLivedPairing"; isDaemon = true; start() }
    }
}
