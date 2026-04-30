package com.example.disastermesh

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
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
    private lateinit var database: DisasterDatabase

    private lateinit var readinessText: TextView
    private lateinit var readinessStartButton: Button

    private lateinit var nodeText: TextView
    private lateinit var connectionText: TextView
    private lateinit var peerStateText: TextView
    private lateinit var alertText: TextView
    private lateinit var logText: TextView
    private lateinit var messageInput: EditText
    private lateinit var landmarkInput: EditText
    private lateinit var statusSpinner: Spinner

    private val handler = Handler(Looper.getMainLooper())
    private val nodeName = "Node-${UUID.randomUUID().toString().take(4)}"

    private val connectedEndpoints = mutableSetOf<String>()
    private val pendingEndpoints = mutableSetOf<String>()
    private val seenMessageIds = mutableSetOf<String>()
    private val alerts = mutableListOf<SosPacket>()

    private val endpointStates = mutableMapOf<String, PeerState>()
    private val endpointNames = mutableMapOf<String, String>()

    private val relayQueue = mutableListOf<RelayJob>()

    private var relayRunning = false
    private var meshStarted = false
    private var mainUiActive = false
    private var isAdvertising = false
    private var isDiscovering = false
    private var retryScheduled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        connectionsClient = Nearby.getConnectionsClient(this)
        database = DisasterDatabase.getDatabase(this)

        buildReadinessUi()
        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()

        if (!mainUiActive && ::readinessText.isInitialized) {
            refreshReadinessUi()
        }
    }

    private fun requiredPermissions(): List<String> {
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

        return permissions
    }

    private fun requestPermissionsIfNeeded() {
        val missingPermissions = requiredPermissions().filter { permission ->
            checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissions(missingPermissions.toTypedArray(), 100)
        } else {
            refreshReadinessUi()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshReadinessUi()
    }

    private fun buildReadinessUi() {
        mainUiActive = false

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(32, 48, 32, 32)
        root.setBackgroundColor(Color.rgb(255, 245, 245))

        val title = TextView(this)
        title.text = "DisasterMesh"
        title.textSize = 34f
        title.gravity = Gravity.CENTER
        title.setTextColor(Color.rgb(120, 0, 0))
        title.setPadding(0, 0, 0, 8)

        val subtitle = TextView(this)
        subtitle.text = "Device Readiness Check"
        subtitle.textSize = 18f
        subtitle.gravity = Gravity.CENTER
        subtitle.setTextColor(Color.DKGRAY)
        subtitle.setPadding(0, 0, 0, 28)

        readinessText = TextView(this)
        readinessText.textSize = 17f
        readinessText.setTextColor(Color.BLACK)
        readinessText.setPadding(0, 0, 0, 24)

        readinessStartButton = Button(this)
        readinessStartButton.text = "START OFFLINE RELAY"
        readinessStartButton.textSize = 17f
        readinessStartButton.setTextColor(Color.WHITE)
        readinessStartButton.setBackgroundColor(Color.rgb(211, 47, 47))

        readinessStartButton.setOnClickListener {
            val readiness = getDeviceReadiness()

            if (!readiness.canStart) {
                Toast.makeText(
                    this,
                    "Fix Bluetooth, Wi-Fi, Location, and permissions first",
                    Toast.LENGTH_LONG
                ).show()

                refreshReadinessUi()
                return@setOnClickListener
            }

            buildMainUi()
            loadAlertsFromDatabase()
            startMesh()
        }

        val refreshButton = Button(this)
        refreshButton.text = "REFRESH CHECKS"
        refreshButton.setOnClickListener {
            refreshReadinessUi()
        }

        val permissionButton = Button(this)
        permissionButton.text = "REQUEST PERMISSIONS AGAIN"
        permissionButton.setOnClickListener {
            requestPermissionsIfNeeded()
        }

        val settingsButton = Button(this)
        settingsButton.text = "OPEN ANDROID SETTINGS"
        settingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }

        val demoButton = Button(this)
        demoButton.text = "RUN DEMO MODE"
        demoButton.setOnClickListener {
            buildMainUi()
            loadAlertsFromDatabase()
            runDemoMode()
        }

        val note = TextView(this)
        note.text =
            "Nearby relay may fail if Bluetooth, Wi-Fi, Location, or permissions are off. Battery saver may reduce reliability."
        note.textSize = 14f
        note.setTextColor(Color.DKGRAY)
        note.setPadding(0, 20, 0, 0)

        root.addView(title)
        root.addView(subtitle)
        root.addView(readinessText)

        root.addView(
            readinessStartButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                130
            )
        )

        root.addView(refreshButton)
        root.addView(permissionButton)
        root.addView(settingsButton)
        root.addView(demoButton)
        root.addView(note)

        setContentView(root)

        refreshReadinessUi()
    }

    private fun refreshReadinessUi() {
        if (!::readinessText.isInitialized) return

        val readiness = getDeviceReadiness()
        val text = StringBuilder()

        text.append(statusLine("Bluetooth", readiness.bluetoothOn, "ON", "OFF"))
        text.append(statusLine("Wi-Fi", readiness.wifiOn, "ON", "OFF"))
        text.append(statusLine("Location", readiness.locationOn, "ON", "OFF"))
        text.append(statusLine("Nearby permissions", readiness.permissionsGranted, "Granted", "Missing"))

        if (readiness.batterySaverOff) {
            text.append("OK Battery saver: OFF\n")
        } else {
            text.append("WARNING Battery saver: ON - relay may be unreliable\n")
        }

        text.append("\nNode name: $nodeName\n")

        if (readiness.canStart) {
            text.append("\nReady to start offline relay.")
            readinessStartButton.isEnabled = true
            readinessStartButton.alpha = 1f
            readinessStartButton.text = "START OFFLINE RELAY"
        } else {
            text.append("\nFix the failed checks before starting relay.")
            readinessStartButton.isEnabled = false
            readinessStartButton.alpha = 0.5f
            readinessStartButton.text = "DEVICE NOT READY"
        }

        readinessText.text = text.toString()
    }

    private fun statusLine(
        label: String,
        ok: Boolean,
        okText: String,
        failText: String
    ): String {
        return if (ok) {
            "OK $label: $okText\n"
        } else {
            "NO $label: $failText\n"
        }
    }

    @SuppressLint("MissingPermission")
    private fun getDeviceReadiness(): DeviceReadiness {
        val bluetoothOn = try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            adapter != null && adapter.isEnabled
        } catch (e: Exception) {
            false
        }

        val wifiOn = try {
            val wifiManager =
                applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            wifiManager.isWifiEnabled
        } catch (e: Exception) {
            false
        }

        val locationOn = try {
            val locationManager =
                getSystemService(Context.LOCATION_SERVICE) as LocationManager

            val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            gpsEnabled || networkEnabled
        } catch (e: Exception) {
            false
        }

        val permissionsGranted = requiredPermissions().all { permission ->
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }

        val batterySaverOff = try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                !powerManager.isPowerSaveMode
            } else {
                true
            }
        } catch (e: Exception) {
            true
        }

        return DeviceReadiness(
            bluetoothOn = bluetoothOn,
            wifiOn = wifiOn,
            locationOn = locationOn,
            permissionsGranted = permissionsGranted,
            batterySaverOff = batterySaverOff
        )
    }

    @SuppressLint("MissingPermission")
    private fun startMesh() {
        if (meshStarted) return

        mainUiActive = true
        meshStarted = true

        startAdvertising()
        startDiscovery()

        addLog("Offline relay started")
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        if (isAdvertising) return

        val options = AdvertisingOptions.Builder()
            .setStrategy(strategy)
            .build()

        connectionsClient.startAdvertising(
            nodeName,
            serviceId,
            connectionLifecycleCallback,
            options
        ).addOnSuccessListener {
            isAdvertising = true
            addLog("Advertising as $nodeName")
        }.addOnFailureListener {
            isAdvertising = false
            addLog("Advertising failed: ${it.message}")
            scheduleMeshRetry("advertising failed")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        if (isDiscovering) return

        val options = DiscoveryOptions.Builder()
            .setStrategy(strategy)
            .build()

        connectionsClient.startDiscovery(
            serviceId,
            endpointDiscoveryCallback,
            options
        ).addOnSuccessListener {
            isDiscovering = true
            addLog("Discovery started")
        }.addOnFailureListener {
            isDiscovering = false
            addLog("Discovery failed: ${it.message}")
            scheduleMeshRetry("discovery failed")
        }
    }

    @SuppressLint("MissingPermission")
    private fun restartMesh() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()

        isAdvertising = false
        isDiscovering = false
        meshStarted = false
        retryScheduled = false

        connectedEndpoints.clear()
        pendingEndpoints.clear()
        endpointStates.clear()
        endpointNames.clear()

        updateStatusUi()
        updatePeerStateUi()

        addLog("Restarting relay...")

        startMesh()
    }

    @SuppressLint("MissingPermission")
    private fun restartDiscoveryAndAdvertisingOnly() {
        if (!mainUiActive) return

        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()

        isAdvertising = false
        isDiscovering = false

        pendingEndpoints.clear()

        addLog("Retrying discovery/advertising...")

        startAdvertising()
        startDiscovery()
    }

    private fun scheduleMeshRetry(reason: String) {
        if (retryScheduled) return

        retryScheduled = true

        addLog("Retry scheduled in 3 seconds: $reason")

        handler.postDelayed({
            retryScheduled = false
            restartDiscoveryAndAdvertisingOnly()
        }, 3000)
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {

        @SuppressLint("MissingPermission")
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            endpointNames[endpointId] = info.endpointName

            if (connectedEndpoints.contains(endpointId)) {
                endpointStates[endpointId] = PeerState.CONNECTED
                updatePeerStateUi()
                return
            }

            if (pendingEndpoints.contains(endpointId)) {
                endpointStates[endpointId] = PeerState.BLOCKED_DUPLICATE
                updatePeerStateUi()
                addLog("Duplicate connection attempt blocked")
                return
            }

            endpointStates[endpointId] = PeerState.DISCOVERED
            updatePeerStateUi()

            if (nodeName > info.endpointName) {
                addLog("Found ${info.endpointName}; waiting for peer to connect")
                return
            }

            pendingEndpoints.add(endpointId)
            endpointStates[endpointId] = PeerState.CONNECTING
            updatePeerStateUi()

            addLog("Found ${info.endpointName}; requesting connection")

            connectionsClient.requestConnection(
                nodeName,
                endpointId,
                connectionLifecycleCallback
            ).addOnFailureListener {
                pendingEndpoints.remove(endpointId)
                endpointStates[endpointId] = PeerState.FAILED
                updatePeerStateUi()
                addLog("Request failed: ${it.message}")
                scheduleMeshRetry("connection request failed")
            }
        }

        override fun onEndpointLost(endpointId: String) {
            pendingEndpoints.remove(endpointId)
            connectedEndpoints.remove(endpointId)

            endpointStates[endpointId] = PeerState.DISCONNECTED

            updateStatusUi()
            updatePeerStateUi()

            addLog("Lost endpoint")
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            endpointNames[endpointId] = info.endpointName
            endpointStates[endpointId] = PeerState.CONNECTING
            pendingEndpoints.add(endpointId)

            updatePeerStateUi()

            addLog("Connection initiated with ${info.endpointName}")

            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener {
                    pendingEndpoints.remove(endpointId)
                    endpointStates[endpointId] = PeerState.FAILED
                    updatePeerStateUi()
                    addLog("Accept failed: ${it.message}")
                    scheduleMeshRetry("accept failed")
                }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pendingEndpoints.remove(endpointId)

            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                endpointStates[endpointId] = PeerState.CONNECTED

                updateStatusUi()
                updatePeerStateUi()

                addLog("Connected to peer")

                syncAlertsToEndpoint(endpointId)
            } else {
                connectedEndpoints.remove(endpointId)
                endpointStates[endpointId] = PeerState.FAILED

                updateStatusUi()
                updatePeerStateUi()

                addLog("Connection failed: ${result.status.statusMessage}")

                scheduleMeshRetry("connection failed")
            }
        }

        override fun onDisconnected(endpointId: String) {
            pendingEndpoints.remove(endpointId)
            connectedEndpoints.remove(endpointId)

            endpointStates[endpointId] = PeerState.DISCONNECTED

            updateStatusUi()
            updatePeerStateUi()

            addLog("Peer disconnected")

            scheduleMeshRetry("peer disconnected")
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
            // Small text payload. No progress UI needed.
        }
    }

    private fun createLocalAlert(status: String, message: String, landmark: String) {
        val safeLandmark = if (landmark.isBlank()) {
            "Unknown location"
        } else {
            landmark.trim()
        }

        val packet = SosPacket(
            messageId = UUID.randomUUID().toString(),
            senderName = nodeName,
            status = status,
            message = message,
            landmark = safeLandmark,
            timestamp = System.currentTimeMillis(),
            hopCount = 0,
            ttl = 5,
            priority = priorityForStatus(status),
            isRelayed = false,
            receivedFrom = "LOCAL"
        )

        seenMessageIds.add(packet.messageId)
        alerts.add(0, packet)

        persistAlert(packet)
        updateAlertsUi()

        enqueueRelay(packet, exceptEndpointId = null)

        addLog("Queued ${priorityLabel(packet.priority)} alert: $status")
    }

    private fun handleIncomingPacket(packet: SosPacket, fromEndpointId: String) {
        runOnUiThread {
            if (seenMessageIds.contains(packet.messageId)) {
                endpointStates[fromEndpointId] = PeerState.BLOCKED_DUPLICATE
                updatePeerStateUi()
                addLog("Duplicate ignored from ${endpointNames[fromEndpointId] ?: fromEndpointId}")
                return@runOnUiThread
            }

            val receivedPacket = packet.copy(
                receivedFrom = endpointNames[fromEndpointId] ?: fromEndpointId
            )

            seenMessageIds.add(receivedPacket.messageId)
            alerts.add(0, receivedPacket)

            persistAlert(receivedPacket)
            updateAlertsUi()

            addLog(
                "Received ${priorityLabel(receivedPacket.priority)} SOS from ${receivedPacket.senderName}"
            )

            if (receivedPacket.ttl > 0) {
                val relayedPacket = receivedPacket.copy(
                    hopCount = receivedPacket.hopCount + 1,
                    ttl = receivedPacket.ttl - 1,
                    isRelayed = true
                )

                enqueueRelay(relayedPacket, exceptEndpointId = fromEndpointId)
                database.alertDao().updateRelayed(receivedPacket.messageId, true)

                addLog(
                    "Relay queued | hop ${relayedPacket.hopCount} | ttl ${relayedPacket.ttl}"
                )
            }
        }
    }

    private fun enqueueRelay(packet: SosPacket, exceptEndpointId: String?) {
        relayQueue.add(RelayJob(packet, exceptEndpointId))

        relayQueue.sortWith(
            compareByDescending<RelayJob> { it.packet.priority }
                .thenByDescending { it.packet.timestamp }
        )

        flushRelayQueue()
    }

    private fun flushRelayQueue() {
        if (relayRunning) return

        relayRunning = true

        handler.post {
            processNextRelayJob()
        }
    }

    private fun processNextRelayJob() {
        if (relayQueue.isEmpty()) {
            relayRunning = false
            return
        }

        val job = relayQueue.removeAt(0)

        val targets = connectedEndpoints.filter { endpointId ->
            endpointId != job.exceptEndpointId
        }

        if (targets.isEmpty()) {
            addLog("No peers available for ${priorityLabel(job.packet.priority)} packet")

            handler.postDelayed({
                processNextRelayJob()
            }, 250)

            return
        }

        targets.forEach { endpointId ->
            sendPacketToEndpoint(endpointId, job.packet)
        }

        addLog(
            "Relay queue sent ${job.packet.status} to ${targets.size} peer(s)"
        )

        handler.postDelayed({
            processNextRelayJob()
        }, 250)
    }

    private fun syncAlertsToEndpoint(endpointId: String) {
        val sortedAlerts = alerts.sortedWith(
            compareByDescending<SosPacket> { it.priority }
                .thenByDescending { it.timestamp }
        )

        sortedAlerts.forEach { packet ->
            sendPacketToEndpoint(endpointId, packet)
        }

        if (sortedAlerts.isNotEmpty()) {
            addLog("Synced ${sortedAlerts.size} stored alerts by priority")
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendPacketToEndpoint(endpointId: String, packet: SosPacket) {
        val payload = Payload.fromBytes(
            packet.toJson().toByteArray(StandardCharsets.UTF_8)
        )

        connectionsClient.sendPayload(endpointId, payload)
            .addOnSuccessListener {
                database.alertDao().updateRelayed(packet.messageId, true)
            }
            .addOnFailureListener {
                endpointStates[endpointId] = PeerState.FAILED
                updatePeerStateUi()
                addLog("Send failed: ${it.message}")
                scheduleMeshRetry("send failed")
            }
    }

    private fun buildMainUi() {
        mainUiActive = true

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
        connectionText.setPadding(0, 0, 0, 10)

        peerStateText = TextView(this)
        peerStateText.text = "Peer states: none"
        peerStateText.textSize = 13f
        peerStateText.setTextColor(Color.DKGRAY)
        peerStateText.setPadding(0, 0, 0, 18)

        statusSpinner = Spinner(this)

        val statuses = listOf(
            "Need Medical Help",
            "Injured",
            "Trapped",
            "Need Help",
            "Need Food/Water",
            "Safe",
            "Volunteer Available",
            "Info"
        )

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            statuses
        )

        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        statusSpinner.adapter = adapter

        landmarkInput = EditText(this)
        landmarkInput.hint = "Location / Landmark"
        landmarkInput.setText("Near library gate")
        landmarkInput.minLines = 1
        landmarkInput.setPadding(16, 8, 16, 8)

        messageInput = EditText(this)
        messageInput.hint = "Emergency message"
        messageInput.setText("I need help")
        messageInput.minLines = 2
        messageInput.setPadding(16, 8, 16, 8)

        val sosButton = Button(this)
        sosButton.text = "BROADCAST SOS"
        sosButton.textSize = 18f
        sosButton.setTextColor(Color.WHITE)
        sosButton.setBackgroundColor(Color.rgb(211, 47, 47))

        sosButton.setOnClickListener {
            val status = statusSpinner.selectedItem.toString()
            val message = messageInput.text.toString().trim()
            val landmark = landmarkInput.text.toString().trim()

            if (message.isEmpty()) {
                Toast.makeText(this, "Enter emergency message", Toast.LENGTH_SHORT).show()
            } else {
                createLocalAlert(status, message, landmark)
            }
        }

        val restartButton = Button(this)
        restartButton.text = "RESTART RELAY"
        restartButton.textSize = 15f

        restartButton.setOnClickListener {
            restartMesh()
        }

        val demoButton = Button(this)
        demoButton.text = "RUN 3-PHONE DEMO MODE"
        demoButton.textSize = 15f

        demoButton.setOnClickListener {
            runDemoMode()
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
        root.addView(peerStateText)
        root.addView(statusSpinner)
        root.addView(landmarkInput)
        root.addView(messageInput)

        root.addView(
            sosButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                130
            )
        )

        root.addView(
            restartButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                100
            )
        )

        root.addView(
            demoButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                100
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

        updateStatusUi()
        updatePeerStateUi()
    }

    private fun runDemoMode() {
        val demoPacket = SosPacket(
            messageId = UUID.randomUUID().toString(),
            senderName = "Demo Phone A",
            status = "Need Medical Help",
            message = "Person injured and needs urgent support",
            landmark = "Near library gate",
            timestamp = System.currentTimeMillis(),
            hopCount = 0,
            ttl = 5,
            priority = 3,
            isRelayed = false,
            receivedFrom = "DEMO"
        )

        if (!seenMessageIds.contains(demoPacket.messageId)) {
            seenMessageIds.add(demoPacket.messageId)
            alerts.add(0, demoPacket)
            persistAlert(demoPacket)
            updateAlertsUi()
        }

        addLog("DEMO: Phone A sends SOS | priority 3 | hop 0 | ttl 5")

        handler.postDelayed({
            addLog("DEMO: Phone B receives SOS from A")
        }, 700)

        handler.postDelayed({
            addLog("DEMO: Phone B relays packet | hop 1 | ttl 4")
        }, 1400)

        handler.postDelayed({
            addLog("DEMO: Phone C receives packet from B")
        }, 2100)

        handler.postDelayed({
            addLog("DEMO: Duplicate packet returns to A - ignored by messageId")
        }, 2800)

        handler.postDelayed({
            addLog("DEMO COMPLETE: A to B to C relay path simulated")
        }, 3500)
    }

    private fun loadAlertsFromDatabase() {
        val storedAlerts = database.alertDao().getAll().map { entity ->
            entity.toPacket()
        }

        alerts.clear()
        alerts.addAll(storedAlerts)

        seenMessageIds.clear()
        seenMessageIds.addAll(storedAlerts.map { packet -> packet.messageId })

        updateAlertsUi()

        if (storedAlerts.isNotEmpty()) {
            addLog("Loaded ${storedAlerts.size} stored alerts from Room database")
        }
    }

    private fun persistAlert(packet: SosPacket) {
        database.alertDao().insertAlert(packet.toEntity())
    }

    private fun updateStatusUi() {
        if (!::connectionText.isInitialized) return

        runOnUiThread {
            connectionText.text = "Connected devices: ${connectedEndpoints.size}"
        }
    }

    private fun updatePeerStateUi() {
        if (!::peerStateText.isInitialized) return

        runOnUiThread {
            if (endpointStates.isEmpty()) {
                peerStateText.text = "Peer states: none"
                return@runOnUiThread
            }

            val text = StringBuilder()
            text.append("Peer states:\n")

            endpointStates.forEach { entry ->
                val name = endpointNames[entry.key] ?: entry.key.take(6)
                text.append("- $name -> ${entry.value.name}\n")
            }

            peerStateText.text = text.toString()
        }
    }

    private fun updateAlertsUi() {
        if (!::alertText.isInitialized) return

        runOnUiThread {
            if (alerts.isEmpty()) {
                alertText.text = "Emergency Alerts\nNo alerts yet."
                return@runOnUiThread
            }

            val sortedAlerts = alerts.sortedWith(
                compareByDescending<SosPacket> { it.priority }
                    .thenByDescending { it.timestamp }
            )

            val text = StringBuilder()

            text.append("Emergency Alerts\n\n")

            sortedAlerts.forEach { alert ->
                text.append("ALERT: ${alert.status} | ${priorityLabel(alert.priority)}\n")
                text.append("From: ${alert.senderName}\n")
                text.append("Landmark: ${alert.landmark}\n")
                text.append("Message: ${alert.message}\n")
                text.append("Hop count: ${alert.hopCount} | TTL: ${alert.ttl}\n")
                text.append("Relayed: ${if (alert.isRelayed) "Yes" else "No"}\n")
                text.append("Received from: ${alert.receivedFrom}\n")
                text.append("Time: ${formatTime(alert.timestamp)}\n")
                text.append("---------------------------\n")
            }

            alertText.text = text.toString()
        }
    }

    private fun addLog(message: String) {
        if (!::logText.isInitialized) return

        runOnUiThread {
            val oldText = logText.text.toString()

            val cleanOld = if (oldText == "Network Logs\nWaiting...") {
                "Network Logs"
            } else {
                oldText
            }

            logText.text = "$cleanOld\n- $message"
        }
    }

    private fun formatTime(timestamp: Long): String {
        val formatter = SimpleDateFormat("hh:mm a", Locale.getDefault())
        return formatter.format(Date(timestamp))
    }
}

