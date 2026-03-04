package com.innosence.disastersos.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.innosence.disastersos.R
import com.innosence.disastersos.data.SOSPacket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * ============================================================================
 *  AlertAdapter.kt — THE TRANSLATOR BETWEEN DATA AND DISPLAY
 * ============================================================================
 *
 *  WHAT IS THIS?
 *  =============
 *  RecyclerView is like an empty bulletin board. The Adapter is the person
 *  who takes SOS postcards (SOSPacket objects) and pins them onto the board
 *  in the correct format.
 *
 *  WHY IS IT IMPORTANT?
 *  ====================
 *  Without an Adapter, the RecyclerView would be blank — even if we have
 *  100 SOS alerts in memory, the user wouldn't see any of them.
 *
 *  HOW DOES IT WORK? (The RecyclerView Pattern)
 *  =============================================
 *  RecyclerView is smart about memory. Here's the trick:
 *
 *  Imagine you have 100 alerts but only 5 fit on screen at a time.
 *  A naive approach would create 100 card views → wastes memory.
 *  RecyclerView only creates ~7 card views (5 visible + 2 buffer).
 *  When you scroll down, the card that scrolled OFF the top gets
 *  "recycled" — its content is replaced with a new alert and it
 *  reappears at the bottom. This is why it's called RecyclerView!
 *
 *  This pattern has 3 key parts:
 *    1. ViewHolder — Holds references to the TextViews in one card
 *    2. onCreateViewHolder — Creates a new blank card (rare, only ~7 times)
 *    3. onBindViewHolder — Fills an existing card with new data (frequent)
 * ============================================================================
 */

class AlertAdapter : RecyclerView.Adapter<AlertAdapter.AlertViewHolder>() {

    /*
     * Our list of received SOS alerts.
     * "mutableListOf()" creates an empty list that we can add items to later.
     * When a new SOS arrives via the mesh, we add it here and notify the
     * RecyclerView to refresh.
     */
    private val alerts = mutableListOf<SOSPacket>()

    // Date formatter — converts timestamp (1709471130000) to readable "20:45:30"
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /*
     * ────────────────────────────────────────────────────────────────
     *  ViewHolder — The "Card Template Holder"
     * ────────────────────────────────────────────────────────────────
     *  This class holds references to all the TextViews inside ONE
     *  alert card. It's like putting sticky notes on a form saying
     *  "this is where the name goes, this is where the GPS goes."
     *
     *  WHY not just use findViewById every time?
     *  Because findViewById is SLOW — it searches through the entire
     *  layout tree. ViewHolder calls it ONCE and remembers the result.
     *  Since RecyclerView recycles cards hundreds of times, this
     *  optimization really adds up.
     */
    class AlertViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvAlertType: TextView = itemView.findViewById(R.id.tvAlertType)
        val tvAlertTime: TextView = itemView.findViewById(R.id.tvAlertTime)
        val tvAlertNodeId: TextView = itemView.findViewById(R.id.tvAlertNodeId)
        val tvAlertLocation: TextView = itemView.findViewById(R.id.tvAlertLocation)
        val tvAlertConfidence: TextView = itemView.findViewById(R.id.tvAlertConfidence)
        val tvAlertBattery: TextView = itemView.findViewById(R.id.tvAlertBattery)
        val tvAlertHops: TextView = itemView.findViewById(R.id.tvAlertHops)
    }

    /*
     * ────────────────────────────────────────────────────────────────
     *  onCreateViewHolder — "Build me a blank card"
     * ────────────────────────────────────────────────────────────────
     *  Called when RecyclerView needs a NEW card view.
     *  This only happens ~7 times (for the visible cards + buffer).
     *  After that, RecyclerView recycles existing cards.
     *
     *  LayoutInflater.from(parent.context) — This is Android's tool
     *  for converting XML layout files into actual View objects.
     *  "Inflate" = "blow up the XML blueprint into a real UI element"
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlertViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_alert, parent, false)
        return AlertViewHolder(view)
    }

    /*
     * ────────────────────────────────────────────────────────────────
     *  onBindViewHolder — "Fill this recycled card with new data"
     * ────────────────────────────────────────────────────────────────
     *  Called EVERY TIME a card needs to display data — either because
     *  it's newly visible or because it's been recycled.
     *
     *  @param holder — The ViewHolder containing the card's TextViews
     *  @param position — Which alert in our list to display (0, 1, 2...)
     */
    override fun onBindViewHolder(holder: AlertViewHolder, position: Int) {
        val packet = alerts[position]

        // Map the distress type to a human-readable emoji + label
        val typeEmoji = when (packet.distressType) {
            "SCREAM" -> "🚨 SCREAM Detected"
            "SOS_TAP" -> "🔔 SOS Tapping Pattern"
            "MANUAL_BUTTON" -> "🆘 Manual SOS"
            "CRASH_DETECTED" -> "💥 Impact/Crash"
            else -> "⚠️ ${packet.distressType}"
        }
        holder.tvAlertType.text = typeEmoji

        // Format the timestamp from milliseconds to "HH:mm:ss"
        holder.tvAlertTime.text = timeFormat.format(Date(packet.timestamp))

        // Truncate the node ID to first 8 characters for readability
        holder.tvAlertNodeId.text = "Node: ${packet.nodeId.take(8)}..."

        // Display GPS with 4 decimal places (≈11 meter accuracy)
        holder.tvAlertLocation.text = "📍 %.4f, %.4f".format(packet.latitude, packet.longitude)

        // Confidence with color coding
        holder.tvAlertConfidence.text = "🎯 ${packet.confidence}%"
        holder.tvAlertBattery.text = "🔋 ${packet.batteryLevel}%"
        holder.tvAlertHops.text = "📡 ${packet.hopCount} hops"
    }

    // Returns the total number of alerts — RecyclerView needs this to know
    // how many cards to prepare
    override fun getItemCount(): Int = alerts.size

    /*
     * ────────────────────────────────────────────────────────────────
     *  addAlert — Called when a new SOS arrives from the mesh
     * ────────────────────────────────────────────────────────────────
     *  Adds the new alert to the TOP of the list (most recent first)
     *  and tells RecyclerView "Hey, I inserted a new item at position 0,
     *  please animate it sliding in."
     */
    fun addAlert(packet: SOSPacket) {
        alerts.add(0, packet) // Add at the beginning (newest first)
        notifyItemInserted(0) // Tell RecyclerView to animate the insertion
    }

    /**
     * Returns the current list of all alerts
     */
    fun getAlerts(): List<SOSPacket> = alerts.toList()
}
