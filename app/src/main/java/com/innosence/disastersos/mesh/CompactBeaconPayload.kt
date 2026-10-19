package com.innosence.disastersos.mesh

import com.innosence.disastersos.data.SOSPacket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * CompactBeaconPayload — 23-byte binary serialization for zero-handshake
 * BLE Advertisement Flooding mesh relay.
 *
 * Binary Layout:
 * [0]      Magic Byte: 0x53 ('S')
 * [1..6]   NodeId: 6 ASCII bytes
 * [7]      Packet Type: 1 = VICTIM_DATA, 2 = RESCUE_START
 * [8..11]  Latitude: Int32 (degrees * 1,000,000)
 * [12..15] Longitude: Int32 (degrees * 1,000,000)
 * [16]     Battery Level: UInt8 (0-100)
 * [17]     Hop Count: UInt8 (0-15)
 * [18..21] Timestamp (Seconds epoch): UInt32
 * [22]     Flags: Bit 0 = isCritical
 */
object CompactBeaconPayload {
    const val COMPANY_ID = 0x05D1 // DisasterSOS custom manufacturer identifier
    const val MAGIC_BYTE: Byte = 0x53 // 'S'

    const val TYPE_VICTIM_SOS: Byte = 1
    const val TYPE_RESCUE_START: Byte = 2

    fun encode(packet: SOSPacket): ByteArray {
        val buffer = ByteBuffer.allocate(23)
        buffer.order(ByteOrder.BIG_ENDIAN)

        // 1. Magic
        buffer.put(MAGIC_BYTE)

        // 2. NodeId (6 ASCII chars)
        val cleanNodeId = packet.nodeId.take(6).padEnd(6, ' ')
        val nodeBytes = cleanNodeId.toByteArray(Charsets.US_ASCII)
        buffer.put(nodeBytes, 0, 6)

        // 3. Type
        val typeByte = if (packet.packetType == SOSPacket.TYPE_RESCUE_START) TYPE_RESCUE_START else TYPE_VICTIM_SOS
        buffer.put(typeByte)

        // 4. Coordinates
        val latInt = (packet.latitude * 1_000_000).toInt()
        val lngInt = (packet.longitude * 1_000_000).toInt()
        buffer.putInt(latInt)
        buffer.putInt(lngInt)

        // 5. Battery
        buffer.put(packet.batteryLevel.coerceIn(0, 100).toByte())

        // 6. Hop count
        buffer.put(packet.hopCount.coerceIn(0, 15).toByte())

        // 7. Timestamp seconds epoch
        val timeSec = (packet.timestamp / 1000L).toInt()
        buffer.putInt(timeSec)

        // 8. Flags
        val flags: Byte = if (packet.isCritical) 1 else 0
        buffer.put(flags)

        return buffer.array()
    }

    fun decode(bytes: ByteArray): SOSPacket? {
        if (bytes.size < 23 || bytes[0] != MAGIC_BYTE) return null

        return try {
            val buffer = ByteBuffer.wrap(bytes)
            buffer.order(ByteOrder.BIG_ENDIAN)

            val magic = buffer.get()
            if (magic != MAGIC_BYTE) return null

            val nodeBytes = ByteArray(6)
            buffer.get(nodeBytes)
            val nodeId = String(nodeBytes, Charsets.US_ASCII).trim()

            val typeByte = buffer.get()
            val packetType = if (typeByte == TYPE_RESCUE_START) SOSPacket.TYPE_RESCUE_START else SOSPacket.TYPE_VICTIM_DATA

            val latInt = buffer.getInt()
            val lngInt = buffer.getInt()
            val lat = latInt.toDouble() / 1_000_000.0
            val lng = lngInt.toDouble() / 1_000_000.0

            val battery = buffer.get().toInt() and 0xFF
            val hopCount = buffer.get().toInt() and 0x0F
            val timeSec = buffer.getInt().toLong() and 0xFFFFFFFFL
            val timestamp = timeSec * 1000L

            val flags = buffer.get()
            val isCritical = (flags.toInt() and 1) != 0

            SOSPacket(
                nodeId = nodeId,
                latitude = lat,
                longitude = lng,
                batteryLevel = battery,
                timestamp = timestamp,
                packetType = packetType,
                hopCount = hopCount,
                sequenceNumber = timestamp,
                isCritical = isCritical
            )
        } catch (e: Exception) {
            null
        }
    }
}
