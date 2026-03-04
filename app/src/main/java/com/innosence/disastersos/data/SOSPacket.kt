package com.innosence.disastersos.data

/*
 * ============================================================================
 *  SOSPacket.kt — THE SOS MESSAGE BLUEPRINT
 * ============================================================================
 *
 *  WHAT IS THIS?
 *  =============
 *  Imagine you're sending a postcard to a rescuer. The postcard has fixed
 *  fields: "From:", "Location:", "Emergency Type:", etc. This data class
 *  is that postcard — it defines the EXACT structure of every SOS message
 *  that travels through the mesh network.
 *
 *  WHY IS IT IMPORTANT?
 *  ====================
 *  If Phone A just sends random text like "HELP!", Phone B won't know
 *  how to read it. But if every phone agrees: "An SOS message always has
 *  a nodeId, latitude, longitude, distressType, confidence, timestamp,
 *  and batteryLevel" — then ANY phone in the mesh can understand it.
 *
 *  HOW DOES IT WORK?
 *  =================
 *  1. The AI detects a scream → A new SOSPacket is created
 *  2. The GPS fills in the latitude/longitude fields
 *  3. The SOSPacket is converted to a JSON string using Gson
 *     Example JSON: {"nodeId":"abc123","latitude":11.0168,"longitude":76.9558,...}
 *  4. This JSON string is sent over Wi-Fi Direct to neighboring phones
 *  5. The receiving phone converts the JSON string BACK into an SOSPacket
 *     and displays it in the Alert Log
 *
 *  WHY "data class" AND NOT A REGULAR "class"?
 *  ===========================================
 *  In Kotlin, a "data class" automatically generates:
 *    - equals() → Can compare two packets: packetA == packetB
 *    - hashCode() → Can store packets in sets/maps efficiently
 *    - toString() → Can print the packet for debugging
 *    - copy() → Can clone a packet and change one field
 *  A regular class would require you to write all this manually.
 *  Less code = fewer bugs = more reliable in an emergency.
 * ============================================================================
 */

/**
 * Represents a single SOS distress alert that travels through the mesh network.
 *
 * @param nodeId       Unique identifier for the sending phone (generated once at app install).
 *                     WHY: So rescuers know which alerts come from the same victim.
 *
 * @param latitude     GPS latitude of the victim's location (e.g., 11.0168 for Coimbatore).
 *                     WHY: Rescuers need exact coordinates to find victims in rubble.
 *
 * @param longitude    GPS longitude of the victim's location (e.g., 76.9558 for Coimbatore).
 *
 * @param distressType What kind of distress was detected.
 *                     Examples: "SCREAM", "SOS_TAP", "MANUAL_BUTTON", "CRASH_DETECTED"
 *                     WHY: Different distress types may need different rescue equipment.
 *
 * @param confidence   How sure the AI is that this is real distress (0 to 100).
 *                     Example: 95 means "95% sure this is a real scream, not wind"
 *                     WHY: Rescuers can prioritize high-confidence alerts first.
 *
 * @param timestamp    When the SOS was created (milliseconds since 1970, standard format).
 *                     WHY: Older SOSes might mean the victim has been waiting longer.
 *
 * @param batteryLevel The sending phone's battery percentage (0 to 100).
 *                     WHY: If battery = 3%, rescuers know this phone will go dark soon
 *                     and must prioritize reaching that victim before losing their signal.
 *
 * @param hopCount     How many phones this SOS has bounced through.
 *                     WHY: If hopCount = 5, we know this victim is far from the rescuer.
 *                     Also prevents infinite loops (we stop relaying after max hops).
 */
data class SOSPacket(
    val nodeId: String,
    val latitude: Double,
    val longitude: Double,
    val distressType: String,
    val confidence: Int,
    val timestamp: Long,
    val batteryLevel: Int,
    val hopCount: Int = 0
) {
    companion object {
        /*
         * MAX_HOPS — The maximum number of times an SOS can bounce between phones.
         * WHY: Without this limit, an SOS would bounce forever in circles
         * (Phone A → B → C → A → B → C...), wasting battery and flooding the network.
         * 10 hops means the SOS can travel through 10 phones before stopping.
         * At ~50 meters per hop (Wi-Fi Direct range), that's ~500 meters of reach.
         */
        const val MAX_HOPS = 10

        /*
         * CONFIDENCE_THRESHOLD — Minimum AI confidence to auto-trigger an SOS.
         * Below 85%, the AI isn't sure enough — could be wind or a car horn.
         * Above 85%, the AI is quite confident it heard human distress.
         * WHY: Prevents false alarms that waste rescuer time and mesh bandwidth.
         */
        const val CONFIDENCE_THRESHOLD = 85
    }

    /**
     * Creates a new copy of this packet with hopCount incremented by 1.
     * Called every time a phone relays this SOS to the next phone.
     *
     * Example flow:
     *   Phone A creates SOS (hopCount = 0)
     *   Phone B receives it, calls incrementHop() → hopCount = 1
     *   Phone C receives it, calls incrementHop() → hopCount = 2
     *   ...and so on until hopCount reaches MAX_HOPS
     */
    fun incrementHop(): SOSPacket = copy(hopCount = hopCount + 1)

    /**
     * Checks if this SOS has already bounced the maximum number of times.
     * If true, the phone should NOT relay it further (end of the line).
     */
    fun hasReachedMaxHops(): Boolean = hopCount >= MAX_HOPS
}
