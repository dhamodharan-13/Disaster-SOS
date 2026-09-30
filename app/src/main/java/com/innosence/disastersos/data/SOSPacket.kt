package com.innosence.disastersos.data

import android.os.Build

/**
 * SOSPacket — The gossip message format.
 */
data class SOSPacket(
    val nodeId: String,
    val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    val latitude: Double,
    val longitude: Double,
    val batteryLevel: Int,
    val timestamp: Long,
    val packetType: String = TYPE_VICTIM_DATA,
    val hopCount: Int = 0,
    val sequenceNumber: Long = System.currentTimeMillis(),
    
    // Priority flag: set true if battery < 15% for urgent relay
    val isCritical: Boolean = false
) {
    companion object {
        const val TYPE_RESCUE_START = "RESCUE_START"
        const val TYPE_VICTIM_DATA = "VICTIM_DATA"
        const val MAX_HOPS = 15
        const val CRITICAL_BATTERY_THRESHOLD = 15
    }

    fun incrementHop(): SOSPacket = copy(hopCount = hopCount + 1)
    fun hasReachedMaxHops(): Boolean = hopCount >= MAX_HOPS
    fun getUniqueKey(): String = nodeId
    
}
