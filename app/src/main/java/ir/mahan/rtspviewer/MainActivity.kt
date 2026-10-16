package ir.mahan.rtspviewer

import android.content.Context
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch

private val Copper = Color(0xFFB87333)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Copper,
                    secondary = Color(0xFFD9A06B),
                    background = Color(0xFF121214),
                    surface = Color(0xFF1C1C20),
                )
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        App()
                    }
                }
            }
        }
    }
}

/* ---------------- تنظیمات ذخیره‌شده ---------------- */

private class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    var url: String
        get() = sp.getString("url", "") ?: ""
        set(v) { sp.edit().putString("url", v).apply() }
    var user: String
        get() = sp.getString("user", "") ?: ""
        set(v) { sp.edit().putString("user", v).apply() }
    var pass: String
        get() = sp.getString("pass", "") ?: ""
        set(v) { sp.edit().putString("pass", v).apply() }
    var tcp: Boolean
        get() = sp.getBoolean("tcp", true)
        set(v) { sp.edit().putBoolean("tcp", v).apply() }
}

/** ساخت لینک نهایی با نام کاربری/رمز (کدگذاری‌شده برای کاراکترهای خاص مثل @ و :) */
fun buildRtspUrl(raw: String, user: String, pass: String): String {
    var u = raw.trim()
    if (!u.startsWith("rtsp://", true) && !u.startsWith("rtsps://", true)) u = "rtsp://$u"
    if (user.isBlank() || Uri.parse(u).userInfo != null) return u
    val i = u.indexOf("://") + 3
    return u.substring(0, i) + Uri.encode(user) + ":" + Uri.encode(pass) + "@" + u.substring(i)
}

fun maskUrl(u: String) = u.replace(Regex("(://[^:/@]+:)[^@]*@"), "$1••••@")

/** جایگزینی مسیر لینک با مسیر پیش‌فرض یک برند */
fun applyPath(url: String, path: String): String {
    val base = Regex("^(rtsps?://[^/?#]+)", RegexOption.IGNORE_CASE).find(url.trim())?.value
        ?: return url
    return base + path
}

private val PathPresets = listOf(
    "هایک‌ویژن – اصلی" to "/Streaming/Channels/101",
    "هایک‌ویژن – فرعی" to "/Streaming/Channels/102",
    "داهوا / ایمو" to "/cam/realmonitor?channel=1&subtype=0",
    "عمومی stream1" to "/stream1",
    "عمومی live" to "/live",
    "H.264" to "/h264",
)

/* ---------------- ریشهٔ برنامه ---------------- */

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var playing by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    val p = playing
    if (p == null) {
        SetupScreen(prefs) { url, tcp -> playing = url to tcp }
    } else {
        ViewerScreen(p.first, p.second) { playing = null }
    }
}

/* ---------------- صفحهٔ ورود اطلاعات و جستجو ---------------- */

@Composable
private fun SetupScreen(prefs: Prefs, onPlay: (String, Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(prefs.url) }
    var user by remember { mutableStateOf(prefs.user) }
    var pass by remember { mutableStateOf(prefs.pass) }
    var tcp by remember { mutableStateOf(prefs.tcp) }
    var showPass by remember { mutableStateOf(false) }

    val found = remember { mutableStateListOf<FoundDevice>() }
    var scanning by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var scanMsg by remember { mutableStateOf<String?>(null) }

    val ltr = TextStyle(textDirection = TextDirection.Ltr)

    fun merge(d: FoundDevice) {
        val i = found.indexOfFirst { it.ip == d.ip }
        if (i < 0) found.add(d) else {
            val o = found[i]
            found[i] = o.copy(
                port = o.port ?: d.port,
                rtspConfirmed = o.rtspConfirmed || d.rtspConfirmed,
                onvifName = o.onvifName ?: d.onvifName,
            )
        }
    }

    fun startScan() {
        val ln = NetScanner.localNet(ctx)
        if (ln == null) { scanMsg = "گوشی به هیچ شبکه‌ای متصل نیست. Wi‑Fi را روشن کنید."; return }
        found.clear(); scanning = true; progress = 0f
        scanMsg = "IP گوشی: ${ln.ip} — در حال جستجوی ${ln.ip.substringBeforeLast('.')}.1 تا .254" +
                if (!ln.isWifi) "\n⚠ اتصال فعلی Wi‑Fi نیست؛ ممکن است دوربین پیدا نشود." else ""
        scope.launch {
            launch { NetScanner.onvifDiscover(ctx).forEach { merge(it) } }
            try {
                NetScanner.scanRtsp(ctx, onFound = { merge(it) }, onProgress = { progress = it })
            } catch (e: Exception) {
                scanMsg = "خطا در جستجو: ${e.message}"
            }
            scanning = false
            if (found.isEmpty()) scanMsg = (scanMsg ?: "") + "\nدوربینی پیدا نشد. مطمئن شوید گوشی و دوربین روی یک روتر هستند."
        }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("نمایشگر دوربین مداربسته", fontSize = 22.sp, color = Copper)

        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text("لینک RTSP") },
            placeholder = { Text("rtsp://192.168.1.64:554/Streaming/Channels/101") },
            singleLine = true, textStyle = ltr,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PathPresets.forEach { (name, path) ->
                AssistChip(onClick = {
                    url = if (url.isBlank()) "" else applyPath(buildRtspUrl(url, "", ""), path)
                }, label = { Text(name, fontSize = 12.sp) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = user, onValueChange = { user = it }, label = { Text("نام کاربری") },
                singleLine = true, textStyle = ltr, modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = pass, onValueChange = { pass = it }, label = { Text("رمز عبور") },
                singleLine = true, textStyle = ltr, modifier = Modifier.weight(1f),
                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { showPass = !showPass }) { Text(if (showPass) "پنهان" else "نمایش", fontSize = 11.sp) }
                }
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = tcp, onCheckedChange = { tcp = it })
            Text("انتقال از طریق TCP (پایدارتر؛ اگر تصویر نیامد خاموش کنید)", fontSize = 13.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (url.isBlank()) return@Button
                    prefs.url = url; prefs.user = user; prefs.pass = pass; prefs.tcp = tcp
                    onPlay(buildRtspUrl(url, user, pass), tcp)
                },
                enabled = url.isNotBlank(), modifier = Modifier.weight(1f)
            ) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("نمایش تصویر") }
            OutlinedButton(onClick = { startScan() }, enabled = !scanning, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Search, null); Spacer(Modifier.width(6.dp)); Text("جستجو در شبکه")
            }
        }

        if (scanning) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = Copper)
        scanMsg?.let { Text(it, fontSize = 12.sp, color = Color(0xFFAAAAAA)) }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(found, key = { it.ip }) { d ->
                Card(
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().clickable {
                        val port = d.port ?: 554
                        val old = Regex("^rtsps?://[^/?#]+", RegexOption.IGNORE_CASE).find(url.trim())
                        val path = if (old != null) url.trim().substring(old.range.last + 1) else ""
                        url = "rtsp://${d.ip}:$port" + path.ifBlank { "/" }
                    }
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${d.ip}${d.port?.let { ":$it" } ?: ""}", style = ltr, fontSize = 16.sp)
                        val tags = buildList {
                            if (d.rtspConfirmed) add("RTSP ✓")
                            else if (d.port != null) add("پورت ${d.port} باز")
                            d.onvifName?.let { add(it) }
                        }
                        Text(tags.joinToString(" · "), fontSize = 12.sp, color = Copper)
                    }
                }
            }
        }
        Text(
            "نکته: روی دستگاه پیدا‌شده بزنید تا آدرس در لینک قرار گیرد، سپس مسیر برند دوربین را انتخاب کنید.",
            fontSize = 11.sp, color = Color(0xFF888888)
        )
    }
}

