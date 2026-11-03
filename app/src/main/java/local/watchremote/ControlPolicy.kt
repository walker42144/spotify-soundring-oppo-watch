package local.watchremote

/** Commands acknowledge success without promising a JSON response body. */
object ControlPolicy {
    fun serverFailure(code: Int, method: String): String = if (method == "GET")
        "网络服务暂时异常（$code），稍后重新同步"
        else "操作返回 $code，结果未确认；请刷新检查，避免重复执行"
    fun needsObject(method: String, status: Int, body: String): Boolean =
        method == "GET" && status != 204 && body.isNotBlank()

    fun forbidden(reason: String): String = when (reason) {
        "PREMIUM_REQUIRED" -> "此操作需要 Premium"
        "RESTRICTION_VIOLATED" -> "播放设备暂不允许此操作，请刷新后再试"
        "NO_ACTIVE_DEVICE" -> "没有活跃设备，请先打开手机或电脑 Spotify"
        "INSUFFICIENT_SCOPE" -> "缺少控制权限，请重新配对授权"
        else -> "设备或接口拒绝此操作（403），请刷新后重试"
    }
}

/** One in-flight command, with a settling interval; rejected taps are never queued. */
class CommandGate(private val clock: () -> Long, private val interval: Long = 800) {
    private var active = false
    private var nextAllowed = 0L
    @Synchronized fun acquire(): Boolean {
        if (active || clock() < nextAllowed) return false
        active = true
        return true
    }
    @Synchronized fun release() { active = false; nextAllowed = clock() + interval }
}

object PollPolicy {
    fun delay(start: Long, now: Long, blockedUntil: Long, playing: Boolean, failures: Int, fast: Boolean): Long {
        val interval = if (failures > 0) (2000L shl (failures - 1).coerceIn(0, 4))
            else if (fast) { if (playing) 1000L else 1500L } else { if (playing) 5000L else 15000L }
        return maxOf(100L, interval - (now - start).coerceAtLeast(0), blockedUntil - now)
    }
}

object RetryPolicy {
    fun mayRetryRead(status: Int) = status in setOf(401, 408, 500, 502, 503, 504)
    fun retryAfterMillis(value: String?, now: Long): Long {
        val seconds = value?.toLongOrNull()
        if (seconds != null) return seconds.coerceIn(1, 86400) * 1000
        val date = try { java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() } catch (_: Exception) { null }
        return if (date != null) (date - now).coerceIn(1000, 86400000) else 30000
    }
}
