package com.innosence.disastersos.mesh

import android.util.Log
import com.innosence.disastersos.data.SOSPacket

/*
 * ============================================================================
 *  MessageRouter.kt — SOS MESSAGE TRAFFIC CONTROLLER
 * ============================================================================
 *
 *  TANGLISH EXPLANATION:
 *  =====================
 *  MeshManager phone-to-phone connection handle pannum.
 *  MessageRouter decide pannum: "Idhu SOS message ah relay pannumaa venaama?"
 *
 *  PROBLEM: "Infinite Loop" — SOS message circular ah bounce aagalaam.
 *  Phone A → B → C → A → B → C → A... (never ending! battery drain!)
 *
 *  SOLUTION: Namma 2 rules follow panrom:
 *  1. "Already seen" check — Same nodeId + timestamp combination oda
 *     SOS already vandhirundha, ignore panrom (duplicate filter)
 *  2. "Max hops" check — SOSPacket la hopCount 10 ku mela pona, stop
 *
 *  ANALOGY:
 *  Post office madhiri — oru letter already deliver aachu na,
 *  same letter ah innoru thadava deliver pannamaatanga la?
 *  Router adhey madhiri — same SOS twice relay pannaadhu.
 * ============================================================================
 */

class MessageRouter {

    companion object {
        private const val TAG = "MessageRouter"

        /*
         * MAX_CACHE_SIZE — Maximum number of "seen" SOS IDs we remember.
         * Memory overflow aagaama irukka, 1000 ku mela ponaa
         * oldest entries delete pannidurom.
         */
        private const val MAX_CACHE_SIZE = 1000
    }

    /*
     * seenMessages — "Already paathaachu" list.
     * Key = "nodeId:timestamp" (unique combination per SOS)
     * LinkedHashSet use panrom because:
     *   - Duplicates automatically reject aagum (Set property)
     *   - Insertion order maintain aagum (Linked property)
     *   - Oldest entry first irukum, easy ah remove pannalam
     */
    private val seenMessages = LinkedHashSet<String>()

    /*
     * ────────────────────────────────────────────────────────────────
     *  shouldRelay() — "Idhu SOS ah forward pannumaa?"
     * ────────────────────────────────────────────────────────────────
     *
     *  TANGLISH:
     *  Oru SOS message vandhaa, 3 questions kekkurom:
     *  1. Idhu already vandhirukkaa? (duplicate check)
     *  2. Maximum hops reach aachirukkaa? (distance check)
     *  3. Idhu namma own message dhaana? (self-echo check)
     *
     *  3 um "NO" na → relay panrom ✅
     *  Yeavadhu onnu "YES" na → drop panrom ❌ (battery save)
     *
     *  @param packet — Incoming SOS packet
     *  @param ownNodeId — Namma phone oda node ID
     *  @return true = relay pannu, false = ignore pannu
     */
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
        markAsSeen(messageKey)
        Log.d(TAG, "✅ Message approved for relay: $messageKey (hop ${packet.hopCount})")
        return true
    }

    /*
     * ────────────────────────────────────────────────────────────────
     *  markAsSeen() — "Idhu paathaachu" nu note panrom
     * ────────────────────────────────────────────────────────────────
     *  Cache overflow aagaama, oldest entries clean up panrom.
     */
    private fun markAsSeen(messageKey: String) {
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
    fun clearCache() {
        seenMessages.clear()
    }

    /**
     * Evvalavu unique messages paathirukkoom
     */
    fun getSeenCount(): Int = seenMessages.size
}
