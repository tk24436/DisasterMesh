package com.example.disastermesh.data

import org.json.JSONObject
import java.util.UUID

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
            put("type", "broadcast")
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

data class DirectMessage(
    val messageId: String,
    val senderName: String,
    val targetName: String,
    val content: String,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val isRead: Boolean = false,
    val ttl: Int = 7  // Max hops before dropping
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("type", "dm")
            put("messageId", messageId)
            put("senderName", senderName)
            put("targetName", targetName)
            put("content", content)
            put("timestamp", timestamp)
            put("ttl", ttl)
        }.toString()
    }

    companion object {
        fun fromJson(json: String, isOutgoing: Boolean = false): DirectMessage {
            val obj = JSONObject(json)
            return DirectMessage(
                messageId = obj.getString("messageId"),
                senderName = obj.getString("senderName"),
                targetName = obj.optString("targetName", ""),
                content = obj.getString("content"),
                timestamp = obj.getLong("timestamp"),
                isOutgoing = isOutgoing,
                ttl = obj.optInt("ttl", 7)
            )
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

fun priorityColor(priority: Int): Int {
    return when (priority) {
        3 -> 0xFFFF3D3D.toInt()
        2 -> 0xFFFF9800.toInt()
        1 -> 0xFF4CAF50.toInt()
        else -> 0xFF448AFF.toInt()
    }
}

fun statusEmoji(status: String): String {
    return when (status) {
        "Need Medical Help" -> "🏥"
        "Injured" -> "🩹"
        "Trapped" -> "🆘"
        "Need Help" -> "⚠️"
        "Need Food/Water" -> "🍽️"
        "Safe" -> "✅"
        "Volunteer Available" -> "🙋"
        else -> "ℹ️"
    }
}
