package com.innosence.disastersos.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.innosence.disastersos.R
import com.innosence.disastersos.data.PreferencesHelper
import com.innosence.disastersos.data.SOSPacket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * AlertAdapter — Manages the list of known phones in the Mesh Knowledge Base.
 */
class AlertAdapter(
    var currentRole: String = PreferencesHelper.ROLE_VICTIM,
    private val onRescueClick: ((String) -> Unit)? = null,
    private val onNavigateClick: ((Double, Double) -> Unit)? = null
) : RecyclerView.Adapter<AlertAdapter.AlertViewHolder>() {

    private val alerts = mutableListOf<SOSPacket>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    class AlertViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvAlertType: TextView = itemView.findViewById(R.id.tvAlertType)
        val tvAlertTime: TextView = itemView.findViewById(R.id.tvAlertTime)
        val tvAlertNodeId: TextView = itemView.findViewById(R.id.tvAlertNodeId)
        val tvAlertLocation: TextView = itemView.findViewById(R.id.tvAlertLocation)
        val tvAlertConfidence: TextView = itemView.findViewById(R.id.tvAlertConfidence)
        val tvAlertBattery: TextView = itemView.findViewById(R.id.tvAlertBattery)
        val tvAlertHops: TextView = itemView.findViewById(R.id.tvAlertHops)
        val btnMarkRescued: Button = itemView.findViewById(R.id.btnMarkRescued)
        val btnNavigate: Button = itemView.findViewById(R.id.btnNavigate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlertViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_alert, parent, false)
        return AlertViewHolder(view)
    }

    override fun onBindViewHolder(holder: AlertViewHolder, position: Int) {
        val packet = alerts[position]

        // Status Label
        holder.tvAlertType.text = when (packet.packetType) {
            SOSPacket.TYPE_RESCUE_START -> "🚨 RESCUE ACTIVE"
            else -> "📱 VICTIM ACTIVE"
        }

        holder.tvAlertTime.text = timeFormat.format(Date(packet.timestamp))
        holder.tvAlertNodeId.text = packet.deviceName
        
        if (packet.latitude == 0.0 && packet.longitude == 0.0) {
            holder.tvAlertLocation.text = "WAITING FOR GPS..."
            holder.btnNavigate.isEnabled = false
            holder.btnNavigate.alpha = 0.5f
        } else {
            holder.tvAlertLocation.text = "%.4f, %.4f".format(packet.latitude, packet.longitude)
            holder.btnNavigate.isEnabled = true
            holder.btnNavigate.alpha = 1.0f
        }
        
        // We reuse Confidence field for Sequence Number (Version) display or just hide it
        holder.tvAlertConfidence.text = "Ver: ${packet.sequenceNumber.toString().takeLast(4)}"
        
        holder.tvAlertBattery.text = "Batt: ${packet.batteryLevel}%"
        holder.tvAlertHops.text = "${packet.hopCount} hops"

        // Handle buttons for Rescuer
        if (currentRole == PreferencesHelper.ROLE_RESCUER) {
            holder.btnMarkRescued.visibility = View.VISIBLE
            holder.btnMarkRescued.setOnClickListener { onRescueClick?.invoke(packet.nodeId) }
            
            holder.btnNavigate.visibility = View.VISIBLE
            holder.btnNavigate.setOnClickListener {
                onNavigateClick?.invoke(packet.latitude, packet.longitude)
            }
        } else {
            holder.btnMarkRescued.visibility = View.GONE
            holder.btnNavigate.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int = alerts.size

    /**
     * DEDUPLICATION: Ensures only the LATEST version of each node's data is shown.
     */
    fun addOrUpdateAlert(packet: SOSPacket) {
        val existingIndex = alerts.indexOfFirst { it.nodeId == packet.nodeId }
        
        if (existingIndex != -1) {
            // ONLY update if the new packet is NEWER (Sequence Number check)
            if (packet.sequenceNumber > alerts[existingIndex].sequenceNumber) {
                alerts[existingIndex] = packet
                notifyItemChanged(existingIndex)
            }
        } else {
            // New phone discovered! Add to top.
            alerts.add(0, packet)
            notifyItemInserted(0)
        }
    }

    fun removeAlertsByNodeId(nodeId: String) {
        val originalSize = alerts.size
        alerts.removeAll { it.nodeId == nodeId }
        if (alerts.size < originalSize) {
            notifyDataSetChanged()
        }
    }
}
