package com.example.disastermesh.mesh

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.disastermesh.data.*
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

interface MeshListener {
    fun onLogMessage(message: String) {}
    fun onAlertReceived(packet: SosPacket) {}
    fun onDirectMessageReceived(message: DirectMessage) {}
    fun onConnectionCountChanged(count: Int) {}
    fun onPeerStatesChanged(states: Map<String, PeerState>, names: Map<String, String>) {}
    fun onMeshStatusChanged(started: Boolean) {}
}

class MeshManager(
    private val context: Context,
    val database: MeshDatabase
) {
    private val serviceId = "com.example.disastermesh.SERVICE"
    private val strategy = Strategy.P2P_CLUSTER

    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val handler = Handler(Looper.getMainLooper())

    var nodeName: String = "Node-${UUID.randomUUID().toString().take(4)}"

    val connectedEndpoints = mutableSetOf<String>()
    private val pendingEndpoints = mutableSetOf<String>()
    val seenMessageIds = mutableSetOf<String>()
    val alerts = mutableListOf<SosPacket>()
    val directMessages = mutableListOf<DirectMessage>()

    val endpointStates = mutableMapOf<String, PeerState>()
    val endpointNames = mutableMapOf<String, String>()

    private val relayQueue = mutableListOf<RelayJob>()

    private var relayRunning = false
    var meshStarted = false
        private set
    private var isAdvertising = false
    private var isDiscovering = false
    private var retryScheduled = false

    private val listeners = mutableListOf<MeshListener>()

    fun addListener(listener: MeshListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: MeshListener) {
        listeners.remove(listener)
    }

    private fun notifyLog(message: String) {
        handler.post { listeners.forEach { it.onLogMessage(message) } }
    }

    private fun notifyAlert(packet: SosPacket) {
        handler.post { listeners.forEach { it.onAlertReceived(packet) } }
    }

    private fun notifyDm(message: DirectMessage) {
        handler.post { listeners.forEach { it.onDirectMessageReceived(message) } }
    }

    private fun notifyConnectionCount() {
        val count = connectedEndpoints.size
        handler.post { listeners.forEach { it.onConnectionCountChanged(count) } }
    }

    private fun notifyPeerStates() {
        val states = endpointStates.toMap()
        val names = endpointNames.toMap()
        handler.post { listeners.forEach { it.onPeerStatesChanged(states, names) } }
    }

    private fun notifyMeshStatus() {
        val started = meshStarted
        handler.post { listeners.forEach { it.onMeshStatusChanged(started) } }
    }

    // ── Mesh lifecycle ──

    @SuppressLint("MissingPermission")
    fun startMesh() {
        if (meshStarted) return

        meshStarted = true

        startAdvertising()
        startDiscovery()

        notifyLog("Offline relay started as $nodeName")
        notifyMeshStatus()
    }

    @SuppressLint("MissingPermission")
    fun stopMesh() {
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

        notifyConnectionCount()
        notifyPeerStates()
        notifyMeshStatus()
        notifyLog("Mesh stopped")
    }

    @SuppressLint("MissingPermission")
    fun restartMesh() {
        stopMesh()
        notifyLog("Restarting relay...")
        startMesh()
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
            notifyLog("Advertising as $nodeName")
        }.addOnFailureListener {
            isAdvertising = false
            notifyLog("Advertising failed: ${it.message}")
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
            notifyLog("Discovery started")
        }.addOnFailureListener {
            isDiscovering = false
            notifyLog("Discovery failed: ${it.message}")
            scheduleMeshRetry("discovery failed")
        }
    }

    @SuppressLint("MissingPermission")
    private fun restartDiscoveryAndAdvertisingOnly() {
        if (!meshStarted) return

        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()

        isAdvertising = false
        isDiscovering = false
        pendingEndpoints.clear()

        notifyLog("Retrying discovery/advertising...")

        startAdvertising()
        startDiscovery()
    }

    private fun scheduleMeshRetry(reason: String) {
        if (retryScheduled) return

        retryScheduled = true
        notifyLog("Retry in 3s: $reason")

        handler.postDelayed({
            retryScheduled = false
            restartDiscoveryAndAdvertisingOnly()
        }, 3000)
    }

    // ── Connection callbacks ──

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {

        @SuppressLint("MissingPermission")
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            endpointNames[endpointId] = info.endpointName

            if (connectedEndpoints.contains(endpointId)) {
                endpointStates[endpointId] = PeerState.CONNECTED
                notifyPeerStates()
                return
            }

            if (pendingEndpoints.contains(endpointId)) {
                endpointStates[endpointId] = PeerState.BLOCKED_DUPLICATE
                notifyPeerStates()
                return
            }

            endpointStates[endpointId] = PeerState.DISCOVERED
            notifyPeerStates()

            if (nodeName > info.endpointName) {
                notifyLog("Found ${info.endpointName}; waiting for peer to connect")
                return
            }

            pendingEndpoints.add(endpointId)
            endpointStates[endpointId] = PeerState.CONNECTING
            notifyPeerStates()

            notifyLog("Found ${info.endpointName}; requesting connection")

            connectionsClient.requestConnection(
                nodeName,
                endpointId,
                connectionLifecycleCallback
            ).addOnFailureListener {
                pendingEndpoints.remove(endpointId)
                endpointStates[endpointId] = PeerState.FAILED
                notifyPeerStates()
                notifyLog("Request failed: ${it.message}")
                scheduleMeshRetry("connection request failed")
            }
        }

        override fun onEndpointLost(endpointId: String) {
            pendingEndpoints.remove(endpointId)
            connectedEndpoints.remove(endpointId)
            endpointStates[endpointId] = PeerState.DISCONNECTED
            notifyConnectionCount()
            notifyPeerStates()
            notifyLog("Lost endpoint ${endpointNames[endpointId] ?: endpointId.take(6)}")
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            endpointNames[endpointId] = info.endpointName
            endpointStates[endpointId] = PeerState.CONNECTING
            pendingEndpoints.add(endpointId)
            notifyPeerStates()
            notifyLog("Connection initiated with ${info.endpointName}")

            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener {
                    pendingEndpoints.remove(endpointId)
                    endpointStates[endpointId] = PeerState.FAILED
                    notifyPeerStates()
                    notifyLog("Accept failed: ${it.message}")
                    scheduleMeshRetry("accept failed")
                }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pendingEndpoints.remove(endpointId)

            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                endpointStates[endpointId] = PeerState.CONNECTED
                notifyConnectionCount()
                notifyPeerStates()
                notifyLog("Connected to ${endpointNames[endpointId] ?: "peer"}")
                syncAlertsToEndpoint(endpointId)
            } else {
                connectedEndpoints.remove(endpointId)
                endpointStates[endpointId] = PeerState.FAILED
                notifyConnectionCount()
                notifyPeerStates()
                notifyLog("Connection failed: ${result.status.statusMessage}")
                scheduleMeshRetry("connection failed")
            }
        }

        override fun onDisconnected(endpointId: String) {
            pendingEndpoints.remove(endpointId)
            connectedEndpoints.remove(endpointId)
            endpointStates[endpointId] = PeerState.DISCONNECTED
            notifyConnectionCount()
            notifyPeerStates()
            notifyLog("Peer ${endpointNames[endpointId] ?: ""} disconnected")
            scheduleMeshRetry("peer disconnected")
        }
    }

    // ── Payload handling ──

    private val payloadCallback = object : PayloadCallback() {

        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val json = String(bytes, StandardCharsets.UTF_8)

            try {
                val obj = JSONObject(json)
                val type = obj.optString("type", "broadcast")

                when (type) {
                    "dm" -> handleIncomingDm(json, endpointId)
                    else -> handleIncomingBroadcast(json, endpointId)
                }
            } catch (e: Exception) {
                notifyLog("Invalid packet received")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Small text payload — no progress UI needed
        }
    }

    private fun handleIncomingBroadcast(json: String, fromEndpointId: String) {
        handler.post {
            val packet = SosPacket.fromJson(json)

            if (seenMessageIds.contains(packet.messageId)) {
                notifyLog("Duplicate broadcast ignored")
                return@post
            }

            val receivedPacket = packet.copy(
                receivedFrom = endpointNames[fromEndpointId] ?: fromEndpointId
            )

            seenMessageIds.add(receivedPacket.messageId)
            alerts.add(0, receivedPacket)
            database.alertDao().insertAlert(receivedPacket.toEntity())
            notifyAlert(receivedPacket)
            notifyLog("Received ${priorityLabel(receivedPacket.priority)} SOS from ${receivedPacket.senderName}")

            if (receivedPacket.ttl > 0) {
                val relayedPacket = receivedPacket.copy(
                    hopCount = receivedPacket.hopCount + 1,
                    ttl = receivedPacket.ttl - 1,
                    isRelayed = true
                )
                enqueueRelay(relayedPacket, exceptEndpointId = fromEndpointId)
                database.alertDao().updateRelayed(receivedPacket.messageId, true)
                notifyLog("Relay queued | hop ${relayedPacket.hopCount} | ttl ${relayedPacket.ttl}")
            }
        }
    }

    private fun handleIncomingDm(json: String, fromEndpointId: String) {
        handler.post {
            val dm = DirectMessage.fromJson(json, isOutgoing = false)

            if (seenMessageIds.contains(dm.messageId)) return@post

            seenMessageIds.add(dm.messageId)
            directMessages.add(dm)
            database.directMessageDao().insert(dm.toEntity())
            notifyDm(dm)
            notifyLog("DM from ${dm.senderName}")
        }
    }

    // ── Sending ──

    fun createLocalAlert(status: String, message: String, landmark: String) {
        val safeLandmark = if (landmark.isBlank()) "Unknown location" else landmark.trim()

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
        database.alertDao().insertAlert(packet.toEntity())
        notifyAlert(packet)
        enqueueRelay(packet, exceptEndpointId = null)
        notifyLog("Broadcast ${priorityLabel(packet.priority)}: $status")
    }

    fun sendDirectMessage(targetEndpointId: String, content: String) {
        val targetName = endpointNames[targetEndpointId] ?: targetEndpointId

        val dm = DirectMessage(
            messageId = UUID.randomUUID().toString(),
            senderName = nodeName,
            targetName = targetName,
            content = content,
            timestamp = System.currentTimeMillis(),
            isOutgoing = true
        )

        seenMessageIds.add(dm.messageId)
        directMessages.add(dm)
        database.directMessageDao().insert(dm.toEntity())
        notifyDm(dm)

        val payload = Payload.fromBytes(
            dm.toJson().toByteArray(StandardCharsets.UTF_8)
        )

        connectionsClient.sendPayload(targetEndpointId, payload)
            .addOnSuccessListener {
                notifyLog("DM sent to $targetName")
            }
            .addOnFailureListener {
                notifyLog("DM send failed: ${it.message}")
            }
    }

    // ── Relay queue ──

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
        handler.post { processNextRelayJob() }
    }

    private fun processNextRelayJob() {
        if (relayQueue.isEmpty()) {
            relayRunning = false
            return
        }

        val job = relayQueue.removeAt(0)

        val targets = connectedEndpoints.filter { it != job.exceptEndpointId }

        if (targets.isEmpty()) {
            notifyLog("No peers for ${priorityLabel(job.packet.priority)} relay")
            handler.postDelayed({ processNextRelayJob() }, 250)
            return
        }

        targets.forEach { endpointId ->
            sendPacketToEndpoint(endpointId, job.packet)
        }

        notifyLog("Relayed ${job.packet.status} to ${targets.size} peer(s)")

        handler.postDelayed({ processNextRelayJob() }, 250)
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
            notifyLog("Synced ${sortedAlerts.size} alerts to new peer")
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
                notifyPeerStates()
                notifyLog("Send failed: ${it.message}")
                scheduleMeshRetry("send failed")
            }
    }

    // ── Data loading ──

    fun loadStoredData() {
        val storedAlerts = database.alertDao().getAll().map { it.toPacket() }
        alerts.clear()
        alerts.addAll(storedAlerts)
        seenMessageIds.addAll(storedAlerts.map { it.messageId })

        val storedDms = database.directMessageDao().getAll().map { it.toDirectMessage() }
        directMessages.clear()
        directMessages.addAll(storedDms)
        seenMessageIds.addAll(storedDms.map { it.messageId })

        if (storedAlerts.isNotEmpty() || storedDms.isNotEmpty()) {
            notifyLog("Loaded ${storedAlerts.size} alerts + ${storedDms.size} DMs from storage")
        }
    }

    fun clearAllData() {
        database.alertDao().deleteAll()
        database.directMessageDao().deleteAll()
        alerts.clear()
        directMessages.clear()
        seenMessageIds.clear()
        notifyLog("All data cleared")
    }

    // ── Demo ──

    fun runDemoMode() {
        val demoPacket = SosPacket(
            messageId = UUID.randomUUID().toString(),
            senderName = "Demo-Rescue-1",
            status = "Need Medical Help",
            message = "Person injured, needs urgent support near collapsed structure",
            landmark = "Near sector 135 library gate",
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
            database.alertDao().insertAlert(demoPacket.toEntity())
            notifyAlert(demoPacket)
        }

        notifyLog("DEMO: Rescue-1 broadcasts EMERGENCY SOS | priority 3 | hop 0")

        handler.postDelayed({
            val safePacket = SosPacket(
                messageId = UUID.randomUUID().toString(),
                senderName = "Demo-Volunteer-2",
                status = "Volunteer Available",
                message = "I am a trained first responder, heading to sector 135",
                landmark = "Cafeteria Tower 6",
                timestamp = System.currentTimeMillis(),
                hopCount = 1,
                ttl = 4,
                priority = 1,
                isRelayed = true,
                receivedFrom = "Demo-Node-B"
            )
            seenMessageIds.add(safePacket.messageId)
            alerts.add(0, safePacket)
            database.alertDao().insertAlert(safePacket.toEntity())
            notifyAlert(safePacket)
            notifyLog("DEMO: Volunteer responds via relay | hop 1 | ttl 4")
        }, 1200)

        handler.postDelayed({
            notifyLog("DEMO: Mesh relay path A→B→C verified ✓")
        }, 2400)

        handler.postDelayed({
            notifyLog("DEMO COMPLETE: 3-node relay simulated successfully")
        }, 3600)
    }

    fun getConnectedPeerList(): List<Pair<String, String>> {
        return connectedEndpoints.map { endpointId ->
            endpointId to (endpointNames[endpointId] ?: "Unknown")
        }
    }
}
