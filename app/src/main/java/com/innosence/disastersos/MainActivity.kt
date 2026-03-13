package com.innosence.disastersos

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.innosence.disastersos.data.PreferencesHelper
import com.innosence.disastersos.data.SOSPacket
import com.innosence.disastersos.databinding.ActivityMainBinding
import com.innosence.disastersos.service.MeshService
import com.innosence.disastersos.ui.AlertAdapter
import com.innosence.disastersos.ui.DeviceAdapter

/*
 * ============================================================================
 *  MainActivity.kt — THE APP'S CONTROL CENTER
 * ============================================================================
 */
class MainActivity : AppCompatActivity(), MeshService.MeshServiceListener {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val TAG = "DisasterSOS"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var alertAdapter: AlertAdapter
    private lateinit var deviceAdapter: DeviceAdapter

    private var meshService: MeshService? = null
    private var isBound = false

    private lateinit var nodeId: String
    private lateinit var prefsHelper: PreferencesHelper
    private var currentRole: String = PreferencesHelper.ROLE_VICTIM
    
    private val hardwareCheckHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MeshService.MeshBinder
            meshService = binder.getService()
            meshService?.setListener(this@MainActivity)
            isBound = true
            updatePeerDisplay()
            Log.d(TAG, "Bound to MeshService ✅")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            meshService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsHelper = PreferencesHelper(this)
        currentRole = prefsHelper.getUserRole()
        nodeId = prefsHelper.getNodeId()

        setupAdapters()
        setupButtons()
        setupRoleUI()
        
        requestPermissions()
        checkAndPromptServices()
        startHardwareMonitoring()

