package com.innosence.disastersos

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.location.Location
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.innosence.disastersos.data.PreferencesHelper
import com.innosence.disastersos.data.SOSPacket
import com.innosence.disastersos.databinding.ActivityMapBinding
import com.innosence.disastersos.service.MeshService
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider
import java.io.File
import android.util.Log

/*
 * ============================================================================
 *  MapActivity.kt — FREE OFFLINE OPENSTREETMAP FOR DISASTER MESH TELEMETRY
 * ============================================================================
 *  - 100% Free: Uses OpenStreetMap (OSMDroid) without any API key or billing
 *  - Auto Caching: Tiles are cached locally in app cache for offline disaster use
 *  - Live Mesh Hops: Renders all discovered nodes and mesh relay paths in real time
 *  - Seamless Navigation: Tap any victim to immediately launch Compass guidance
 * ============================================================================
 */
class MapActivity : AppCompatActivity(), MeshService.MeshServiceListener {

    companion object {
        const val EXTRA_TARGET_LAT = "TARGET_LAT"
        const val EXTRA_TARGET_LNG = "TARGET_LNG"
        const val EXTRA_TARGET_NODE_ID = "TARGET_NODE_ID"
        private const val DEFAULT_ZOOM = 16.5
    }

    private lateinit var binding: ActivityMapBinding
    private lateinit var prefsHelper: PreferencesHelper
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var meshService: MeshService? = null
    private var isBound = false

    private var myLat: Double = 0.0
    private var myLng: Double = 0.0
    private var myNodeId: String = ""
    private var currentRole: String = PreferencesHelper.ROLE_VICTIM

    private var isTacticalDarkMode = true
    private var isMeshLinesVisible = true
    private var selectedPacket: SOSPacket? = null

    // Overlays
    private var myLocationMarker: Marker? = null
    private val nodeMarkers = mutableMapOf<String, Marker>()
    private val meshPolylines = mutableListOf<Polyline>()
    private var compassOverlay: CompassOverlay? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MeshService.MeshBinder
            meshService = binder.getService()
            meshService?.addListener(this@MapActivity)
            isBound = true
            
            // Populate initial knowledge base
            val packets = meshService?.getKnowledgeBase() ?: emptyList()
            updateMapWithPackets(packets)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            meshService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize OSMDroid configuration safely before inflating views
        try {
            val osmBaseDir = File(cacheDir, "osmdroid")
            if (!osmBaseDir.exists()) osmBaseDir.mkdirs()
            val osmTileDir = File(osmBaseDir, "tiles")
            if (!osmTileDir.exists()) osmTileDir.mkdirs()

            Configuration.getInstance().osmdroidBasePath = osmBaseDir
            Configuration.getInstance().osmdroidTileCache = osmTileDir
            val prefs = getSharedPreferences("${packageName}_osm", Context.MODE_PRIVATE)
            Configuration.getInstance().load(this, prefs)
            Configuration.getInstance().userAgentValue = "DisasterSOS-Mesh/1.0 (Android; Emergency Mesh System)"
        } catch (e: Exception) {
            Log.e("MapActivity", "Error setting up OSM configuration", e)
        }

        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsHelper = PreferencesHelper(this)
        myNodeId = prefsHelper.getNodeId()
        currentRole = prefsHelper.getUserRole()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupMap()
        setupControls()
        fetchCurrentLocation()
        bindMeshService()

        // Check if opened with a specific target to focus on
        val targetLat = intent.getDoubleExtra(EXTRA_TARGET_LAT, 0.0)
        val targetLng = intent.getDoubleExtra(EXTRA_TARGET_LNG, 0.0)
        val targetNodeId = intent.getStringExtra(EXTRA_TARGET_NODE_ID)

