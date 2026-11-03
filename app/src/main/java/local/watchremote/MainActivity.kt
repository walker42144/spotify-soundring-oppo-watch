package local.watchremote

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.graphics.Color
import android.os.*
import android.view.*
import android.widget.*
import android.net.ConnectivityManager
import android.net.Network
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val images = Executors.newSingleThreadExecutor()
    private val refreshQueued = AtomicBoolean(false)
    private val gate = CommandGate({ SystemClock.elapsedRealtime() }, 350)
    private lateinit var client: SpotifyClient
    private lateinit var covers: CoverCache
    private lateinit var frame: FrameLayout
    private var home: HomePanel? = null
    private var scroll: ScrollView? = null
    private var body: LinearLayout? = null
    private var status: TextView? = null
    private var volumeLabel: TextView? = null
    private var seekLabel: TextView? = null
    private var volumeBar: SeekBar? = null
    private var seekBar: SeekBar? = null
    private var shuffleButton: Button? = null
    private var repeatButton: Button? = null
    private var crownMode = "scroll"
    private data class Route(val name: String, val offset: Int = 0, val item: JSONObject? = null, val y: Int = 0)
    private var route = Route("home")
    private val history = java.util.ArrayDeque<Route>()
    private var restoreY = 0
    private var dragging = false
    private var dragDevice: String? = null
    private var dragTrack = ""
    private fun open(target: Route, remember: Boolean = true) {
        if (remember && target.name != route.name) history.addLast(route.copy(y = scroll?.scrollY ?: 0))
        if (target.name == "home") history.clear()
        route = target; restoreY = target.y
        when (target.name) {
            "playlist" -> renderPlaylist(target.item!!, target.offset)
            "playlists" -> { resetPage("playlists"); pageShell("我的歌单"); playlists(target.offset) }
            else -> renderPage(target.name)
        }
        scroll?.post { scroll?.scrollTo(0, restoreY) }
    }
    private fun back() { if (history.isNotEmpty()) open(history.removeLast(), false) else navigate("home") }
    private fun reloadPage() { open(route.copy(y = scroll?.scrollY ?: 0), false) }
    private var fallbackTitle: TextView? = null
    private var fallbackArtist: TextView? = null
    private var fallbackPlay: Button? = null
    private var pair: PairServer? = null
    private lateinit var connectivity: ConnectivityManager
    private var networkRegistered = false
    @Volatile private var online = true
    private var pageRetry: (() -> Unit)? = null
    private var pageReadFailed = false
    @Volatile private var state = Playback()
    @Volatile private var foreground = false
    @Volatile private var authorized = false
    @Volatile private var busy = false
    @Volatile private var page = "home"
    @Volatile private var generation = 0
    private var lastSync = 0L
    private var lastPollStart = 0L
    private var pollFailures = 0
    @Volatile private var requestMs = 0L
    @Volatile private var pollPeriodMs = 0L
    @Volatile private var requestCount = 0L
    private var lastError: String? = null
    private var lastErrorToast = 0L
    private var coverUrl = ""
    private var lastCoverAttempt = ""
    private var coverRetryAt = 0L
    private var coverFailures = 0
    private var pending: Runnable? = null
    private val settings by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val tick = object : Runnable {
        override fun run() { if (foreground && page in listOf("home", "adjust", "track")) refresh() }
    }
    private val animation = object : Runnable {
        override fun run() {
            if (!foreground) return
            home?.progress(state)
            updateFreshness()
            if (page == "adjust" && !dragging && !busy && pending == null) {
                seekBar?.progress = state.positionNow() / 1000
                seekLabel?.text = "${time(state.positionNow())} / ${time(state.duration)}"
            }
            ui.postDelayed(this, 1000)
        }
    }
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (intent?.action == Intent.ACTION_SCREEN_OFF) stopVisibleWork() }
    }
    private val networkEvents = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { ui.post {
            online = true; pollFailures = 0; updateHome()
            if (foreground) { if (pageReadFailed) pageRetry?.invoke() else refresh() }
        } }
        override fun onLost(network: Network) {
            client.cancelRead()
            ui.post { online = connectivity.activeNetwork != null; updateHome(); if (foreground) schedule() }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = SpotifyClient(AuthStore(this)); covers = CoverCache(this)
        connectivity = getSystemService(ConnectivityManager::class.java)
        online = connectivity.activeNetwork != null
        frame = FrameLayout(this).apply { setBackgroundColor(INK) }
        window.decorView.setBackgroundColor(INK); setContentView(frame)
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        connectivity.registerDefaultNetworkCallback(networkEvents); networkRegistered = true
        navigate("home")
        worker.execute {
            try { client.load(); authorized = client.authorized() }
            catch (_: Exception) { showError("无法读取授权，请在设置中重新配对") }
            ui.post { updateHome(); if (authorized) refresh() else navigate("settings") }
        }
    }
    private fun time(milliseconds: Int): String = "%d:%02d".format(milliseconds.coerceAtLeast(0) / 60000, milliseconds.coerceAtLeast(0) / 1000 % 60)
    private fun feedback() { if (settings.getBoolean("haptic", true)) frame.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
    private fun showError(text: String) { ui.post {
        val repeat = lastError == text && SystemClock.elapsedRealtime() - lastErrorToast < 10000
        lastError = text
        status?.text = text
        if (!repeat && status == null) {
            lastErrorToast = SystemClock.elapsedRealtime()
            val code = Regex("[（(]([0-9]{3})[）)]").find(text)?.groupValues?.get(1)
            val brief = if (code != null && code.startsWith("5")) "网络服务暂时异常（$code）" else text.take(24)
            Toast.makeText(this, brief, Toast.LENGTH_SHORT).show()
        }
        updateHome()
    } }
    private fun failure(e: Exception) {
        if (e is ReadCancelled) return
        val text = when (e) {
            is ApiFailure -> e.userText
            is java.net.SocketTimeoutException -> "网络响应超时，操作结果未确认；请刷新，勿重复提交"
            is org.json.JSONException -> "返回数据格式异常，请刷新重试"
            is java.io.IOException -> "网络连接失败，请检查手表网络后刷新"
            else -> "操作未完成，请刷新后重试"
        }
        showError(text)
        // Safe diagnostics contain no response body, account ID or token.
        try { java.io.File(filesDir, "last-error.json").writeText(JSONObject().put("type", e.javaClass.simpleName)
            .put("http", (e as? ApiFailure)?.code ?: 0).put("reason", (e as? ApiFailure)?.reason?.takeIf { it.matches(Regex("[A-Z_]{1,64}")) } ?: "")
            .put("at", System.currentTimeMillis()).toString()) } catch (_: Exception) {}
    }
    private fun schedule() { ui.post {
        ui.removeCallbacks(tick)
        if (foreground && authorized && page in listOf("home", "adjust", "track")) {
            val delay = PollPolicy.delay(lastPollStart, SystemClock.elapsedRealtime(), client.blockedUntil, state.playing, pollFailures, settings.getBoolean("fastSync", true))
            ui.postDelayed(tick, delay)
        }
    } }
    private fun refresh() {
        if (!foreground || !authorized || busy || !refreshQueued.compareAndSet(false, true)) return
        worker.execute {
            try {
                if (foreground) {
                    if (!online) throw ApiFailure("手表网络已断开，联网后会自动恢复同步", -1)
                    sync(); pollFailures = 0
                }
            } catch (e: Exception) { if (e !is ReadCancelled) { pollFailures++; failure(e) } }
            finally { refreshQueued.set(false); schedule() }
        }
    }
    private fun sync() {
        val previousStart = lastPollStart
        lastPollStart = SystemClock.elapsedRealtime()
        requestCount++
        val fresh = client.playback()
        requestMs = SystemClock.elapsedRealtime() - lastPollStart
        pollPeriodMs = if (previousStart == 0L) 0 else lastPollStart - previousStart
        state = fresh; lastSync = SystemClock.elapsedRealtime()
        ui.post {
            if (!busy && lastError != null) { lastError = null; status?.text = "" }
            updateHome(); if (page == "adjust" && pending == null && !busy) updateSliders()
        }
        if (BuildConfig.DEBUG) try { java.io.File(filesDir, "health.json").writeText(JSONObject().put("authorized", client.authorized())
            .put("activeDevice", fresh.deviceId != null).put("playing", fresh.playing).put("at", System.currentTimeMillis())
            .put("requestMs", requestMs).put("pollPeriodMs", pollPeriodMs).toString()) } catch (_: Exception) {}
    }
    private fun mutate(check: ((Playback) -> Boolean)? = null, onSuccess: (() -> Unit)? = null, action: () -> Unit): Boolean {
        if (!foreground || !authorized) { showError("请先完成配对"); return false }
        if (!online) { showError("手表网络已断开，联网后再操作"); return false }
        if (busy || !gate.acquire()) return false
        val commandPage = generation
        client.cancelRead()
        feedback(); busy = true; ui.removeCallbacks(tick); updateHome()
        status?.text = "正在执行…"
        worker.execute {
            try {
                if (foreground) {
                    action()
                    if (foreground) {
                        sync()
                        if (check != null && !check(state) && foreground) { Thread.sleep(250); sync() }
                        ui.post {
                            lastError = null
                            status?.text = if (check != null && !check(state)) "已提交，设备状态尚未确认，请刷新" else "已同步"
                            if (foreground && commandPage == generation && (check == null || check(state))) onSuccess?.invoke()
                        }
                    }
                }
            } catch (e: Exception) { failure(e) }
            finally {
                gate.release()
                ui.postDelayed({ busy = false; updateHome(); if (page == "adjust" && pending == null) updateSliders(); schedule() }, 350)
            }
        }
        return true
    }
    private fun deviceId(): String {
        if (state.restricted) throw ApiFailure("此设备不允许远程控制，请选择其他播放设备")
        return state.deviceId ?: throw ApiFailure("没有活跃设备，请先在手机或电脑打开 Spotify")
    }
    private fun playerPath(name: String, id: String = deviceId()) = "/me/player/$name?device_id=${SpotifyClient.enc(id)}"
    private fun control(name: String) {
        val desired = !state.playing
        when (name) {
            "toggle" -> mutate({ it.playing == desired }) { client.request("PUT", playerPath(if (desired) "play" else "pause")) }
            "previous", "next" -> mutate { client.request("POST", playerPath(name)) }
        }
    }
    private fun updateHome() {
        home?.update(state, busy || !online, authorized)
        if (authorized && lastSync == 0L) {
            home?.title?.text = if (!online) "手表未联网" else if (lastError != null) "暂未取得播放状态" else "正在同步…"
            home?.artist?.text = if (!online) "联网后会自动重试" else "正在连接播放设备"
        }
        updateFreshness()
        fallbackTitle?.text = state.title
        fallbackArtist?.text = state.artist
        fallbackPlay?.apply { text = if (busy) "正在执行…" else if (state.playing) "暂停" else "播放"; isEnabled = state.deviceId != null && !busy }
        val image = state.cover
        if (home == null || !foreground) return
        if (image != lastCoverAttempt) {
            coverRetryAt = 0; coverFailures = 0
            val cached = covers.cached(image)
            home?.artwork(cached)
            if (cached != null) { lastCoverAttempt = image; coverUrl = image; return }
        }
        if (coverUrl != image && SystemClock.elapsedRealtime() >= coverRetryAt) {
            lastCoverAttempt = image
            coverUrl = image
            if (image.isBlank()) home?.artwork(null)
            if (image.isNotBlank()) images.execute {
                if (state.cover != image) return@execute
                val bitmap = try { covers.get(image) } catch (_: Exception) { null }
                ui.post { if (coverUrl == image && authorized) {
                    if (bitmap != null) {
                        coverFailures = 0
                        home?.artwork(bitmap)
                    } else {
                        coverUrl = ""
                        coverRetryAt = SystemClock.elapsedRealtime() + (2000L shl coverFailures.coerceIn(0, 4))
                        coverFailures++
                    }
                } }
            }
        }
    }
    private fun updateFreshness() {
        val age = if (lastSync == 0L) -1 else (SystemClock.elapsedRealtime() - lastSync) / 1000
        home?.device?.apply {
            text = if (age < 0) "选择播放设备" else if (!online) "离线 · ${age}秒前同步" else "${state.deviceName} · ${age}秒前"
            contentDescription = "播放设备 ${state.deviceName}，上次同步 ${age.coerceAtLeast(0)} 秒前"
            setTextColor(MUTED)
        }
        if (!online || age > 20) home?.visible(false)
        else if (foreground) home?.visible(true)
    }
    private fun resetPage(name: String) {
        home?.visible(false)
        lastError = null
        pageRetry = null; pageReadFailed = false
        generation++; page = name; pending?.let { ui.removeCallbacks(it) }; pending = null
        ui.removeCallbacks(tick); frame.removeAllViews(); home = null; scroll = null; body = null; status = null
        volumeBar = null; seekBar = null; volumeLabel = null; seekLabel = null
        shuffleButton = null; repeatButton = null
        fallbackTitle = null; fallbackArtist = null; fallbackPlay = null
        dragging = false
        crownMode = settings.getString("crownMode", "scroll") ?: "scroll"
    }
    private fun navigate(name: String) { open(Route(name)) }
    private fun renderPage(name: String) {
        resetPage(name)
        if (name == "home" && resources.configuration.fontScale <= 1.15f) {
            home = HomePanel(this, ::navigate, ::control)
            frame.addView(home, FrameLayout.LayoutParams(-1, -1))
            enter(home!!)
            coverUrl = ""; updateHome(); refresh(); return
        }
        pageShell(when(name) { "home" -> "正在播放"; "playlists" -> "我的歌单"; "adjust" -> "播放调节"; "devices" -> "播放设备"; "track" -> "歌曲详情"; else -> "更多" })
        when (name) {
            "home" -> accessibleHome()
            "playlists" -> playlists(0)
            "devices" -> devices()
            "adjust" -> adjust()
            "track" -> { label(state.title, 18f); label(state.artist); label("${time(state.positionNow())} / ${time(state.duration)}"); row("播放设备", state.deviceName) { navigate("devices") }; refresh() }
            else -> settingsPage()
        }
        schedule()
    }
    private fun pageShell(title: String) {
        val linear = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(40))
        }
        val scroller = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(linear) }
        scroll = scroller; body = linear; frame.addView(scroller)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(IconButton(this, "back", "返回上一页") { back() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(watchText(this, title, 17f).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            maxLines = 2
        }, LinearLayout.LayoutParams(0, -2, 1f))
        linear.addView(header, LinearLayout.LayoutParams(-1, -2))
        status = label(lastError ?: "", 11f).apply { setTextColor(MUTED) }
        status?.apply {
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        enter(scroller)
    }
    private fun enter(view: View) {
        if (motionEnabled()) {
            view.alpha = 0f; view.translationY = dp(6).toFloat()
            view.animate().alpha(1f).translationY(0f).setDuration(190).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }
    private fun label(text: String, size: Float = 14f): TextView = watchText(this, text, size).apply {
        setPadding(dp(2), dp(5), dp(2), dp(5)); body!!.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun row(title: String, subtitle: String = "", enabled: Boolean = true, action: () -> Unit): Button {
        val text = if (subtitle.isBlank()) title else "$title\n$subtitle"
        return watchButton(this, text, action).apply {
            isEnabled = enabled; alpha = if (enabled) 1f else .5f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minHeight = dp(54); maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
            if (subtitle.isNotBlank()) {
                val styled = android.text.SpannableString(text)
                styled.setSpan(android.text.style.RelativeSizeSpan(.76f), title.length + 1, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                styled.setSpan(android.text.style.ForegroundColorSpan(MUTED), title.length + 1, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setText(styled)
            }
            body!!.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        }
    }
    private fun accessibleHome() {
        fallbackTitle = label(state.title, 18f); fallbackArtist = label(state.artist)
        fallbackPlay = row(if (state.playing) "暂停" else "播放", enabled = state.deviceId != null) { control("toggle") }
        row("上一首") { control("previous") }; row("下一首") { control("next") }
        row("歌单") { navigate("playlists") }; row("调节") { navigate("adjust") }; row("设备") { navigate("devices") }; row("更多") { navigate("settings") }
    }
    private fun readPage(load: () -> JSONObject?, render: (JSONObject?) -> Unit) {
        val expected = generation
        val base = body?.childCount ?: 0
        pageReadFailed = false
        pageRetry = {
            if (expected == generation) {
                while ((body?.childCount ?: 0) > base) body?.removeViewAt(base)
                readPage(load, render)
            }
        }
        status?.text = "正在读取…"
        worker.execute {
            if (expected != generation) return@execute
            try {
                if (!online) throw ApiFailure("手表网络已断开，联网后可重试", -1)
                val data = load()
                ui.post { if (expected == generation) { pageReadFailed = false; status?.text = ""; render(data); scroll?.post { scroll?.scrollTo(0, restoreY) } } }
            } catch (e: Exception) {
                if (expected == generation) {
                    failure(e)
                    ui.post { if (expected == generation) {
                        pageReadFailed = true
                        if (e is ReadCancelled) status?.text = "网络切换或操作已中断读取，可重试"
                        row("重试读取") { pageRetry?.invoke() }
                    } }
                }
            }
        }
    }
    private fun playlists(offset: Int) {
        readPage({ client.request("GET", "/me/playlists?limit=20&offset=$offset") }) { value ->
            val list = value?.optJSONArray("items") ?: JSONArray()
            if (list.length() == 0) label("这里还没有歌单")
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val id = item.optString("id"); if (id.isBlank()) continue
                row(item.optString("name", "未命名歌单"), item.optJSONObject("owner")?.optString("display_name") ?: "") { playlist(item, 0) }
            }
            if (offset > 0) row("上一页") { listPage(offset - 20) }
            if (!value?.optString("next").isNullOrBlank() && value?.optString("next") != "null") row("下一页") { listPage(offset + 20) }
            row("刷新歌单") { listPage(offset) }
        }
    }
    private fun listPage(offset: Int) { open(Route("playlists", offset.coerceAtLeast(0)), false) }
    private fun playlist(item: JSONObject, offset: Int) { open(Route("playlist", offset, item)) }
    private fun renderPlaylist(item: JSONObject, offset: Int) {
        resetPage("playlist"); pageShell(item.optString("name", "歌单"))
        row("全部播放") { mutate(onSuccess = { navigate("home") }) { client.request("PUT", playerPath("play"), JSONObject().put("context_uri", item.getString("uri"))) } }.apply {
            background = shape(GREEN, 100f); setTextColor(INK); gravity = Gravity.CENTER
        }
        val id = item.getString("id")
        readPage({
            try { client.request("GET", "/playlists/${SpotifyClient.enc(id)}/items?limit=20&offset=$offset") }
            catch (e: ApiFailure) {
                if (e.code == 403 && e.reason != "INSUFFICIENT_SCOPE") throw ApiFailure("此歌单暂不可读取。请优先选择自己创建或协作的歌单。", 403)
                throw e
            }
        }) { value ->
            val items = value?.optJSONArray("items") ?: JSONArray()
            if (items.length() == 0) label("歌单内容为空或不可读取")
            for (index in 0 until items.length()) {
                val entry = items.optJSONObject(index) ?: continue
                val track = entry.optJSONObject("item") ?: entry.optJSONObject("track")
                val uri = track?.optString("uri") ?: ""
                val allowed = !entry.optBoolean("is_local") && uri.matches(Regex("spotify:(track|episode):[A-Za-z0-9]+")) && track?.optBoolean("is_playable", true) != false
                row(track?.optString("name") ?: "不可用的歌曲", if (allowed) track?.optJSONArray("artists")?.let { artists -> (0 until artists.length()).mapNotNull { artists.optJSONObject(it)?.optString("name") }.joinToString(" · ") }?.takeIf { it.isNotBlank() } ?: "点按播放" else "暂不可播放", allowed) {
                    mutate({ it.uri == uri }, onSuccess = { navigate("home") }) { client.request("PUT", playerPath("play"), JSONObject().put("uris", JSONArray().put(uri))) }
                }
            }
            if (offset > 0) row("上一页") { playlist(item, offset - 20) }
            if (!value?.optString("next").isNullOrBlank() && value?.optString("next") != "null") row("下一页") { playlist(item, offset + 20) }
            row("返回歌单") { back() }
        }
    }
    private fun devices() {
        readPage({ client.request("GET", "/me/player/devices") }) { value ->
            val list = value?.optJSONArray("devices") ?: JSONArray()
            if (list.length() == 0) label("请先打开手机或电脑上的 Spotify，再刷新")
            for (i in 0 until list.length()) {
                val d = list.optJSONObject(i) ?: continue
                val id = d.optString("id"); val name = d.optString("name", "播放设备")
                val enabled = id.isNotBlank() && id != "null" && !d.optBoolean("is_restricted") && !d.optBoolean("is_active")
                row(name, if (d.optBoolean("is_active")) "当前设备" else if (!enabled) "不允许远程控制" else "切换到这里", enabled) {
                    AlertDialog.Builder(this).setMessage("将播放切换到 $name？")
                        .setPositiveButton("切换") { _, _ -> mutate({ it.deviceId == id }, onSuccess = { back() }) {
                            client.request("PUT", "/me/player", JSONObject().put("device_ids", JSONArray().put(id)).put("play", false))
                        } }.setNegativeButton("取消", null).show()
                }
            }
            row("刷新设备") { reloadPage() }
        }
    }
    private fun adjust() {
        row("表冠调节", "点按选择音量、进度或滚动") {
            AlertDialog.Builder(this).setItems(arrayOf("滚动页面", "调节音量", "调节进度")) { _, which ->
                crownMode = arrayOf("scroll", "volume", "seek")[which]
                settings.edit().putBoolean("crown", true).putString("crownMode", crownMode).apply()
                status?.text = "表冠：" + arrayOf("滚动", "音量", "进度")[which]
            }.show()
        }
        label("音量")
        volumeLabel = label("")
        volumeBar = SeekBar(this).apply {
            max = 100; minHeight = dp(48); progressTintList = android.content.res.ColorStateList.valueOf(GREEN)
            contentDescription = "播放设备音量"
            setOnSeekBarChangeListener(slider("volume") { value -> volumeLabel?.text = "$value%" })
            body!!.addView(this, LinearLayout.LayoutParams(-1, dp(48)))
        }
        label("播放进度")
        seekLabel = label("")
        seekBar = SeekBar(this).apply {
            minHeight = dp(48); progressTintList = android.content.res.ColorStateList.valueOf(GREEN)
            contentDescription = "歌曲播放位置"
            setOnSeekBarChangeListener(slider("seek") { value -> seekLabel?.text = "${time(value * 1000)} / ${time(state.duration)}" })
            body!!.addView(this, LinearLayout.LayoutParams(-1, dp(48)))
        }
        shuffleButton = row(if (state.shuffle) "随机播放：开" else "随机播放：关", enabled = state.allows("toggling_shuffle")) {
            val desired = !state.shuffle
            mutate({ it.shuffle == desired }) { client.request("PUT", playerPath("shuffle") + "&state=$desired") }
        }
        repeatButton = row("循环：" + when(state.repeat) { "track" -> "单曲"; "context" -> "列表"; else -> "关闭" }, enabled = state.deviceId != null) {
            val next = when(state.repeat) { "off" -> "context"; "context" -> "track"; else -> "off" }
            mutate({ it.repeat == next }) { client.request("PUT", playerPath("repeat") + "&state=$next") }
        }
        row("刷新状态") { refresh() }
        updateSliders(); refresh()
    }
    private fun slider(kind: String, change: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(bar: SeekBar?) {
            dragging = true; dragDevice = state.deviceId; dragTrack = state.uri
            pending?.let { ui.removeCallbacks(it) }; pending = null
        }
        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) { if (fromUser) change(progress) }
        override fun onStopTrackingTouch(bar: SeekBar?) {
            dragging = false
            if (dragDevice != state.deviceId || (kind == "seek" && dragTrack != state.uri)) {
                showError("播放目标已改变，请重新调节"); updateSliders()
            } else if (bar != null) deferControl(kind, bar.progress)
        }
    }
    private fun updateSliders() {
        if (dragging || pending != null) return
        shuffleButton?.apply { text = if (state.shuffle) "随机播放：开" else "随机播放：关"; isEnabled = !busy && state.allows("toggling_shuffle"); alpha = if (isEnabled) 1f else .5f }
        repeatButton?.apply { text = "循环：" + when(state.repeat) { "track" -> "单曲"; "context" -> "列表"; else -> "关闭" }; isEnabled = !busy && state.deviceId != null && !state.restricted; alpha = if (isEnabled) 1f else .5f }
        volumeBar?.apply { isEnabled = !busy && state.volumeSupported && state.volume != null && !state.restricted; progress = state.volume ?: 0 }
        volumeLabel?.text = if (state.volumeSupported && state.volume != null) "${state.volume}%" else "此设备未开放远程音量"
        seekBar?.apply { max = (state.duration / 1000).coerceAtLeast(1); isEnabled = !busy && state.duration > 0 && state.allows("seeking"); progress = state.positionNow() / 1000 }
        seekLabel?.text = "${time(state.positionNow())} / ${time(state.duration)}"
    }
    private fun deferControl(kind: String, value: Int) {
        pending?.let { ui.removeCallbacks(it) }
        val id = state.deviceId; val uri = state.uri; val deadline = SystemClock.elapsedRealtime() + 10000
        val run = object : Runnable {
            override fun run() {
                if (!foreground || page != "adjust" || id != state.deviceId || (kind == "seek" && uri != state.uri)) { pending = null; return }
                val accepted = mutate {
                    if (id != deviceId() || (kind == "seek" && uri != state.uri)) throw ApiFailure("播放目标已改变，请重新调节")
                    val suffix = if (kind == "volume") "volume_percent=${value.coerceIn(0, 100)}" else "position_ms=${(value.toLong() * 1000).coerceIn(0, state.duration.toLong())}"
                    client.request("PUT", playerPath(kind) + "&$suffix")
                }
                if (accepted) pending = null
                else if (SystemClock.elapsedRealtime() < deadline) ui.postDelayed(this, 250)
                else { pending = null; showError("调节尚未提交，请重试"); updateSliders() }
            }
        }
        pending = run; ui.postDelayed(run, 450)
    }
    private fun settingsPage() {
        label(if (authorized) "Spotify 已连接" else "尚未连接 Spotify")
        row("播放设备", state.deviceName) { navigate("devices") }
        row("歌曲详情") { navigate("track") }
        row("同步状态", if (lastSync == 0L) "尚未同步" else "${(SystemClock.elapsedRealtime() - lastSync) / 1000} 秒前") { refresh() }
        row("表冠操作", if (settings.getBoolean("crown", false)) "已启用" else "点此启用滚动") {
            settings.edit().putBoolean("crown", !settings.getBoolean("crown", false)).apply(); reloadPage()
        }
        row("触觉反馈", if (settings.getBoolean("haptic", true)) "开" else "关") {
            settings.edit().putBoolean("haptic", !settings.getBoolean("haptic", true)).apply(); reloadPage()
        }
        row("动态封面背景", if (settings.getBoolean("dynamicBackground", true)) "开 · 播放时缓慢流动" else "关") {
            settings.edit().putBoolean("dynamicBackground", !settings.getBoolean("dynamicBackground", true)).apply(); reloadPage()
        }
        row("界面动画", if (settings.getBoolean("animations", true)) "开" else "关") {
            settings.edit().putBoolean("animations", !settings.getBoolean("animations", true)).apply(); reloadPage()
        }
        row("同步速度", if (settings.getBoolean("fastSync", true)) "快速 · 播放时约每秒同步" else "省电 · 播放时约每 5 秒同步") {
            settings.edit().putBoolean("fastSync", !settings.getBoolean("fastSync", true)).apply(); reloadPage()
        }
        row("检测手表网络") { networkProbe() }
        row("电脑配对", "有效期 5 分钟") { startPairing() }
        row("退出登录") {
            AlertDialog.Builder(this).setMessage("清除手表上的授权和封面缓存？")
                .setPositiveButton("清除") { _, _ ->
                    pair?.close(); pair = null; pending?.let { ui.removeCallbacks(it) }; pending = null
                    worker.execute {
                        client.logout(); authorized = false; state = Playback()
                        images.execute { covers.clear() }
                        ui.post { lastError = null; navigate("settings") }
                    }
                }.setNegativeButton("取消", null).show()
        }
        label("声环 ${BuildConfig.VERSION_NAME}\n声音由手机或电脑播放", 11f).setTextColor(MUTED)
    }
    private fun networkProbe() {
        val expected = generation; status?.text = "正在检测…"
        worker.execute {
            try {
                val lines = listOf("https://api.spotify.com/v1/me/player", "https://accounts.spotify.com/authorize").map {
                    val c = URL(it).openConnection() as HttpURLConnection
                    try { c.instanceFollowRedirects = false; c.connectTimeout = 8000; c.readTimeout = 8000; "${URL(it).host}: ${c.responseCode}" } finally { c.disconnect() }
                }
                ui.post { if (expected == generation) status?.text = lines.joinToString("\n") + "\n401 表示服务可达" }
            } catch (e: Exception) { failure(e) }
        }
    }
    private fun startPairing() {
        pair?.close()
        try {
            pair = PairServer { value ->
                worker.submit { client.provision(value); authorized = true }.get()
                ui.post { lastError = null; Toast.makeText(this, "配对成功", Toast.LENGTH_SHORT).show(); navigate("home") }
            }.also {
                it.start(); status?.text = "配对码 ${it.code}\n在电脑工具中输入，5 分钟有效"
            }
        } catch (_: Exception) { showError("配对暂不可用，请重新打开应用重试") }
    }
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL && settings.getBoolean("crown", false)) {
            val delta = event.getAxisValue(MotionEvent.AXIS_SCROLL).takeIf { it != 0f } ?: event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (delta != 0f) {
                if (page == "adjust" && crownMode != "scroll") {
                    val bar = if (crownMode == "volume") volumeBar else seekBar
                    if (bar?.isEnabled == true) {
                        val increment = (if (delta > 0) 1 else -1) * (if (crownMode == "volume") 2 else 5)
                        bar.progress = (bar.progress + increment).coerceIn(0, bar.max)
                        if (crownMode == "volume") volumeLabel?.text = "${bar.progress}%"
                        else seekLabel?.text = "${time(bar.progress * 1000)} / ${time(state.duration)}"
                        deferControl(crownMode, bar.progress)
                    }
                    return true
                }
                scroll?.smoothScrollBy(0, (-delta * dp(24)).toInt())
                return scroll != null || super.onGenericMotionEvent(event)
            }
        }
        return super.onGenericMotionEvent(event)
    }
    private fun stopVisibleWork() {
        if (::client.isInitialized) client.cancelRead()
        home?.visible(false)
        foreground = false; ui.removeCallbacks(tick); ui.removeCallbacks(animation)
        pending?.let { ui.removeCallbacks(it) }; pending = null
    }
    override fun onResume() {
        super.onResume(); foreground = true; ui.removeCallbacks(animation); ui.post(animation)
        home?.visible(true)
        updateHome()
        if (::client.isInitialized) { if (pageReadFailed) pageRetry?.invoke() else refresh() }
    }
    override fun onPause() { stopVisibleWork(); super.onPause() }
    override fun onBackPressed() { if (page != "home") back() else super.onBackPressed() }
    override fun dump(prefix: String, fd: java.io.FileDescriptor?, writer: java.io.PrintWriter, args: Array<out String>?) {
        super.dump(prefix, fd, writer, args)
        writer.println("${prefix}WatchRemote version=${BuildConfig.VERSION_NAME} authorized=$authorized active=${state.deviceId != null} playing=${state.playing} foreground=$foreground online=$online page=$page")
        writer.println("${prefix}WatchRemote requests=$requestCount requestMs=$requestMs pollPeriodMs=$pollPeriodMs syncAgeMs=${if (lastSync == 0L) -1 else SystemClock.elapsedRealtime() - lastSync}")
        try {
            val error = JSONObject(java.io.File(filesDir, "last-error.json").readText())
            writer.println("${prefix}WatchRemote lastHttp=${error.optInt("http")} errorAt=${error.optLong("at")}")
        } catch (_: Exception) {}
    }
    override fun onDestroy() {
        stopVisibleWork(); pair?.close(); unregisterReceiver(screenOff)
        if (networkRegistered) connectivity.unregisterNetworkCallback(networkEvents)
        worker.shutdownNow(); images.shutdownNow(); ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
