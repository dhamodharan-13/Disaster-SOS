package com.innosence.disastersos.data

import android.content.Context
import android.content.SharedPreferences

/*
 * ============================================================================
 *  PreferencesHelper.kt — SAVING USER CHOICES
 * ============================================================================
 *
 *  WHAT IS THIS?
 *  =============
 *  This class easily manages saving and retrieving the user's selected role
 *  (RESCUER or VICTIM) so we don't have to ask them every single time
 *  they open the app.
 * ============================================================================
 */
class PreferencesHelper(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "DisasterSOSPrefs"
        private const val KEY_USER_ROLE = "USER_ROLE"
        private const val KEY_RESCUED_NODES = "RESCUED_NODES"
        private const val KEY_NODE_ID = "NODE_ID"

        const val ROLE_NONE = "NONE"
        const val ROLE_RESCUER = "RESCUER"
        const val ROLE_VICTIM = "VICTIM"
    }

    /**
     * Saves the role the user chose.
     */
    fun saveUserRole(role: String) {
        prefs.edit().putString(KEY_USER_ROLE, role).apply()
    }

    /**
     * Retrieves the saved role. Returns "NONE" if they haven't chosen yet.
     */
    fun getUserRole(): String {
        return prefs.getString(KEY_USER_ROLE, ROLE_NONE) ?: ROLE_NONE
    }

    /**
     * Marks a specific node ID as rescued.
     */
    fun markNodeRescued(nodeId: String) {
        val currentNodes = getRescuedNodes().toMutableSet()
        currentNodes.add(nodeId)
        prefs.edit().putStringSet(KEY_RESCUED_NODES, currentNodes).apply()
    }

    /**
     * Retrieves the set of all node IDs that have been marked as rescued.
     */
    fun getRescuedNodes(): Set<String> {
        return prefs.getStringSet(KEY_RESCUED_NODES, emptySet()) ?: emptySet()
    }

    /**
     * Checks if a specific node ID has been marked as rescued.
     */
    fun isNodeRescued(nodeId: String): Boolean {
        return getRescuedNodes().contains(nodeId)
    }

    /**
     * Retrieves the saved Node ID, generating one and saving it if it doesn't exist.
     */
    fun getNodeId(): String {
        var nodeId = prefs.getString(KEY_NODE_ID, null)
        if (nodeId == null) {
            nodeId = java.util.UUID.randomUUID().toString().take(12)
            prefs.edit().putString(KEY_NODE_ID, nodeId).apply()
        }
        return nodeId
    }
}