        if (targetLat != 0.0 && targetLng != 0.0) {
            val targetPoint = GeoPoint(targetLat, targetLng)
            binding.mapView.controller.setZoom(17.5)
            binding.mapView.controller.animateTo(targetPoint)
            if (!targetNodeId.isNullOrEmpty()) {
                showNodeDetails(
                    SOSPacket(
                        nodeId = targetNodeId,
                        latitude = targetLat,
                        longitude = targetLng,
                        batteryLevel = 100,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private fun setupMap() {
        try {
            binding.mapView.apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                controller.setZoom(DEFAULT_ZOOM)
            }
        } catch (e: Exception) {
            Log.e("MapActivity", "Map setup error", e)
        }

        // Add Compass Overlay safely (gracefully handles devices without compass sensor)
        try {
            val compass = CompassOverlay(this, InternalCompassOrientationProvider(this), binding.mapView)
            compass.enableCompass()
            binding.mapView.overlays.add(compass)
            compassOverlay = compass
        } catch (e: Exception) {
            Log.e("MapActivity", "Compass sensor not supported on device", e)
        }

        // Apply initial Tactical Dark mode styling
        applyTacticalDarkMode(isTacticalDarkMode)
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        // My Location Center button
        binding.fabMyLocation.setOnClickListener {
            if (myLat != 0.0 && myLng != 0.0) {
                val myPoint = GeoPoint(myLat, myLng)
                binding.mapView.controller.animateTo(myPoint)
                binding.mapView.controller.setZoom(17.5)
            } else {
                fetchCurrentLocation()
                Toast.makeText(this, "Acquiring current GPS location...", Toast.LENGTH_SHORT).show()
            }
        }

        // Fit All Discovered Nodes + User button
        binding.fabFitAll.setOnClickListener {
            fitAllMarkersInView()
        }

        // Toggle Tactical Dark vs Day map mode
        binding.fabMapTheme.setOnClickListener {
            isTacticalDarkMode = !isTacticalDarkMode
            applyTacticalDarkMode(isTacticalDarkMode)
            val modeName = if (isTacticalDarkMode) "Tactical Dark" else "Standard Day"
            Toast.makeText(this, "Map Style: $modeName", Toast.LENGTH_SHORT).show()
        }

        // Toggle Mesh Relay Lines
        binding.fabToggleMeshLines.setOnClickListener {
            isMeshLinesVisible = !isMeshLinesVisible
            val state = if (isMeshLinesVisible) "visible" else "hidden"
            Toast.makeText(this, "Mesh hop lines $state", Toast.LENGTH_SHORT).show()
            val packets = meshService?.getKnowledgeBase() ?: emptyList()
            updateMapWithPackets(packets)
        }

        // Selected Node Sheet Close button
        binding.btnCloseSelectedNode.setOnClickListener {
            binding.cardSelectedNode.visibility = View.GONE
            selectedPacket = null
        }

        // Compass Navigate Button
        binding.btnNavigateCompass.setOnClickListener {
            val packet = selectedPacket
            if (packet != null && packet.latitude != 0.0 && packet.longitude != 0.0) {
                val intent = Intent(this, CompassActivity::class.java).apply {
                    putExtra("TARGET_LAT", packet.latitude)
                    putExtra("TARGET_LNG", packet.longitude)
                }
                startActivity(intent)
            }
        }

        // Rescuer Mark Rescued Button
        binding.btnMarkRescued.setOnClickListener {
            val packet = selectedPacket
            if (packet != null) {
                prefsHelper.markNodeRescued(packet.nodeId)
                Toast.makeText(this, "Node marked as RESCUED! ✅", Toast.LENGTH_SHORT).show()
                binding.cardSelectedNode.visibility = View.GONE
                meshService?.forceStatusRefresh()
            }
        }
    }

    private fun applyTacticalDarkMode(enabled: Boolean) {
        try {
            val tilesOverlay = binding.mapView.overlayManager?.tilesOverlay
            if (tilesOverlay != null) {
                if (enabled) {
                    val inverseMatrix = ColorMatrix(
                        floatArrayOf(
                            -1.0f, 0.0f, 0.0f, 0.0f, 255.0f,
                            0.0f, -1.0f, 0.0f, 0.0f, 255.0f,
                            0.0f, 0.0f, -1.0f, 0.0f, 255.0f,
                            0.0f, 0.0f, 0.0f, 1.0f, 0.0f
                        )
                    )
                    tilesOverlay.setColorFilter(ColorMatrixColorFilter(inverseMatrix))
                } else {
                    tilesOverlay.setColorFilter(null)
                }
                binding.mapView.invalidate()
            }
        } catch (e: Exception) {
            Log.e("MapActivity", "Error applying map filter", e)
        }
    }

    private fun fetchCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    myLat = location.latitude
                    myLng = location.longitude
                    updateMyLocationMarker()
                }
            }

        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null && (myLat == 0.0 && myLng == 0.0)) {
                myLat = location.latitude
                myLng = location.longitude
                updateMyLocationMarker()
            }
        }
    }

    private fun updateMyLocationMarker() {
        if (myLat == 0.0 && myLng == 0.0) return

        val myPoint = GeoPoint(myLat, myLng)
        if (myLocationMarker == null) {
            val marker = Marker(binding.mapView).apply {
                position = myPoint
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = ContextCompat.getDrawable(this@MapActivity, R.drawable.ic_map_my_loc)
                title = "You (${if (currentRole == PreferencesHelper.ROLE_RESCUER) "Rescuer" else "Victim"})"
                snippet = "%.4f, %.4f".format(myLat, myLng)
                setOnMarkerClickListener { m, _ ->
                    m.showInfoWindow()
                    true
                }
            }
            binding.mapView.overlays.add(marker)
            myLocationMarker = marker

            // Center camera initially on user if no target was supplied
            val targetLat = intent.getDoubleExtra(EXTRA_TARGET_LAT, 0.0)
            if (targetLat == 0.0) {
                binding.mapView.controller.setCenter(myPoint)
            }
        } else {
            myLocationMarker?.position = myPoint
        }
        binding.mapView.invalidate()
    }

    private fun updateMapWithPackets(packets: List<SOSPacket>) {
        runOnUiThread {
            val otherNodes = packets.filter { it.nodeId != myNodeId && it.latitude != 0.0 && it.longitude != 0.0 }
            binding.tvNodeCountBadge.text = "${otherNodes.size} Nodes"

            // Remove old polylines
            meshPolylines.forEach { binding.mapView.overlays.remove(it) }
            meshPolylines.clear()

            // Keep track of active IDs
            val activeNodeIds = otherNodes.map { it.nodeId }.toSet()

            // Remove markers for nodes no longer in range
            val iterator = nodeMarkers.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key !in activeNodeIds) {
                    binding.mapView.overlays.remove(entry.value)
                    iterator.remove()
                }
            }

            // Update or add markers for active nodes
            for (packet in otherNodes) {
                val nodePoint = GeoPoint(packet.latitude, packet.longitude)
                var marker = nodeMarkers[packet.nodeId]

                val isRescued = prefsHelper.isNodeRescued(packet.nodeId)
                val isRescueActive = packet.packetType == SOSPacket.TYPE_RESCUE_START

                val markerDrawable = when {
                    isRescued -> ContextCompat.getDrawable(this, R.drawable.ic_map_safe)
                    isRescueActive -> ContextCompat.getDrawable(this, R.drawable.ic_map_rescuer)
                    else -> ContextCompat.getDrawable(this, R.drawable.ic_map_victim_sos)
                }

                if (marker == null) {
                    marker = Marker(binding.mapView).apply {
                        position = nodePoint
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        icon = markerDrawable
                        setOnMarkerClickListener { _, _ ->
                            showNodeDetails(packet)
                            binding.mapView.controller.animateTo(nodePoint)
                            true
                        }
                    }
                    binding.mapView.overlays.add(marker)
                    nodeMarkers[packet.nodeId] = marker
                } else {
                    marker.position = nodePoint
                    marker.icon = markerDrawable
                }

                // Draw Mesh Relay Hop Lines from current user to node
                if (isMeshLinesVisible && myLat != 0.0 && myLng != 0.0) {
                    val line = Polyline(binding.mapView).apply {
                        addPoint(GeoPoint(myLat, myLng))
                        addPoint(nodePoint)
                        outlinePaint.strokeWidth = if (packet.hopCount <= 1) 5f else 3.5f
                        outlinePaint.color = when {
                            !isRescueActive && !isRescued -> Color.parseColor("#FFFF5252") // Red SOS link
                            packet.hopCount <= 1 -> Color.parseColor("#FF42A5F5")          // Direct Blue link
                            else -> Color.parseColor("#FFFFAB40")                          // Amber multi-hop link
                        }
                    }
                    binding.mapView.overlays.add(line)
                    meshPolylines.add(line)
                }
            }

            binding.mapView.invalidate()
        }
    }

    private fun showNodeDetails(packet: SOSPacket) {
        selectedPacket = packet
        binding.cardSelectedNode.visibility = View.VISIBLE

        val isRescued = prefsHelper.isNodeRescued(packet.nodeId)
        val isRescueActive = packet.packetType == SOSPacket.TYPE_RESCUE_START

        // Node Title
        val roleTitle = if (isRescueActive) "Rescuer" else "Victim"
        val displayName = if (packet.deviceName.isNotBlank()) packet.deviceName else "$roleTitle #${packet.nodeId.takeLast(4)}"
        binding.tvSelectedNodeTitle.text = displayName

        // Role & Status Badge
        when {
            isRescued -> {
                binding.tvSelectedNodeRoleBadge.text = "RESCUED / SAFE"
                binding.tvSelectedNodeRoleBadge.setTextColor(ContextCompat.getColor(this, R.color.safe_green))
                binding.tvSelectedNodeRoleBadge.setBackgroundResource(R.drawable.bg_role_badge_rescuer)
            }
            isRescueActive -> {
                binding.tvSelectedNodeRoleBadge.text = "RESCUER ACTIVE"
                binding.tvSelectedNodeRoleBadge.setTextColor(ContextCompat.getColor(this, R.color.info_blue))
                binding.tvSelectedNodeRoleBadge.setBackgroundResource(R.drawable.bg_role_badge_rescuer)
            }
            else -> {
                binding.tvSelectedNodeRoleBadge.text = "SOS EMERGENCY"
                binding.tvSelectedNodeRoleBadge.setTextColor(ContextCompat.getColor(this, R.color.emergency_red))
                binding.tvSelectedNodeRoleBadge.setBackgroundResource(R.drawable.bg_role_badge_victim)
            }
        }

        // Distance & Bearing calculation
        if (myLat != 0.0 && myLng != 0.0) {
            val results = FloatArray(2)
            Location.distanceBetween(myLat, myLng, packet.latitude, packet.longitude, results)
            val distanceMeters = results[0]
            val distanceStr = if (distanceMeters < 1000) {
                "${distanceMeters.toInt()} m away"
            } else {
                "%.1f km away".format(distanceMeters / 1000)
            }
            binding.tvSelectedNodeDistance.text = "📍 $distanceStr"
        } else {
            binding.tvSelectedNodeDistance.text = "📍 Location locked"
        }

        // Hop Info
        binding.tvSelectedNodeHopInfo.text = if (packet.hopCount <= 1) {
            "⚡ Direct 1-Hop Mesh"
        } else {
            "📶 ${packet.hopCount} Hops via Mesh Relay"
        }

        // Coordinates & Timestamp
        binding.tvSelectedNodeDetails.text = "Lat: %.4f, Lng: %.4f • Battery: %d%%".format(
            packet.latitude, packet.longitude, packet.batteryLevel
        )

        // Rescuer-specific action button
        if (currentRole == PreferencesHelper.ROLE_RESCUER && !isRescueActive && !isRescued) {
            binding.btnMarkRescued.visibility = View.VISIBLE
        } else {
            binding.btnMarkRescued.visibility = View.GONE
        }
    }

    private fun fitAllMarkersInView() {
        val points = mutableListOf<GeoPoint>()
        if (myLat != 0.0 && myLng != 0.0) {
            points.add(GeoPoint(myLat, myLng))
        }
        nodeMarkers.values.forEach { points.add(it.position) }

        if (points.isEmpty()) {
            Toast.makeText(this, "No nodes or GPS location available yet", Toast.LENGTH_SHORT).show()
            return
        }

        if (points.size == 1) {
            binding.mapView.controller.animateTo(points[0])
            binding.mapView.controller.setZoom(17.0)
            return
        }

        var minLat = 90.0
        var maxLat = -90.0
        var minLng = 180.0
        var maxLng = -180.0

        for (p in points) {
            if (p.latitude < minLat) minLat = p.latitude
            if (p.latitude > maxLat) maxLat = p.latitude
            if (p.longitude < minLng) minLng = p.longitude
            if (p.longitude > maxLng) maxLng = p.longitude
        }

        // Add 20% margin
        val latMargin = (maxLat - minLat).coerceAtLeast(0.002) * 0.2
        val lngMargin = (maxLng - minLng).coerceAtLeast(0.002) * 0.2

        val box = BoundingBox(
            maxLat + latMargin,
            maxLng + lngMargin,
            minLat - latMargin,
            minLng - lngMargin
        )
        binding.mapView.zoomToBoundingBox(box, true, 100)
    }

    // --- MeshServiceListener Callbacks ---

    override fun onKnowledgeBaseUpdated(data: List<SOSPacket>) {
        updateMapWithPackets(data)
    }

    override fun onPeerCountChanged(count: Int) {
        runOnUiThread {
            val packets = meshService?.getKnowledgeBase() ?: emptyList()
            updateMapWithPackets(packets)
        }
    }

    private fun bindMeshService() {
        val intent = Intent(this, MeshService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        try {
            binding.mapView.onResume()
            compassOverlay?.enableCompass()
            fetchCurrentLocation()
        } catch (e: Exception) {
            Log.e("MapActivity", "onResume error", e)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            binding.mapView.onPause()
            compassOverlay?.disableCompass()
        } catch (e: Exception) {
            Log.e("MapActivity", "onPause error", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (isBound) {
                meshService?.removeListener(this@MapActivity)
                unbindService(serviceConnection)
                isBound = false
            }
            binding.mapView.onDetach()
        } catch (e: Exception) {
            Log.e("MapActivity", "onDestroy error", e)
        }
    }
}
