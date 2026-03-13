package com.innosence.disastersos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import com.google.android.material.button.MaterialButton
import com.innosence.disastersos.R

class CompassActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null

    private var targetLat: Double = 0.0
    private var targetLng: Double = 0.0
    private var myLat: Double = 0.0
    private var myLng: Double = 0.0

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)

    private lateinit var ivCompassArrow: ImageView
    private lateinit var tvDistanceText: TextView
    private lateinit var fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient
    
    private val locationCallback = object : com.google.android.gms.location.LocationCallback() {
        override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
            val location = result.lastLocation
            if (location != null) {
                myLat = location.latitude
                myLng = location.longitude
                updateDistance()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compass)

        ivCompassArrow = findViewById(R.id.ivCompassArrow)
        tvDistanceText = findViewById(R.id.tvDistanceText)

        targetLat = intent.getDoubleExtra("TARGET_LAT", 0.0)
        targetLng = intent.getDoubleExtra("TARGET_LNG", 0.0)

        findViewById<MaterialButton>(R.id.btnEndNavigation).setOnClickListener {
            finish()
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startLocationUpdates()
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val request = com.google.android.gms.location.LocationRequest.Builder(
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 2000
            ).setMinUpdateDistanceMeters(1f).build()
            
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        magnetometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, gravity, 0, event.values.size)
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, geomagnetic, 0, event.values.size)
        }

        val R = FloatArray(9)
        val I = FloatArray(9)

        if (SensorManager.getRotationMatrix(R, I, gravity, geomagnetic)) {
            val orientation = FloatArray(3)
            SensorManager.getOrientation(R, orientation)

            val azimuthInRadians = orientation[0]
            val azimuthInDegrees = Math.toDegrees(azimuthInRadians.toDouble()).toFloat()

            if (myLat != 0.0 && myLng != 0.0) {
                // Calculate bearing to target
                val myLocation = Location("").apply {
                    latitude = myLat
                    longitude = myLng
                }
                val targetLocation = Location("").apply {
                    latitude = targetLat
                    longitude = targetLng
                }

                val bearingToTarget = myLocation.bearingTo(targetLocation)

                // Calculate the direction the arrow should point relative to the phone's current orientation
                val arrowRotation = bearingToTarget - azimuthInDegrees

                ivCompassArrow.rotation = arrowRotation
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not actively used, but required by interface
    }

    private fun updateDistance() {
        val myLocation = Location("").apply {
            latitude = myLat
            longitude = myLng
        }
        val targetLocation = Location("").apply {
            latitude = targetLat
            longitude = targetLng
        }

        val distance = myLocation.distanceTo(targetLocation)
        tvDistanceText.text = "%.0f meters".format(distance)
    }
}
