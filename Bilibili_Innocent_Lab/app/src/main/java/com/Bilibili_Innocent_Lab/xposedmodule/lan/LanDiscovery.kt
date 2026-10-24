package com.Bilibili_Innocent_Lab.xposedmodule.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.Inet4Address
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal data class LanWifi(val network: Network, val address: Inet4Address) {
    companion object {
        fun current(context: Context): LanWifi {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            return manager.allNetworks.mapNotNull { network ->
                val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                val address = manager.getLinkProperties(network)?.linkAddresses?.map { it.address }
                    ?.filterIsInstance<Inet4Address>()?.firstOrNull { LanAddress.privateV4(it.hostAddress.orEmpty()) != null }
                    ?: return@mapNotNull null
                LanWifi(network, address)
            }.singleOrNull() ?: error("wifi-required")
        }
    }
}

/** DNS-SD contains no title, CID, UID, device nickname or watch state. */
internal class LanDiscovery(context: Context, id: String, port: Int, private val wifi: LanWifi,
    private val lostNetwork: () -> Unit) : AutoCloseable {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val closed = AtomicBoolean(false)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val found = ConcurrentHashMap<String, LanPeer>()
    private val waiting = ArrayDeque<NsdServiceInfo>() // main thread
    private var resolving = false
    private var registered = false
    private var discovering = false
    @Volatile var failure: String? = null; private set
    fun peers(): List<LanPeer> = found.values.sortedBy { it.id }
    private val registration = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) { if (closed.get()) runCatching { manager.unregisterService(this) } else registered = true }
        override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { failure = "discovery-register-$code" }
        override fun onServiceUnregistered(info: NsdServiceInfo) { registered = false }
        override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) { registered = false }
    }
    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) { discovering = true; if (closed.get()) runCatching { manager.stopServiceDiscovery(this) } }
        override fun onStartDiscoveryFailed(type: String, code: Int) { failure = "discovery-start-$code" }
        override fun onStopDiscoveryFailed(type: String, code: Int) { discovering = false }
        override fun onDiscoveryStopped(type: String) { discovering = false }
        override fun onServiceFound(info: NsdServiceInfo) {
            main.post {
                if (closed.get() || !info.serviceName.startsWith("BIL-") || info.serviceName == "BIL-$id" || waiting.size >= 32 || found.size >= 32) return@post
                waiting.addLast(info); next()
            }
        }
        override fun onServiceLost(info: NsdServiceInfo) { found.remove(info.serviceName.removePrefix("BIL-")) }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) { if (network == wifi.network && !closed.get()) lostNetwork() }
        override fun onLinkPropertiesChanged(network: Network, properties: android.net.LinkProperties) {
            if (network == wifi.network && properties.linkAddresses.none { it.address == wifi.address } && !closed.get()) lostNetwork()
        }
    }
    init {
        try {
            connectivity.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), networkCallback)
            manager.registerService(NsdServiceInfo().apply {
                serviceName = "BIL-$id"; serviceType = TYPE; this.port = port
                setAttribute("id", id)
            }, NsdManager.PROTOCOL_DNS_SD, registration)
            manager.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (error: Exception) { close(); throw error }
    }
    @Suppress("DEPRECATION") // Serialized resolver is available on all supported hosts (API 27+).
    private fun next() {
        if (closed.get() || resolving || waiting.isEmpty()) return
        resolving = true
        val info = waiting.removeFirst()
        try { manager.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, code: Int) { main.post { resolving = false; next() } }
            override fun onServiceResolved(service: NsdServiceInfo) {
                main.post {
                    if (!closed.get()) {
                        val id = service.attributes["id"]?.let { String(it, Charsets.US_ASCII) }.orEmpty()
                        val ip = service.host?.hostAddress.orEmpty()
                        if (id.matches(Regex("[A-Za-z0-9_-]{22}")) && LanAddress.privateV4(ip) != null && service.port in 1..65535) {
                            found[id] = LanPeer(id, URI("http://$ip:${service.port}"))
                        }
                    }
                    resolving = false; next()
                }
            }
        }) } catch (_: Exception) { resolving = false; failure = "discovery-resolve-unavailable" }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        if (registered) runCatching { manager.unregisterService(registration) }
        if (discovering) runCatching { manager.stopServiceDiscovery(discovery) }
        found.clear(); main.post { waiting.clear() }
    }
    companion object { const val TYPE = "_bil-continuity._tcp." }
}
