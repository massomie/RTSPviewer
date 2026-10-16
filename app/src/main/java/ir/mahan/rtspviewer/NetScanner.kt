package ir.mahan.rtspviewer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

data class FoundDevice(
    val ip: String,
    val port: Int?,          // پورت RTSP باز (اگر پیدا شد)
    val rtspConfirmed: Boolean,
    val onvifName: String? = null,
)

data class LocalNet(val ip: String, val prefix: Int, val isWifi: Boolean)

object NetScanner {

    /** آدرس IPv4 گوشی در شبکهٔ فعلی (Wi-Fi). */
    fun localNet(ctx: Context): LocalNet? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net)
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val lp = cm.getLinkProperties(net) ?: return null
        val la = lp.linkAddresses.firstOrNull {
            it.address is Inet4Address && !it.address.isLoopbackAddress
        } ?: return null
        return LocalNet(la.address.hostAddress ?: return null, la.prefixLength, isWifi)
    }

    /**
     * اسکن کل زیرشبکهٔ /24 روی پورت‌های RTSP.
     * برای هر پورت باز، درخواست OPTIONS فرستاده می‌شود تا RTSP بودن تأیید شود.
     */
    suspend fun scanRtsp(
        ctx: Context,
        ports: List<Int> = listOf(554, 8554, 10554),
        onFound: suspend (FoundDevice) -> Unit,
        onProgress: suspend (Float) -> Unit,
    ) = coroutineScope {
        val ln = localNet(ctx) ?: error("گوشی به شبکه متصل نیست")
        val base = ln.ip.substringBeforeLast('.')
        val total = 254
        val done = AtomicInteger(0)
        val sem = Semaphore(64)
        (1..254).map { i ->
            launch(Dispatchers.IO) {
                sem.withPermit {
                    val host = "$base.$i"
                    if (host != ln.ip) {
                        for (p in ports) {
                            val r = probe(host, p)
                            if (r != null) {
                                withContext(Dispatchers.Main) { onFound(FoundDevice(host, p, r)) }
                                break
                            }
                        }
                    }
                    val d = done.incrementAndGet()
                    withContext(Dispatchers.Main) { onProgress(d.toFloat() / total) }
                }
            }
        }.joinAll()
    }

    /** null = پورت بسته؛ true = پاسخ RTSP؛ false = پورت باز ولی پاسخ RTSP نداد */
    private fun probe(host: String, port: Int, timeoutMs: Int = 350): Boolean? {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.soTimeout = 800
                try {
                    val req = "OPTIONS rtsp://$host:$port/ RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: RtspViewer\r\n\r\n"
                    s.getOutputStream().apply { write(req.toByteArray()); flush() }
                    val buf = ByteArray(64)
                    val n = s.getInputStream().read(buf)
                    n > 0 && String(buf, 0, n).startsWith("RTSP/")
                } catch (_: Exception) {
                    false
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** کشف دوربین‌های ONVIF با WS-Discovery (مولتی‌کست 239.255.255.250:3702). */
    suspend fun onvifDiscover(ctx: Context, waitMs: Long = 3000): List<FoundDevice> =
        withContext(Dispatchers.IO) {
            val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
            val lock = wifi?.createMulticastLock("onvif")?.apply {
                setReferenceCounted(false); acquire()
            }
            val result = linkedMapOf<String, FoundDevice>()
            try {
                DatagramSocket().use { sock ->
                    sock.soTimeout = 500
                    val msg = probeXml().toByteArray()
                    val group = InetAddress.getByName("239.255.255.250")
                    repeat(2) { sock.send(DatagramPacket(msg, msg.size, group, 3702)) }
                    val end = System.currentTimeMillis() + waitMs
                    val buf = ByteArray(8192)
                    while (System.currentTimeMillis() < end) {
                        try {
                            val pkt = DatagramPacket(buf, buf.size)
                            sock.receive(pkt)
                            val ip = pkt.address.hostAddress ?: continue
                            val xml = String(pkt.data, 0, pkt.length)
                            result[ip] = FoundDevice(ip, null, false, parseName(xml) ?: "ONVIF")
                        } catch (_: SocketTimeoutException) { /* ادامه */ }
                    }
                }
            } catch (_: Exception) {
            } finally {
                lock?.release()
            }
            result.values.toList()
        }

    private fun parseName(xml: String): String? {
        val scopes = Regex("Scopes[^>]*>([^<]*)<").find(xml)?.groupValues?.get(1) ?: return null
        fun pick(key: String) = Regex("onvif://www\\.onvif\\.org/$key/([^\\s]+)")
            .find(scopes)?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        val name = pick("name")
        val hw = pick("hardware")
        return listOfNotNull(name, hw).distinct().joinToString(" · ").ifBlank { null }
    }

    private fun probeXml() = """<?xml version="1.0" encoding="UTF-8"?>
<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl"><e:Header><w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID><w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header><e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"""
}
