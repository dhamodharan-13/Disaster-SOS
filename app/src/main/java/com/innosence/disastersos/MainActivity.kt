package com.innosence.disastersos

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.net.wifi.p2p.WifiP2pManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.gson.Gson
import com.innosence.disastersos.data.SOSPacket
import com.innosence.disastersos.databinding.ActivityMainBinding
import com.innosence.disastersos.mesh.MeshManager
import com.innosence.disastersos.ml.DistressDetector
import com.innosence.disastersos.ui.AlertAdapter
import java.util.UUID

/*
 * ============================================================================
 *  MainActivity.kt — THE APP'S BRAIN / CONTROL CENTER
 * ============================================================================
 *
 *  WHAT IS THIS?
 *  =============
 *  This is the FIRST thing that runs when the user opens the app.
 *  It's like the director of a movie — it doesn't act itself, but it
 *  tells everyone else what to do:
 *    - "Hey UI, show the dashboard"
 *    - "Hey Android, give me microphone permission"
 *    - "Hey GPS, what are my coordinates?"
 *    - "Hey MeshManager, start looking for nearby phones"
 *    - "Hey DistressDetector, start listening for screams"
 *
 *  WHY IS IT IMPORTANT?
 *  ====================
 *  Without this file, the app has no entry point — Android wouldn't know
 *  what to show when the user taps the app icon.
 *
 *  HOW DOES IT WORK? (Activity Lifecycle)
 *  ======================================
 *  Android Activities have a "lifecycle" — a series of stages:
 *
 *    onCreate()  → App is opening for the first time. Set up the UI.
 *    onResume()  → App is visible and in the foreground.
 *    onPause()   → User switched to another app. Save state.
 *    onDestroy() → App is closing. Clean up resources.
 *
 *  We put our setup code in onCreate() because it runs exactly ONCE
 *  when the Activity is first created.
 * ============================================================================
 */

class MainActivity : AppCompatActivity() {

    // ─────────────────────────────────────────────────────────────────
    //  COMPANION OBJECT — Constants shared across all instances
    // ─────────────────────────────────────────────────────────────────
    companion object {
        /*
         * Permission request codes — when we ask the user for permission,
         * Android sends back a number so we know WHICH permission they
         * responded to. These are our custom ID numbers.
         */
        private const val PERMISSION_REQUEST_CODE = 1001

        // Tag for log messages — helps us filter our app's logs in Android Studio
        private const val TAG = "DisasterSOS"
    }

    // ─────────────────────────────────────────────────────────────────
    //  PROPERTIES (Variables that live as long as the Activity exists)
    // ─────────────────────────────────────────────────────────────────

    /*
     * VIEW BINDING — A type-safe way to access UI elements.
     *
     * Old way (dangerous):
     *   val button = findViewById<Button>(R.id.btnSOS)
     *   // Can crash if ID doesn't exist or is wrong type!
     *
     * New way (View Binding):
     *   binding.btnSOS  ← compiler-checked, cannot crash!
     *
     * "lateinit" means "I promise to initialize this later (in onCreate)
     * before using it." Kotlin requires this because the binding can't
     * be created until the layout is inflated.
     */
    private lateinit var binding: ActivityMainBinding

    // The RecyclerView adapter — manages the list of SOS alert cards
    private lateinit var alertAdapter: AlertAdapter

    // GPS provider — Google's best way to get location on Android
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // Our custom mesh networking manager (we'll build this in Phase 3)
    private var meshManager: MeshManager? = null

    // Our AI distress detector (we'll build this in Phase 2)
    private var distressDetector: DistressDetector? = null

    // The phone's last known GPS coordinates
    private var lastLatitude: Double = 0.0
    private var lastLongitude: Double = 0.0

    /*
     * NODE ID — A unique identifier for THIS phone.
     * Generated once using UUID (Universally Unique Identifier).
     * UUID generates a random string like "a1b2c3d4-e5f6-7890-..."
     * that is mathematically guaranteed to be unique across all devices.
     *
     * WHY: So when a rescuer receives 5 SOS alerts, they know which
     * ones came from the same victim and which are from different people.
     */
    private val nodeId: String = UUID.randomUUID().toString().take(12)

