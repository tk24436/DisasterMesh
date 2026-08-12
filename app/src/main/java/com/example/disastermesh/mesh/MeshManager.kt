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
    fun onTopologyChanged(graph: Map<String, Set<String>>) {}
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
        set(value) {
            meshPeers.remove(field)
            field = value
            if (meshStarted) meshPeers[value] = System.currentTimeMillis()
        }

    // ── Smart Topology Constants ──
    companion object {
        /** Maximum number of direct Nearby Connections to maintain */
        const val MAX_CONNECTIONS = 4

        /** Minimum connections before self-healing kicks in */
        const val MIN_CONNECTIONS = 2

        /** How many hops away a peer can be before we consider a direct connection unnecessary */
        const val REDUNDANCY_HOP_THRESHOLD = 2

        /** Delay between connection retry attempts (exponential backoff base) */
        const val BACKOFF_BASE_MS = 3000L

        /** Maximum backoff delay */
        const val BACKOFF_MAX_MS = 30000L
    }

    // Direct connections
    val connectedEndpoints = ConcurrentHashMap.newKeySet<String>()
    private val pendingEndpoints = ConcurrentHashMap.newKeySet<String>()
    val endpointNames = ConcurrentHashMap<String, String>()

    val endpointStates: Map<String, PeerState>
        get() = connectedEndpoints.associateWith { PeerState.CONNECTED }

    // ── Network Topology Graph ──
    // Maps node name -> set of directly connected peer names
    // This is the "mental map" each device builds from heartbeats
    val networkGraph = ConcurrentHashMap<String, Set<String>>()

    // Track which peers we intentionally skipped (redundant)
    private val skippedEndpoints = ConcurrentHashMap.newKeySet<String>()

    // Backoff tracker: endpointId -> retry count
    private val retryCount = ConcurrentHashMap<String, Int>()

    // Mesh-wide peer discovery (gossip protocol)
    // Tracks Name -> LastSeenTimestamp
    val meshPeers = ConcurrentHashMap<String, Long>()

    // Bounded set to prevent memory leak on long-running deployments.
    // Evicts oldest entry when size exceeds maxSize.
    class BoundedSet(private val maxSize: Int) {
        private val set = LinkedHashSet<String>()
        @Synchronized fun add(id: String): Boolean {
            if (set.contains(id)) return false
            if (set.size >= maxSize) set.remove(set.iterator().next())
            set.add(id)
            return true
        }
        @Synchronized fun contains(id: String) = set.contains(id)
        @Synchronized fun addAll(ids: Collection<String>) = ids.forEach { add(it) }
        @Synchronized fun clear() = set.clear()
    }

    val seenMessageIds = BoundedSet(500)   // DMs + SOS alerts
    private val seenHeartbeatIds = BoundedSet(200)  // Heartbeats only
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

    // Staleness check — proactively push Online→Offline transitions to UI
    private var stalenessRunnable: Runnable? = null
    private val PEER_OFFLINE_THRESHOLD = 45_000L
    private val STALENESS_CHECK_INTERVAL = 15_000L

    // ── Nostr bridge ──
    var nostrService: NostrService? = null

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

    /** Public entry point for external components (e.g. NostrService) to emit log messages */
    fun logFromExternal(msg: String) = notifyLog(msg)
    private fun notifyAlert(p: SosPacket) = forEachListener { it.onAlertReceived(p) }
    private fun notifyDm(m: DirectMessage) = forEachListener { it.onDirectMessageReceived(m) }
    private fun notifyMeshStatus() { val s = meshStarted; forEachListener { it.onMeshStatusChanged(s) } }
    private fun notifyConnectionCount() { val c = connectedEndpoints.size; forEachListener { it.onConnectionCountChanged(c) } }
    private fun notifyMeshPeers() { val p = meshPeers.keys.toSet(); forEachListener { it.onMeshPeersChanged(p) } }
    private fun notifyTopology() {
        val snapshot = networkGraph.mapValues { it.value.toSet() }
        forEachListener { it.onTopologyChanged(snapshot) }
    }
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
        meshPeers[nodeName] = System.currentTimeMillis() // We are always a known peer

        startAdvertising()
        startDiscovery()
        startPeerAnnouncements()
        startStalenessCheck()

        notifyLog("Mesh started as $nodeName")
        notifyMeshStatus()
    }

    @SuppressLint("MissingPermission")
    fun stopMesh() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()

        stopPeerAnnouncements()
        stopStalenessCheck()
        isAdvertising = false
        isDiscovering = false
        meshStarted = false

        connectedEndpoints.clear()
        pendingEndpoints.clear()
        endpointNames.clear()
        skippedEndpoints.clear()
        retryCount.clear()
        networkGraph.clear()

        notifyConnectionCount()
        notifyPeerStates()
        notifyMeshStatus()
        notifyTopology()
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

    // ── Smart Topology: Graph helpers ──

    /**
     * BFS to find the shortest hop count from [startNode] to [targetNode]
     * using the known networkGraph. Returns Int.MAX_VALUE if unreachable.
     */
    fun hopsTo(targetNode: String, startNode: String = nodeName): Int {
        if (startNode == targetNode) return 0
        val visited = mutableSetOf(startNode)
        val queue = ArrayDeque<Pair<String, Int>>() // (node, depth)
        queue.add(startNode to 0)

        while (queue.isNotEmpty()) {
            val (current, depth) = queue.removeFirst()
            val neighbors = networkGraph[current] ?: continue
            for (neighbor in neighbors) {
                if (neighbor == targetNode) return depth + 1
                if (visited.add(neighbor)) {
                    queue.add(neighbor to depth + 1)
                }
            }
        }
        return Int.MAX_VALUE
    }

    /**
     * Decide whether to accept a new direct connection to [peerName].
     * Returns true if the connection is needed, false if redundant.
     */
    private fun shouldConnect(peerName: String): Boolean {
        // Always connect if we're below minimum
        if (connectedEndpoints.size < MIN_CONNECTIONS) return true

        // Don't exceed max
        if (connectedEndpoints.size >= MAX_CONNECTIONS) {
            notifyLog("⛔ At max connections ($MAX_CONNECTIONS), skipping $peerName")
            return false
        }

        // Check if already reachable within REDUNDANCY_HOP_THRESHOLD
        val hops = hopsTo(peerName)
        if (hops <= REDUNDANCY_HOP_THRESHOLD) {
            notifyLog("↪ $peerName already reachable in $hops hops, skipping direct connect")
            return false
        }

        return true
    }

    /**
     * Get peers reachable only via relay (multi-hop), not directly connected.
     */
    fun getRelayPeers(): Set<String> {
        val directNames = connectedEndpoints.mapNotNull { endpointNames[it] }.toSet()
        return meshPeers.keys.filter { peer ->
            peer != nodeName && peer !in directNames
        }.toSet()
    }

    /**
     * Get directly connected peer names.
     */
    fun getDirectPeerNames(): Set<String> {
        return connectedEndpoints.mapNotNull { endpointNames[it] }.toSet()
    }

    // ── Peer announcements (Enhanced Heartbeat protocol) ──

    private fun startPeerAnnouncements() {
        stopPeerAnnouncements()
        peerAnnounceRunnable = object : Runnable {
            override fun run() {
                if (!meshStarted) return
                sendHeartbeat()
                handler.postDelayed(this, PEER_ANNOUNCE_INTERVAL)
            }
        }
        handler.postDelayed(peerAnnounceRunnable!!, 3000) // first announce after 3s
    }

    private fun stopPeerAnnouncements() {
        peerAnnounceRunnable?.let { handler.removeCallbacks(it) }
        peerAnnounceRunnable = null
    }

    private fun startStalenessCheck() {
        stopStalenessCheck()
        stalenessRunnable = object : Runnable {
            private var prevOnlineSet = emptySet<String>()
            override fun run() {
                if (!meshStarted) return
                val now = System.currentTimeMillis()
                val currentOnline = meshPeers.keys
                    .filter { it != nodeName && (now - (meshPeers[it] ?: 0)) < PEER_OFFLINE_THRESHOLD }
                    .toSet()
                if (currentOnline != prevOnlineSet) {
                    prevOnlineSet = currentOnline
                    notifyMeshPeers()
                }

                // Prune stale entries from the topology graph
                val staleNodes = meshPeers.entries
                    .filter { (now - it.value) >= PEER_OFFLINE_THRESHOLD && it.key != nodeName }
                    .map { it.key }
                staleNodes.forEach { staleNode ->
                    networkGraph.remove(staleNode)
                    networkGraph.forEach { (_, neighbors) ->
                        (neighbors as? MutableSet)?.remove(staleNode)
                    }
                }
                if (staleNodes.isNotEmpty()) notifyTopology()

                handler.postDelayed(this, STALENESS_CHECK_INTERVAL)
            }
        }
        handler.postDelayed(stalenessRunnable!!, STALENESS_CHECK_INTERVAL)
    }

    private fun stopStalenessCheck() {
        stalenessRunnable?.let { handler.removeCallbacks(it) }
        stalenessRunnable = null
    }

    /**
     * Enhanced heartbeat: includes our direct connections so peers
     * can build a topology map of the entire network.
     */
    private fun sendHeartbeat() {
        if (connectedEndpoints.isEmpty()) return

        meshPeers[nodeName] = System.currentTimeMillis()

        // Update our own entry in the network graph
        val myDirectPeers = connectedEndpoints.mapNotNull { endpointNames[it] }.toSet()
        networkGraph[nodeName] = myDirectPeers

        val heartbeatId = UUID.randomUUID().toString()
        seenHeartbeatIds.add(heartbeatId)

        val peersArray = JSONArray()
        myDirectPeers.forEach { peersArray.put(it) }

        val json = JSONObject().apply {
            put("type", "heartbeat")
            put("sender", nodeName)
            put("msgId", heartbeatId)
            put("peers", peersArray)  // NEW: include direct peer list
        }.toString()

        val payload = Payload.fromBytes(json.toByteArray(StandardCharsets.UTF_8))
        connectedEndpoints.forEach { endpointId ->
            connectionsClient.sendPayload(endpointId, payload)
        }
    }

    /**
     * Process a received heartbeat: build the topology map, deduplicate, and relay.
     */
    private fun handleHeartbeat(json: String, fromEndpointId: String) {
        val obj = JSONObject(json)
        val msgId = obj.getString("msgId")
        val sender = obj.getString("sender")

        // Prevent infinite loops using the dedicated heartbeat dedup set
        if (!seenHeartbeatIds.add(msgId)) return

        if (sender == nodeName) return // Ignore our own heartbeat

        val isNew = !meshPeers.containsKey(sender)
        meshPeers[sender] = System.currentTimeMillis()

        // Parse the peer list and update the topology graph
        val peersArray = obj.optJSONArray("peers")
        if (peersArray != null) {
            val peerSet = mutableSetOf<String>()
            for (i in 0 until peersArray.length()) {
                val peerName = peersArray.getString(i)
                peerSet.add(peerName)
                // Also mark these peers as "seen" (they exist in the mesh)
                if (!meshPeers.containsKey(peerName)) {
                    meshPeers[peerName] = System.currentTimeMillis()
                }
            }
            networkGraph[sender] = peerSet
            notifyTopology()
        }

        if (isNew) {
            notifyLog("Discovered $sender via mesh heartbeat")
            notifyMeshPeers()
        }

        // Relay to other connected peers
        val payload = Payload.fromBytes(json.toByteArray(StandardCharsets.UTF_8))
        connectedEndpoints.filter { it != fromEndpointId }.forEach { endpointId ->
            connectionsClient.sendPayload(endpointId, payload)
        }
    }

    // ── Connection callbacks (Smart Routing) ──

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {

        @SuppressLint("MissingPermission")
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val peerName = info.endpointName
            endpointNames[endpointId] = peerName
            meshPeers[peerName] = System.currentTimeMillis()

            // Already connected to this specific endpoint
            if (connectedEndpoints.contains(endpointId)) return

            // Already connected to a device with this name
            if (isConnectedToName(peerName)) return

            // Already have a pending request for this endpoint
            if (pendingEndpoints.contains(endpointId)) return

            // ── SMART ROUTING: Check if we actually need this connection ──
            if (!shouldConnect(peerName)) {
                skippedEndpoints.add(endpointId)
                return
            }

            // ── COLLISION AVOIDANCE: Only the lexicographically smaller node initiates ──
            // This prevents both nodes from requesting each other simultaneously
            if (nodeName > peerName) {
                notifyLog("⏳ Waiting for $peerName to initiate (collision avoidance)")
                return
            }

            pendingEndpoints.add(endpointId)
            notifyLog("Connecting to $peerName...")

            connectionsClient.requestConnection(nodeName, endpointId, connectionLifecycleCallback)
                .addOnFailureListener {
                    pendingEndpoints.remove(endpointId)
                    notifyLog("Request to $peerName failed: ${it.message}")

                    // Exponential backoff retry
                    val retries = retryCount.getOrDefault(endpointId, 0)
                    if (retries < 3) {
                        retryCount[endpointId] = retries + 1
                        val delay = minOf(BACKOFF_BASE_MS * (1L shl retries), BACKOFF_MAX_MS)
                        handler.postDelayed({
                            if (meshStarted && !connectedEndpoints.contains(endpointId)
                                && !pendingEndpoints.contains(endpointId)
                                && shouldConnect(peerName)) {
                                notifyLog("Retrying connection to $peerName (attempt ${retries + 2})...")
                                pendingEndpoints.add(endpointId)
                                connectionsClient.requestConnection(nodeName, endpointId, connectionLifecycleCallback)
                                    .addOnFailureListener { pendingEndpoints.remove(endpointId) }
                            }
                        }, delay)
                    }
                }
        }

        override fun onEndpointLost(endpointId: String) {
            // Critical fix: DO NOT remove from connectedEndpoints here!
            // onEndpointLost only means the Bluetooth advertisement stopped 
            // (e.g. they switched to WiFi Direct). The actual connection is still alive!
            // Connection drops are handled exclusively by onDisconnected.
            pendingEndpoints.remove(endpointId)
            skippedEndpoints.remove(endpointId)
            notifyLog("Lost advertisement for ${endpointNames[endpointId] ?: endpointId.take(6)}")
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val peerName = info.endpointName
            endpointNames[endpointId] = peerName
            meshPeers[peerName] = System.currentTimeMillis()
            pendingEndpoints.add(endpointId)

            // Reject only if we're already CONNECTED to this exact same name
            if (isConnectedToName(peerName)) {
                notifyLog("Rejecting duplicate from $peerName")
                connectionsClient.rejectConnection(endpointId)
                pendingEndpoints.remove(endpointId)
                return
            }

            // ── SMART ROUTING: Check if we should accept incoming connections ──
            // For incoming connections (where the other side initiated), we are more lenient:
            // only reject if we are truly at max AND the peer is already reachable.
            if (connectedEndpoints.size >= MAX_CONNECTIONS && hopsTo(peerName) <= REDUNDANCY_HOP_THRESHOLD) {
                notifyLog("Rejecting $peerName — at max connections and already reachable")
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
            retryCount.remove(endpointId)
            val peerName = endpointNames[endpointId] ?: "peer"

            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                notifyConnectionCount()
                notifyPeerStates()
                notifyLog("✅ Connected to $peerName (${connectedEndpoints.size}/$MAX_CONNECTIONS)")

                // Update our topology entry immediately
                val myDirectPeers = connectedEndpoints.mapNotNull { endpointNames[it] }.toSet()
                networkGraph[nodeName] = myDirectPeers
                notifyTopology()

                // Immediately exchange heartbeats and sync alerts
                syncAlertsToEndpoint(endpointId)
                handler.postDelayed({ sendHeartbeat() }, 500)
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

            // Update our topology entry
            val myDirectPeers = connectedEndpoints.mapNotNull { endpointNames[it] }.toSet()
            networkGraph[nodeName] = myDirectPeers
            notifyTopology()

            // ── SELF-HEALING: If we've dropped below minimum, actively seek new connections ──
            if (meshStarted && connectedEndpoints.size < MIN_CONNECTIONS) {
                notifyLog("🔧 Self-healing: only ${connectedEndpoints.size} connections, seeking more...")
                // Clear skipped endpoints so we reconsider them
                skippedEndpoints.clear()
                // Restart discovery to find new peers
                if (!isDiscovering) {
                    isDiscovering = false
                    startDiscovery()
                }
            }
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
                    "heartbeat" -> handleHeartbeat(json, endpointId)
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

            // Bridge to Nostr if available
            nostrService?.publishAlert(received)
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
            } else if (dm.ttl > 0) {
                // Not for us and TTL remaining — relay it (decrement TTL)
                val relayed = dm.copy(ttl = dm.ttl - 1)
                relayDm(relayed.toJson(), fromEndpointId)
                notifyLog("Relaying DM from ${dm.senderName} → ${dm.targetName} (TTL=${relayed.ttl})")
            } else {
                notifyLog("DM from ${dm.senderName} dropped — TTL exhausted")
            }
        }
    }

    /**
     * Called by NostrService when it receives an alert from the Nostr network.
     * Injects it into the local mesh as if it was received from a peer.
     */
    fun receiveFromNostr(packet: SosPacket) {
        handler.post {
            if (seenMessageIds.contains(packet.messageId)) return@post
            seenMessageIds.add(packet.messageId)
            synchronized(alerts) { alerts.add(0, packet) }
            database.alertDao().insertAlert(packet.toEntity())
            notifyAlert(packet)
            notifyLog("📡 Alert from Nostr: ${packet.senderName}: ${packet.status}")

            // Relay into the local mesh
            if (packet.ttl > 0) {
                val relayed = packet.copy(hopCount = packet.hopCount + 1, ttl = packet.ttl - 1, isRelayed = true)
                enqueueRelay(relayed, exceptEndpointId = null)
            }
        }
    }

    /**
     * Called by NostrService when it receives a DM from the Nostr network.
     */
    fun receiveFromNostr(dm: DirectMessage) {
        handler.post {
            if (seenMessageIds.contains(dm.messageId)) return@post
            seenMessageIds.add(dm.messageId)

            if (dm.targetName == nodeName) {
                synchronized(directMessages) { directMessages.add(dm) }
                database.directMessageDao().insert(dm.toEntity())
                notifyDm(dm)
                notifyLog("📡 DM from Nostr: ${dm.senderName}")
            } else if (dm.ttl > 0) {
                // Relay into the local mesh
                val relayed = dm.copy(ttl = dm.ttl - 1)
                val payload = Payload.fromBytes(relayed.toJson().toByteArray(StandardCharsets.UTF_8))
                connectedEndpoints.forEach { connectionsClient.sendPayload(it, payload) }
                notifyLog("📡 Relaying Nostr DM ${dm.senderName} → ${dm.targetName}")
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

        // Bridge to Nostr
        nostrService?.publishAlert(packet)
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

        // Also bridge to Nostr
        nostrService?.publishDm(dm)
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
        return meshPeers.keys.filter { it != nodeName }.toSet()
    }
    
    /** Check if a peer has been seen in the last 45 seconds */
    fun isPeerOnline(peerName: String): Boolean {
        val lastSeen = meshPeers[peerName] ?: return false
        return (System.currentTimeMillis() - lastSeen) < 45_000L
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

        // Simulate mesh peers and topology
        val now = System.currentTimeMillis()
        listOf("Demo-Rescue-1", "Demo-Volunteer-2", "Demo-Node-B", "Demo-Node-C").forEach { 
            meshPeers[it] = now 
        }

        // Simulate a topology graph
        networkGraph[nodeName] = setOf("Demo-Rescue-1", "Demo-Volunteer-2")
        networkGraph["Demo-Rescue-1"] = setOf(nodeName, "Demo-Node-B")
        networkGraph["Demo-Volunteer-2"] = setOf(nodeName, "Demo-Node-C")
        networkGraph["Demo-Node-B"] = setOf("Demo-Rescue-1", "Demo-Node-C")
        networkGraph["Demo-Node-C"] = setOf("Demo-Volunteer-2", "Demo-Node-B")

        notifyMeshPeers()
        notifyTopology()

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
