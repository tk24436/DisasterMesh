package com.example.disastermesh.ui

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.example.disastermesh.ai.FirstAidKnowledgeBase
import com.example.disastermesh.ai.GemmaEngine

class AiHelperFragment : Fragment() {

    private lateinit var chatContainer: LinearLayout
    private lateinit var chatScrollView: ScrollView
    private lateinit var queryInput: EditText
    private lateinit var quickActionsContainer: LinearLayout
    private lateinit var aiStatusBadge: TextView
    private lateinit var modelBanner: LinearLayout

    private val knowledgeBase = FirstAidKnowledgeBase()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var gemmaInitialized = false

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

        // ── Header ──
        root.addView(UiUtils.makeTitle(ctx, "🤖 AI First Aid"))

        // ── Badge row ──
        val badgeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }

        val offlineBadge = UiUtils.makeChipButton(ctx, "⚡ Offline", UiColors.accentGreen).apply {
            textSize = 11f; isClickable = false
        }
        badgeRow.addView(offlineBadge)
        badgeRow.addView(spacerView(ctx, dp(6)))

        aiStatusBadge = UiUtils.makeChipButton(ctx, "📱 Knowledge Base", UiColors.accentPurple).apply {
            textSize = 11f; isClickable = false
        }
        badgeRow.addView(aiStatusBadge)
        badgeRow.addView(spacerView(ctx, dp(6)))

        val indiaBadge = UiUtils.makeChipButton(ctx, "🇮🇳 India", UiColors.accentOrange).apply {
            textSize = 11f; isClickable = false
        }
        badgeRow.addView(indiaBadge)
        root.addView(badgeRow)

        // ── Model-not-found banner (hidden by default) ──
        modelBanner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = UiUtils.roundedBackground(
                Color.argb(40, 255, 152, 0), 10, ctx
            )
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = dp(8); params.bottomMargin = dp(4)
            layoutParams = params
            visibility = View.GONE
        }

        val bannerTitle = TextView(ctx).apply {
            text = "🧠 Snapdragon AI Available — Model Not Found"
            textSize = 13f
            setTextColor(UiColors.accentOrange)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        }
        modelBanner.addView(bannerTitle)

        val bannerBody = TextView(ctx).apply {
            text = "Push the Gemma model file to enable NPU inference:\n\nadb push gemma4_2b_SM8850.litertlm \\\n  /sdcard/Android/data/com.example.disastermesh/files/"
            textSize = 11f
            setTextColor(UiColors.textSecondary)
            setPadding(0, dp(4), 0, 0)
            setTypeface(android.graphics.Typeface.MONOSPACE)
        }
        modelBanner.addView(bannerBody)
        root.addView(modelBanner)

        // ── Disclaimer ──
        val disclaimer = TextView(ctx).apply {
            text = "⚠ AI guidance is not a substitute for professional medical help. Call 102/108 for ambulance."
            textSize = 11f
            setTextColor(UiColors.accentOrange)
            background = UiUtils.roundedBackground(Color.argb(30, 255, 152, 0), 8, ctx)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = dp(4); params.bottomMargin = dp(8)
            layoutParams = params
        }
        root.addView(disclaimer)

        // ── Quick actions ──
        root.addView(UiUtils.makeSectionHeader(ctx, "⚡ QUICK ACTIONS"))
        quickActionsContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }
        val quickScrollH = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(quickActionsContainer)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = dp(4)
            layoutParams = params
        }
        root.addView(quickScrollH)
        buildQuickActions()

        // ── Chat area ──
        val chatScroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        chatContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        addAiBubble(
            "👋 Hello! I'm your offline first aid assistant.\n\n" +
            "Ask me anything about emergency first aid, disaster safety, or India emergency contacts.\n\n" +
            "Try tapping a quick action above, or type your question below."
        )
        chatScroll.addView(chatContainer)
        chatScrollView = chatScroll
        root.addView(chatScroll)

        // ── Input row ──
        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        queryInput = EditText(ctx).apply {
            hint = "Ask about first aid…"
            textSize = 14f
            setTextColor(UiColors.textPrimary)
            setHintTextColor(UiColors.textDim)
            background = UiUtils.roundedBackground(UiColors.bgInput, 20, ctx)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            minLines = 1; maxLines = 3
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        inputRow.addView(queryInput)
        val askButton = UiUtils.makeChipButton(ctx, "Ask", UiColors.accentRed)
        askButton.setOnClickListener { handleQuery() }
        inputRow.addView(askButton)
        root.addView(inputRow)

        // ── Boot GemmaEngine in background ──
        bootGemmaEngine()

        return root
    }

    // ── GemmaEngine Init ──────────────────────────────────────────────────

    private fun bootGemmaEngine() {
        val ctx = context ?: return

        if (!GemmaEngine.isArm64) {
            // Non-arm64 device — silently use knowledge base
            return
        }

        if (!GemmaEngine.modelExists(ctx)) {
            // arm64 but no model file — show the adb banner
            mainHandler.post {
                if (isAdded) modelBanner.visibility = View.VISIBLE
            }
            return
        }

        // Model file found — try to init on background thread
        Thread {
            val success = GemmaEngine.initialize(ctx)
            gemmaInitialized = success
            mainHandler.post {
                if (!isAdded) return@post
                if (success) {
                    aiStatusBadge.text = "🧠 Snapdragon NPU"
                    aiStatusBadge.setTextColor(UiColors.accentCyan)
                    addAiBubble("🚀 Gemma 4 2B loaded on the Snapdragon NPU! You'll get real AI responses powered by your device's on-chip intelligence.")
                    chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
                } else {
                    addAiBubble("⚠️ NPU model failed to load (${GemmaEngine.lastError}). Using offline knowledge base instead.")
                    chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }.start()
    }

    // ── Quick Action Chips ────────────────────────────────────────────────

    private fun buildQuickActions() {
        val ctx = requireContext()
        val dp = { v: Int -> UiUtils.dp(ctx, v) }
        val actions = knowledgeBase.getQuickActions()
        val colors = listOf(
            UiColors.accentRed, UiColors.accentOrange, UiColors.accentBlue,
            UiColors.accentGreen, UiColors.accentPurple, UiColors.accentCyan,
            UiColors.accentOrange, UiColors.accentRed
        )
        actions.forEachIndexed { index, (label, query) ->
            val baseColor = colors[index % colors.size]
            val chipBg = Color.argb(40,
                Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
            val chip = UiUtils.makeChipButton(ctx, label, chipBg, baseColor)
            chip.setOnClickListener { queryInput.setText(query); handleQuery() }
            val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            p.marginEnd = dp(6)
            quickActionsContainer.addView(chip, p)
        }
    }

    // ── Query Handling ────────────────────────────────────────────────────

    private fun handleQuery() {
        val query = queryInput.text.toString().trim()
        if (query.isEmpty()) return
        queryInput.text.clear()

        addUserBubble(query)
        chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }

        if (gemmaInitialized && GemmaEngine.isReady) {
            runGemmaInference(query)
        } else {
            runKnowledgeBaseSearch(query)
        }
    }

    private fun runGemmaInference(query: String) {
        // Create the bubble first with a "thinking" placeholder
        val thinkingText = "💭 Thinking…"
        val bubbleView = addAiBubbleStreaming(thinkingText)
        val bubbleTextView = bubbleView.tag as? TextView ?: return

        chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }

        val tokenBuffer = StringBuilder()
        Thread {
            GemmaEngine.generateResponse(
                query = query,
                onToken = { token ->
                    tokenBuffer.append(token)
                    mainHandler.post {
                        if (isAdded) {
                            bubbleTextView.text = tokenBuffer.toString()
                            chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
                        }
                    }
                },
                onDone = {
                    mainHandler.post {
                        if (isAdded && tokenBuffer.isEmpty()) {
                            bubbleTextView.text = "I couldn't generate a response. Try rephrasing."
                        }
                        chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
                    }
                },
                onError = { err ->
                    mainHandler.post {
                        if (isAdded) {
                            bubbleTextView.text = "⚠️ AI error: $err\n\nFalling back to knowledge base…"
                            // Fall back gracefully
                            runKnowledgeBaseSearch(query)
                        }
                    }
                }
            )
        }.start()
    }

    private fun runKnowledgeBaseSearch(query: String) {
        val result = knowledgeBase.search(query)
        if (result != null) {
            addAiBubble(knowledgeBase.formatResponse(result))
        } else {
            addAiBubble(
                "I couldn't find a specific match for \"$query\".\n\n" +
                "Try asking about:\n" +
                "• CPR or cardiac arrest\n• Bleeding or wounds\n• Burns treatment\n" +
                "• Snake bite first aid\n• Earthquake or flood safety\n" +
                "• Choking or drowning\n• Heat stroke\n• Emergency contacts India\n\n" +
                "📞 Universal Emergency: 112\n🚑 Ambulance: 102 / 108"
            )
        }
        chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
    }

    // ── Bubble Builders ───────────────────────────────────────────────────

    private fun addUserBubble(text: String) {
        val ctx = requireContext()
        val dp = { v: Int -> UiUtils.dp(ctx, v) }
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.END
            val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            p.bottomMargin = dp(8); layoutParams = p
        }
        val bubble = TextView(ctx).apply {
            this.text = text; textSize = 14f
            setTextColor(UiColors.textPrimary)
            background = UiUtils.roundedBackground(UiColors.accentBlue, 16, ctx)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(48)
            }
        }
        wrapper.addView(bubble); chatContainer.addView(wrapper)
    }

    private fun addAiBubble(text: String): View {
        val ctx = requireContext()
        val dp = { v: Int -> UiUtils.dp(ctx, v) }
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.START
            val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            p.bottomMargin = dp(8); layoutParams = p
        }
        val labelRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        val aiLabel = TextView(ctx).apply {
            this.text = if (gemmaInitialized && GemmaEngine.isReady) "🧠 Snapdragon AI" else "🤖 AI First Aid"
            textSize = 12f; setTextColor(UiColors.accentCyan)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        }
        labelRow.addView(aiLabel); wrapper.addView(labelRow)

        val bubble = TextView(ctx).apply {
            this.text = text; textSize = 14f
            setTextColor(UiColors.textPrimary); setLineSpacing(0f, 1.3f)
            background = UiUtils.outlineBackground(UiColors.bgCard, UiColors.accentCyan, 1, 16, ctx)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(24)
            }
        }
        wrapper.addView(bubble); chatContainer.addView(wrapper)
        return wrapper
    }

    /**
     * Creates a streaming AI bubble whose text is updated token-by-token.
     * The [View.tag] holds the inner [TextView] so the caller can update it.
     */
    private fun addAiBubbleStreaming(initialText: String): View {
        val wrapper = addAiBubble(initialText)
        // The last child of wrapper is the bubble TextView
        val bubbleTextView = (wrapper as LinearLayout).getChildAt(1) as TextView
        wrapper.tag = bubbleTextView
        return wrapper
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun spacerView(ctx: android.content.Context, width: Int): View =
        View(ctx).apply { layoutParams = LinearLayout.LayoutParams(width, 1) }

    override fun onDestroyView() {
        super.onDestroyView()
        if (gemmaInitialized) {
            Thread { GemmaEngine.close() }.start()
            gemmaInitialized = false
        }
    }
}
