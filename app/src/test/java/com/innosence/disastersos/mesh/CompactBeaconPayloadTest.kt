package com.innosence.disastersos.mesh

import com.innosence.disastersos.data.SOSPacket
import org.junit.Assert.*
import org.junit.Test

class CompactBeaconPayloadTest {

    @Test
    fun testEncodeAndDecodeVictimSOS() {
        val original = SOSPacket(
            nodeId = "A1B2C3",
            latitude = 12.971598,
            longitude = 77.594562,
            batteryLevel = 85,
            timestamp = 1700000000000L,
            packetType = SOSPacket.TYPE_VICTIM_DATA,
            hopCount = 0,
            isCritical = true
        )

        val encoded = CompactBeaconPayload.encode(original)
        assertEquals(23, encoded.size)
        assertEquals(CompactBeaconPayload.MAGIC_BYTE, encoded[0])

        val decoded = CompactBeaconPayload.decode(encoded)
        assertNotNull(decoded)
        assertEquals("A1B2C3", decoded!!.nodeId)
        assertEquals(SOSPacket.TYPE_VICTIM_DATA, decoded.packetType)
        assertEquals(12.971598, decoded.latitude, 0.00001)
        assertEquals(77.594562, decoded.longitude, 0.00001)
        assertEquals(85, decoded.batteryLevel)
        assertEquals(0, decoded.hopCount)
        assertTrue(decoded.isCritical)
    }

    @Test
    fun testEncodeAndDecodeRescueStartMultiHop() {
        val original = SOSPacket(
            nodeId = "RESCU1",
            latitude = -33.868820,
            longitude = 151.209296,
            batteryLevel = 99,
            timestamp = 1710000000000L,
            packetType = SOSPacket.TYPE_RESCUE_START,
            hopCount = 3,
            isCritical = false
        )

        val encoded = CompactBeaconPayload.encode(original)
        assertEquals(23, encoded.size)

        val decoded = CompactBeaconPayload.decode(encoded)
        assertNotNull(decoded)
        assertEquals("RESCU1", decoded!!.nodeId)
        assertEquals(SOSPacket.TYPE_RESCUE_START, decoded.packetType)
        assertEquals(-33.868820, decoded.latitude, 0.00001)
        assertEquals(151.209296, decoded.longitude, 0.00001)
        assertEquals(99, decoded.batteryLevel)
        assertEquals(3, decoded.hopCount)
        assertFalse(decoded.isCritical)
    }

    @Test
    fun testDecodeRejectsInvalidMagicOrLength() {
        val invalidBytes = ByteArray(10) { 0 }
        assertNull(CompactBeaconPayload.decode(invalidBytes))

        val wrongMagic = ByteArray(23) { 0 }
        wrongMagic[0] = 0x12 // not MAGIC_BYTE
        assertNull(CompactBeaconPayload.decode(wrongMagic))
    }
}
