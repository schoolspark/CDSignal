package `in`.chinmoydas.signal.utils

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue

class LocalLinkManager(
    context: Context,
    private val onServiceFound: (String, InetAddress, Int) -> Unit,
    private val onServiceLost: (String) -> Unit
) {
    private val tag = "LocalLink"
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val SERVICE_TYPE = "_cdsignal._tcp."

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    // Serialize resolutions to prevent "FAILURE_ALREADY_ACTIVE"
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    @Volatile private var isResolving = false

    @Volatile private var isDiscoveryStarted = false
    @Volatile private var currentRegisteredName: String? = null

    fun startAdvertising(name: String, port: Int) {
        val cleanName = name.replace(Regex("[^A-Za-z0-9]"), "")
        // Prevent redundant registrations
        if (currentRegisteredName == cleanName && registrationListener != null) return
        stopAdvertising()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "CD-$cleanName"
            serviceType = SERVICE_TYPE
            this.port = port
        }

        val newListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                currentRegisteredName = cleanName
                Log.d(tag, "LAN Broadcast Active: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, err: Int) {
                registrationListener = null
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                currentRegisteredName = null
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, err: Int) {}
        }

        registrationListener = newListener
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, newListener)
        } catch (e: Exception) {
            Log.e(tag, "LAN Adv Failed", e)
        }
    }

    fun stopAdvertising() {
        registrationListener?.let {
            try { nsdManager.unregisterService(it) } catch (e: Exception) {}
        }
        registrationListener = null
        currentRegisteredName = null
    }

    fun startDiscovery() {
        if (isDiscoveryStarted) return
        resolveQueue.clear()
        isResolving = false

        val newListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {
                isDiscoveryStarted = true
                Log.d(tag, "LAN Discovery Started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                // [UPGRADE] Proactive Filtering: Don't resolve our own service
                val myName = currentRegisteredName ?: "SKIP_CHECK"
                if (service.serviceType.contains("cdsignal") && !service.serviceName.contains(myName)) {
                    resolveQueue.add(service)
                    processResolveQueue()
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                val cleanName = service.serviceName.removePrefix("CD-")
                onServiceLost(cleanName)
            }

            override fun onDiscoveryStopped(type: String) { isDiscoveryStarted = false }
            override fun onStartDiscoveryFailed(type: String, err: Int) { stopDiscovery() }
            override fun onStopDiscoveryFailed(type: String, err: Int) { stopDiscovery() }
        }

        discoveryListener = newListener
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, newListener)
        } catch (e: Exception) {
            isDiscoveryStarted = false
        }
    }

    private fun processResolveQueue() {
        if (isResolving || resolveQueue.isEmpty()) return
        val service = resolveQueue.peek() ?: return
        isResolving = true

        try {
            nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, err: Int) {
                    resolveQueue.poll()
                    isResolving = false
                    // Process next in queue after a tiny delay to let the system breathe
                    processResolveQueue()
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    resolveQueue.poll()
                    isResolving = false

                    val host = info.host
                    val port = info.port
                    val cleanName = info.serviceName.removePrefix("CD-")

                    // Trigger Callback with LAN IP
                    onServiceFound(cleanName, host, port)

                    processResolveQueue()
                }
            })
        } catch (e: Exception) {
            isResolving = false
        }
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            if (isDiscoveryStarted) {
                try { nsdManager.stopServiceDiscovery(it) } catch (e: Exception) {}
            }
        }
        discoveryListener = null
        isDiscoveryStarted = false
        resolveQueue.clear()
    }

    fun stop() {
        stopAdvertising()
        stopDiscovery()
    }
}