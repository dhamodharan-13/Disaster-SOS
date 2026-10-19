package com.innosence.disastersos.mesh

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.innosence.disastersos.data.SOSPacket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.concurrent.CopyOnWriteArrayList
import java.net.ServerSocket
import java.net.Socket

/*
 * ============================================================================
 *  MeshManager.kt — THE BUCKET BRIGADE CONTROLLER
 * ============================================================================
 *
 *  ARCHITECTURE OVERVIEW:
 *  ======================
 *  MeshManager acts as the primary Wi-Fi Direct network orchestrator, enabling
 *  peer-to-peer discovery and multi-hop SOS packet dissemination without
 *  internet or cellular infrastructure.
 *
 *  HOW IT WORKS:
 *  =============
 *  Step 1: DISCOVERY (Peer Detection)
 *  ─────────────────────────────────
 *  - Uses Wi-Fi Direct (Wi-Fi P2P) Service Discovery protocols.
 *  - Radio emits beacon frames to continuously identify nearby mobile devices.
 *  - Populates discovered peer tables with reachable nodes.
 *
 *  Step 2: CONNECTION (Dynamic Ad-hoc Grouping)
 *  ───────────────────────────────────────────
 *  - Negotiates Wi-Fi Direct Group formation.
 *  - One node dynamically becomes the Group Owner (acting as an autonomous local AP).
 *  - Other nodes join as P2P Clients to establish TCP/IP communication channels.
 *
 *  Step 3: DATA TRANSMISSION & RELAY (Gossip Flooding)
 *  ──────────────────────────────────────────────────
 *  - Encodes SOSPacket telemetry into JSON / compact binary payload.
 *  - Transmits via local TCP sockets across peer endpoints.
 *  - Relays incoming alerts across hops up to the defined MAX_HOPS threshold.
 *
 *  KEY CONCEPTS:
 *  =============
 *  - WifiP2pManager: Android framework API for Wi-Fi P2P hardware state.
 *  - BroadcastReceiver: Event bus listening for P2P connection and peer change intents.
 *  - ServerSocket: Persistent listener accepting incoming telemetry from neighboring peers.
 *  - Socket: Ephemeral client connections transmitting alert packets directly to peer IPs.
 * ============================================================================
 */

