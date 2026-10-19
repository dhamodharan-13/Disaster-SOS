package com.innosence.disastersos.mesh

import android.util.Log
import com.innosence.disastersos.data.SOSPacket

/*
 * ============================================================================
 *  MessageRouter.kt — SOS MESSAGE TRAFFIC CONTROLLER
 * ============================================================================
 *
 *  PURPOSE:
 *  ========
 *  MessageRouter arbitrates incoming emergency packets, ensuring packets are
 *  forwarded across the multi-hop mesh while preventing broadcast storms and
 *  infinite looping.
 *
 *  LOOP PREVENTION RULES:
 *  ======================
 *  1. Duplicate Suppression: Tracks recently observed (nodeId + timestamp)
 *     signatures. Re-broadcast packets matching known signatures are dropped.
 *  2. Hop Count Threshold: Drops packets exceeding MAX_HOPS (15) to constrain
 *     network diameter and conserve battery.
 *  3. Self-Echo Filter: Automatically drops packets originated by this device.
 * ============================================================================
 */

class MessageRouter {

    companion object {
        private const val TAG = "MessageRouter"

        /*
         * MAX_CACHE_SIZE — Maximum number of unique SOS packet signatures
         * retained in memory before purging the oldest entries (LRU-style).
         */
        private const val MAX_CACHE_SIZE = 1000
    }

    /*
     * seenMessages: Set of observed packet keys ("nodeId:timestamp").
     * LinkedHashSet maintains insertion order for predictable cache eviction.
     */
    private val seenMessages = LinkedHashSet<String>()

    /*
     * ────────────────────────────────────────────────────────────────
     *  shouldRelay() — Packet Forwarding Policy
     * ────────────────────────────────────────────────────────────────
     *  Evaluates whether an incoming packet should be retransmitted:
     *  1. Has this packet already been processed? (Duplicate check)
     *  2. Has the packet exceeded the maximum hop count? (Range boundary)
     *  3. Was this packet originated by this node? (Self-echo check)
     *
     *  @param packet Incoming SOS packet
     *  @param ownNodeId Local device node identifier
     *  @return true if packet is novel and eligible for relay; false otherwise
     */
    @Synchronized
    fun shouldRelay(packet: SOSPacket, ownNodeId: String): Boolean {
        // Generate unique key for this specific SOS
        val messageKey = "${packet.nodeId}:${packet.timestamp}"

        // Check 1: Namma own message ah? (Echo prevention)
        if (packet.nodeId == ownNodeId) {
            Log.d(TAG, "⏭ Skipping own message")
            return false
        }

        // Check 2: Already paathirukkoma? (Duplicate prevention)
        if (seenMessages.contains(messageKey)) {
            Log.d(TAG, "⏭ Skipping duplicate: $messageKey")
            return false
        }

        // Check 3: Max hops reach aachirukkaa? (Loop prevention)
        if (packet.hasReachedMaxHops()) {
            Log.d(TAG, "⏭ Skipping max-hops-reached: ${packet.hopCount} hops")
            return false
        }

        // All checks passed ✅ — remember this message and allow relay
        markAsSeenInternal(messageKey)
        Log.d(TAG, "✅ Message approved for relay: $messageKey (hop ${packet.hopCount})")
        return true
    }

    /*
     * ────────────────────────────────────────────────────────────────
     *  markAsSeen() — "Idhu paathaachu" nu note panrom
     * ────────────────────────────────────────────────────────────────
     *  Cache overflow aagaama, oldest entries clean up panrom.
     */
    @Synchronized
    fun markAsSeen(messageKey: String) {
        markAsSeenInternal(messageKey)
    }

    private fun markAsSeenInternal(messageKey: String) {
        seenMessages.add(messageKey)

        // Cache size limit exceed aana, oldest entries remove
        while (seenMessages.size > MAX_CACHE_SIZE) {
            val oldest = seenMessages.first()
            seenMessages.remove(oldest)
            Log.d(TAG, "🗑 Evicted oldest cache entry: $oldest")
        }
    }

    /**
     * Cache clear panrom — testing ku useful
     */
    @Synchronized
    fun clearCache() {
        seenMessages.clear()
    }

    /**
     * Evvalavu unique messages paathirukkoom
     */
    @Synchronized
    fun getSeenCount(): Int = seenMessages.size

}