/* ---------------- صفحهٔ نمایش با زوم ---------------- */

@OptIn(UnstableApi::class)
@Composable
private fun ViewerScreen(url: String, forceTcp: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? ComponentActivity
    var status by remember { mutableStateOf<String?>("در حال اتصال…") }
    var reloadKey by remember { mutableIntStateOf(0) }

    val player = remember {
        ExoPlayer.Builder(ctx)
            .setLoadControl(
                // بافر کوچک برای تأخیر کم در تصویر زنده
                DefaultLoadControl.Builder().setBufferDurationsMs(500, 2000, 250, 500).build()
            ).build()
    }

    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                status = when (state) {
                    Player.STATE_BUFFERING -> "در حال دریافت تصویر…"
                    Player.STATE_READY -> null
                    Player.STATE_ENDED -> "ارتباط قطع شد"
                    else -> status
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                val cause = generateSequence(error as Throwable) { it.cause }.last().message
                status = "خطا: ${error.errorCodeName}\n${cause ?: ""}\n\n" +
                        "• لینک/مسیر را بررسی کنید\n• نام کاربری و رمز را بررسی کنید\n• گزینهٔ TCP را تغییر دهید"
            }
        }
        player.addListener(l)
        onDispose {
            player.removeListener(l); player.release()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    LaunchedEffect(reloadKey) {
        status = "در حال اتصال…"
        val src = RtspMediaSource.Factory()
            .setForceUseRtpTcp(forceTcp)
            .setTimeoutMs(8000)
            .createMediaSource(MediaItem.fromUri(url))
        player.setMediaSource(src)
        player.prepare()
        player.playWhenReady = true
    }

    BackHandler { onBack() }

    // وضعیت زوم
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val mx = box.width * (s - 1f) / 2f
        val my = box.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clipToBounds()
                .onSizeChanged { box = it }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val ns = (scale * zoom).coerceIn(1f, 10f)
                        val c = centroid - Offset(box.width / 2f, box.height / 2f)
                        // زوم حول نقطهٔ بین دو انگشت
                        val no = (offset - c) * (ns / scale) + c + pan
                        scale = ns
                        offset = clamp(no, ns)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { tap ->
                        if (scale > 1.05f) { scale = 1f; offset = Offset.Zero }
                        else {
                            val ns = 3f
                            val c = tap - Offset(box.width / 2f, box.height / 2f)
                            scale = ns
                            offset = clamp(-c * (ns - 1f), ns)
                        }
                    })
                }
        ) {
            AndroidView(
                factory = { c ->
                    (LayoutInflater.from(c).inflate(R.layout.player_view, null) as PlayerView)
                        .also { it.player = player }
                },
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                }
            )

            // نوار بالا
            Row(
                Modifier.fillMaxWidth().background(Color(0x88000000)).statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "بازگشت", tint = Color.White)
                }
                Text(maskUrl(url), color = Color.White, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Text("×" + "%.1f".format(scale), color = Copper, fontSize = 13.sp)
                TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("1:1", color = Color.White) }
                TextButton(onClick = {
                    activity?.requestedOrientation =
                        if (activity?.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
                            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }) { Text("⟲", color = Color.White, fontSize = 18.sp) }
                IconButton(onClick = { reloadKey++ }) {
                    Icon(Icons.Default.Refresh, "اتصال مجدد", tint = Color.White)
                }
            }

            status?.let {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp)
                            .background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (it.startsWith("در حال")) CircularProgressIndicator(color = Copper)
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
