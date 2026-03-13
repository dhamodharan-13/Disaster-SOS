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
 *  TANGLISH EXPLANATION:
 *  =====================
 *  Idhu namma app oda "postman" — oru phone la irundhu innooru phone ku
 *  SOS message anuppura velai idhu dhan pannum.
 *
 *  EPDI WORK AAGUM (Simple Version):
 *  ══════════════════════════════════
 *
 *  Step 1: DISCOVERY (Nearby phones ah thedhuradhu)
 *  ─────────────────
 *  Phone A solum: "Yaaravadhu irukkeengala?" (discoverPeers)
 *  Phone B, C respond pannuvanga: "Naanga irukom!" (peer list)
 *  → Idhu Wi-Fi Direct oda "Service Discovery" protocol use pannum.
 *     Phone oda Wi-Fi chip special "beacon" signals anuppum.
 *     Nearby phones ah automatically detect pannum.
 *
 *  Step 2: CONNECTION (Connect aaguradhu)
 *  ──────────────────
 *  Phone A says: "Phone B, naan connect aagalamaa?" (connect)
 *  Phone B says: "OK da, vaa!" (accept)
 *  → Wi-Fi Direct la, oru phone "Group Owner" (GO) aagum = mini router.
 *     Marudha phone "Client" aagum. Aprom TCP/IP use panni data anuppalam.
 *
 *  Step 3: DATA TRANSFER (SOS message anuppuradhu)
 *  ────────────────────
 *  Phone A creates SOSPacket → JSON string aakkum → TCP socket vazhiya
 *  Phone B ku anuppum → Phone B JSON ah paathudtu SOSPacket aakkum →
 *  Alert Log la display pannum → Vera phones ku relay pannum.
 *
 *  IMPORTANT CONCEPTS:
 *  ═══════════════════
 *  - WifiP2pManager = Android oda official Wi-Fi Direct API
 *  - BroadcastReceiver = "Event listener" — Wi-Fi state change aana
 *    Android nammaku signal anuppum, namma react pannurom
 *  - TCP Socket = Internet la use panra same technology, but here
 *    it works LOCALLY between two phones without internet
 *  - ServerSocket = "Phone B oru kadai open pannum" (listening)
 *  - Socket = "Phone A kadai ku pogum" (connecting)
 * ============================================================================
 */

