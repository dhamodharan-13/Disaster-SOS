package com.innosence.disastersos.ui

import android.annotation.SuppressLint
import android.net.wifi.p2p.WifiP2pDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.innosence.disastersos.R

class DeviceAdapter : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    private val devices = mutableListOf<WifiP2pDevice>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_connected_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size

    @SuppressLint("NotifyDataSetChanged")
    fun updateDevices(newDevices: List<WifiP2pDevice>) {
        devices.clear()
        devices.addAll(newDevices)
        notifyDataSetChanged()
    }

    class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvDeviceName: TextView = itemView.findViewById(R.id.tvDeviceName)
        private val tvDeviceAddress: TextView = itemView.findViewById(R.id.tvDeviceAddress)

        fun bind(device: WifiP2pDevice) {
            tvDeviceName.text = if (device.deviceName.isNullOrBlank()) "Unknown Device" else device.deviceName
            tvDeviceAddress.text = device.deviceAddress ?: "Unknown MAC"
        }
    }
}
