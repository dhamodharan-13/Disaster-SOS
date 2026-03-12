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
    
    // IMPROVEMENT: Priority flags for better mesh management
    val isCritical: Boolean = false, // Set true if battery < 15%
    val version: Int = 1 // Schema versioning
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
    
    // Check if this packet needs urgent attention
    fun needsPriority(): Boolean = isCritical || batteryLevel < CRITICAL_BATTERY_THRESHOLD
}
