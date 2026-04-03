package `in`.chinmoydas.signal.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.sqrt

class SensorHelper(context: Context, private val onEmergency: (String) -> Unit) : SensorEventListener {

    private val tag = "SensorHelper"
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var isMonitoring = false

    // [FIX] Use SupervisorJob so we can cleanly cancel and restart the scope
    private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Civil Settings (3.5G is roughly equivalent to a hard drop onto concrete)
    private val CRASH_THRESHOLD = 3.5f
    private var lastMoveTime = System.currentTimeMillis()

    // [CRITICAL FIX] Debounce tracker to prevent SOS Network Spam
    private var lastEmergencyTime = 0L
    private val EMERGENCY_COOLDOWN_MS = 15_000L // Prevent another trigger for 15 seconds

    fun start() {
        if (!isMonitoring && accelerometer != null) {
            // Re-initialize scope if it was previously cancelled
            if (!scope.isActive) scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
            isMonitoring = true
            lastMoveTime = System.currentTimeMillis()
        }
    }

    fun stop() {
        if (!isMonitoring) return
        sensorManager.unregisterListener(this)
        isMonitoring = false
        // [FIX] Cleanly cancel any pending coroutines to prevent memory leaks
        scope.cancel()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isMonitoring) return

        event?.let {
            val x = it.values[0]; val y = it.values[1]; val z = it.values[2]

            // 1. Calculate overall G-Force vector magnitude
            val gForce = sqrt(x*x + y*y + z*z) / SensorManager.GRAVITY_EARTH

            // 2. Crash Detection with Anti-Spam Cooldown
            if (gForce > CRASH_THRESHOLD) {
                val currentTime = System.currentTimeMillis()

                // Only trigger if 15 seconds have passed since the last impact
                if (currentTime - lastEmergencyTime > EMERGENCY_COOLDOWN_MS) {
                    lastEmergencyTime = currentTime

                    scope.launch {
                        // Pass the exact G-Force to the callback for logging/UI
                        val formattedGForce = String.format("%.1f", gForce)
                        onEmergency("Hard Impact Detected! (${formattedGForce}G)")
                    }
                }
            }

            // 3. Movement Tracker (Can be used later for 'Dead Man's Switch' logic)
            if (abs(x) > 2.0 || abs(y) > 2.0 || abs(z - 9.8) > 2.0) {
                lastMoveTime = System.currentTimeMillis()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for simple accelerometer monitoring
    }
}