enum class PeerState {
    DISCOVERED,
    CONNECTING,
    CONNECTED,
    FAILED,
    DISCONNECTED,
    BLOCKED_DUPLICATE
}

data class DeviceReadiness(
    val bluetoothOn: Boolean,
    val wifiOn: Boolean,
    val locationOn: Boolean,
    val permissionsGranted: Boolean,
    val batterySaverOff: Boolean
) {
    val canStart: Boolean
        get() = bluetoothOn && wifiOn && locationOn && permissionsGranted
}

data class RelayJob(
    val packet: SosPacket,
    val exceptEndpointId: String?
)

data class SosPacket(
    val messageId: String,
    val senderName: String,
    val status: String,
    val message: String,
    val landmark: String,
    val timestamp: Long,
    val hopCount: Int,
    val ttl: Int,
    val priority: Int,
    val isRelayed: Boolean,
    val receivedFrom: String
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("messageId", messageId)
            put("senderName", senderName)
            put("status", status)
            put("message", message)
            put("landmark", landmark)
            put("timestamp", timestamp)
            put("hopCount", hopCount)
            put("ttl", ttl)
            put("priority", priority)
            put("isRelayed", isRelayed)
            put("receivedFrom", receivedFrom)
        }.toString()
    }

    fun toEntity(): AlertEntity {
        return AlertEntity(
            messageId = messageId,
            senderName = senderName,
            status = status,
            message = message,
            landmark = landmark,
            timestamp = timestamp,
            hopCount = hopCount,
            ttl = ttl,
            priority = priority,
            isRelayed = isRelayed,
            receivedFrom = receivedFrom
        )
    }

    companion object {
        fun fromJson(json: String): SosPacket {
            val obj = JSONObject(json)
            val status = obj.optString("status", "Info")

            return SosPacket(
                messageId = obj.getString("messageId"),
                senderName = obj.getString("senderName"),
                status = status,
                message = obj.getString("message"),
                landmark = obj.optString("landmark", "Unknown location"),
                timestamp = obj.getLong("timestamp"),
                hopCount = obj.getInt("hopCount"),
                ttl = obj.getInt("ttl"),
                priority = obj.optInt("priority", priorityForStatus(status)),
                isRelayed = obj.optBoolean("isRelayed", false),
                receivedFrom = obj.optString("receivedFrom", "UNKNOWN")
            )
        }
    }
}

