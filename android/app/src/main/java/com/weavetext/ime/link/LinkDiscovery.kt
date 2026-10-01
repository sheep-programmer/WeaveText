package com.weavetext.ime.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet6Address
import java.util.ArrayDeque

/** Android's system DNS-SD broker resolves Wi-Fi interfaces and network changes. */
internal interface LinkDiscoveryAgent { fun start(info: JSONObject); fun stop() }

internal class LinkDiscovery(ctx: Context, private val command: (JSONObject) -> Unit,
    private val status: (String, String?) -> Unit) : LinkDiscoveryAgent {
    private val nsd = ctx.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var epoch = 0
    private var discovery: NsdManager.DiscoveryListener? = null
    private var registration: NsdManager.RegistrationListener? = null
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var failed = false

    private val connectivity = ctx.getSystemService(ConnectivityManager::class.java)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentNetwork: Network? = null
    private var currentInfo: JSONObject? = null
    private val resolvedIds = HashMap<String, String>()
    private var resolveToken = 0

    override fun start(info: JSONObject) {
        stopServices()
        currentInfo = info
        if (networkCallback == null) {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { main.post {
                    val previous = currentNetwork; currentNetwork = network
                    if (previous != null && previous != network) currentInfo?.let { start(it) }
                } }
                override fun onLost(network: Network) { main.post {
                    if (currentNetwork == network) status("ready", "网络已断开，重新联网后会自动发现设备")
                } }
            }
            networkCallback = cb
            runCatching { connectivity?.registerDefaultNetworkCallback(cb) }
        }
        val generation = epoch
        failed = false
        val manager = nsd ?: run { status("failed", "系统网络发现不可用"); return }
        status("searching", null)
        val service = NsdServiceInfo().apply {
            serviceName = info.optString("id")
            serviceType = TYPE
            port = info.optInt("port")
            setAttribute("id", info.optString("id"))
            setAttribute("n", info.optString("name"))
            setAttribute("p", "android")
            setAttribute("v", "1")
        }
        val register = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) {}
            override fun onRegistrationFailed(s: NsdServiceInfo, code: Int) { report(generation, "广播设备失败（$code），可用地址配对") }
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, code: Int) {}
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { report(generation, "扫描未启动（$code），检查本地网络权限后重试") }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                main.post { if (generation == epoch && service.serviceName != info.optString("id")) { pending.add(service); resolve(generation) } }
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                main.post { if (generation == epoch) command(JSONObject().put("op", "discoveryLost").put("id", resolvedIds.remove(service.serviceName) ?: service.serviceName)) }
            }
        }
        registration = register; discovery = listener
        runCatching {
            manager.registerService(service, NsdManager.PROTOCOL_DNS_SD, register)
            manager.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { report(generation, "网络发现无法启动：${it.message}") }
        main.postDelayed({ if (generation == epoch && !failed) status("ready", null) }, 12_000)
    }

    @Suppress("DEPRECATION")
    private fun resolve(generation: Int) {
        if (resolving || pending.isEmpty() || generation != epoch) return
        resolving = true
        val service = pending.removeFirst()
        val token = ++resolveToken
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(s: NsdServiceInfo, code: Int) = finish(null)
            override fun onServiceResolved(s: NsdServiceInfo) = finish(s)
            private fun finish(s: NsdServiceInfo?) {
                main.post {
                    if (generation != epoch || token != resolveToken) return@post
                    resolveToken++; resolving = false
                    s?.let {
                        val hosts = if (Build.VERSION.SDK_INT >= 34) it.hostAddresses else listOfNotNull(it.host)
                        val endpoints = hosts.mapNotNull { host ->
                            val address = host.hostAddress?.substringBefore('%') ?: return@mapNotNull null
                            val scoped = if (host is Inet6Address && host.isLinkLocalAddress && host.scopeId > 0) "$address%${host.scopeId}" else address
                            if (host is Inet6Address) "[$scoped]:${it.port}" else "$address:${it.port}"
                        }
                        fun attr(key: String) = it.attributes[key]?.toString(Charsets.UTF_8).orEmpty()
                        val id = attr("id").ifEmpty { it.serviceName }
                        resolvedIds[service.serviceName] = id
                        if (it.port > 0 && endpoints.isNotEmpty()) {
                            command(JSONObject().put("op", "discovered").put("id", id)
                                .put("name", attr("n")).put("platform", attr("p"))
                                .put("addrs", JSONArray(endpoints)))
                            if (!failed) status("ready", null)
                        }
                    }
                    resolve(generation)
                }
            }
        }
        runCatching { nsd?.resolveService(service, listener) }.onFailure {
            resolving = false; resolveToken++; resolve(generation)
        }
        main.postDelayed({
            if (generation == epoch && resolving && token == resolveToken) {
                resolving = false; resolveToken++
                report(generation, "设备地址解析超时，可重新扫描或扫码配对")
                resolve(generation)
            }
        }, 8000)
    }

    private fun report(generation: Int, message: String) { main.post { if (generation == epoch) { failed = true; status("failed", message) } } }
    private fun stopServices() {
        epoch++; pending.clear(); resolving = false; resolveToken++
        resolvedIds.values.forEach { command(JSONObject().put("op", "discoveryLost").put("id", it)) }; resolvedIds.clear()
        discovery?.let { runCatching { nsd?.stopServiceDiscovery(it) } }
        registration?.let { runCatching { nsd?.unregisterService(it) } }
        discovery = null; registration = null
    }
    override fun stop() {
        currentInfo = null; stopServices()
        networkCallback?.let { runCatching { connectivity?.unregisterNetworkCallback(it) } }
        networkCallback = null; currentNetwork = null
    }
    companion object { const val TYPE = "_weavelink._tcp." }
}
