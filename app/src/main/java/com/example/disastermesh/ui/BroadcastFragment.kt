package com.example.disastermesh.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.example.disastermesh.MainActivity
import com.example.disastermesh.data.*
import com.example.disastermesh.mesh.MeshListener
import com.example.disastermesh.ui.UiColors
import com.example.disastermesh.ui.UiUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BroadcastFragment : Fragment(), MeshListener {

    private lateinit var connectionStatusText: TextView
    private lateinit var alertContainer: LinearLayout
    private lateinit var statusSpinner: Spinner
    private lateinit var landmarkInput: EditText
    private lateinit var messageInput: EditText

    private val mainActivity get() = (requireActivity() as MainActivity)
    private val meshManager get() = mainActivity.meshManager
    private val isBound get() = mainActivity.isBound

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val dp = { v: Int -> UiUtils.dp(ctx, v) }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiColors.bgPrimary)
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }

        // Header
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }

        val title = UiUtils.makeTitle(ctx, "📡 Broadcast")
        title.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        headerRow.addView(title)

        connectionStatusText = TextView(ctx).apply {
            textSize = 13f
            setTextColor(UiColors.accentGreen)
            gravity = Gravity.END
        }
        headerRow.addView(connectionStatusText)
        root.addView(headerRow)

        // Removed nodeName and peerStateText from here

        // Input section
        root.addView(UiUtils.makeSectionHeader(ctx, "⚡ SEND ALERT"))

        statusSpinner = Spinner(ctx).apply {
            background = UiUtils.roundedBackground(UiColors.bgInput, 10, ctx)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        val statuses = listOf(
            "Need Medical Help", "Injured", "Trapped",
            "Need Help", "Need Food/Water",
            "Safe", "Volunteer Available", "Info"
        )

        val spinnerAdapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, statuses)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        statusSpinner.adapter = spinnerAdapter

        val spinnerParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        spinnerParams.bottomMargin = dp(8)
        root.addView(statusSpinner, spinnerParams)

        landmarkInput = UiUtils.makeStyledInput(ctx, "📍 Location / Landmark")
        root.addView(landmarkInput)

        messageInput = UiUtils.makeStyledInput(ctx, "✏️ Emergency message", minLines = 2)
        root.addView(messageInput)

        val sosButton = UiUtils.makeStyledButton(ctx, "🔴  BROADCAST SOS", UiColors.accentRed)
        sosButton.setOnClickListener { sendBroadcast() }
        val sosBtnParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        sosBtnParams.bottomMargin = dp(12)
        root.addView(sosButton, sosBtnParams)

        // Scrollable alerts + logs
        val scrollContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        scrollContent.addView(UiUtils.makeSectionHeader(ctx, "📋 ALERTS"))

        alertContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollContent.addView(alertContainer)

        // Removed NETWORK LOG section

        root.addView(UiUtils.wrapInScroll(ctx, scrollContent))

        return root
    }

    override fun onResume() {
        super.onResume()
        if (!isBound) return
        meshManager.addListener(this)
        refreshAlerts()
        updateConnectionStatus(meshManager.connectedEndpoints.size)
    }

    override fun onPause() {
        super.onPause()
        if (!isBound) return
        meshManager.removeListener(this)
    }

    private fun sendBroadcast() {
        if (!isBound) return
        val status = statusSpinner.selectedItem.toString()
        val message = messageInput.text.toString().trim()
        val landmark = landmarkInput.text.toString().trim()

        if (message.isEmpty()) {
            Toast.makeText(requireContext(), "Enter emergency message", Toast.LENGTH_SHORT).show()
            return
        }

        meshManager.createLocalAlert(status, message, landmark)
        messageInput.text.clear()
    }

    private fun refreshAlerts() {
        if (!::alertContainer.isInitialized || !isBound) return

        alertContainer.removeAllViews()
        val ctx = requireContext()

        val sortedAlerts = meshManager.alerts.sortedWith(
            compareByDescending<SosPacket> { it.priority }
                .thenByDescending { it.timestamp }
        )

        if (sortedAlerts.isEmpty()) {
            val empty = TextView(ctx).apply {
                text = "No alerts yet. Broadcast an SOS or wait for incoming alerts."
                textSize = 14f
                setTextColor(UiColors.textDim)
                setPadding(0, UiUtils.dp(ctx, 8), 0, UiUtils.dp(ctx, 8))
            }
            alertContainer.addView(empty)
            return
        }

        sortedAlerts.take(50).forEach { alert ->
            alertContainer.addView(createAlertCard(ctx, alert))
        }
    }

    private fun createAlertCard(ctx: android.content.Context, alert: SosPacket): View {
        val color = priorityColor(alert.priority)
        val card = UiUtils.makeCard(ctx, color)

        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val emoji = TextView(ctx).apply {
            text = statusEmoji(alert.status)
            textSize = 20f
            setPadding(0, 0, UiUtils.dp(ctx, 8), 0)
        }
        headerRow.addView(emoji)

        val statusLabel = TextView(ctx).apply {
            text = "${alert.status}  •  ${priorityLabel(alert.priority)}"
            textSize = 15f
            setTextColor(color)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(statusLabel)

        val time = TextView(ctx).apply {
            text = formatTime(alert.timestamp)
            textSize = 12f
            setTextColor(UiColors.textDim)
        }
        headerRow.addView(time)

        card.addView(headerRow)

        val sender = TextView(ctx).apply {
            text = "From: ${alert.senderName}"
            textSize = 13f
            setTextColor(UiColors.textSecondary)
            setPadding(0, UiUtils.dp(ctx, 4), 0, 0)
        }
        card.addView(sender)

        if (alert.landmark != "Unknown location") {
            val landmark = TextView(ctx).apply {
                text = "📍 ${alert.landmark}"
                textSize = 13f
                setTextColor(UiColors.textSecondary)
            }
            card.addView(landmark)
        }

        val msg = TextView(ctx).apply {
            text = alert.message
            textSize = 14f
            setTextColor(UiColors.textPrimary)
            setPadding(0, UiUtils.dp(ctx, 4), 0, UiUtils.dp(ctx, 4))
        }
        card.addView(msg)

        val meta = TextView(ctx).apply {
            text = "Hop: ${alert.hopCount} • TTL: ${alert.ttl} • ${if (alert.isRelayed) "Relayed" else "Direct"} • via ${alert.receivedFrom}"
            textSize = 11f
            setTextColor(UiColors.textDim)
        }
        card.addView(meta)

        return card
    }

    private fun updateConnectionStatus(count: Int) {
        if (!::connectionStatusText.isInitialized) return
        connectionStatusText.text = if (count > 0) "🟢 $count peers" else "🔴 No peers"
        connectionStatusText.setTextColor(
            if (count > 0) UiColors.accentGreen else UiColors.statusDisconnected
        )
    }

    // Removed updatePeerStates and addLogLine

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(timestamp))
    }

    // ── MeshListener callbacks ──

    override fun onLogMessage(message: String) {
        // Logs disabled in UI
    }

    override fun onAlertReceived(packet: SosPacket) {
        refreshAlerts()
    }

    override fun onConnectionCountChanged(count: Int) {
        updateConnectionStatus(count)
    }

    override fun onPeerStatesChanged(states: Map<String, PeerState>, names: Map<String, String>) {
        // Peer states removed from UI
    }
}
