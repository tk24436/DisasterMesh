package com.example.disastermesh.mesh

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.example.disastermesh.data.DirectMessage
import com.example.disastermesh.data.SosPacket
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Lightweight Nostr relay client using OkHttp WebSockets.
 *
 * When the device has internet connectivity, this service connects to public
 * Nostr relays and bridges SOS alerts and DMs between the local mesh and the
 * global Nostr network.
 *
 * We use NIP-01 (Basic Protocol) text notes with a custom "t" tag
 * ("disastermesh") so we only see events from other DisasterMesh nodes.
 *
 * Key types used:
 *   kind 1  – SOS broadcast alerts
 *   kind 4  – Direct messages (plaintext for simplicity; NIP-04 encryption
 *             can be added later)
 *
 * Event format (NIP-01):
 * {
 *   "id": <sha256 hex>,
 *   "pubkey": <32-byte hex>,
 *   "created_at": <unix timestamp>,
 *   "kind": 1,
 *   "tags": [["t","disastermesh"]],
 *   "content": "<json payload>",
 *   "sig": <64-byte hex>   // we use a dummy sig – relays that enforce sigs
 *                           // will reject, but many public relays accept unsigned events
 * }
 */
class NostrService(
    private val context: Context,
    private val meshManager: MeshManager
) {
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // Infinite for WebSocket
        .build()

    // Public Nostr relays (add more as needed)
    private val relayUrls = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.nostr.band"
    )

    private val activeConnections = mutableMapOf<String, WebSocket>()
    private var isRunning = false

    // Simple keypair: 32-byte private key, derive pubkey
    private var privateKeyHex: String = ""
    private var pubkeyHex: String = ""

    // Subscription ID for filtering events
    private val subscriptionId = "dm_sub_${System.currentTimeMillis()}"

    // Track seen event IDs to prevent duplicates
    private val seenNostrIds = MeshManager.BoundedSet(300)

    /**
     * Initialize keys from SharedPreferences or generate new ones.
     */
    fun init() {
        val prefs = context.getSharedPreferences("disastermesh_nostr", Context.MODE_PRIVATE)
        privateKeyHex = prefs.getString("nostr_privkey", null) ?: run {
            val key = ByteArray(32)
            SecureRandom().nextBytes(key)
            val hex = key.toHex()
            prefs.edit().putString("nostr_privkey", hex).apply()
            hex
        }
        // Simple pubkey derivation: SHA-256 of privkey (NOT proper Schnorr, but
        // sufficient for our custom event routing where signature verification
        // is not required by peers).
        pubkeyHex = sha256Hex(privateKeyHex.hexToBytes())
    }

    /**
     * Start connecting to Nostr relays if internet is available.
     */
    fun start() {
        if (isRunning) return
        isRunning = true
        connectToRelays()
        // Periodically check connectivity and reconnect
        handler.postDelayed(reconnectRunnable, 30_000)
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(reconnectRunnable)
        activeConnections.values.forEach { it.close(1000, "Shutting down") }
        activeConnections.clear()
    }

    private val reconnectRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (hasInternet()) {
                // Reconnect any dropped relays
                relayUrls.forEach { url ->
                    if (!activeConnections.containsKey(url)) {
                        connectToRelay(url)
                    }
                }
            }
            handler.postDelayed(this, 30_000)
        }
    }

    private fun connectToRelays() {
        if (!hasInternet()) {
            meshManager.notifyLogPublic("📡 Nostr: No internet, will retry later")
            return
        }
        relayUrls.forEach { connectToRelay(it) }
    }

    private fun connectToRelay(url: String) {
        val request = Request.Builder().url(url).build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                activeConnections[url] = webSocket
                meshManager.notifyLogPublic("📡 Nostr: Connected to $url")
                // Subscribe to DisasterMesh events
                subscribe(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleRelayMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                activeConnections.remove(url)
                // Silent failure — reconnect will handle it
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                activeConnections.remove(url)
            }
        })
    }

    /**
     * Subscribe to events tagged with "disastermesh".
     * NIP-01 REQ format: ["REQ", <subscription_id>, <filter>]
     */
    private fun subscribe(ws: WebSocket) {
        val filter = JSONObject().apply {
            put("kinds", JSONArray().put(1).put(4))
            put("#t", JSONArray().put("disastermesh"))
            put("since", System.currentTimeMillis() / 1000 - 3600) // Last hour
            put("limit", 50)
        }
        val req = JSONArray().apply {
            put("REQ")
            put(subscriptionId)
            put(filter)
        }
        ws.send(req.toString())
    }

    /**
     * Handle incoming relay messages (NIP-01 format).
     * ["EVENT", <subscription_id>, <event>]
     */
    private fun handleRelayMessage(text: String) {
        try {
            val arr = JSONArray(text)
            val type = arr.getString(0)

            if (type == "EVENT" && arr.length() >= 3) {
                val event = arr.getJSONObject(2)
                val eventId = event.getString("id")
                val eventPubkey = event.getString("pubkey")

                // Skip our own events
                if (eventPubkey == pubkeyHex) return

                // Deduplicate
                if (!seenNostrIds.add(eventId)) return

                val content = event.getString("content")
                val kind = event.getInt("kind")

                when (kind) {
                    1 -> {
                        // SOS broadcast
                        try {
                            val packet = SosPacket.fromJson(content)
                            val nostrPacket = packet.copy(receivedFrom = "NOSTR")
                            meshManager.receiveFromNostr(nostrPacket)
                        } catch (e: Exception) {
                            // Not a valid SosPacket JSON, ignore
                        }
                    }
                    4 -> {
                        // Direct message
                        try {
                            val dm = DirectMessage.fromJson(content, isOutgoing = false)
                            meshManager.receiveFromNostr(dm)
                        } catch (e: Exception) {
                            // Not a valid DM JSON, ignore
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Malformed relay message, ignore
        }
    }

    /**
     * Publish an SOS alert to all connected Nostr relays.
     */
    fun publishAlert(packet: SosPacket) {
        if (!hasInternet() || activeConnections.isEmpty()) return
        val event = buildEvent(kind = 1, content = packet.toJson())
        broadcastEvent(event)
    }

    /**
     * Publish a DM to all connected Nostr relays.
     */
    fun publishDm(dm: DirectMessage) {
        if (!hasInternet() || activeConnections.isEmpty()) return
        val event = buildEvent(kind = 4, content = dm.toJson())
        broadcastEvent(event)
    }

    /**
     * Build a NIP-01 event.
     * Note: We use a simplified signing approach. The "sig" field is a hash
     * of the serialized event (not a proper Schnorr signature). This is
     * sufficient for relays that don't enforce BIP-340 verification, which
     * is the case for many public relays.
     */
    private fun buildEvent(kind: Int, content: String): JSONObject {
        val createdAt = System.currentTimeMillis() / 1000
        val tags = JSONArray().apply {
            put(JSONArray().apply {
                put("t")
                put("disastermesh")
            })
        }

        // NIP-01 event ID = sha256 of serialized event
        val serialized = JSONArray().apply {
            put(0)               // reserved
            put(pubkeyHex)       // pubkey
            put(createdAt)       // created_at
            put(kind)            // kind
            put(tags)            // tags
            put(content)         // content
        }
        val eventId = sha256Hex(serialized.toString().toByteArray())

        // Simplified signature (hash of id + privkey)
        val sig = sha256Hex((eventId + privateKeyHex).toByteArray())
        // Pad sig to 128 hex chars (64 bytes) as expected by relays
        val paddedSig = sig.padEnd(128, '0')

        return JSONObject().apply {
            put("id", eventId)
            put("pubkey", pubkeyHex)
            put("created_at", createdAt)
            put("kind", kind)
            put("tags", tags)
            put("content", content)
            put("sig", paddedSig)
        }
    }

    private fun broadcastEvent(event: JSONObject) {
        val msg = JSONArray().apply {
            put("EVENT")
            put(event)
        }.toString()

        activeConnections.values.forEach { ws ->
            try {
                ws.send(msg)
            } catch (e: Exception) {
                // Connection dropped, will be cleaned up by reconnect
            }
        }
    }

    // ── Utility ──

    fun hasInternet(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun getConnectedRelayCount(): Int = activeConnections.size

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        val len = this.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i + 1], 16)).toByte()
        }
        return data
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).toHex()
    }
}

/**
 * Extension to allow MeshManager to call notifyLog from NostrService
 * (which runs on a background OkHttp thread).
 */
fun MeshManager.notifyLogPublic(msg: String) {
    // Post to main thread via a handler since OkHttp callbacks are on background threads
    Handler(Looper.getMainLooper()).post {
        // Trigger via a dummy listener approach: create a local alert log
        // by calling the public addListener/forEachListener chain
        this.logFromExternal(msg)
    }
}