    // Tracks whether the AI listener is currently active
    private var isListening: Boolean = false

    // JSON converter — turns SOSPacket objects into JSON strings and back
    private val gson = Gson()

    // ─────────────────────────────────────────────────────────────────
    //  onCreate() — THE APP'S STARTING POINT
    // ─────────────────────────────────────────────────────────────────
    /*
     * This function runs ONCE when the app opens. Think of it as the
     * "setup" phase before a cricket match — laying out the pitch,
     * placing the stumps, warming up.
     *
     * @param savedInstanceState — If the app was killed and is being
     * restarted, this contains the saved state. null on first launch.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        /*
         * STEP 1: Inflate the layout
         * ──────────────────────────
         * "Inflate" means "read the XML layout file and convert it into
         * actual View objects on screen." After this line, the binding
         * object gives us type-safe access to every UI element by its ID.
         */
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        /*
         * STEP 2: Set up the Alert Log (RecyclerView)
         * ────────────────────────────────────────────
         * LinearLayoutManager says "display items in a vertical list"
         * (as opposed to a grid or staggered layout).
         */
        alertAdapter = AlertAdapter()
        binding.rvAlertLog.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = alertAdapter
        }

        /*
         * STEP 3: Initialize the GPS provider
         * ─────────────────────────────────────
         * FusedLocationProviderClient is Google's recommended way to get
         * GPS on Android. "Fused" means it intelligently combines GPS
         * satellites + Wi-Fi + cell towers for the best possible accuracy.
         * Since we're offline, it will fall back to GPS-only (which works
         * fine without internet — GPS is a satellite system, not internet!).
         */
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        /*
         * STEP 4: Set up button click listeners
         * ──────────────────────────────────────
         * "setOnClickListener" is like setting a trap:
         * "When someone touches this button, execute this code block."
         */
        setupButtons()

        /*
         * STEP 5: Request permissions
         * ───────────────────────────
         * Android requires that apps ASK the user for dangerous permissions
         * (mic, GPS, etc.) at runtime, not just declare them in AndroidManifest.
         * This is a safety feature — the user can deny any permission.
         */
        requestPermissions()

        /*
         * STEP 6: Update the battery display
         * ───────────────────────────────────
         */
        updateBatteryLevel()

        /*
         * STEP 7: Initialize the mesh manager
         * ────────────────────────────────────
         * Sets up Wi-Fi Direct for peer discovery.
         */
        initializeMeshManager()
    }

    // ─────────────────────────────────────────────────────────────────
    //  BUTTON SETUP — What happens when the user taps buttons
    // ─────────────────────────────────────────────────────────────────
    private fun setupButtons() {

        /*
         * SOS BUTTON — The big red emergency button.
         * When pressed, it immediately:
         *   1. Gets the current GPS location
         *   2. Creates an SOSPacket with distressType = "MANUAL_BUTTON"
         *   3. Broadcasts it over the mesh network
         *   4. Adds it to the local alert log
         *
         * WHY "MANUAL_BUTTON" type? To distinguish from AI-detected
         * distress. A manual press means the victim is conscious and
         * able to interact with the phone.
         */
        binding.btnSOS.setOnClickListener {
            sendManualSOS()
        }

        /*
         * TOGGLE LISTENING BUTTON — Starts/stops the AI listener.
         * When active, the phone's microphone feeds audio into the
         * TinyML model every few seconds. When inactive, it saves battery.
         */
        binding.btnToggleListening.setOnClickListener {
            toggleListening()
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  MANUAL SOS — User pressed the panic button
    // ─────────────────────────────────────────────────────────────────
    private fun sendManualSOS() {
        /*
         * Step 1: Get the latest GPS coordinates.
         * We call fetchLocation() which updates lastLatitude/lastLongitude.
         * Then we create and broadcast the SOS packet.
         */
        fetchLocation { lat, lon ->
            val sosPacket = SOSPacket(
                nodeId = nodeId,
                latitude = lat,
                longitude = lon,
                distressType = "MANUAL_BUTTON",
                confidence = 100,  // Manual press = 100% confidence (human did it)
                timestamp = System.currentTimeMillis(),
                batteryLevel = getBatteryLevel(),
                hopCount = 0       // This is the origin — hasn't bounced yet
            )

            // Add to our own alert log so the user sees confirmation
            addAlertToLog(sosPacket)

            // Broadcast over the mesh network to nearby phones
            meshManager?.broadcastSOS(sosPacket)

            // Update the status indicator to show SOS was sent
            runOnUiThread {
                binding.tvStatusIcon.text = "🔴"
                binding.tvStatus.text = "🚨 SOS SENT!"
                binding.tvStatus.setTextColor(
                    ContextCompat.getColor(this, R.color.emergency_red)
                )
                Toast.makeText(this, "SOS Alert Broadcasted!", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  TOGGLE AI LISTENING — Start/Stop the distress detector
    // ─────────────────────────────────────────────────────────────────
    private fun toggleListening() {
        if (isListening) {
            // Stop listening
            distressDetector?.stopListening()
            isListening = false
            binding.btnToggleListening.text = "🎤 Start Listening"
            binding.tvStatusIcon.text = "⏸"
            binding.tvStatus.text = getString(R.string.status_idle)
            binding.tvStatus.setTextColor(
                ContextCompat.getColor(this, R.color.text_secondary)
            )
        } else {
            // Start listening — initialize the detector if needed
            if (distressDetector == null) {
                distressDetector = DistressDetector(this) { packet ->
                    /*
                     * This lambda (callback function) is called whenever
                     * the AI detects distress with confidence above the threshold.
                     *
                     * Think of it like setting an alarm:
                     * "When the AI hears a scream, run this code."
                     */
                    runOnUiThread {
                        addAlertToLog(packet)
                        meshManager?.broadcastSOS(packet)
                        binding.tvStatusIcon.text = "🔴"
                        binding.tvStatus.text = getString(R.string.status_alert_detected)
                        binding.tvStatus.setTextColor(
                            ContextCompat.getColor(this, R.color.emergency_red)
                        )
                    }
                }
            }
            distressDetector?.startListening()
            isListening = true
            binding.btnToggleListening.text = "⏹ Stop Listening"
            binding.tvStatusIcon.text = "🟢"
            binding.tvStatus.text = getString(R.string.status_listening)
            binding.tvStatus.setTextColor(
                ContextCompat.getColor(this, R.color.safe_green)
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  MESH MANAGER INITIALIZATION
    // ─────────────────────────────────────────────────────────────────
    private fun initializeMeshManager() {
        /*
         * MeshManager handles all Wi-Fi Direct operations.
         * We pass it two callbacks:
         *   1. onSOSReceived — Called when another phone sends us an SOS
         *   2. onPeersChanged — Called when the number of nearby phones changes
         */
        meshManager = MeshManager(
            context = this,
            onSOSReceived = { packet ->
                runOnUiThread {
                    addAlertToLog(packet)

                    // If this SOS hasn't reached max hops, relay it further!
                    if (!packet.hasReachedMaxHops()) {
                        meshManager?.broadcastSOS(packet.incrementHop())
                    }
                }
            },
            onPeersChanged = { peerCount ->
                runOnUiThread {
                    updatePeerCount(peerCount)
                }
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────
    //  UI UPDATE HELPERS
    // ─────────────────────────────────────────────────────────────────

    /**
     * Adds an SOSPacket to the alert log and makes it visible.
     */
    private fun addAlertToLog(packet: SOSPacket) {
        runOnUiThread {
            alertAdapter.addAlert(packet)
            // Show the RecyclerView and hide the "No alerts" message
            binding.rvAlertLog.visibility = android.view.View.VISIBLE
            binding.tvEmptyLog.visibility = android.view.View.GONE
            // Scroll to the newest alert at the top
            binding.rvAlertLog.scrollToPosition(0)
        }
    }

    /**
     * Updates the peer count display when phones join/leave the mesh.
     */
    private fun updatePeerCount(count: Int) {
        binding.tvPeerBadge.text = count.toString()
        if (count > 0) {
            binding.tvMeshStatus.text = getString(R.string.mesh_active)
            binding.tvPeerCount.text = getString(R.string.peers_connected, count)
            binding.tvMeshStatus.setTextColor(
                ContextCompat.getColor(this, R.color.safe_green)
            )
        } else {
            binding.tvMeshStatus.text = getString(R.string.mesh_inactive)
            binding.tvPeerCount.text = getString(R.string.peers_none)
            binding.tvMeshStatus.setTextColor(
                ContextCompat.getColor(this, R.color.text_secondary)
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  GPS LOCATION
    // ─────────────────────────────────────────────────────────────────

    /**
     * Fetches the phone's current GPS coordinates.
     *
     * HOW IT WORKS:
     * GPS uses satellites orbiting Earth. Your phone receives signals
     * from 4+ satellites and triangulates its position. This works
     * completely WITHOUT internet or cell towers.
     *
     * @param callback — A function to call with the coordinates once found
     */
    private fun fetchLocation(callback: (Double, Double) -> Unit) {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // If permission denied, use last known coordinates
            callback(lastLatitude, lastLongitude)
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                lastLatitude = location.latitude
                lastLongitude = location.longitude
                binding.tvGpsCoordinates.text = "%.4f, %.4f".format(
                    lastLatitude, lastLongitude
                )
            }
            callback(lastLatitude, lastLongitude)
        }.addOnFailureListener {
            // GPS failed — use last known coordinates
            callback(lastLatitude, lastLongitude)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  BATTERY LEVEL
    // ─────────────────────────────────────────────────────────────────

    /**
     * Gets the current battery percentage.
     *
     * WHY include battery in SOS?
     * If a victim's phone is at 3%, rescuers know they might lose
     * contact soon and should prioritize reaching them quickly.
     */
    private fun getBatteryLevel(): Int {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun updateBatteryLevel() {
        val level = getBatteryLevel()
        binding.tvBatteryLevel.text = "🔋 $level%"
        // Color code: green if good, amber if medium, red if critical
        val color = when {
            level > 50 -> R.color.safe_green
            level > 20 -> R.color.warning_amber
            else -> R.color.emergency_red
        }
        binding.tvBatteryLevel.setTextColor(ContextCompat.getColor(this, color))
    }

    // ─────────────────────────────────────────────────────────────────
    //  PERMISSIONS
    // ─────────────────────────────────────────────────────────────────

    /**
     * Requests all necessary runtime permissions.
     *
     * WHY RUNTIME PERMISSIONS?
     * Since Android 6.0, declaring permissions in AndroidManifest is NOT
     * enough. The app must also ask the user at runtime. This protects
     * users from apps that silently access their microphone or location.
     *
     * Our app needs: Microphone (for AI), GPS (for coordinates),
     * and Nearby Wi-Fi Devices (for mesh network).
     */
    private fun requestPermissions() {
        val permissionsNeeded = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        // Android 13+ requires a special permission for nearby Wi-Fi
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsNeeded.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        // Filter out permissions already granted
        val notGranted = permissionsNeeded.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                notGranted.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        } else {
            // All permissions already granted — start location updates
            fetchLocation { _, _ -> }
        }
    }

    /**
     * Called when the user responds to the permission dialog.
     * If they granted permissions, we start the location services.
     * If they denied, we show a toast explaining why we need them.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                fetchLocation { _, _ -> }
                Toast.makeText(this, "All permissions granted ✅", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "⚠️ Some permissions were denied. The app needs mic, GPS, and Wi-Fi to function.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  LIFECYCLE — Clean up when the app closes
    // ─────────────────────────────────────────────────────────────────
    override fun onDestroy() {
        super.onDestroy()
        /*
         * When the app is closed, we MUST stop the detector and mesh
         * to free up the microphone and Wi-Fi hardware for other apps.
         * Not doing this can cause memory leaks and battery drain.
         */
        distressDetector?.stopListening()
        meshManager?.cleanup()
    }
}
