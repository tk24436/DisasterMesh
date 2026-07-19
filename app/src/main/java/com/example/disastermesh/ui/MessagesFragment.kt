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
    private lateinit var peerSection: LinearLayout
    private lateinit var subtitleText: TextView

    private var selectedPeerName: String? = null

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

        peerSection = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        
        peerSection.addView(UiUtils.makeTitle(ctx, "💬 Messages"))
        subtitleText = TextView(ctx).apply {
            text = "DMs relay through the mesh via hops"
            textSize = 13f
            setTextColor(UiColors.textDim)
            setPadding(0, 0, 0, UiUtils.dp(ctx, 8))
        }
        peerSection.addView(subtitleText)

        // Peer list section
        peerSection.addView(UiUtils.makeSectionHeader(ctx, "MESH PEERS"))

        peerContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        
        val peerScroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(peerContainer)
        }
        peerSection.addView(peerScroll)

        emptyText = TextView(ctx).apply {
            text = "No peers found yet.\nStart the mesh and wait for nearby devices."
            textSize = 14f
            setTextColor(UiColors.textDim)
            setPadding(0, dp(8), 0, dp(16))
        }
        peerSection.addView(emptyText)
        
        root.addView(peerSection)

        // Chat section
        chatSection = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(8))
        }
        
        val backBtn = TextView(ctx).apply {
            text = "←"
            textSize = 24f
            setTextColor(UiColors.textDim)
            setPadding(0, 0, dp(16), 0)
            isClickable = true
            setOnClickListener { closeChat() }
        }
        headerRow.addView(backBtn)
        
        chatHeader = TextView(ctx).apply {
            textSize = 15f
            setTextColor(UiColors.accentCyan)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        }
        headerRow.addView(chatHeader)
        chatSection.addView(headerRow)

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
        if (!isBound) return
        meshManager.addListener(this)
        refreshPeerList()
        if (selectedPeerName != null) {
            openChat(selectedPeerName!!)
        } else {
            closeChat()
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isBound) return
        meshManager.removeListener(this)
    }

    private fun refreshPeerList() {
        if (!::peerContainer.isInitialized || !isBound) return

        val allPeers = meshManager.getAllMeshPeers()

        if (allPeers.isEmpty()) {
            peerContainer.removeAllViews()
            emptyText.visibility = View.VISIBLE
            return
        }

        // Run DB query off main thread, then update UI
        Thread {
            val allDMs = meshManager.database.directMessageDao().getAll()
            val latestMsgTime = mutableMapOf<String, Long>()
            for (peer in allPeers) {
                val peerDms = allDMs.filter { it.senderName == peer || it.targetName == peer }
                latestMsgTime[peer] = peerDms.maxOfOrNull { it.timestamp } ?: 0L
            }
            val sortedPeers = allPeers.sortedWith(
                compareByDescending<String> { meshManager.isPeerOnline(it) }
                    .thenByDescending { latestMsgTime[it] ?: 0L }
                    .thenBy { it }
            )

            // Post UI updates back to main thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                buildPeerCards(sortedPeers)
            }
        }.start()
    }

    private fun buildPeerCards(sortedPeers: List<String>) {
        if (!isAdded) return
        val ctx = requireContext()
        val dp = { v: Int -> UiUtils.dp(ctx, v) }

        peerContainer.removeAllViews()
        emptyText.visibility = View.GONE

        sortedPeers.forEach { peerName ->
            val isOnline = meshManager.isPeerOnline(peerName)

            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = UiUtils.roundedBackground(UiColors.bgCard, 12, ctx)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                isFocusable = true
                if (peerName == selectedPeerName) {
                    background = UiUtils.outlineBackground(
                        UiColors.bgCard, UiColors.accentCyan, 1, 12, ctx
                    )
                }
            }

            // Green dot for online, grey for offline
            val dotColor = if (isOnline) UiColors.statusConnected else UiColors.textDim
            card.addView(UiUtils.makeStatusDot(ctx, dotColor))

            val nameCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameText = TextView(ctx).apply {
                text = peerName
                textSize = 15f
                setTextColor(if (isOnline) UiColors.textPrimary else UiColors.textSecondary)
            }
            nameCol.addView(nameText)

            val routeLabel = TextView(ctx).apply {
                text = if (isOnline) "Online" else "Offline"
                textSize = 11f
                setTextColor(if (isOnline) UiColors.accentGreen else UiColors.textDim)
            }
            nameCol.addView(routeLabel)

            card.addView(nameCol)

            // Unread count badge
            val unread = synchronized(meshManager.directMessages) {
                meshManager.directMessages.count {
                    !it.isOutgoing && it.senderName == peerName && !it.isRead
                }
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

        peerSection.visibility = View.GONE
        chatSection.visibility = View.VISIBLE
        chatHeader.text = "💬 Chat with $peerName"
        refreshChat()
    }
    
    private fun closeChat() {
        if (!::chatSection.isInitialized) return
        
        selectedPeerName = null
        chatSection.visibility = View.GONE
        peerSection.visibility = View.VISIBLE
        // Clear keyboard focus if needed
        messageInput.clearFocus()
        refreshPeerList()
    }

    private fun refreshChat() {
        if (!::chatContainer.isInitialized || selectedPeerName == null || !isBound) return

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
        val target = selectedPeerName ?: return
        val content = messageInput.text.toString().trim()
        if (content.isEmpty() || !isBound) return

        meshManager.sendDirectMessage(target, content)
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

    override fun onMeshPeersChanged(peers: Set<String>) {
        refreshPeerList()
    }
}
