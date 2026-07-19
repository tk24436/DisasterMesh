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
import java.util.concurrent.ConcurrentHashMap

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

    val connectedEndpoints = ConcurrentHashMap.newKeySet<String>()
    private val pendingEndpoints = ConcurrentHashMap.newKeySet<String>()
    val seenMessageIds = ConcurrentHashMap.newKeySet<String>()
    val alerts = mutableListOf<SosPacket>()
    val directMessages = mutableListOf<DirectMessage>()

    // Track by endpointName to deduplicate the same physical device showing up
    // with different endpointIds across reconnection cycles
    val endpointStates = ConcurrentHashMap<String, PeerState>()
    val endpointNames = ConcurrentHashMap<String, String>()

    // Reverse lookup: endpointName -> set of endpointIds (for dedup)
    private val nameToEndpoints = ConcurrentHashMap<String, MutableSet<String>>()

    private val relayQueue = mutableListOf<RelayJob>()

    private var relayRunning = false
    var meshStarted = false
        private set
    private var isAdvertising = false
    private var isDiscovering = false

    private val listeners = mutableListOf<MeshListener>()

    fun addListener(listener: MeshListener) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) listeners.add(listener)
        }
    }

    fun removeListener(listener: MeshListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    private fun notifyLog(message: String) {
        handler.post {
            synchronized(listeners) { listeners.toList() }.forEach { it.onLogMessage(message) }
        }
    }

    private fun notifyAlert(packet: SosPacket) {
        handler.post {
            synchronized(listeners) { listeners.toList() }.forEach { it.onAlertReceived(packet) }
        }
    }

    private fun notifyDm(message: DirectMessage) {
        handler.post {
            synchronized(listeners) { listeners.toList() }.forEach { it.onDirectMessageReceived(message) }
        }
    }

    private fun notifyConnectionCount() {
        val count = connectedEndpoints.size
        handler.post {
            synchronized(listeners) { listeners.toList() }.forEach { it.onConnectionCountChanged(count) }
        }
    }

    private fun notifyPeerStates() {
        // Only show CONNECTED and CONNECTING peers (no ghosts)
        val activeStates = endpointStates.filter { (_, state) ->
            state == PeerState.CONNECTED || state == PeerState.CONNECTING || state == PeerState.DISCOVERED
        }
        val activeNames = endpointNames.filter { (id, _) -> activeStates.containsKey(id) }
        handler.post {
            synchronized(listeners) { listeners.toList() }.forEach { it.onPeerStatesChanged(activeStates, activeNames) }
        }
    }

    private fun notifyMeshStatus() {
        val started = meshStarted
        handler.post {
            synchronized(listeners) { listeners.toList() }.forEach { it.onMeshStatusChanged(started) }
        }
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

        connectedEndpoints.clear()
        pendingEndpoints.clear()
        endpointStates.clear()
        endpointNames.clear()
        nameToEndpoints.clear()

        notifyConnectionCount()
        notifyPeerStates()
        notifyMeshStatus()
        notifyLog("Mesh stopped")
    }

    @SuppressLint("MissingPermission")
    fun restartMesh() {
        stopMesh()
        handler.postDelayed({
            notifyLog("Restarting relay...")
            startMesh()
        }, 1000)
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
        }
    }

    // ── Deduplication helpers ──

    /** Check if we already have an active connection to a device with this name */
    private fun isAlreadyConnectedByName(peerName: String): Boolean {
        val existingIds = nameToEndpoints[peerName] ?: return false
        return existingIds.any { connectedEndpoints.contains(it) }
    }

    /** Check if we already have a pending connection to a device with this name */
    private fun isPendingByName(peerName: String): Boolean {
        val existingIds = nameToEndpoints[peerName] ?: return false
        return existingIds.any { pendingEndpoints.contains(it) }
    }

    /** Track the mapping of endpointName -> endpointId */
    private fun trackNameMapping(endpointId: String, peerName: String) {
        endpointNames[endpointId] = peerName
        nameToEndpoints.getOrPut(peerName) { ConcurrentHashMap.newKeySet() }.add(endpointId)
    }

    /** Clean up stale endpoint entries (DISCONNECTED/FAILED) to prevent map bloat */
    private fun cleanupStaleEndpoints() {
        val staleIds = endpointStates.filter { (_, state) ->
            state == PeerState.DISCONNECTED || state == PeerState.FAILED
        }.keys

        staleIds.forEach { id ->
            val name = endpointNames[id]
            // Only remove if this device doesn't have another active endpointId
            if (name != null) {
                val otherIds = nameToEndpoints[name]
                val hasOtherActive = otherIds?.any { otherId ->
                    otherId != id && (connectedEndpoints.contains(otherId) || pendingEndpoints.contains(otherId))
                } ?: false

                if (!hasOtherActive) {
                    // Keep the entry around briefly — don't remove it
                } else {
                    // Another ID for this device is active, so remove this stale one
                    endpointStates.remove(id)
                    endpointNames.remove(id)
                    otherIds?.remove(id)
                }
            }
        }
    }

    // ── Connection callbacks ──

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {

        @SuppressLint("MissingPermission")
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val peerName = info.endpointName
            trackNameMapping(endpointId, peerName)

            // Already connected to this endpoint specifically
            if (connectedEndpoints.contains(endpointId)) {
                endpointStates[endpointId] = PeerState.CONNECTED
                notifyPeerStates()
                return
            }

            // Already connected to a device with this same name (different endpointId)
            if (isAlreadyConnectedByName(peerName)) {
                notifyLog("Ignoring duplicate discovery of $peerName")
                return
            }

            // Already have a pending connection to this endpoint or this name
            if (pendingEndpoints.contains(endpointId) || isPendingByName(peerName)) {
                return
            }

            endpointStates[endpointId] = PeerState.DISCOVERED
            notifyPeerStates()

            // Collision avoidance: the device whose name is lexicographically smaller initiates
            if (nodeName > peerName) {
                notifyLog("Found $peerName; waiting for peer to connect")
                return
            }

            // If names are identical (unlikely but possible), use endpointId as tiebreaker
            if (nodeName == peerName) {
                return  // Don't connect to ourselves
            }

            pendingEndpoints.add(endpointId)
            endpointStates[endpointId] = PeerState.CONNECTING
            notifyPeerStates()

            notifyLog("Found $peerName; requesting connection")

            connectionsClient.requestConnection(
                nodeName,
                endpointId,
                connectionLifecycleCallback
            ).addOnFailureListener {
                pendingEndpoints.remove(endpointId)
                endpointStates[endpointId] = PeerState.FAILED
                notifyPeerStates()
                notifyLog("Request to $peerName failed: ${it.message}")
                cleanupStaleEndpoints()
            }
        }

        override fun onEndpointLost(endpointId: String) {
            val name = endpointNames[endpointId] ?: endpointId.take(6)
            pendingEndpoints.remove(endpointId)

            // Only update state if this endpoint was not connected
            if (!connectedEndpoints.contains(endpointId)) {
                endpointStates.remove(endpointId)
                endpointNames.remove(endpointId)
                nameToEndpoints[name]?.remove(endpointId)
            }

            notifyConnectionCount()
            notifyPeerStates()
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val peerName = info.endpointName
            trackNameMapping(endpointId, peerName)

            // Reject if we already have an active connection to this device name
            if (isAlreadyConnectedByName(peerName)) {
                notifyLog("Rejecting duplicate connection from $peerName")
                connectionsClient.rejectConnection(endpointId)
                return
            }

            endpointStates[endpointId] = PeerState.CONNECTING
            pendingEndpoints.add(endpointId)
            notifyPeerStates()
            notifyLog("Connection initiated with $peerName")

            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener {
                    pendingEndpoints.remove(endpointId)
                    endpointStates[endpointId] = PeerState.FAILED
                    notifyPeerStates()
                    notifyLog("Accept failed: ${it.message}")
                    cleanupStaleEndpoints()
                }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pendingEndpoints.remove(endpointId)

            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                endpointStates[endpointId] = PeerState.CONNECTED
                notifyConnectionCount()
                notifyPeerStates()
                notifyLog("✅ Connected to ${endpointNames[endpointId] ?: "peer"}")
                syncAlertsToEndpoint(endpointId)
                cleanupStaleEndpoints()
            } else {
                connectedEndpoints.remove(endpointId)
                endpointStates[endpointId] = PeerState.FAILED
                notifyConnectionCount()
                notifyPeerStates()
                notifyLog("Connection failed: ${result.status.statusMessage}")
                cleanupStaleEndpoints()
            }
        }

        override fun onDisconnected(endpointId: String) {
            pendingEndpoints.remove(endpointId)
            connectedEndpoints.remove(endpointId)
            endpointStates[endpointId] = PeerState.DISCONNECTED
            notifyConnectionCount()
            notifyPeerStates()
            notifyLog("Peer ${endpointNames[endpointId] ?: ""} disconnected")

            // Clean up after a brief delay (allow rediscovery first)
            handler.postDelayed({ cleanupStaleEndpoints() }, 5000)
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
                return@post
            }

            val receivedPacket = packet.copy(
                receivedFrom = endpointNames[fromEndpointId] ?: fromEndpointId
            )

            seenMessageIds.add(receivedPacket.messageId)
            synchronized(alerts) { alerts.add(0, receivedPacket) }
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
            }
        }
    }

    private fun handleIncomingDm(json: String, fromEndpointId: String) {
        handler.post {
            val dm = DirectMessage.fromJson(json, isOutgoing = false)

            if (seenMessageIds.contains(dm.messageId)) return@post

            seenMessageIds.add(dm.messageId)
            synchronized(directMessages) { directMessages.add(dm) }
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
        synchronized(alerts) { alerts.add(0, packet) }
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
        synchronized(directMessages) { directMessages.add(dm) }
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
        synchronized(relayQueue) {
            relayQueue.add(RelayJob(packet, exceptEndpointId))
            relayQueue.sortWith(
                compareByDescending<RelayJob> { it.packet.priority }
                    .thenByDescending { it.packet.timestamp }
            )
        }
        flushRelayQueue()
    }

    private fun flushRelayQueue() {
        if (relayRunning) return
        relayRunning = true
        handler.post { processNextRelayJob() }
    }

    private fun processNextRelayJob() {
        val job = synchronized(relayQueue) {
            if (relayQueue.isEmpty()) {
                relayRunning = false
                return
            }
            relayQueue.removeAt(0)
        }

        val targets = connectedEndpoints.filter { it != job.exceptEndpointId }

        if (targets.isEmpty()) {
            handler.postDelayed({ processNextRelayJob() }, 500)
            return
        }

        targets.forEach { endpointId ->
            sendPacketToEndpoint(endpointId, job.packet)
        }

        handler.postDelayed({ processNextRelayJob() }, 500)
    }

    private fun syncAlertsToEndpoint(endpointId: String) {
        val sortedAlerts = synchronized(alerts) {
            alerts.sortedWith(
                compareByDescending<SosPacket> { it.priority }
                    .thenByDescending { it.timestamp }
            )
        }

        sortedAlerts.forEach { packet ->
            sendPacketToEndpoint(endpointId, packet)
        }

        if (sortedAlerts.isNotEmpty()) {
            notifyLog("Synced ${sortedAlerts.size} alerts to new peer")
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendPacketToEndpoint(endpointId: String, packet: SosPacket) {
        if (!connectedEndpoints.contains(endpointId)) return

        val payload = Payload.fromBytes(
            packet.toJson().toByteArray(StandardCharsets.UTF_8)
        )

        connectionsClient.sendPayload(endpointId, payload)
            .addOnSuccessListener {
                database.alertDao().updateRelayed(packet.messageId, true)
            }
            .addOnFailureListener {
                notifyLog("Send failed to ${endpointNames[endpointId]}: ${it.message}")
            }
    }

    // ── Data loading ──

    fun loadStoredData() {
        val storedAlerts = database.alertDao().getAll().map { it.toPacket() }
        synchronized(alerts) {
            alerts.clear()
            alerts.addAll(storedAlerts)
        }
        seenMessageIds.addAll(storedAlerts.map { it.messageId })

        val storedDms = database.directMessageDao().getAll().map { it.toDirectMessage() }
        synchronized(directMessages) {
            directMessages.clear()
            directMessages.addAll(storedDms)
        }
        seenMessageIds.addAll(storedDms.map { it.messageId })

        if (storedAlerts.isNotEmpty() || storedDms.isNotEmpty()) {
            notifyLog("Loaded ${storedAlerts.size} alerts + ${storedDms.size} DMs from storage")
        }
    }

    fun clearAllData() {
        database.alertDao().deleteAll()
        database.directMessageDao().deleteAll()
        synchronized(alerts) { alerts.clear() }
        synchronized(directMessages) { directMessages.clear() }
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
            synchronized(alerts) { alerts.add(0, demoPacket) }
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
            synchronized(alerts) { alerts.add(0, safePacket) }
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
        // Deduplicate by name: only return one endpointId per unique device name
        val seenNames = mutableSetOf<String>()
        return connectedEndpoints.mapNotNull { endpointId ->
            val name = endpointNames[endpointId] ?: "Unknown"
            if (seenNames.add(name)) {
                endpointId to name
            } else {
                null
            }
        }
    }
}