class MeshManager(
    private val context: Context,
    /*
     * Callbacks — "Enna nadanthaa ennaku sollu" functions.
     * onSOSReceived: Vera phone irundhu SOS vandhaa idha call pannu
     * onPeersChanged: Nearby phones count change aana idha call pannu
     */
    private val onSOSReceived: (SOSPacket) -> Unit,
    private val onPeersChanged: (Int) -> Unit
) {
    companion object {
        private const val TAG = "MeshManager"

        /*
         * SERVER_PORT — TCP server oda port number.
         *
         * TANGLISH: Port nu enna? Oru building la rooms madhiri.
         * Building address = IP address. Room number = Port.
         * "8888 room la SOS messages vangikurom" nu solrom.
         *
         * 8888 choose pannom because:
         * - Well-known ports (0-1023) system use pannum, avoid pannanum
         * - 8888 easy to remember, usually not used by other apps
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
     * BroadcastReceiver — Android oda EVENT SYSTEM.
     *
     * TANGLISH:
     * Android la "something happened" nu signal varum — adhukku
     * "Broadcast" nu per. Namma "Receiver" register pannom, adhunala
     * relevant events nammaku varum.
     *
     * Wi-Fi Direct la 4 main events irukku:
     * 1. WIFI_P2P_STATE_CHANGED → Wi-Fi Direct ON/OFF aana
     * 2. WIFI_P2P_PEERS_CHANGED → Nearby phones list change aana
     * 3. WIFI_P2P_CONNECTION_CHANGED → Connection status change aana
     * 4. WIFI_P2P_THIS_DEVICE_CHANGED → Our own device info change aana
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
     *  init {} — Constructor, object create aanavudan run aagum
     * ────────────────────────────────────────────────────────────────
     *  TANGLISH: Class oda "birth" — object piranthavudan setup panrom.
     *  Wi-Fi Direct manager grab panrom, channel open panrom,
     *  BroadcastReceiver register panrom.
     */
    init {
        // Wi-Fi Direct system service grab panrom
        wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

        // Channel open panrom — namma app ku Wi-Fi Direct ku naduvula communication pipe
        channel = wifiP2pManager?.initialize(context, Looper.getMainLooper(), null)

        // BroadcastReceiver register panrom — Wi-Fi events kekkurom
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
     *  broadcastSOS() — SOS MESSAGE ANUPPURADHU
     * ────────────────────────────────────────────────────────────────
     *
     *  TANGLISH:
     *  Idhu dhan MAIN function — SOS packet ah ellaa connected phones
     *  ku anuppurom. 
     *
     *  Steps:
     *  1. SOSPacket ah JSON string aakkurom (serialize)
     *  2. Discovered peers list la loop panrom
     *  3. Ovvoru peer ku TCP socket open panni JSON string anuppurom
     *
     *  WHY JSON?
     *  JSON = JavaScript Object Notation. Idhu oru universal format —
     *  Android, iPhone, laptop, server — ellaam JSON purinjukkum.
     *  Example: {"nodeId":"abc123","confidence":92}
     *
     *  WHY TCP?
     *  TCP = Transmission Control Protocol. Idhu "guaranteed delivery"
     *  kudukum — message reach aana confirm pannum. UDP la message
     *  lost aagalaam, but SOS message lost aagakkoodaadhu!
     */
    fun broadcastSOS(packet: SOSPacket) {
        val jsonString = gson.toJson(packet)
        Log.d(TAG, "📤 Broadcasting SOS: $jsonString")

        // Background thread la anuppurom — network operations main thread la pannakkoodaadhu
        Thread {
            for (peer in discoveredPeers) {
                try {
                    sendToPeer(peer, jsonString)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send to ${peer.deviceName}: ${e.message}")
                }
            }
        }.start()
    }

    /*
     * ────────────────────────────────────────────────────────────────
     *  sendToPeer() — SINGLE PHONE KU MESSAGE ANUPPURADHU
     * ────────────────────────────────────────────────────────────────
     *
     *  TANGLISH:
     *  TCP Socket use panni oru specific phone ku JSON string anuppurom.
     *
     *  Socket connection = "Phone call" madhiri:
     *  1. Number dial panrom (Socket connect to IP:PORT)
     *  2. Line connect aagum
     *  3. Message solrom (write JSON string)
     *  4. Phone cut panrom (close socket)
     */
    private fun sendToPeer(peer: WifiP2pDevice, jsonString: String) {
        try {
            // In a real P2P scenario, we'd resolve the peer's IP first
            // For now, we use the group owner's address
            wifiP2pManager?.requestConnectionInfo(channel) { info ->
                if (info.isGroupOwner) {
                    Log.d(TAG, "We are the Group Owner, skipping TCP client send to avoid echoing to self")
                    return@requestConnectionInfo
                }

                val groupOwnerAddress = info.groupOwnerAddress?.hostAddress ?: return@requestConnectionInfo

                Thread {
                    try {
                        Socket(groupOwnerAddress, SERVER_PORT).use { socket ->
                            val writer = PrintWriter(socket.getOutputStream(), true)
                            writer.println(jsonString)
                            writer.flush()
                            Log.d(TAG, "✅ SOS sent to ${peer.deviceName}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Socket send failed: ${e.message}")
                    }
                }.start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Send failed: ${e.message}")
        }
    }

    /*
     * ────────────────────────────────────────────────────────────────
     *  startServer() — INCOMING SOS MESSAGES KU LISTEN PANRADHU
     * ────────────────────────────────────────────────────────────────
     *
     *  TANGLISH:
     *  Oru kadai open panra madhiri — "SOS messages vanga ready!" 🏪
     *  ServerSocket port 8888 la open pannum. Vera phone connect
     *  aana, JSON string read pannum, SOSPacket aakkum, callback
     *  vazhiya MainActivity ku anuppum.
     *
     *  Idhu continuous loop la run aagum — eppovum ready ah irukum
     *  incoming messages ku.
     *
     *  WHY SERVER-CLIENT MODEL?
     *  Wi-Fi Direct la, "Group Owner" server madhiri act pannum.
     *  Marudha phones (clients) server ku connect panni data anuppum.
     *  Server continuously listen pannum, client vandhaa data vaangum.
     */
    private fun startServer() {
        if (isServerRunning) return

        isServerRunning = true

        Thread {
            try {
                serverSocket = ServerSocket(SERVER_PORT)
                Log.d(TAG, "🏪 Server started on port $SERVER_PORT — listening for SOS...")

                while (isServerRunning) {
                    try {
                        // accept() = "Wait till someone connects" — blocks here.
                        // We shouldn't set timeout here because we WANT it to block and wait for connections
                        val clientSocket = serverSocket?.accept() ?: break
                        
                        clientSocket.use { socket ->
                            // Set timeout ON THE CLIENT SOCKET so we don't block forever if a peer drops connection mid-transfer
                            socket.soTimeout = 5000

                            // Vera phone connect aachu! Data read panrom
                            val reader = BufferedReader(
                                InputStreamReader(socket.getInputStream())
                            )
                            val jsonString = reader.readLine()

                            if (jsonString != null) {
                                // JSON string ah SOSPacket object aakkurom (deserialize)
                                val packet = gson.fromJson(jsonString, SOSPacket::class.java)
                                Log.d(TAG, "📥 SOS RECEIVED from node ${packet.nodeId}!")

                                // MainActivity ku callback vazhiya alert anuppurom
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
