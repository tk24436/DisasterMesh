package com.example.disastermesh.ui

import android.graphics.Color
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessagesFragment : Fragment(), MeshListener {

    private lateinit var peerContainer: LinearLayout
    private lateinit var chatContainer: LinearLayout
    private lateinit var chatScrollView: ScrollView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: TextView
    private lateinit var chatHeader: TextView
    private lateinit var emptyText: TextView
    private lateinit var chatSection: LinearLayout

    private var selectedEndpointId: String? = null
    private var selectedPeerName: String? = null

    private val meshManager get() = (requireActivity() as MainActivity).meshManager

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

        root.addView(UiUtils.makeTitle(ctx, "💬 Messages"))
        root.addView(UiUtils.makeSubtitle(ctx, "Private • Not Relayed • Direct Only"))

        // Peer list section
        root.addView(UiUtils.makeSectionHeader(ctx, "CONNECTED PEERS"))

        peerContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(peerContainer)

        emptyText = TextView(ctx).apply {
            text = "No peers connected yet.\nStart the mesh and wait for nearby devices."
            textSize = 14f
            setTextColor(UiColors.textDim)
            setPadding(0, dp(8), 0, dp(16))
        }
        root.addView(emptyText)

        // Chat section
        chatSection = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        chatHeader = TextView(ctx).apply {
            textSize = 15f
            setTextColor(UiColors.accentCyan)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(8))
        }
        chatSection.addView(chatHeader)

        val chatScroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        chatContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        chatScroll.addView(chatContainer)
        chatScrollView = chatScroll
        chatSection.addView(chatScroll)

        // Input row
        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }

        messageInput = EditText(ctx).apply {
            hint = "Type a message…"
            textSize = 14f
            setTextColor(UiColors.textPrimary)
            setHintTextColor(UiColors.textDim)
            background = UiUtils.roundedBackground(UiColors.bgInput, 20, ctx)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            minLines = 1
            maxLines = 3
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        inputRow.addView(messageInput)

        sendButton = UiUtils.makeChipButton(ctx, "Send", UiColors.accentBlue)
        sendButton.setOnClickListener { sendDm() }
        inputRow.addView(sendButton)

        chatSection.addView(inputRow)

        root.addView(chatSection, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        return root
    }

    override fun onResume() {
        super.onResume()
        meshManager.addListener(this)
        refreshPeerList()
        if (selectedPeerName != null) {
            refreshChat()
        }
    }

    override fun onPause() {
        super.onPause()
        meshManager.removeListener(this)
    }

    private fun refreshPeerList() {
        if (!::peerContainer.isInitialized) return

        peerContainer.removeAllViews()
        val ctx = requireContext()
        val dp = { v: Int -> UiUtils.dp(ctx, v) }
        val peers = meshManager.getConnectedPeerList()

        if (peers.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            return
        }

        emptyText.visibility = View.GONE

        peers.forEach { (endpointId, peerName) ->
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = UiUtils.roundedBackground(UiColors.bgCard, 12, ctx)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                isFocusable = true
                val isSelected = endpointId == selectedEndpointId
                if (isSelected) {
                    background = UiUtils.outlineBackground(
                        UiColors.bgCard, UiColors.accentCyan, 1, 12, ctx
                    )
                }
            }

            val dot = UiUtils.makeStatusDot(ctx, UiColors.statusConnected)
            card.addView(dot)

            val nameText = TextView(ctx).apply {
                text = peerName
                textSize = 15f
                setTextColor(UiColors.textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            card.addView(nameText)

            // Unread count
            val unread = meshManager.directMessages.count {
                !it.isOutgoing && it.senderName == peerName && !it.isRead
            }
            if (unread > 0) {
                val badge = TextView(ctx).apply {
                    text = "$unread"
                    textSize = 12f
                    setTextColor(UiColors.textPrimary)
                    gravity = Gravity.CENTER
                    background = UiUtils.roundedBackground(UiColors.accentRed, 12, ctx)
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                }
                card.addView(badge)
            }

            val arrow = TextView(ctx).apply {
                text = "›"
                textSize = 22f
                setTextColor(UiColors.textDim)
                setPadding(dp(8), 0, 0, 0)
            }
            card.addView(arrow)

            card.setOnClickListener {
                selectedEndpointId = endpointId
                selectedPeerName = peerName
                meshManager.database.directMessageDao().markRead(peerName)
                openChat(peerName)
                refreshPeerList()
            }

            val cardParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            cardParams.bottomMargin = dp(6)
            peerContainer.addView(card, cardParams)
        }
    }

    private fun openChat(peerName: String) {
        if (!::chatSection.isInitialized) return

        chatSection.visibility = View.VISIBLE
        chatHeader.text = "💬 Chat with $peerName"
        refreshChat()
    }

    private fun refreshChat() {
        if (!::chatContainer.isInitialized || selectedPeerName == null) return

        chatContainer.removeAllViews()
        val ctx = requireContext()
        val dp = { v: Int -> UiUtils.dp(ctx, v) }

        val messages = meshManager.database.directMessageDao()
            .getMessagesWithPeer(selectedPeerName!!)
            .map { it.toDirectMessage() }

        if (messages.isEmpty()) {
            val empty = TextView(ctx).apply {
                text = "No messages yet. Say hello! 👋"
                textSize = 14f
                setTextColor(UiColors.textDim)
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(24))
            }
            chatContainer.addView(empty)
            return
        }

        messages.forEach { dm ->
            chatContainer.addView(createMessageBubble(ctx, dm))
        }

        // Scroll to bottom
        chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun createMessageBubble(ctx: android.content.Context, dm: DirectMessage): View {
        val dp = { v: Int -> UiUtils.dp(ctx, v) }
        val isOutgoing = dm.isOutgoing

        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (isOutgoing) Gravity.END else Gravity.START
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = dp(6)
            layoutParams = params
        }

        val bubble = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val bgColor = if (isOutgoing) UiColors.accentBlue else UiColors.bgCard
            background = UiUtils.roundedBackground(bgColor, 16, ctx)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            val bubbleParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            bubbleParams.marginStart = if (isOutgoing) dp(48) else 0
            bubbleParams.marginEnd = if (isOutgoing) 0 else dp(48)
            layoutParams = bubbleParams
        }

        val content = TextView(ctx).apply {
            text = dm.content
            textSize = 14f
            setTextColor(UiColors.textPrimary)
        }
        bubble.addView(content)

        val time = TextView(ctx).apply {
            text = formatTime(dm.timestamp)
            textSize = 11f
            setTextColor(if (isOutgoing) Color.argb(180, 255, 255, 255) else UiColors.textDim)
            gravity = Gravity.END
        }
        bubble.addView(time)

        wrapper.addView(bubble)
        return wrapper
    }

    private fun sendDm() {
        val endpointId = selectedEndpointId ?: return
        val content = messageInput.text.toString().trim()
        if (content.isEmpty()) return

        meshManager.sendDirectMessage(endpointId, content)
        messageInput.text.clear()
        refreshChat()
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(timestamp))
    }

    // ── MeshListener ──

    override fun onDirectMessageReceived(message: DirectMessage) {
        refreshPeerList()
        if (message.senderName == selectedPeerName || message.targetName == selectedPeerName) {
            refreshChat()
        }
    }

    override fun onPeerStatesChanged(states: Map<String, PeerState>, names: Map<String, String>) {
        refreshPeerList()
    }

    override fun onConnectionCountChanged(count: Int) {
        refreshPeerList()
    }

}
