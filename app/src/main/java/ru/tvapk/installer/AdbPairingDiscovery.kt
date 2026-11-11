package ru.tvapk.installer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class AdbPairingEndpoint(
    val name: String,
    val host: String,
    val port: Int
)

/**
 * Discovers only Android Wireless Debugging pairing advertisements.
 * This is intentionally separate from _adb-tls-connect._tcp and legacy _adb._tcp:
 * the pairing and connection ports are different services.
 */
internal fun discoverAdbPairingEndpoints(
    context: Context,
    timeoutMs: Long = 6500L
): List<AdbPairingEndpoint> {
    require(timeoutMs in 500L..20_000L) { "Discovery timeout is outside the supported range" }
    val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        ?: return emptyList()
    val found = ConcurrentHashMap<String, AdbPairingEndpoint>()
    val seen = ConcurrentHashMap.newKeySet<String>()
    val queue = java.util.concurrent.ConcurrentLinkedQueue<NsdServiceInfo>()
    val resolving = AtomicBoolean(false)
    val accepting = AtomicBoolean(true)
    val stopped = AtomicBoolean(false)
    val finished = CountDownLatch(1)
    val ownAddresses = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .flatMap { it.inetAddresses.toList() }
            .mapNotNull { it.hostAddress?.substringBefore('%')?.lowercase(java.util.Locale.ROOT) }
            .toSet()
    }.getOrDefault(emptySet())

    fun resolveNext() {
        if (stopped.get() || !resolving.compareAndSet(false, true)) return
        val service = queue.poll()
        if (service == null) {
            resolving.set(false)
            return
        }
        runCatching {
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    resolving.set(false)
                    resolveNext()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host?.let {
                        TvAutomationEngine.usableDiscoveredAddressHost(it, ownAddresses)
                    }
                    val port = serviceInfo.port
                    if (!stopped.get() && host != null && port in 1..65535) {
                        val endpoint = AdbPairingEndpoint(
                            name = serviceInfo.serviceName.ifBlank { "Android TV" },
                            host = host,
                            port = port
                        )
                        found["$host:$port"] = endpoint
                    }
                    resolving.set(false)
                    resolveNext()
                }
            })
        }.onFailure {
            resolving.set(false)
            resolveNext()
        }
    }

    val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            val key = "${serviceInfo.serviceType}/${serviceInfo.serviceName}"
            if (accepting.get() && seen.add(key)) {
                queue.offer(serviceInfo)
                resolveNext()
            }
        }
        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            finished.countDown()
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
    }

    var started = false
    try {
        nsd.discoverServices(
            PAIRING_SERVICE_TYPE,
            NsdManager.PROTOCOL_DNS_SD,
            listener
        )
        started = true
        finished.await(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    } catch (_: Exception) {
        // NSD can be unavailable on restricted Wi-Fi networks; return no endpoints.
    } finally {
        accepting.set(false)
        if (started) runCatching { nsd.stopServiceDiscovery(listener) }
        val drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!Thread.currentThread().isInterrupted &&
            System.nanoTime() < drainDeadline &&
            (resolving.get() || queue.isNotEmpty())
        ) {
            try {
                Thread.sleep(25)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        stopped.set(true)
    }

    return found.values.sortedWith(
        compareBy<AdbPairingEndpoint> { it.name.lowercase() }
            .thenBy { it.host }
            .thenBy { it.port }
    )
}

internal fun isSameNumericIpAddress(first: String, second: String): Boolean {
    val canonicalFirst = canonicalAddress(first) ?: return false
    return canonicalFirst == canonicalAddress(second)
}

internal fun matchingAdbPairingEndpoints(
    host: String,
    endpoints: List<AdbPairingEndpoint>
): List<AdbPairingEndpoint> {
    return endpoints.filter { isSameNumericIpAddress(host, it.host) }
        .distinctBy { it.port }
        .sortedBy { it.port }
}

/**
 * Canonicalize numeric IP literals only. InetAddress.getByName also accepts hostnames
 * and may perform DNS lookups; pairing endpoint matching must stay local and deterministic.
 */
private fun canonicalAddress(value: String): String? {
    val literal = value.trim()
    if (literal.isEmpty()) return null

    val hasZone = '%' in literal
    val address = literal.substringBefore('%')
    if (hasZone) {
        val zone = literal.substringAfter('%')
        if (!address.contains(':') || !zone.matches(Regex("[A-Za-z0-9_.-]+"))) return null
    }

    val bytes = if (address.contains(':')) {
        if (!address.matches(Regex("[0-9A-Fa-f:.]+"))) return null
        runCatching { java.net.InetAddress.getByName(address).address }
            .getOrNull()
            ?.takeIf { it.size == 16 }
            ?: return null
    } else {
        val parts = address.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { part ->
            if (!part.matches(Regex("[0-9]{1,3}"))) return null
            part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        }
        ByteArray(4) { index -> octets[index].toByte() }
    }

    return bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private const val PAIRING_SERVICE_TYPE = "_adb-tls-pairing._tcp."
