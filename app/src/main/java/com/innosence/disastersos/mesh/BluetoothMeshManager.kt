package com.innosence.disastersos.mesh

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.google.gson.Gson
import com.innosence.disastersos.data.SOSPacket
import java.nio.charset.Charset
import java.util.UUID

/*
 * ============================================================================
 *  BluetoothMeshManager.kt — BLUETOOTH MULTI-HOP MESH NETWORK
 * ============================================================================
 *
 *  WHY BLUETOOTH INSTEAD OF ONLY WI-FI DIRECT?
 *  =============================================
 *  Wi-Fi Direct forces one phone to be "Group Owner" (GO). A phone that
 *  is a client of one GO cannot simultaneously be a GO for another phone.
 *  This breaks the A ↔ B ↔ C chain we need.
 *
 *  Bluetooth solves this:
 *  - A phone can act as BOTH a BLE advertiser AND a scanner simultaneously
 *  - A phone can maintain multiple GATT connections at once
 *  - BLE uses very little battery compared to classic Bluetooth
 *
 *  HOW IT WORKS (BLE Advertising + Scanning):
 *  ==========================================
 *  1. VICTIM phones "advertise" (yell: "I'm here!") using BLE
 *  2. RESCUER/VICTIM phones "scan" (listen: "Who's nearby?")
 *  3. When two phones find each other, they connect via GATT
 *  4. They exchange SOSPackets as JSON strings over BLE characteristics
 *  5. Each phone relays received packets to other connected phones
 *     (except the one it received from — prevents echo loops)
 *
 *  RSSI (Received Signal Strength Indicator):
 *  ==========================================
 *  Every BLE scan result includes an RSSI value (in dBm).
 *  We can estimate distance from this:
 *    -30 dBm ≈ very close (< 1 meter)
 *    -60 dBm ≈ nearby (3-5 meters)
 *    -90 dBm ≈ far (10-20 meters)
 *  This helps Rescuers roughly locate victims without GPS.
 * ============================================================================
 */