@Entity(tableName = "alerts")
data class AlertEntity(
    @PrimaryKey val messageId: String,
    val senderName: String,
    val status: String,
    val message: String,
    val landmark: String,
    val timestamp: Long,
    val hopCount: Int,
    val ttl: Int,
    val priority: Int,
    val isRelayed: Boolean,
    val receivedFrom: String
) {
    fun toPacket(): SosPacket {
        return SosPacket(
            messageId = messageId,
            senderName = senderName,
            status = status,
            message = message,
            landmark = landmark,
            timestamp = timestamp,
            hopCount = hopCount,
            ttl = ttl,
            priority = priority,
            isRelayed = isRelayed,
            receivedFrom = receivedFrom
        )
    }
}

@Dao
interface AlertDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAlert(alert: AlertEntity)

    @Query("SELECT * FROM alerts ORDER BY priority DESC, timestamp DESC")
    fun getAll(): List<AlertEntity>

    @Query("UPDATE alerts SET isRelayed = :value WHERE messageId = :messageId")
    fun updateRelayed(messageId: String, value: Boolean)
}

@Database(
    entities = [AlertEntity::class],
    version = 1,
    exportSchema = false
)
abstract class DisasterDatabase : RoomDatabase() {

    abstract fun alertDao(): AlertDao

    companion object {
        @Volatile
        private var INSTANCE: DisasterDatabase? = null

        fun getDatabase(context: Context): DisasterDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DisasterDatabase::class.java,
                    "disastermesh.db"
                )
                    .allowMainThreadQueries()
                    .fallbackToDestructiveMigration()
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}

fun priorityForStatus(status: String): Int {
    return when (status) {
        "Need Medical Help" -> 3
        "Injured" -> 3
        "Trapped" -> 3
        "Need Help" -> 2
        "Need Food/Water" -> 2
        "Safe" -> 1
        "Volunteer Available" -> 1
        else -> 0
    }
}

fun priorityLabel(priority: Int): String {
    return when (priority) {
        3 -> "EMERGENCY"
        2 -> "HELP"
        1 -> "SAFE"
        else -> "INFO"
    }
}