package com.pakomo.ui.screens

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pakomo.ui.components.ScreenHeader
import com.pakomo.ui.theme.LocalAppLanguage
import com.pakomo.ui.theme.LocalPakomoColors
import com.pakomo.ui.theme.t
import com.pakomo.vpn.VpnServiceController
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Target(val label: String, val host: String, val enLabel: String = label)

private enum class Phase { PENDING, RUNNING, DONE }

private data class Probe(
    val target: Target,
    val phase: Phase = Phase.PENDING,
    val ip: String? = null,
    val family: String? = null,
    val rtts: List<Long> = emptyList(),
    val loss: Int = 0,
    val samples: Int = 0,
) {
    val ok get() = rtts.isNotEmpty()
    val rttMs get() = if (ok) rtts.average().roundToLong() else null
    val jitterMs: Long?
        get() = if (rtts.size < 2) null else {
            val avg = rtts.average()
            rtts.map { abs(it - avg) }.average().roundToLong()
        }
    val lossPercent get() = if (samples > 0) loss * 100 / samples else 0
}

private val TARGETS = listOf(
    Target("百度", "www.baidu.com", enLabel = "Baidu"),
    Target("淘宝", "www.taobao.com", enLabel = "Taobao"),
    Target("腾讯", "www.qq.com", enLabel = "Tencent"),
    Target("微博", "weibo.com", enLabel = "Weibo"),
    Target("哔哩哔哩", "www.bilibili.com", enLabel = "Bilibili"),
    Target("抖音", "www.douyin.com", enLabel = "Douyin"),
    Target("京东", "www.jd.com", enLabel = "JD"),
    Target("网易", "www.163.com", enLabel = "NetEase"),
    Target("Google", "www.google.com"),
    Target("Cloudflare", "www.cloudflare.com"),
    Target("GitHub", "github.com"),
    Target("YouTube", "www.youtube.com"),
)

@Composable
fun LatencyTestScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val probes = remember { mutableStateListOf<Probe>().apply { addAll(TARGETS.map { Probe(it) }) } }
    var testing by remember { mutableStateOf(false) }

    fun runAll() {
        if (testing) return
        testing = true
        val proxy = VpnServiceController.activeProxy
        Log.i(TAG, "Latency test started: targets=${TARGETS.size}, route=${if (proxy == null) "direct" else "tunnel"}")
        for (i in probes.indices) probes[i] = Probe(probes[i].target, Phase.RUNNING)
        scope.launch {
            TARGETS.mapIndexed { index, target ->
                launch { probes[index] = probe(target, proxy) }
            }.joinAll()
            Log.i(TAG, "Latency test completed: reachable=${probes.count { it.ok }}/${probes.size}")
            testing = false
        }
    }

    LaunchedEffect(Unit) { runAll() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        ScreenHeader(
            title = t("延迟测试", "Latency test"),
            onBack = onBack,
            action = {
                TextButton(onClick = { runAll() }, enabled = !testing) {
                    Text(if (testing) t("测试中", "Testing") else t("重新测试", "Retest"))
                }
            },
        )
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            probes.forEach { probe -> ProbeRow(probe) }
        }
    }
}

@Composable
private fun ProbeRow(probe: Probe) {
    val colors = LocalPakomoColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.border),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (LocalAppLanguage.current == com.pakomo.core.model.AppLanguage.EN) probe.target.enLabel else probe.target.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textPrimary,
                    )
                    probe.family?.let {
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (it == "IPv6") colors.accent else colors.muted,
                        )
                    }
                }
                Text(probe.target.host, style = MaterialTheme.typography.bodySmall, color = colors.muted, fontFamily = FontFamily.Monospace)
                Text(
                    text = probe.ip ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.size(10.dp))
            when (probe.phase) {
                Phase.RUNNING -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = colors.accent,
                )
                Phase.PENDING -> Text("—", color = colors.muted)
                Phase.DONE -> Metrics(probe)
            }
        }
    }
}

