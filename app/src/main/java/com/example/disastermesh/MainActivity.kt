package com.example.disastermesh

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : Activity() {

    private val serviceId = "com.example.disastermesh.SERVICE"
    private val strategy = Strategy.P2P_CLUSTER

    private lateinit var connectionsClient: ConnectionsClient

    private lateinit var nodeText: TextView
    private lateinit var connectionText: TextView
    private lateinit var alertText: TextView
    private lateinit var logText: TextView
    private lateinit var messageInput: EditText
    private lateinit var statusSpinner: Spinner

    private val nodeName = "Node-${UUID.randomUUID().toString().take(4)}"

    private val connectedEndpoints = mutableSetOf<String>()
    private val seenMessageIds = mutableSetOf<String>()
    private val alerts = mutableListOf<SosPacket>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        connectionsClient = Nearby.getConnectionsClient(this)

        buildUi()
        requestPermissionsIfNeeded()
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf<String>()

        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        requestPermissions(permissions.toTypedArray(), 100)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startMesh()
    }

    @SuppressLint("MissingPermission")
    private fun startMesh() {
        startAdvertising()
        startDiscovery()
        addLog("Offline relay started")
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder()
            .setStrategy(strategy)
            .build()

        connectionsClient.startAdvertising(
            nodeName,
            serviceId,
            connectionLifecycleCallback,
            options
        ).addOnSuccessListener {
            addLog("Advertising as $nodeName")
        }.addOnFailureListener {
            addLog("Advertising failed: ${it.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder()
            .setStrategy(strategy)
            .build()

        connectionsClient.startDiscovery(
            serviceId,
            endpointDiscoveryCallback,
            options
        ).addOnSuccessListener {
            addLog("Discovery started")
        }.addOnFailureListener {
            addLog("Discovery failed: ${it.message}")
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        @SuppressLint("MissingPermission")
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            addLog("Found ${info.endpointName}")

            connectionsClient.requestConnection(
                nodeName,
                endpointId,
                connectionLifecycleCallback
            ).addOnFailureListener {
                addLog("Request failed: ${it.message}")
            }
        }

        override fun onEndpointLost(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            updateStatusUi()
            addLog("Lost endpoint")
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            addLog("Connection initiated with ${info.endpointName}")

            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener {
                    addLog("Accept failed: ${it.message}")
                }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                updateStatusUi()
                addLog("Connected to peer")

                syncAlertsToEndpoint(endpointId)
            } else {
                addLog("Connection failed: ${result.status.statusMessage}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            updateStatusUi()
            addLog("Peer disconnected")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val json = String(bytes, StandardCharsets.UTF_8)

            try {
                val packet = SosPacket.fromJson(json)
                handleIncomingPacket(packet, endpointId)
            } catch (e: Exception) {
                addLog("Invalid packet received")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // small text payload, no progress needed
        }
    }

    private fun createLocalAlert(status: String, message: String) {
        val packet = SosPacket(
            messageId = UUID.randomUUID().toString(),
            senderName = nodeName,
            status = status,
            message = message,
            timestamp = System.currentTimeMillis(),
            hopCount = 0,
            ttl = 5
        )

        seenMessageIds.add(packet.messageId)
        alerts.add(0, packet)

        updateAlertsUi()
        broadcastPacket(packet)

        addLog("Broadcasted: $status")
    }

    private fun handleIncomingPacket(packet: SosPacket, fromEndpointId: String) {
        runOnUiThread {
            if (seenMessageIds.contains(packet.messageId)) {
                addLog("Duplicate ignored")
                return@runOnUiThread
            }

            seenMessageIds.add(packet.messageId)
            alerts.add(0, packet)

            updateAlertsUi()
            addLog("Received SOS from ${packet.senderName}")

            if (packet.ttl > 0) {
                val relayedPacket = packet.copy(
                    hopCount = packet.hopCount + 1,
                    ttl = packet.ttl - 1
                )

                relayPacket(relayedPacket, fromEndpointId)
                addLog("Relayed packet | hop ${relayedPacket.hopCount} | ttl ${relayedPacket.ttl}")
            }
        }
    }

    private fun syncAlertsToEndpoint(endpointId: String) {
        alerts.forEach { packet ->
            sendPacketToEndpoint(endpointId, packet)
        }
    }

    private fun broadcastPacket(packet: SosPacket) {
        connectedEndpoints.forEach { endpointId ->
            sendPacketToEndpoint(endpointId, packet)
        }
    }

    private fun relayPacket(packet: SosPacket, exceptEndpointId: String) {
        connectedEndpoints
            .filter { it != exceptEndpointId }
            .forEach { endpointId ->
                sendPacketToEndpoint(endpointId, packet)
            }
    }

    @SuppressLint("MissingPermission")
    private fun sendPacketToEndpoint(endpointId: String, packet: SosPacket) {
        val payload = Payload.fromBytes(packet.toJson().toByteArray(StandardCharsets.UTF_8))

        connectionsClient.sendPayload(endpointId, payload)
            .addOnFailureListener {
                addLog("Send failed: ${it.message}")
            }
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(32, 48, 32, 32)
        root.setBackgroundColor(Color.rgb(255, 245, 245))

        val title = TextView(this)
        title.text = "DisasterMesh"
        title.textSize = 32f
        title.setTextColor(Color.rgb(120, 0, 0))
        title.gravity = Gravity.CENTER
        title.setPadding(0, 0, 0, 8)

        val subtitle = TextView(this)
        subtitle.text = "Offline Emergency Relay Network"
        subtitle.textSize = 16f
        subtitle.gravity = Gravity.CENTER
        subtitle.setTextColor(Color.DKGRAY)
        subtitle.setPadding(0, 0, 0, 24)

        nodeText = TextView(this)
        nodeText.text = "Node: $nodeName"
        nodeText.textSize = 15f
        nodeText.setTextColor(Color.BLACK)

        connectionText = TextView(this)
        connectionText.text = "Connected devices: 0"
        connectionText.textSize = 15f
        connectionText.setTextColor(Color.BLACK)
        connectionText.setPadding(0, 0, 0, 20)

        statusSpinner = Spinner(this)
        val statuses = listOf(
            "Need Help",
            "Injured",
            "Trapped",
            "Need Medical Help",
            "Need Food/Water",
            "Safe",
            "Volunteer Available"
        )

        statusSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            statuses
        )

        messageInput = EditText(this)
        messageInput.hint = "Example: I am near Block C gate"
        messageInput.setText("I need help near Block C")
        messageInput.setPadding(16, 8, 16, 8)

        val sosButton = Button(this)
        sosButton.text = "BROADCAST SOS"
        sosButton.textSize = 18f
        sosButton.setTextColor(Color.WHITE)
        sosButton.setBackgroundColor(Color.rgb(211, 47, 47))

        sosButton.setOnClickListener {
            val status = statusSpinner.selectedItem.toString()
            val message = messageInput.text.toString().trim()

            if (message.isEmpty()) {
                Toast.makeText(this, "Enter emergency message", Toast.LENGTH_SHORT).show()
            } else {
                createLocalAlert(status, message)
            }
        }

        alertText = TextView(this)
        alertText.text = "Emergency Alerts\nNo alerts yet."
        alertText.textSize = 15f
        alertText.setTextColor(Color.BLACK)
        alertText.setPadding(0, 24, 0, 16)

        logText = TextView(this)
        logText.text = "Network Logs\nWaiting..."
        logText.textSize = 13f
        logText.setTextColor(Color.DKGRAY)
        logText.setPadding(0, 16, 0, 0)

        val scroll = ScrollView(this)
        val scrollContent = LinearLayout(this)
        scrollContent.orientation = LinearLayout.VERTICAL

        scrollContent.addView(alertText)
        scrollContent.addView(logText)
        scroll.addView(scrollContent)

        root.addView(title)
        root.addView(subtitle)
        root.addView(nodeText)
        root.addView(connectionText)
        root.addView(statusSpinner)
        root.addView(messageInput)

        root.addView(
            sosButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                140
            )
        )

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
    }

    private fun updateStatusUi() {
        runOnUiThread {
            connectionText.text = "Connected devices: ${connectedEndpoints.size}"
        }
    }

    private fun updateAlertsUi() {
        runOnUiThread {
            if (alerts.isEmpty()) {
                alertText.text = "Emergency Alerts\nNo alerts yet."
                return@runOnUiThread
            }

            val text = StringBuilder()
            text.append("Emergency Alerts\n\n")

            alerts.forEach { alert ->
                text.append("🚨 ${alert.status}\n")
                text.append("From: ${alert.senderName}\n")
                text.append("Message: ${alert.message}\n")
                text.append("Hop count: ${alert.hopCount} | TTL: ${alert.ttl}\n")
                text.append("Time: ${formatTime(alert.timestamp)}\n")
                text.append("---------------------------\n")
            }

            alertText.text = text.toString()
        }
    }

    private fun addLog(message: String) {
        runOnUiThread {
            val oldText = logText.text.toString()
            val cleanOld = if (oldText == "Network Logs\nWaiting...") {
                "Network Logs"
            } else {
                oldText
            }

            logText.text = "$cleanOld\n• $message"
        }
    }

    private fun formatTime(timestamp: Long): String {
        val formatter = SimpleDateFormat("hh:mm a", Locale.getDefault())
        return formatter.format(Date(timestamp))
    }
}

data class SosPacket(
    val messageId: String,
    val senderName: String,
    val status: String,
    val message: String,
    val timestamp: Long,
    val hopCount: Int,
    val ttl: Int
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("messageId", messageId)
            put("senderName", senderName)
            put("status", status)
            put("message", message)
            put("timestamp", timestamp)
            put("hopCount", hopCount)
            put("ttl", ttl)
        }.toString()
    }

    companion object {
        fun fromJson(json: String): SosPacket {
            val obj = JSONObject(json)

            return SosPacket(
                messageId = obj.getString("messageId"),
                senderName = obj.getString("senderName"),
                status = obj.getString("status"),
                message = obj.getString("message"),
                timestamp = obj.getLong("timestamp"),
                hopCount = obj.getInt("hopCount"),
                ttl = obj.getInt("ttl")
            )
        }
    }
}