package com.innosence.disastersos.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.*
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.innosence.disastersos.MainActivity
import com.innosence.disastersos.data.PreferencesHelper
import com.innosence.disastersos.data.SOSPacket
import com.innosence.disastersos.mesh.BluetoothMeshManager
import com.innosence.disastersos.mesh.MeshManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class MeshService : Service() {

    private val binder = MeshBinder()
    private lateinit var prefsHelper: PreferencesHelper
    private lateinit var nodeId: String
    private var currentRole: String = PreferencesHelper.ROLE_VICTIM

    private var meshManager: MeshManager? = null
    private var bluetoothMeshManager: BluetoothMeshManager? = null
    private val knowledgeBase = ConcurrentHashMap<String, SOSPacket>()

    private var isGossipActive = false
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val handler = Handler(Looper.getMainLooper())
    private var listener: MeshServiceListener? = null

    private var lastBroadcastPacket: SOSPacket? = null
    private var currentGossipInterval = GOSSIP_INTERVAL_NORMAL

    interface MeshServiceListener {
        fun onKnowledgeBaseUpdated(data: List<SOSPacket>)
        fun onPeerCountChanged(count: Int)
    }

    inner class MeshBinder : Binder() {
        fun getService(): MeshService = this@MeshService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        private const val TAG = "MeshService"
        private const val NOTIFICATION_CHANNEL_ID = "disaster_sos_mesh"
        private const val NOTIFICATION_ID = 1

        private const val GOSSIP_INTERVAL_NORMAL = 15_000L
        private const val GOSSIP_INTERVAL_URGENT = 3_000L
        private const val GOSSIP_INTERVAL_CRITICAL = 5_000L
        
        private const val GPS_CHANGE_THRESHOLD = 0.0001
        private const val BATTERY_CHANGE_THRESHOLD = 2

        fun start(context: Context) {
            val intent = Intent(context, MeshService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        prefsHelper = PreferencesHelper(this)
        nodeId = prefsHelper.getNodeId()
        currentRole = prefsHelper.getUserRole()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        if (currentRole == PreferencesHelper.ROLE_RESCUER) {
            isGossipActive = true
        }

        initializeNetworking()
        startGossipLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        
        // Android 14 (SDK 34) fix: Specify foreground service types in startForeground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or 
                       ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        return START_STICKY
    }

    private fun initializeNetworking() {
        meshManager = MeshManager(
            context = this,
            onSOSReceived = { packet -> handleIncomingGossip(packet) },
            onPeersChanged = { count -> listener?.onPeerCountChanged(count) }
        )

        bluetoothMeshManager = BluetoothMeshManager(
            context = this,
            nodeId = nodeId,
            onPacketReceived = { packet -> handleIncomingGossip(packet) },
            onPeerDiscovered = { _, rssi -> 
                updateAdaptiveInterval(rssi)
                val count = bluetoothMeshManager?.getConnectedPeerCount() ?: 0
                listener?.onPeerCountChanged(count)
            }
        )
        bluetoothMeshManager?.initialize()
    }

    private fun handleIncomingGossip(packet: SOSPacket) {
        if (packet.packetType == SOSPacket.TYPE_RESCUE_START && !isGossipActive) {
            isGossipActive = true
            broadcastOwnStatus()
        }

        val existing = knowledgeBase[packet.nodeId]
        if (existing != null && existing.sequenceNumber >= packet.sequenceNumber) return

        knowledgeBase[packet.nodeId] = packet
        listener?.onKnowledgeBaseUpdated(knowledgeBase.values.toList())

        if (!packet.hasReachedMaxHops()) {
            val relayPacket = packet.incrementHop()
            if (packet.needsPriority()) {
                Thread {
                    meshManager?.broadcastSOS(relayPacket)
                    bluetoothMeshManager?.broadcastPacket(relayPacket)
                }.start()
            } else {
                meshManager?.broadcastSOS(relayPacket)
                bluetoothMeshManager?.broadcastPacket(relayPacket)
            }
        }
    }

    fun startRescueOperation() {
        if (currentRole != PreferencesHelper.ROLE_RESCUER) return
        val startPacket = SOSPacket(
            nodeId = nodeId,
            latitude = 0.0,
            longitude = 0.0,
            batteryLevel = getBatteryLevel(),
            timestamp = System.currentTimeMillis(),
            packetType = SOSPacket.TYPE_RESCUE_START,
            hopCount = 0
        )
        meshManager?.broadcastSOS(startPacket)
        bluetoothMeshManager?.broadcastPacket(startPacket)
    }

    private fun startGossipLoop() {
        handler.post(object : Runnable {
            override fun run() {
                if (isGossipActive) {
                    broadcastOwnStatus()
                }
                handler.postDelayed(this, currentGossipInterval)
            }
        })
    }

    private fun updateAdaptiveInterval(rssi: Int) {
        if (currentRole == PreferencesHelper.ROLE_VICTIM) {
            currentGossipInterval = if (rssi > -65) {
                GOSSIP_INTERVAL_URGENT
            } else if (getBatteryLevel() < SOSPacket.CRITICAL_BATTERY_THRESHOLD) {
                GOSSIP_INTERVAL_CRITICAL
            } else {
                GOSSIP_INTERVAL_NORMAL
            }
        }
    }

    private fun broadcastOwnStatus() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            checkDeltaAndSend(0.0, 0.0)
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            checkDeltaAndSend(location?.latitude ?: 0.0, location?.longitude ?: 0.0)
        }
    }

    private fun checkDeltaAndSend(lat: Double, lon: Double) {
        val battery = getBatteryLevel()
        val currentPacket = SOSPacket(
            nodeId = nodeId,
            latitude = lat,
            longitude = lon,
            batteryLevel = battery,
            timestamp = System.currentTimeMillis(),
            packetType = SOSPacket.TYPE_VICTIM_DATA,
            hopCount = 0,
            isCritical = (battery < SOSPacket.CRITICAL_BATTERY_THRESHOLD)
        )

        val last = lastBroadcastPacket
        if (last == null || 
            abs(last.latitude - lat) > GPS_CHANGE_THRESHOLD ||
            abs(last.longitude - lon) > GPS_CHANGE_THRESHOLD ||
            abs(last.batteryLevel - battery) >= BATTERY_CHANGE_THRESHOLD ||
            System.currentTimeMillis() - last.timestamp > GOSSIP_INTERVAL_NORMAL) {
            
            sendOwnData(currentPacket)
            lastBroadcastPacket = currentPacket
        }
    }

    private fun sendOwnData(packet: SOSPacket) {
        knowledgeBase[nodeId] = packet
        listener?.onKnowledgeBaseUpdated(knowledgeBase.values.toList())
        meshManager?.broadcastSOS(packet)
        bluetoothMeshManager?.broadcastPacket(packet)
    }

    private fun getBatteryLevel(): Int {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    fun setListener(listener: MeshServiceListener?) {
        this.listener = listener
        listener?.onKnowledgeBaseUpdated(knowledgeBase.values.toList())
    }

    fun getKnowledgeBase() = knowledgeBase.values.toList()
    fun getDiscoveredPeers() = meshManager?.getDiscoveredPeers() ?: emptyList()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Mesh Network Status",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val status = if (currentRole == PreferencesHelper.ROLE_RESCUER) "Rescuer Active" else "Mesh Mode"
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("🛡️ DisasterSOS: $status")
            .setContentText("Intelligent Mesh Relay Running")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        meshManager?.cleanup()
        bluetoothMeshManager?.cleanup()
    }
}
