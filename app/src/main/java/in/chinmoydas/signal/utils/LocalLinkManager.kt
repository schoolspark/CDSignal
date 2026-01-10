package `in`.chinmoydas.signal.utils

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.InetAddress

class LocalLinkManager(
    context: Context,
    private val onServiceFound: (String, InetAddress, Int) -> Unit,
    private val onServiceLost: (String) -> Unit
) {

    private val tag = "LocalLink"
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    // Using ._tcp is generally more reliable for NSD resolution on Android than ._udp,
    // even if the actual traffic is UDP.
    private val SERVICE_TYPE = "_cdsignal._tcp."

    // Important: We hold references to the CURRENT listeners so we can unregister them.
    // We do NOT define them as 'val' objects here, or they cannot be reused.
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    @Volatile private var isDiscoveryStarted = false
    @Volatile private var currentRegisteredName: String? = null

    // --- FIX: ROBUST ADVERTISING ---
    fun startAdvertising(name: String, port: Int) {
        val cleanName = name.replace(Regex("[^A-Za-z0-9]"), "")

        // 1. Prevention: If we are already advertising this EXACT name, do nothing.
        if (currentRegisteredName == cleanName && registrationListener != null) {
            return
        }

        // 2. Cleanup: Stop any existing advertisement first.
        stopAdvertising()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "CD-$cleanName"
            serviceType = SERVICE_TYPE
            this.port = port
        }

        // 3. New Listener: Create a FRESH listener object for every registration.
        // This prevents the "listener already in use" crash.
        val newListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                currentRegisteredName = cleanName
                Log.d(tag, "Service Registered: ${NsdServiceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(tag, "Registration failed: Error $errorCode")
                currentRegisteredName = null
                registrationListener = null
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                Log.d(tag, "Service Unregistered")
                currentRegisteredName = null
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(tag, "Unregistration failed: Error $errorCode")
            }
        }

        registrationListener = newListener
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, newListener)
        } catch (e: Exception) {
            Log.e(tag, "Failed to register service", e)
            registrationListener = null
        }
    }

    fun stopAdvertising() {
        val listener = registrationListener
        if (listener != null) {
            try {
                nsdManager.unregisterService(listener)
            } catch (e: Exception) {
                // Ignore "Service not registered" errors, just clean up
            }
        }
        registrationListener = null
        currentRegisteredName = null
    }

    // --- DISCOVERY LOGIC ---
    fun startDiscovery() {
        if (isDiscoveryStarted) return

        // Create a FRESH discovery listener every time
        val newListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                isDiscoveryStarted = true
                Log.d(tag, "Discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                // Filter specifically for our service type
                if (service.serviceType.contains("cdsignal")) {
                    try {
                        nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                Log.e(tag, "Resolve failed: $errorCode")
                            }

                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                // Ignore our own signal
                                if (serviceInfo.serviceName.contains(currentRegisteredName ?: "SKIP_CHECK")) return

                                val host = serviceInfo.host
                                val port = serviceInfo.port
                                // Remove prefix if present for cleaner UI
                                val cleanFoundName = serviceInfo.serviceName.removePrefix("CD-")
                                onServiceFound(cleanFoundName, host, port)
                            }
                        })
                    } catch (e: Exception) {
                        Log.e(tag, "Resolution error", e)
                    }
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                val cleanLostName = service.serviceName.removePrefix("CD-")
                onServiceLost(cleanLostName)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                isDiscoveryStarted = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(tag, "Discovery start failed: $errorCode")
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                stopDiscovery()
            }
        }

        discoveryListener = newListener
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, newListener)
        } catch (e: Exception) {
            Log.e(tag, "Discovery init failed", e)
            isDiscoveryStarted = false
        }
    }

    fun stopDiscovery() {
        val listener = discoveryListener
        if (listener != null && isDiscoveryStarted) {
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.w(tag, "Error stopping discovery", e)
            }
        }
        discoveryListener = null
        isDiscoveryStarted = false
    }

    fun stop() {
        stopAdvertising()
        stopDiscovery()
    }
}