@SuppressLint("MissingPermission")
class BluetoothMeshManager(
    private val context: Context,
    private val nodeId: String,
    private val onPacketReceived: (SOSPacket) -> Unit,
    private val onPeerDiscovered: (String, Int) -> Unit  // (deviceAddress, rssi)
) {
    companion object {
        private const val TAG = "BluetoothMesh"

        // Custom UUID for our DisasterSOS service — all app instances use this
        // to recognize each other among all the other BLE devices around.
        val SERVICE_UUID: UUID = UUID.fromString("d1535053-1234-5678-abcd-deadbeef0001")

        // UUID for the characteristic that carries the SOS data (JSON string)
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("d1535053-1234-5678-abcd-deadbeef0002")

        // Maximum BLE payload per write. BLE 4.2+ supports up to 512 bytes,
        // but we keep it safe at 500 for compatibility.
        private const val MAX_BLE_PAYLOAD = 500

        // How often to restart scanning (BLE scanning has a system-imposed
        // 30-minute limit on some Android versions)
        private const val SCAN_RESTART_INTERVAL_MS = 25 * 60 * 1000L  // 25 minutes
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val gson = Gson()
    private val handler = Handler(Looper.getMainLooper())
    private val messageRouter = MessageRouter()

    // Track connected devices to relay messages through the mesh
    private val connectedDevices = mutableMapOf<String, BluetoothGatt>()

    // Track discovered peers and their RSSI for distance estimation
    private val peerRSSI = mutableMapOf<String, Int>()

    // Track devices connected to our GATT server (Clients that connected to us)
    private val serverConnectedDevices = mutableSetOf<BluetoothDevice>()

    // BLE Scanner and Advertiser
    private var bleScanner: BluetoothLeScanner? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var isScanning = false
    private var isAdvertising = false

    // Buffer for receiving chunked messages (BLE has small MTU)
    private val receiveBuffers = mutableMapOf<String, StringBuilder>()

    // ────────────────────────────────────────────────────────────────
    //  initialize() — Set up BLE advertising and scanning
    // ────────────────────────────────────────────────────────────────
    fun initialize() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e(TAG, "❌ Bluetooth is not available or not enabled")
            return
        }

        // Start the GATT server (to receive incoming connections)
        startGattServer()

        // Start advertising (so other phones can find us)
        startAdvertising()

        // Start scanning (so we can find other phones)
        startScanning()

        Log.i(TAG, "✅ Bluetooth Mesh initialized — nodeId: $nodeId")
    }

    // ────────────────────────────────────────────────────────────────
    //  BLE ADVERTISING — "I'M HERE!" beacon
    // ────────────────────────────────────────────────────────────────
    private fun startAdvertising() {
        bleAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (bleAdvertiser == null) {
            Log.e(TAG, "❌ BLE Advertising not supported on this device")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)  // Advertise forever
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)  // Save space in the ad packet
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        bleAdvertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            Log.i(TAG, "✅ BLE Advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            Log.e(TAG, "❌ BLE Advertising failed: error $errorCode")
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  BLE SCANNING — "WHO'S NEARBY?" listener
    // ────────────────────────────────────────────────────────────────
    private fun startScanning() {
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bleScanner == null) {
            Log.e(TAG, "❌ BLE Scanner not available")
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanner?.startScan(listOf(filter), settings, scanCallback)
        isScanning = true
        Log.i(TAG, "✅ BLE Scanning started")

        // Schedule periodic restart to avoid Android's scan timeout
        handler.postDelayed({
            if (isScanning) {
                stopScanning()
                startScanning()
            }
        }, SCAN_RESTART_INTERVAL_MS)
    }

    private fun stopScanning() {
        bleScanner?.stopScan(scanCallback)
        isScanning = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { scanResult ->
                val device = scanResult.device
                val rssi = scanResult.rssi
                val address = device.address

                // Update RSSI map
                peerRSSI[address] = rssi

                // Notify the UI about this discovered peer
                onPeerDiscovered(address, rssi)

                // If not already connected, try to connect
                if (!connectedDevices.containsKey(address)) {
                    Log.i(TAG, "📡 Found peer: $address (RSSI: $rssi dBm, ~${estimateDistance(rssi)}m)")
                    connectToDevice(device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "❌ BLE Scan failed: error $errorCode")
            isScanning = false
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  GATT SERVER — Receives incoming BLE connections + data
    // ────────────────────────────────────────────────────────────────
    private fun startGattServer() {
        val service = BluetoothGattService(
            SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val characteristic = BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE or
                    BluetoothGattCharacteristic.PERMISSION_READ
        )

        service.addCharacteristic(characteristic)

        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        gattServer?.addService(service)
        Log.i(TAG, "✅ GATT Server started")
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "📲 Device connected to GATT server: ${device.address}")
                    serverConnectedDevices.add(device)
                    handler.post { onPeerDiscovered(device.address, 0) }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "📴 Device disconnected from GATT server: ${device.address}")
                    serverConnectedDevices.remove(device)
                    connectedDevices.remove(device.address)
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (characteristic.uuid == CHARACTERISTIC_UUID && value != null) {
                val chunk = String(value, Charset.forName("UTF-8"))
                handleIncomingChunk(device.address, chunk)
            }

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
            }
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  CONNECT TO A DISCOVERED DEVICE
    // ────────────────────────────────────────────────────────────────
    private fun connectToDevice(device: BluetoothDevice) {
        // Use autoConnect = false for immediate, much faster connection.
        // We also explicitly specify TRANSPORT_LE to avoid fallback to classic Bluetooth.
        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattClientCallback)
        }
        
        if (gatt != null) {
            connectedDevices[device.address] = gatt
            Log.i(TAG, "🔗 Connecting to: ${device.address}")
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "✅ Connected to: ${gatt.device.address}")
                    // Negotiate a larger MTU to prevent dropping large JSON packets
                    val mtuRequested = gatt.requestMtu(512)
                    if (!mtuRequested) {
                        Log.w(TAG, "⚠️ MTU request failed, falling back to discoverServices immediately")
                        gatt.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "📴 Disconnected from: ${gatt.device.address}")
                    connectedDevices.remove(gatt.device.address)
                    gatt.close()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "✅ MTU negotiated to: $mtu on ${gatt.device.address}")
            } else {
                Log.w(TAG, "⚠️ MTU negotiation failed on ${gatt.device.address}, status $status")
            }
            // Proceed to discover services only after MTU negotiation completes
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    Log.i(TAG, "✅ DisasterSOS service found on: ${gatt.device.address}")
                    val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID)
                    if (characteristic != null) {
                        gatt.setCharacteristicNotification(characteristic, true)
                        Log.i(TAG, "✅ Notifications enabled for ${gatt.device.address}")
                    }
                } else {
                    Log.w(TAG, "⚠ DisasterSOS service NOT found on: ${gatt.device.address}")
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                val chunk = String(value, Charset.forName("UTF-8"))
                handleIncomingChunk(gatt.device.address, chunk)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                @Suppress("DEPRECATION")
                val value = characteristic.value
                if (value != null) {
                    val chunk = String(value, Charset.forName("UTF-8"))
                    handleIncomingChunk(gatt.device.address, chunk)
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  MESSAGE HANDLING — Receive, parse, and relay
    // ────────────────────────────────────────────────────────────────

    /**
     * Handles incoming data chunks. BLE has limited MTU, so large
     * JSON strings may arrive in multiple pieces.
     * We use "<<END>>" as a delimiter to know when a full message
     * has been received.
     */
    private fun handleIncomingChunk(senderAddress: String, chunk: String) {
        val buffer = receiveBuffers.getOrPut(senderAddress) { StringBuilder() }
        buffer.append(chunk)

        if (buffer.contains("<<END>>")) {
            val fullMessage = buffer.toString().replace("<<END>>", "")
            receiveBuffers.remove(senderAddress)

            try {
                val packet = gson.fromJson(fullMessage, SOSPacket::class.java)
                Log.i(TAG, "📨 Received ${packet.packetType} from ${packet.nodeId}")

                // Check if we should relay this message (prevents loops)
                if (messageRouter.shouldRelay(packet, nodeId)) {
                    // Deliver to our app's callback
                    handler.post { onPacketReceived(packet) }

                    // Relay to all OTHER connected devices (not the sender)
                    relayToOthers(packet.incrementHop(), senderAddress)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to parse incoming packet: ${e.message}")
            }
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  BROADCAST — Send a packet to ALL connected peers
    // ────────────────────────────────────────────────────────────────
    fun broadcastPacket(packet: SOSPacket) {
        val jsonString = gson.toJson(packet) + "<<END>>"
        val data = jsonString.toByteArray(Charset.forName("UTF-8"))

        messageRouter.markAsSeen("${packet.nodeId}:${packet.timestamp}")

        connectedDevices.forEach { (address, gatt) ->
            sendDataToDevice(gatt, data)
            Log.i(TAG, "📤 Sent ${packet.packetType} to $address")
        }

        // Also try to write via GATT server to server-connected devices
        serverConnectedDevices.forEach { device ->
            sendDataFromServer(device, data)
            Log.i(TAG, "📤 Sent ${packet.packetType} to server-client ${device.address}")
        }
        
        Log.i(TAG, "📡 Broadcast complete — sent to ${connectedDevices.size + serverConnectedDevices.size} peers")
    }

    /**
     * Relay a packet to all connected peers EXCEPT the one that sent it
     * (to prevent echo loops: A→B→A→B→A...)
     */
    private fun relayToOthers(packet: SOSPacket, excludeAddress: String) {
        val jsonString = gson.toJson(packet) + "<<END>>"
        val data = jsonString.toByteArray(Charset.forName("UTF-8"))

        connectedDevices.forEach { (address, gatt) ->
            if (address != excludeAddress) {
                sendDataToDevice(gatt, data)
                Log.i(TAG, "🔄 Relayed ${packet.packetType} to $address")
            }
        }

        serverConnectedDevices.forEach { device ->
            if (device.address != excludeAddress) {
                sendDataFromServer(device, data)
                Log.i(TAG, "🔄 Relayed ${packet.packetType} to server-client ${device.address}")
            }
        }
    }

    /**
     * Send raw byte data to a connected GATT device, chunked if needed.
     */
    private fun sendDataToDevice(gatt: BluetoothGatt, data: ByteArray) {
        val service = gatt.getService(SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID) ?: return

        // Network operations should not block the main thread
        Thread {
            try {
                // Chunk the data if it exceeds the BLE MTU
                var offset = 0
                while (offset < data.size) {
                    val end = minOf(offset + MAX_BLE_PAYLOAD, data.size)
                    val chunk = data.copyOfRange(offset, end)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(
                            characteristic,
                            chunk,
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        characteristic.value = chunk
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(characteristic)
                    }
                    offset = end

                    // Small delay between chunks to avoid BLE congestion
                    Thread.sleep(50)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to send data to device: ${e.message}")
            }
        }.start()
    }

    /**
     * Send raw byte data to a device connected to our GATT server.
     */
    private fun sendDataFromServer(device: BluetoothDevice, data: ByteArray) {
        val service = gattServer?.getService(SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID) ?: return

        Thread {
            try {
                var offset = 0
                while (offset < data.size) {
                    val end = minOf(offset + MAX_BLE_PAYLOAD, data.size)
                    val chunk = data.copyOfRange(offset, end)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gattServer?.notifyCharacteristicChanged(device, characteristic, false, chunk)
                    } else {
                        @Suppress("DEPRECATION")
                        characteristic.value = chunk
                        @Suppress("DEPRECATION")
                        gattServer?.notifyCharacteristicChanged(device, characteristic, false)
                    }
                    offset = end

                    // Small delay between chunks to avoid BLE congestion
                    Thread.sleep(50)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to send data from server: ${e.message}")
            }
        }.start()
    }

    // ────────────────────────────────────────────────────────────────
    //  RSSI DISTANCE ESTIMATION
    // ────────────────────────────────────────────────────────────────
    /**
     * Estimates the distance (in meters) from a Bluetooth RSSI value.
     *
     * This uses the Log-Distance Path Loss model:
     *   distance = 10 ^ ((measuredPower - rssi) / (10 * n))
     *
     * Where:
     *   measuredPower = RSSI at 1 meter (typically -59 dBm for BLE)
     *   n = path loss exponent (2.0 for open space, ~3.0 indoors)
     */
    fun estimateDistance(rssi: Int): Int {
        val measuredPower = -59  // Calibrated RSSI at 1 meter
        val n = 2.5  // Indoor/rubble environment factor
        val distance = Math.pow(10.0, (measuredPower - rssi) / (10.0 * n))
        return distance.toInt().coerceAtLeast(1)  // Minimum 1 meter
    }

    /**
     * Returns RSSI readings for all discovered peers.
     */
    fun getPeerRSSIMap(): Map<String, Int> = peerRSSI.toMap()

    /**
     * Returns number of currently connected peers.
     */
    fun getConnectedPeerCount(): Int {
        val uniqueAddresses = mutableSetOf<String>()
        uniqueAddresses.addAll(connectedDevices.keys)
        serverConnectedDevices.forEach { uniqueAddresses.add(it.address) }
        return uniqueAddresses.size
    }

    // ────────────────────────────────────────────────────────────────
    //  CLEANUP — Release all Bluetooth resources
    // ────────────────────────────────────────────────────────────────
    fun cleanup() {
        stopScanning()

        bleAdvertiser?.stopAdvertising(advertiseCallback)
        isAdvertising = false

        connectedDevices.values.forEach { gatt ->
            gatt.disconnect()
            gatt.close()
        }
        connectedDevices.clear()
        
        serverConnectedDevices.clear()

        gattServer?.close()
        gattServer = null

        handler.removeCallbacksAndMessages(null)

        Log.i(TAG, "🧹 Bluetooth Mesh cleaned up")
    }
}
