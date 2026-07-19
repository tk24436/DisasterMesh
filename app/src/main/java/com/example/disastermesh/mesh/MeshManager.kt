package com.example.disastermesh.mesh

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.disastermesh.data.*
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import org.json.JSONArray
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
    fun onMeshPeersChanged(peers: Set<String>) {}
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

    // Direct connections
    val connectedEndpoints = ConcurrentHashMap.newKeySet<String>()
    private val pendingEndpoints = ConcurrentHashMap.newKeySet<String>()
    val endpointNames = ConcurrentHashMap<String, String>()

    // Mesh-wide peer discovery (gossip protocol)
    // All peers known across the entire mesh, including multi-hop
    val meshPeers = ConcurrentHashMap.newKeySet<String>()

    val seenMessageIds = ConcurrentHashMap.newKeySet<String>()
    val alerts = mutableListOf<SosPacket>()
    val directMessages = mutableListOf<DirectMessage>()

    private val relayQueue = mutableListOf<RelayJob>()

    private var relayRunning = false
    var meshStarted = false
        private set
    private var isAdvertising = false
    private var isDiscovering = false

    private val listeners = mutableListOf<MeshListener>()

    // Peer announcement interval
    private var peerAnnounceRunnable: Runnable? = null
    private val PEER_ANNOUNCE_INTERVAL = 10_000L // every 10 seconds

    fun addListener(listener: MeshListener) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) listeners.add(listener)
        }
    }

    fun removeListener(listener: MeshListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private fun forEachListener(action: (MeshListener) -> Unit) {
        handler.post {
            synchronized(listeners) { listeners.toList() }.forEach(action)
        }
    }

    private fun notifyLog(msg: String) = forEachListener { it.onLogMessage(msg) }
    private fun notifyAlert(p: SosPacket) = forEachListener { it.onAlertReceived(p) }
    private fun notifyDm(m: DirectMessage) = forEachListener { it.onDirectMessageReceived(m) }
    private fun notifyMeshStatus() { val s = meshStarted; forEachListener { it.onMeshStatusChanged(s) } }
    private fun notifyConnectionCount() { val c = connectedEndpoints.size; forEachListener { it.onConnectionCountChanged(c) } }
    private fun notifyMeshPeers() { val p = meshPeers.toSet(); forEachListener { it.onMeshPeersChanged(p) } }
    private fun notifyPeerStates() {
        // Build a clean map of only connected peers
        val states = mutableMapOf<String, PeerState>()
        val names = mutableMapOf<String, String>()
        connectedEndpoints.forEach { id ->
            states[id] = PeerState.CONNECTED
            names[id] = endpointNames[id] ?: "Unknown"
        }
        forEachListener { it.onPeerStatesChanged(states, names) }
    }

    // ── Mesh lifecycle ──

    @SuppressLint("MissingPermission")
    fun startMesh() {
        if (meshStarted) return
        meshStarted = true
        meshPeers.add(nodeName) // We are always a known peer

        startAdvertising()
        startDiscovery()
        startPeerAnnouncements()

        notifyLog("Mesh started as $nodeName")
        notifyMeshStatus()
    }

    @SuppressLint("MissingPermission")
    fun stopMesh() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()

        stopPeerAnnouncements()
        isAdvertising = false
        isDiscovering = false
        meshStarted = false

        connectedEndpoints.clear()
        pendingEndpoints.clear()
        endpointNames.clear()

        notifyConnectionCount()
        notifyPeerStates()
        notifyMeshStatus()
        notifyLog("Mesh stopped")
    }

    @SuppressLint("MissingPermission")
    fun restartMesh() {
        stopMesh()
        handler.postDelayed({
            notifyLog("Restarting mesh...")
            startMesh()
        }, 1500)
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        if (isAdvertising) return
        connectionsClient.startAdvertising(
            nodeName, serviceId, connectionLifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(strategy).build()
        ).addOnSuccessListener {
            isAdvertising = true
            notifyLog("Advertising as $nodeName")
        }.addOnFailureListener {
            isAdvertising = false
            notifyLog("Advertising failed: ${it.message}")
            // Retry after delay
            handler.postDelayed({ if (meshStarted && !isAdvertising) startAdvertising() }, 5000)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        if (isDiscovering) return
        connectionsClient.startDiscovery(
            serviceId, endpointDiscoveryCallback,
            DiscoveryOptions.Builder().setStrategy(strategy).build()
        ).addOnSuccessListener {
            isDiscovering = true
            notifyLog("Discovery started")
        }.addOnFailureListener {
            isDiscovering = false
            notifyLog("Discovery failed: ${it.message}")
            handler.postDelayed({ if (meshStarted && !isDiscovering) startDiscovery() }, 5000)
        }
    }

    // ── Peer announcements (gossip protocol) ──

    private fun startPeerAnnouncements() {
        stopPeerAnnouncements()
        peerAnnounceRunnable = object : Runnable {
            override fun run() {
                if (!meshStarted) return
                broadcastPeerList()
                handler.postDelayed(this, PEER_ANNOUNCE_INTERVAL)
            }
        }
        handler.postDelayed(peerAnnounceRunnable!!, 3000) // first announce after 3s
    }

    private fun stopPeerAnnouncements() {
        peerAnnounceRunnable?.let { handler.removeCallbacks(it) }
        peerAnnounceRunnable = null
    }

    /** Broadcast our known peer list to all directly connected nodes */
    private fun broadcastPeerList() {
        if (connectedEndpoints.isEmpty()) return

        val json = JSONObject().apply {
            put("type", "peers")
            put("from", nodeName)
            put("peers", JSONArray(meshPeers.toList()))
        }.toString()

        val payload = Payload.fromBytes(json.toByteArray(StandardCharsets.UTF_8))
        connectedEndpoints.forEach { endpointId ->
            connectionsClient.sendPayload(endpointId, payload)
        }
    }

    /** Handle incoming peer list from a neighbor — merge and re-gossip if new peers found */
    private fun handlePeerAnnouncement(json: String) {
        val obj = JSONObject(json)
        val peersArray = obj.getJSONArray("peers")
        var newPeersFound = false

        for (i in 0 until peersArray.length()) {
            val peerName = peersArray.getString(i)
            if (peerName != nodeName && meshPeers.add(peerName)) {
                newPeersFound = true
            }
        }

        if (newPeersFound) {
            notifyLog("Discovered ${meshPeers.size - 1} peers across mesh")
            notifyMeshPeers()
            // Re-gossip our updated list so the new names propagate further
            broadcastPeerList()
        }
    }

    // ── Connection callbacks ──

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {

        @SuppressLint("MissingPermission")
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val peerName = info.endpointName
            endpointNames[endpointId] = peerName
            meshPeers.add(peerName)

            // Already connected to this specific endpoint
            if (connectedEndpoints.contains(endpointId)) return

            // Already connected to a device with this name
            if (isConnectedToName(peerName)) return

            // Already have a pending request for this endpoint
            if (pendingEndpoints.contains(endpointId)) return

            // ALWAYS try to connect — no collision avoidance
            // Nearby Connections handles simultaneous requests gracefully
            pendingEndpoints.add(endpointId)
            notifyLog("Connecting to $peerName...")

            connectionsClient.requestConnection(nodeName, endpointId, connectionLifecycleCallback)
                .addOnFailureListener {
                    pendingEndpoints.remove(endpointId)
                    notifyLog("Request to $peerName failed: ${it.message}")
                    // Retry once after a short delay
                    handler.postDelayed({
                        if (meshStarted && !connectedEndpoints.contains(endpointId) && !pendingEndpoints.contains(endpointId)) {
                            notifyLog("Retrying connection to $peerName...")
                            pendingEndpoints.add(endpointId)
                            connectionsClient.requestConnection(nodeName, endpointId, connectionLifecycleCallback)
                                .addOnFailureListener { pendingEndpoints.remove(endpointId) }
                        }
                    }, 3000)
                }
        }

        override fun onEndpointLost(endpointId: String) {
            pendingEndpoints.remove(endpointId)
            if (connectedEndpoints.remove(endpointId)) {
                notifyConnectionCount()
                notifyPeerStates()
            }
            notifyLog("Lost ${endpointNames[endpointId] ?: endpointId.take(6)}")
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val peerName = info.endpointName
            endpointNames[endpointId] = peerName
            meshPeers.add(peerName)
            pendingEndpoints.add(endpointId)

            // Reject only if we're already CONNECTED to this exact same name
            if (isConnectedToName(peerName)) {
                notifyLog("Rejecting duplicate from $peerName")
                connectionsClient.rejectConnection(endpointId)
                pendingEndpoints.remove(endpointId)
                return
            }

            notifyLog("Accepting connection from $peerName")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener {
                    pendingEndpoints.remove(endpointId)
                    notifyLog("Accept failed: ${it.message}")
                }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pendingEndpoints.remove(endpointId)
            val peerName = endpointNames[endpointId] ?: "peer"

            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                notifyConnectionCount()
                notifyPeerStates()
                notifyLog("✅ Connected to $peerName")

                // Immediately exchange peer lists and sync alerts
                syncAlertsToEndpoint(endpointId)
                handler.postDelayed({ broadcastPeerList() }, 500)
            } else {
                connectedEndpoints.remove(endpointId)
                notifyConnectionCount()
                notifyPeerStates()
                notifyLog("Connection to $peerName failed: ${result.status.statusMessage}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            pendingEndpoints.remove(endpointId)
            connectedEndpoints.remove(endpointId)
            val peerName = endpointNames[endpointId] ?: ""
            notifyConnectionCount()
            notifyPeerStates()
            notifyLog("$peerName disconnected")
            // Don't remove from meshPeers — they're still reachable via other routes
        }
    }

    private fun isConnectedToName(peerName: String): Boolean {
        return connectedEndpoints.any { endpointNames[it] == peerName }
    }

    // ── Payload handling ──

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val json = String(bytes, StandardCharsets.UTF_8)
            try {
                val obj = JSONObject(json)
                when (obj.optString("type", "broadcast")) {
                    "dm" -> handleIncomingDm(json, endpointId)
                    "peers" -> handlePeerAnnouncement(json)
                    else -> handleIncomingBroadcast(json, endpointId)
                }
            } catch (e: Exception) {
                notifyLog("Invalid packet")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun handleIncomingBroadcast(json: String, fromEndpointId: String) {
        handler.post {
            val packet = SosPacket.fromJson(json)
            if (seenMessageIds.contains(packet.messageId)) return@post

            val received = packet.copy(receivedFrom = endpointNames[fromEndpointId] ?: fromEndpointId)
            seenMessageIds.add(received.messageId)
            synchronized(alerts) { alerts.add(0, received) }
            database.alertDao().insertAlert(received.toEntity())
            notifyAlert(received)
            notifyLog("SOS from ${received.senderName}: ${received.status}")

            // Relay if TTL remaining
            if (received.ttl > 0) {
                val relayed = received.copy(hopCount = received.hopCount + 1, ttl = received.ttl - 1, isRelayed = true)
                enqueueRelay(relayed, exceptEndpointId = fromEndpointId)
            }
        }
    }

    private fun handleIncomingDm(json: String, fromEndpointId: String) {
        handler.post {
            val dm = DirectMessage.fromJson(json, isOutgoing = false)
            if (seenMessageIds.contains(dm.messageId)) return@post
            seenMessageIds.add(dm.messageId)

            // Is this DM addressed to us?
            if (dm.targetName == nodeName) {
                // It's for us — store and display
                synchronized(directMessages) { directMessages.add(dm) }
                database.directMessageDao().insert(dm.toEntity())
                notifyDm(dm)
                notifyLog("DM from ${dm.senderName}")
            } else {
                // Not for us — relay it to all connected peers (except sender)
                relayDm(json, fromEndpointId)
                notifyLog("Relaying DM from ${dm.senderName} → ${dm.targetName}")
            }
        }
    }

    /** Forward a DM packet to all connected peers except the one we received it from */
    @SuppressLint("MissingPermission")
    private fun relayDm(json: String, exceptEndpointId: String) {
        val payload = Payload.fromBytes(json.toByteArray(StandardCharsets.UTF_8))
        connectedEndpoints.filter { it != exceptEndpointId }.forEach { endpointId ->
            connectionsClient.sendPayload(endpointId, payload)
        }
    }

    // ── Sending ──

    fun createLocalAlert(status: String, message: String, landmark: String) {
        val safeLandmark = if (landmark.isBlank()) "Unknown location" else landmark.trim()
        val packet = SosPacket(
            messageId = UUID.randomUUID().toString(),
            senderName = nodeName, status = status, message = message,
            landmark = safeLandmark, timestamp = System.currentTimeMillis(),
            hopCount = 0, ttl = 5, priority = priorityForStatus(status),
            isRelayed = false, receivedFrom = "LOCAL"
        )
        seenMessageIds.add(packet.messageId)
        synchronized(alerts) { alerts.add(0, packet) }
        database.alertDao().insertAlert(packet.toEntity())
        notifyAlert(packet)
        enqueueRelay(packet, exceptEndpointId = null)
        notifyLog("Broadcast ${priorityLabel(packet.priority)}: $status")
    }

    /**
     * Send a DM. If the target is directly connected, send directly.
     * Otherwise, flood it to all connected peers (mesh relay).
     */
    @SuppressLint("MissingPermission")
    fun sendDirectMessage(targetName: String, content: String) {
        val dm = DirectMessage(
            messageId = UUID.randomUUID().toString(),
            senderName = nodeName, targetName = targetName,
            content = content, timestamp = System.currentTimeMillis(),
            isOutgoing = true
        )

        seenMessageIds.add(dm.messageId)
        synchronized(directMessages) { directMessages.add(dm) }
        database.directMessageDao().insert(dm.toEntity())
        notifyDm(dm)

        val payload = Payload.fromBytes(dm.toJson().toByteArray(StandardCharsets.UTF_8))

        // Try direct first
        val directEndpoint = connectedEndpoints.firstOrNull { endpointNames[it] == targetName }
        if (directEndpoint != null) {
            connectionsClient.sendPayload(directEndpoint, payload)
                .addOnSuccessListener { notifyLog("DM sent to $targetName (direct)") }
                .addOnFailureListener { notifyLog("DM failed: ${it.message}") }
        } else {
            // Target is not directly connected — flood to all peers (mesh relay)
            connectedEndpoints.forEach { endpointId ->
                connectionsClient.sendPayload(endpointId, payload)
            }
            notifyLog("DM sent to $targetName (via mesh relay)")
        }
    }

    // ── Relay queue ──

    private fun enqueueRelay(packet: SosPacket, exceptEndpointId: String?) {
        synchronized(relayQueue) {
            relayQueue.add(RelayJob(packet, exceptEndpointId))
            relayQueue.sortWith(compareByDescending<RelayJob> { it.packet.priority }.thenByDescending { it.packet.timestamp })
        }
        flushRelayQueue()
    }

    private fun flushRelayQueue() {
        if (relayRunning) return
        relayRunning = true
        handler.post { processNextRelayJob() }
    }

    @SuppressLint("MissingPermission")
    private fun processNextRelayJob() {
        val job = synchronized(relayQueue) {
            if (relayQueue.isEmpty()) { relayRunning = false; return }
            relayQueue.removeAt(0)
        }
        val targets = connectedEndpoints.filter { it != job.exceptEndpointId }
        if (targets.isNotEmpty()) {
            val payload = Payload.fromBytes(job.packet.toJson().toByteArray(StandardCharsets.UTF_8))
            targets.forEach { connectionsClient.sendPayload(it, payload) }
        }
        handler.postDelayed({ processNextRelayJob() }, 300)
    }

    @SuppressLint("MissingPermission")
    private fun syncAlertsToEndpoint(endpointId: String) {
        val sorted = synchronized(alerts) {
            alerts.sortedWith(compareByDescending<SosPacket> { it.priority }.thenByDescending { it.timestamp })
        }
        sorted.forEach { packet ->
            if (connectedEndpoints.contains(endpointId)) {
                val payload = Payload.fromBytes(packet.toJson().toByteArray(StandardCharsets.UTF_8))
                connectionsClient.sendPayload(endpointId, payload)
            }
        }
        if (sorted.isNotEmpty()) notifyLog("Synced ${sorted.size} alerts to new peer")
    }

    // ── Data loading ──

    fun loadStoredData() {
        val storedAlerts = database.alertDao().getAll().map { it.toPacket() }
        synchronized(alerts) { alerts.clear(); alerts.addAll(storedAlerts) }
        seenMessageIds.addAll(storedAlerts.map { it.messageId })

        val storedDms = database.directMessageDao().getAll().map { it.toDirectMessage() }
        synchronized(directMessages) { directMessages.clear(); directMessages.addAll(storedDms) }
        seenMessageIds.addAll(storedDms.map { it.messageId })

        if (storedAlerts.isNotEmpty() || storedDms.isNotEmpty())
            notifyLog("Loaded ${storedAlerts.size} alerts + ${storedDms.size} DMs")
    }

    fun clearAllData() {
        database.alertDao().deleteAll()
        database.directMessageDao().deleteAll()
        synchronized(alerts) { alerts.clear() }
        synchronized(directMessages) { directMessages.clear() }
        seenMessageIds.clear()
        notifyLog("All data cleared")
    }

    // ── Peer list helpers ──

    /** Get directly connected peers (deduplicated by name) */
    fun getConnectedPeerList(): List<Pair<String, String>> {
        val seen = mutableSetOf<String>()
        return connectedEndpoints.mapNotNull { id ->
            val name = endpointNames[id] ?: "Unknown"
            if (seen.add(name)) id to name else null
        }
    }

    /** Get all mesh peers (including multi-hop), excluding self */
    fun getAllMeshPeers(): Set<String> {
        return meshPeers.filter { it != nodeName }.toSet()
    }

    // ── Demo ──

    fun runDemoMode() {
        val demoPacket = SosPacket(
            messageId = UUID.randomUUID().toString(), senderName = "Demo-Rescue-1",
            status = "Need Medical Help", message = "Person injured near collapsed structure",
            landmark = "Sector 135 library gate", timestamp = System.currentTimeMillis(),
            hopCount = 0, ttl = 5, priority = 3, isRelayed = false, receivedFrom = "DEMO"
        )
        seenMessageIds.add(demoPacket.messageId)
        synchronized(alerts) { alerts.add(0, demoPacket) }
        database.alertDao().insertAlert(demoPacket.toEntity())
        notifyAlert(demoPacket)
        notifyLog("DEMO: Emergency SOS broadcast")

        // Simulate mesh peers
        meshPeers.addAll(listOf("Demo-Rescue-1", "Demo-Volunteer-2", "Demo-Node-B", "Demo-Node-C"))
        notifyMeshPeers()

        handler.postDelayed({
            val p2 = SosPacket(
                messageId = UUID.randomUUID().toString(), senderName = "Demo-Volunteer-2",
                status = "Volunteer Available", message = "Heading to sector 135",
                landmark = "Cafeteria Tower 6", timestamp = System.currentTimeMillis(),
                hopCount = 2, ttl = 3, priority = 1, isRelayed = true, receivedFrom = "Demo-Node-C"
            )
            seenMessageIds.add(p2.messageId)
            synchronized(alerts) { alerts.add(0, p2) }
            database.alertDao().insertAlert(p2.toEntity())
            notifyAlert(p2)
            notifyLog("DEMO: Volunteer relay via Node-B → Node-C (2 hops)")
        }, 1500)

        handler.postDelayed({ notifyLog("DEMO COMPLETE: mesh relay verified ✓") }, 3000)
    }
}
