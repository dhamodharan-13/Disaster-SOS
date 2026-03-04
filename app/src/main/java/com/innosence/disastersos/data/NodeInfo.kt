package com.innosence.disastersos.data

/*
 * ============================================================================
 *  NodeInfo.kt — INFORMATION ABOUT A CONNECTED PHONE
 * ============================================================================
 *
 *  WHAT IS THIS?
 *  =============
 *  When our phone discovers another phone via Wi-Fi Direct, we need to
 *  remember WHO we found. This data class stores basic information about
 *  each connected peer (other phone) in the mesh network.
 *
 *  WHY IS IT IMPORTANT?
 *  ====================
 *  Imagine you're in a dark building shouting for help. You hear 3 voices
 *  reply. You want to know: "Who are these people? Are they close? Are they
 *  rescuers or other victims?" NodeInfo answers those questions for phones.
 *
 *  HOW DOES IT WORK?
 *  =================
 *  When Android's Wi-Fi Direct discovers a nearby phone, it gives us the
 *  phone's name and MAC address. We wrap this into a NodeInfo object and
 *  display it in the dashboard's peer count.
 * ============================================================================
 */

/**
 * Represents a peer device discovered in the mesh network.
 *
 * @param deviceName   The human-readable name of the phone (e.g., "Pixel 7a" or "Samsung A54")
 * @param deviceAddress The MAC address — a unique hardware identifier for the Wi-Fi chip
 *                      (e.g., "AA:BB:CC:DD:EE:FF"). No two devices share the same MAC.
 * @param isConnected   Whether we have an active data connection to this peer right now.
 *                      true = We can send/receive SOS packets to/from this device.
 *                      false = We can see them nearby but haven't connected yet.
 */
data class NodeInfo(
    val deviceName: String,
    val deviceAddress: String,
    val isConnected: Boolean = false
)
