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

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var isMonitoring = false
    private val scope = CoroutineScope(Dispatchers.Default)

    // Civil Settings (Harder to trigger accidentally)
    private val CRASH_THRESHOLD = 3.5f // 3.5G (Hard fall or Bike crash)
    private val STATIONARY_TIMEOUT = 60_000L // 60 seconds motionless
    private var lastMoveTime = System.currentTimeMillis()

    fun start() {
        if (!isMonitoring && accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
            isMonitoring = true
            startStationaryCheck()
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        isMonitoring = false
        scope.coroutineContext.cancelChildren()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            val x = it.values[0]; val y = it.values[1]; val z = it.values[2]

            // 1. Crash/Hard Fall
            val gForce = sqrt(x*x + y*y + z*z) / SensorManager.GRAVITY_EARTH
            if (gForce > CRASH_THRESHOLD) {
                // In public app, wait 3 seconds to see if user picks it up
                scope.launch {
                    delay(3000)
                    onEmergency("Hard Impact Detected!")
                }
            }

            // 2. Movement Reset
            if (abs(x) > 2.0 || abs(y) > 2.0 || abs(z - 9.8) > 2.0) {
                lastMoveTime = System.currentTimeMillis()
            }
        }
    }

    private fun startStationaryCheck() {
        scope.launch {
            while (isMonitoring) {
                delay(10000)
                // Only trigger if enabled in settings (passed via constructor ideally)
                // For public app, maybe don't auto-trigger SOS for stillness unless requested
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}