        if (hasRequiredPermissions()) {
            startAndBindMeshService()
        }
    }

    private fun startAndBindMeshService() {
        if (isBound) return
        MeshService.start(this)
        val intent = Intent(this, MeshService::class.java)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    private fun hasRequiredPermissions(): Boolean {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        return permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startAndBindMeshService()
            } else {
                Toast.makeText(this, "Permissions required for mesh networking", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupAdapters() {
        // We use alertAdapter to show the list of all phones in the Knowledge Base
        alertAdapter = AlertAdapter(currentRole, ::onRescuedClicked) { lat, lon -> 
            startCompassActivity(lat, lon) 
        }
        binding.rvAlertLog.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = alertAdapter
        }

        deviceAdapter = DeviceAdapter()
        binding.rvConnectedDevices.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = deviceAdapter
        }
    }

    private fun setupButtons() {
        // Victim doesn't have a manual SOS button anymore per new workflow
        binding.btnSOS.visibility = View.GONE

        // Rescuer-only button: START RESCUE
        binding.btnFindVictims.setOnClickListener {
            meshService?.startRescueOperation()
            setStatusDotColor(R.color.info_blue)
            binding.tvStatus.text = "Rescue Operation Started"
            Toast.makeText(this, "Global Rescue Signal Sent!", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupRoleUI() {
        if (currentRole == PreferencesHelper.ROLE_RESCUER) {
            binding.tvRoleLabel.text = getString(R.string.role_rescuer)
            binding.tvRoleLabel.setTextColor(ContextCompat.getColor(this, R.color.safe_green))
            binding.tvRoleLabel.setBackgroundResource(R.drawable.bg_role_badge_rescuer)
            
            binding.btnFindVictims.visibility = View.VISIBLE
            binding.btnFindVictims.text = "START RESCUE"
            
            binding.llConnectedDevicesSection.visibility = View.VISIBLE
            setStatusDotColor(R.color.info_blue)
            binding.tvStatus.text = getString(R.string.status_ready)
        } else {
            binding.tvRoleLabel.text = getString(R.string.role_victim)
            binding.tvRoleLabel.setTextColor(ContextCompat.getColor(this, R.color.warning_amber))
            binding.tvRoleLabel.setBackgroundResource(R.drawable.bg_role_badge_victim)
            
            binding.btnFindVictims.visibility = View.GONE
            binding.llConnectedDevicesSection.visibility = View.GONE
            
            setStatusDotColor(R.color.text_secondary)
            binding.tvStatus.text = "Waiting for Rescuer signal..."
        }
        
        // Hide the victim list recycler as it's redundant with the knowledge base log
        binding.rvVictimList.visibility = View.GONE
        binding.tvVictimListTitle.visibility = View.GONE
    }

    // --- MeshServiceListener Callbacks ---

    override fun onKnowledgeBaseUpdated(data: List<SOSPacket>) {
        runOnUiThread {
            // Update the main log with all nodes known to the network
            val filteredData = data.filter { it.nodeId != nodeId } // Don't show ourselves
            
            if (filteredData.isNotEmpty()) {
                binding.rvAlertLog.visibility = View.VISIBLE
                binding.tvEmptyLog.visibility = View.GONE
                
                // AlertAdapter manages unique nodes automatically now
                filteredData.forEach { alertAdapter.addOrUpdateAlert(it) }
            }
        }
    }

    override fun onPeerCountChanged(count: Int) {
        runOnUiThread { updatePeerDisplay() }
    }

    private fun updatePeerDisplay() {
        val count = meshService?.getDiscoveredPeers()?.size ?: 0
        binding.tvPeerBadge.text = count.toString()
        if (count > 0) {
            binding.tvMeshStatus.text = getString(R.string.mesh_active)
            binding.tvPeerCount.text = getString(R.string.peers_connected, count)
            binding.tvMeshStatus.setTextColor(ContextCompat.getColor(this, R.color.safe_green))
        } else {
            binding.tvMeshStatus.text = getString(R.string.mesh_inactive)
            binding.tvPeerCount.text = getString(R.string.peers_none)
            binding.tvMeshStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
        
        val devices = meshService?.getDiscoveredPeers() ?: emptyList()
        deviceAdapter.updateDevices(devices)
        binding.rvConnectedDevices.visibility = if (devices.isNotEmpty()) View.VISIBLE else View.GONE
        binding.tvEmptyConnectedDevices.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onRescuedClicked(victimNodeId: String) {
        prefsHelper.markNodeRescued(victimNodeId)
        alertAdapter.removeAlertsByNodeId(victimNodeId)
        Toast.makeText(this, "Marked as Rescued!", Toast.LENGTH_SHORT).show()
    }

    private fun startCompassActivity(targetLat: Double, targetLng: Double) {
        val intent = Intent(this, CompassActivity::class.java).apply {
            putExtra("TARGET_LAT", targetLat)
            putExtra("TARGET_LNG", targetLng)
        }
        startActivity(intent)
    }

    private fun setStatusDotColor(colorResId: Int) {
        val dot = binding.viewStatusDot.background
        if (dot is GradientDrawable) {
            dot.setColor(ContextCompat.getColor(this, colorResId))
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        val notGranted = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (notGranted.isNotEmpty()) ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    private fun checkAndPromptServices() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (bluetoothManager.adapter?.isEnabled == false) startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            AlertDialog.Builder(this).setTitle("Enable GPS").setMessage("Location required for SOS.").setPositiveButton("Settings") { _, _ -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }.show()
        }
    }

    private fun startHardwareMonitoring() {
        hardwareCheckHandler.post(object : Runnable {
            override fun run() {
                val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val isBtOff = bluetoothManager.adapter?.isEnabled == false
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val isWifiOff = !wifiManager.isWifiEnabled
                
                binding.llHardwareWarning.visibility = if (isBtOff || isWifiOff) View.VISIBLE else View.GONE
                binding.tvHardwareWarningText.text = when {
                    isBtOff && isWifiOff -> "BT and Wi-Fi are OFF"
                    isBtOff -> "Bluetooth is OFF"
                    isWifiOff -> "Wi-Fi is OFF"
                    else -> ""
                }
                hardwareCheckHandler.postDelayed(this, 3000)
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            meshService?.setListener(null)
            unbindService(serviceConnection)
            isBound = false
        }
        hardwareCheckHandler.removeCallbacksAndMessages(null)
    }
}
