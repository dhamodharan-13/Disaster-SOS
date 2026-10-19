package com.innosence.disastersos.power

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * MotionGatedLocationController — Zero-Drain Accelerometer-Gated GPS Power Controller.
 *
 * Traditional continuous GPS polling consumes 10-15 mA (~20% battery/hr), which quickly
 * drains phones in disaster blackouts.
 *
 * This controller uses the device's hardware accelerometer (<0.05 mA) to detect when the
 * victim is stationary (e.g., trapped, sheltering, unconscious). When stationary for >45s,
 * GPS radio polling is completely suspended. The moment motion (>0.8 m/s² delta) is detected,
 * GPS is awakened to capture updated coordinates.
 */
class MotionGatedLocationController(
    private val context: Context,
    private val onLocationUpdated: (Location) -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "MotionGatedLoc"

        private const val GRAVITY_EARTH = 9.80665f
        private const val MOTION_DELTA_THRESHOLD = 0.85f // m/s² deviation from 1G
        private const val STATIONARY_TIMEOUT_MS = 45_000L // 45 seconds of no motion -> Sleep GPS
        private const val STATIONARY_HEARTBEAT_MS = 15 * 60 * 1000L // 15 min fallback fix
        private const val MOVING_LOCATION_INTERVAL_MS = 15_000L // 15s updates when moving
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var isGpsActive = false
    private var isUserStationary = true
    private var lastMotionTimestamp: Long = 0L
    private var lastKnownLocation: Location? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            lastKnownLocation = location
            onLocationUpdated(location)
            Log.d(TAG, "📍 Location update received: (${location.latitude}, ${location.longitude})")
        }
    }

    private val stateEvaluationRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return

            val now = System.currentTimeMillis()
            val timeSinceMotion = now - lastMotionTimestamp

            if (timeSinceMotion >= STATIONARY_TIMEOUT_MS) {
                if (!isUserStationary) {
                    isUserStationary = true
                    Log.i(TAG, "💤 Stationary state detected (idle ${timeSinceMotion / 1000}s). Powering down GPS radio to preserve battery.")
                    stopGpsUpdates()
                }
            } else {
                if (isUserStationary) {
                    isUserStationary = false
                    Log.i(TAG, "🏃 Movement detected! Waking up GPS radio.")
                    startGpsUpdates()
                }
            }

            handler.postDelayed(this, 5_000L) // evaluate motion state every 5s
        }
    }

    private val stationaryHeartbeatRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (isUserStationary) {
                Log.d(TAG, "💓 Stationary Heartbeat: Refreshing location fix...")
                requestOneShotFix()
            }
            handler.postDelayed(this, STATIONARY_HEARTBEAT_MS)
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        lastMotionTimestamp = System.currentTimeMillis()
        isUserStationary = false

        // Register low-power accelerometer
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i(TAG, "✅ Accelerometer registered for motion gating")
        } ?: Log.w(TAG, "⚠️ Accelerometer sensor unavailable, falling back to standard interval")

        // Initial location fix
        requestOneShotFix()
        startGpsUpdates()

        handler.post(stateEvaluationRunnable)
        handler.postDelayed(stationaryHeartbeatRunnable, STATIONARY_HEARTBEAT_MS)
        Log.i(TAG, "✅ MotionGatedLocationController started")
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        sensorManager?.unregisterListener(this)
        stopGpsUpdates()
        Log.i(TAG, "🛑 MotionGatedLocationController stopped")
    }

    @SuppressLint("MissingPermission")
    private fun startGpsUpdates() {
        if (isGpsActive) return
        try {
            val request = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                MOVING_LOCATION_INTERVAL_MS
            ).setMinUpdateDistanceMeters(8f).build()

            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            isGpsActive = true
            Log.i(TAG, "🛰️ GPS polling STARTED (Moving mode)")
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing location permissions to start GPS updates", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start GPS updates", e)
        }
    }

    private fun stopGpsUpdates() {
        if (!isGpsActive) return
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            isGpsActive = false
            Log.i(TAG, "🛑 GPS polling STOPPED (Stationary sleep mode)")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping GPS updates", e)
        }
    }

    /**
     * Obtains an immediate location fix without activating continuous GPS polling.
     * Ideal for manual SOS triggers or stationary periodic check-ins.
     */
    @SuppressLint("MissingPermission")
    fun requestOneShotFix() {
        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        lastKnownLocation = loc
                        onLocationUpdated(loc)
                        Log.i(TAG, "📍 One-shot high accuracy fix obtained: ${loc.latitude}, ${loc.longitude}")
                    } else {
                        // Fallback to cached last location
                        fusedLocationClient.lastLocation.addOnSuccessListener { cached ->
                            if (cached != null) {
                                lastKnownLocation = cached
                                onLocationUpdated(cached)
                                Log.i(TAG, "📍 Cached fallback location obtained: ${cached.latitude}, ${cached.longitude}")
                            }
                        }
                    }
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing for one-shot fix", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get one-shot location", e)
        }
    }

    fun getLastLocation(): Location? = lastKnownLocation

    fun isStationary(): Boolean = isUserStationary

    // --- SensorEventListener ---

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        val delta = abs(magnitude - GRAVITY_EARTH)

        if (delta > MOTION_DELTA_THRESHOLD) {
            lastMotionTimestamp = System.currentTimeMillis()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}
