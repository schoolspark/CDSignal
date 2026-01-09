package `in`.chinmoydas.signal.utils

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.InetAddress

class LocalLinkManager(
    context: Context, 
    private val onUserFound: (String, InetAddress, Int) -> Unit,
    private val onUserLost: (String) -> Unit
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val SERVICE_TYPE = "_cdsignal._udp" // Removed trailing dot for registration
    private var serviceName = "CD_User_${System.currentTimeMillis() % 1000}"

    private var isDiscovering = false
    private var isAdvertising = false

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) { 
            Log.d("LocalLink", "Discovery Started")
            isDiscovering = true 
        }
        override fun onServiceFound(service: NsdServiceInfo) {
            Log.d("LocalLink", "Service Found: ${service.serviceName} type: ${service.serviceType}")
            // Check for our service type (system might add dots)
            if (service.serviceType.contains("cdsignal") && service.serviceName != serviceName) {
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.e("LocalLink", "Resolve Failed: $errorCode")
                    }
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        Log.d("LocalLink", "Service Resolved: ${serviceInfo.serviceName} at ${serviceInfo.host}")
                        onUserFound(serviceInfo.serviceName, serviceInfo.host, serviceInfo.port)
                    }
                })
            }
        }
        override fun onServiceLost(service: NsdServiceInfo) {
            Log.d("LocalLink", "Service Lost: ${service.serviceName}")
            onUserLost(service.serviceName.removePrefix("CD-"))
        }
        override fun onDiscoveryStopped(serviceType: String) { isDiscovering = false }
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { 
            Log.e("LocalLink", "Discovery Start Failed: $errorCode")
            try { nsdManager.stopServiceDiscovery(this) } catch (e: Exception) {}
            isDiscovering = false
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { isDiscovering = false }
    }

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) { 
            serviceName = NsdServiceInfo.serviceName
            Log.d("LocalLink", "Service Registered as $serviceName")
            isAdvertising = true 
        }
        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { 
            Log.e("LocalLink", "Registration Failed: $errorCode")
            isAdvertising = false 
        }
        override fun onServiceUnregistered(arg0: NsdServiceInfo) { isAdvertising = false }
        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { isAdvertising = false }
    }

    fun startAdvertising(myUsername: String, port: Int) {
        if (isAdvertising) stopAdvertising()
        val cleanName = myUsername.replace(Regex("[^A-Za-z0-9]"), "")
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "CD-$cleanName"
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        try { 
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener) 
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun startDiscovery() {
        if (isDiscovering) return
        try {
            // Most NsdManager versions work better without the trailing dot here
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun stopAdvertising() {
        if (isAdvertising) {
            try { nsdManager.unregisterService(registrationListener) } catch (e: Exception) {}
            isAdvertising = false
        }
    }

    fun stopDiscovery() {
        if (isDiscovering) {
            try { nsdManager.stopServiceDiscovery(discoveryListener) } catch (e: Exception) {}
            isDiscovering = false
        }
    }

    fun stop() {
        stopAdvertising()
        stopDiscovery()
    }
}