@Composable
private fun Metrics(probe: Probe) {
    val colors = LocalPakomoColors.current
    Column(horizontalAlignment = Alignment.End) {
        if (probe.ok) {
            Text(
                text = "${probe.rttMs} ms",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.accent,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = t("抖动", "Jitter") + " ${probe.jitterMs ?: 0} ms",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                fontFamily = FontFamily.Monospace,
            )
        } else {
            Text(t("不可达", "Unreachable"), style = MaterialTheme.typography.bodyMedium, color = colors.danger)
        }
        Text(
            text = t("丢包", "Loss") + " ${probe.lossPercent}%",
            style = MaterialTheme.typography.bodySmall,
            color = if (probe.lossPercent > 0) colors.danger else colors.muted,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private const val SAMPLES = 6
private const val SRC_PORT = 40000
private const val PROBE_PORT = 80
private const val TIMEOUT_MS = 3_000

private suspend fun probe(target: Target, proxy: VpnServiceController.ActiveProxy?): Probe =
    withContext(Dispatchers.IO) {
        val address = runCatching { InetAddress.getByName(target.host) }.getOrNull()
        val family = when (address) {
            null -> null
            is Inet6Address -> "IPv6"
            else -> "IPv4"
        }
        val request = (
            "HEAD / HTTP/1.1\r\nHost: ${target.host}\r\n" +
                "Connection: close\r\nUser-Agent: Pakomo\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val rtts = ArrayList<Long>()
        var loss = 0
        var consecutiveFail = 0
        repeat(SAMPLES) {
            if (consecutiveFail >= 2) {
                loss++ // give up early on an unreachable host instead of burning every timeout
            } else {
                val ms = runCatching { measureOnce(target.host, request, proxy) }.getOrNull()
                if (ms != null) {
                    rtts.add(ms)
                    consecutiveFail = 0
                } else {
                    loss++
                    consecutiveFail++
                }
            }
        }
        Probe(target, Phase.DONE, address?.hostAddress, family, rtts, loss, SAMPLES).also { result ->
            Log.i(
                TAG,
                "Probe completed: host=${target.host}, reachable=${result.ok}, rttMs=${result.rttMs ?: -1}, loss=${result.lossPercent}%",
            )
        }
    }

/** One HTTP HEAD round-trip in milliseconds; the timer covers only request→first-response byte. */
private fun measureOnce(
    host: String,
    request: ByteArray,
    proxy: VpnServiceController.ActiveProxy?,
): Long {
    val socket = if (proxy != null) socksConnect(proxy, host, PROBE_PORT) else directConnect(host, PROBE_PORT)
    socket.use {
        it.soTimeout = TIMEOUT_MS
        val out = it.getOutputStream()
        val input = it.getInputStream()
        val start = System.nanoTime()
        out.write(request)
        out.flush()
        val buffer = ByteArray(64)
        if (input.read(buffer) < 0) throw EOFException()
        return (System.nanoTime() - start) / 1_000_000
    }
}

private fun directConnect(host: String, port: Int): Socket =
    Socket().apply { connect(InetSocketAddress(host, port), TIMEOUT_MS) }

/** Routes through the running loopback SOCKS proxy so the measurement passes through shaping. */
private fun socksConnect(proxy: VpnServiceController.ActiveProxy, host: String, port: Int): Socket {
    val socket = Socket()
    socket.connect(InetSocketAddress("127.0.0.1", proxy.port), TIMEOUT_MS)
    socket.soTimeout = TIMEOUT_MS
    val out = socket.getOutputStream()
    val input = socket.getInputStream()
    try {
        out.write(attributionPreamble(port))
        out.write(byteArrayOf(5, 1, 2)) // greeting: user/pass auth
        out.flush()
        readExact(input, 2)
        val user = proxy.username.toByteArray(Charsets.UTF_8)
        val pass = proxy.password.toByteArray(Charsets.UTF_8)
        val auth = ByteArrayOutputStream()
        auth.write(1); auth.write(user.size); auth.write(user); auth.write(pass.size); auth.write(pass)
        out.write(auth.toByteArray())
        out.flush()
        readExact(input, 2)
        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        val connect = ByteArrayOutputStream()
        connect.write(byteArrayOf(5, 1, 0, 3)); connect.write(hostBytes.size); connect.write(hostBytes)
        connect.write((port ushr 8) and 0xff); connect.write(port and 0xff)
        out.write(connect.toByteArray())
        out.flush()
        val reply = readExact(input, 4)
        if (reply[1].toInt() != 0) throw EOFException("socks reply ${reply[1]}")
        val addrLen = when (reply[3].toInt() and 0xff) {
            1 -> 4
            4 -> 16
            3 -> readExact(input, 1)[0].toInt() and 0xff
            else -> 0
        }
        readExact(input, addrLen + 2)
        return socket
    } catch (error: Exception) {
        runCatching { socket.close() }
        throw error
    }
}

private fun attributionPreamble(port: Int): ByteArray {
    val loopback = InetAddress.getByName("127.0.0.1").address
    val bo = ByteArrayOutputStream()
    bo.write('P'.code); bo.write('K'.code); bo.write('M'.code); bo.write('O'.code)
    bo.write(1); bo.write(6) // version, TCP
    bo.write((SRC_PORT ushr 8) and 0xff); bo.write(SRC_PORT and 0xff)
    bo.write((port ushr 8) and 0xff); bo.write(port and 0xff)
    bo.write(loopback); bo.write(loopback) // src ip, dst ip (loopback: valid, non-wildcard)
    bo.write(0); bo.write(0)
    return bo.toByteArray()
}

private fun readExact(input: InputStream, size: Int): ByteArray {
    val bytes = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val read = input.read(bytes, offset, size - offset)
        if (read < 0) throw EOFException()
        offset += read
    }
    return bytes
}

private const val TAG = "PakomoLatency"