class MeshManager(
    private val context: Context,
    /*
     * Callbacks:
     * - onSOSReceived: Invoked when a valid telemetry/distress packet is received from a peer.
     * - onPeersChanged: Invoked when the count of active or discovered peers changes.
     */
    private val onSOSReceived: (SOSPacket) -> Unit,
    private val onPeersChanged: (Int) -> Unit
) {
    companion object {
        private const val TAG = "MeshManager"

        /*
         * SERVER_PORT: Port number for local TCP socket listening.
         * Default: 8888 (standard non-privileged port commonly used for local P2P services).
         */
        private const val SERVER_PORT = 8888

        // How often to retry discovery (in case phones didn't find each other yet)
        private const val DISCOVERY_RETRY_INTERVAL_MS = 15_000L  // 15 seconds
    }

    // Wi-Fi Direct manager — Android system service, namma control panrom
    private var wifiP2pManager: WifiP2pManager? = null

    // Channel — namma app ku Wi-Fi Direct system ku naduvula oru "pipe"
    private var channel: WifiP2pManager.Channel? = null

    // JSON converter — SOSPacket ↔ JSON string conversion ku
    private val gson = Gson()

    // Handler for periodic re-discovery
    private val handler = Handler(Looper.getMainLooper())

    // Currently discovered peers (nearby phones)
    private val discoveredPeers = mutableListOf<WifiP2pDevice>()

    // Server socket — listens for incoming SOS messages from other phones
    private var serverSocket: ServerSocket? = null
    private var isServerRunning = false

    // Track if we're already connected to a P2P group (prevents duplicate connections)
    private var isConnected = false

    /*
     * BroadcastReceiver for Wi-Fi Direct framework events:
     * 1. WIFI_P2P_STATE_CHANGED: Wi-Fi P2P hardware state toggled ON/OFF.
     * 2. WIFI_P2P_PEERS_CHANGED: Nearby reachable peer device list updated.
     * 3. WIFI_P2P_CONNECTION_CHANGED: Group formation or connection status changed.
     * 4. WIFI_P2P_THIS_DEVICE_CHANGED: Current device network configuration changed.
     */
    private val wifiP2pReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {

                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    /*
                     * Wi-Fi Direct ON ah OFF ah nu check panrom.
                     * User Wi-Fi off pannittaa na, mesh work aagaadhu.
                     */
                    val state = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE, -1
                    )
                    if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        Log.d(TAG, "✅ Wi-Fi Direct is ENABLED")
                    } else {
                        Log.w(TAG, "❌ Wi-Fi Direct is DISABLED — mesh won't work!")
                    }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    /*
                     * Nearby phones list update aayirukku!
                     * Pudhu phones vandhirukkalaam, old phones ponirukkalaam.
                     * requestPeers() call panni latest list vangikurom.
                     */
                    Log.d(TAG, "📡 Peer list changed! Requesting updated list...")
                    wifiP2pManager?.requestPeers(channel) { peers: WifiP2pDeviceList ->
                        discoveredPeers.clear()
                        discoveredPeers.addAll(peers.deviceList)
                        Log.d(TAG, "Found ${discoveredPeers.size} nearby devices")
                        onPeersChanged(discoveredPeers.size)

                        // AUTO-CONNECT: If we found peers, connect to the first available one!
                        if (discoveredPeers.isNotEmpty()) {
                            val firstPeer = discoveredPeers[0]
                            Log.d(TAG, "🔗 Auto-connecting to: ${firstPeer.deviceName}")
                            connectToPeer(firstPeer)
                        }
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    /*
                     * Connection status change aayirukku!
                     * Successfully connect aana, data transfer start panrom.
                     */
                    Log.d(TAG, "🔗 Connection state changed!")
                    wifiP2pManager?.requestConnectionInfo(channel) { info: WifiP2pInfo ->
                        if (info.groupFormed) {
                            isConnected = true
                            Log.d(TAG, "Group formed! Am I owner? ${info.isGroupOwner}")

                            // Notify UI that we have a connected peer
                            onPeersChanged(maxOf(discoveredPeers.size, 1))

                            if (info.isGroupOwner) {
                                // Naan Group Owner — server start panrom
                                startServer()
                            } else {
                                // Naan Client — Group Owner oda IP ku connect
                                val ownerAddress = info.groupOwnerAddress?.hostAddress
                                if (ownerAddress != null) {
                                    Log.d(TAG, "Connecting to group owner at $ownerAddress")
                                    // Start server on client side too (to receive relayed messages)
                                    startServer()
                                }
                            }
                        } else {
                            isConnected = false
                            Log.d(TAG, "📴 Group dissolved — not connected")
                            onPeersChanged(0)
                        }
                    }
                }
            }
        }
    }

    /*
     * ────────────────────────────────────────────────────────────────
     *  init {} — Component initialization
     * ────────────────────────────────────────────────────────────────
     *  Initializes Wi-Fi P2P manager, communication channel, and event receiver.
     */
    init {
        // Obtain Wi-Fi Direct system service
        wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

        // Initialize communication channel with Wi-Fi P2P framework
        channel = wifiP2pManager?.initialize(context, Looper.getMainLooper(), null)

        // Register broadcast receiver for Wi-Fi P2P system intents
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(wifiP2pReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(wifiP2pReceiver, intentFilter)
        }

        Log.d(TAG, "MeshManager initialized ✅")

        // Immediately start looking for nearby phones
        startDiscovery()

        // Start the server to listen for incoming SOS messages
        startServer()

        // Schedule periodic re-discovery so phones keep trying to find each other
        schedulePeriodicDiscovery()
    }

    /*
     * Periodically restart peer discovery.
     * We keep trying to find NEW peers even if we are already connected.
     */
    private fun schedulePeriodicDiscovery() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                // In Wi-Fi Direct, discovery might fail if we are in the middle of a connection
                // but we try anyway to keep the mesh alive.
                Log.d(TAG, "🔄 Mesh check: Discovering new peers...")
                startDiscovery()
                handler.postDelayed(this, DISCOVERY_RETRY_INTERVAL_MS)
            }
        }, DISCOVERY_RETRY_INTERVAL_MS)
    }

    /*
     * ────────────────────────────────────────────────────────────────
     *  startDiscovery() — NEARBY PHONES THEDHURADHU
     * ────────────────────────────────────────────────────────────────
     */
    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "📡 Peer discovery active")
            }

            override fun onFailure(reasonCode: Int) {
                // If busy, it usually means we are connecting or already have a group.
                // We don't log error for BUSY to keep logs clean.
                if (reasonCode != WifiP2pManager.BUSY) {
                    Log.w(TAG, "❌ Discovery failed: $reasonCode")
                }
            }
        })
    }

    /*
     * ────────────────────────────────────────────────────────────────
     *  connectToPeer() — ORU PHONE KU CONNECT AAGURADHU
     * ────────────────────────────────────────────────────────────────
     */
    @SuppressLint("MissingPermission")
    fun connectToPeer(device: WifiP2pDevice) {
        if (isConnected && device.status == WifiP2pDevice.CONNECTED) return

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = android.net.wifi.WpsInfo.PBC
            // Prefer NOT to be group owner if we are a victim, to let rescuers take charge
            groupOwnerIntent = 0 
        }

        wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "🔗 Connecting to ${device.deviceName}...")
            }

            override fun onFailure(reason: Int) {
                if (reason != WifiP2pManager.BUSY) {
                    Log.e(TAG, "❌ Connection failed: $reason")
                }
            }
        })
    }

    /*
     * ────────────────────────────────────────────────────────────────
     *  broadcastSOS() — SOS Packet Dissemination
     * ────────────────────────────────────────────────────────────────
     *  Serializes the SOSPacket and sends it across connected Wi-Fi Direct peers
     *  over reliable TCP sockets.
     */
    // Track IP addresses of connected clients (for Group Owner broadcasting)
    private val clientIPs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun broadcastSOS(packet: SOSPacket) {
        val jsonString = gson.toJson(packet)
        Log.d(TAG, "📤 Broadcasting SOS via Wi-Fi Direct: $jsonString")

        Thread {
            wifiP2pManager?.requestConnectionInfo(channel) { info ->
                if (info.isGroupOwner) {
                    // Group Owner broadcasts to all recorded client IPs
                    for (clientIp in clientIPs) {
                        try {
                            Socket(clientIp, SERVER_PORT).use { socket ->
                                val writer = PrintWriter(socket.getOutputStream(), true)
                                writer.println(jsonString)
                                writer.flush()
                                Log.d(TAG, "✅ SOS sent to client IP $clientIp")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to send to client $clientIp: ${e.message}")
                        }
                    }
                } else {
                    // Client sends to Group Owner
                    val groupOwnerAddress = info.groupOwnerAddress?.hostAddress ?: return@requestConnectionInfo
                    try {
                        Socket(groupOwnerAddress, SERVER_PORT).use { socket ->
                            val writer = PrintWriter(socket.getOutputStream(), true)
                            writer.println(jsonString)
                            writer.flush()
                            Log.d(TAG, "✅ SOS sent to Group Owner at $groupOwnerAddress")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Socket send to Group Owner failed: ${e.message}")
                    }
                }
            }
        }.start()
    }

    private fun startServer() {
        if (isServerRunning) return

        isServerRunning = true

        Thread {
            try {
                serverSocket = ServerSocket(SERVER_PORT)
                Log.d(TAG, "🏪 Server started on port $SERVER_PORT — listening for SOS...")

                while (isServerRunning) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        
                        clientSocket.use { socket ->
                            val remoteIp = socket.inetAddress?.hostAddress
                            if (remoteIp != null) {
                                clientIPs.add(remoteIp)
                            }
                            socket.soTimeout = 5000

                            val reader = BufferedReader(
                                InputStreamReader(socket.getInputStream())
                            )
                            val jsonString = reader.readLine()

                            if (jsonString != null) {
                                val packet = gson.fromJson(jsonString, SOSPacket::class.java)
                                Log.d(TAG, "📥 SOS RECEIVED from node ${packet.nodeId}!")

                                onSOSReceived(packet)
                            }
                        }
                    } catch (e: Exception) {
                        if (isServerRunning) {
                            Log.e(TAG, "Error handling client connection: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}")
            }
        }.start()
    }

    /*
     * ────────────────────────────────────────────────────────────────
     *  getDiscoveredPeers() — Nearby phones list return pannum
     * ────────────────────────────────────────────────────────────────
     */
    fun getDiscoveredPeers(): List<WifiP2pDevice> = discoveredPeers.toList()

    /*
     * ────────────────────────────────────────────────────────────────
     *  cleanup() — Ellaam close panrom, resources free panrom
     * ────────────────────────────────────────────────────────────────
     *  App close aana, Wi-Fi Direct resources release pannanum.
     *  Illanaa battery drain, memory leak, vera apps ku problem.
     */
    fun cleanup() {
        try {
            isServerRunning = false
            isConnected = false
            serverSocket?.close()
            handler.removeCallbacksAndMessages(null)
            context.unregisterReceiver(wifiP2pReceiver)
            wifiP2pManager?.removeGroup(channel, null)
            Log.d(TAG, "MeshManager cleaned up ✅")
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error: ${e.message}")
        }
    }
}
