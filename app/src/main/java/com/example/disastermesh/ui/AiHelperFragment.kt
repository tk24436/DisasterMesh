package com.example.disastermesh.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.example.disastermesh.ai.FirstAidKnowledgeBase

class AiHelperFragment : Fragment() {

    private lateinit var chatContainer: LinearLayout
    private lateinit var chatScrollView: ScrollView
    private lateinit var queryInput: EditText
    private lateinit var quickActionsContainer: LinearLayout

    private val knowledgeBase = FirstAidKnowledgeBase()

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
        root.addView(UiUtils.makeTitle(ctx, "🤖 AI First Aid"))

        val subtitleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }

        val offlineBadge = UiUtils.makeChipButton(ctx, "⚡ Offline", UiColors.accentGreen).apply {
            textSize = 11f
            isClickable = false
        }
        subtitleRow.addView(offlineBadge)

        val spacer = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(6), 1)
        }
        subtitleRow.addView(spacer)

        val deviceBadge = UiUtils.makeChipButton(ctx, "📱 On-Device", UiColors.accentPurple).apply {
            textSize = 11f
            isClickable = false
        }
        subtitleRow.addView(deviceBadge)

        val spacer2 = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(6), 1)
        }
        subtitleRow.addView(spacer2)

        val indiaBadge = UiUtils.makeChipButton(ctx, "🇮🇳 India", UiColors.accentOrange).apply {
            textSize = 11f
            isClickable = false
        }
        subtitleRow.addView(indiaBadge)

        root.addView(subtitleRow)

        // Disclaimer
        val disclaimer = TextView(ctx).apply {
            text = "⚠ AI guidance is not a substitute for professional medical help. Call 102/108 for ambulance."
            textSize = 11f
            setTextColor(UiColors.accentOrange)
            background = UiUtils.roundedBackground(
                android.graphics.Color.argb(30, 255, 152, 0), 8, ctx
            )
            setPadding(dp(10), dp(6), dp(10), dp(6))
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = dp(8)
            params.bottomMargin = dp(8)
            layoutParams = params
        }
        root.addView(disclaimer)

        // Quick actions
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

        // Build quick action chips
        buildQuickActions()

        // Chat area
        val chatScroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        chatContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }

        // Welcome message
        addAiBubble(
            "👋 Hello! I'm your offline first aid assistant.\n\n" +
            "Ask me anything about emergency first aid, disaster safety, or India emergency contacts.\n\n" +
            "Try tapping a quick action above, or type your question below."
        )

        chatScroll.addView(chatContainer)
        chatScrollView = chatScroll
        root.addView(chatScroll)

        // Input row
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
            minLines = 1
            maxLines = 3
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        inputRow.addView(queryInput)

        val askButton = UiUtils.makeChipButton(ctx, "Ask", UiColors.accentRed)
        askButton.setOnClickListener { handleQuery() }
        inputRow.addView(askButton)

        root.addView(inputRow)

        return root
    }

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
            val chipColor = android.graphics.Color.argb(
                40,
                android.graphics.Color.red(colors[index % colors.size]),
                android.graphics.Color.green(colors[index % colors.size]),
                android.graphics.Color.blue(colors[index % colors.size])
            )

            val chip = UiUtils.makeChipButton(ctx, label, chipColor, colors[index % colors.size])
            chip.setOnClickListener {
                queryInput.setText(query)
                handleQuery()
            }

            val chipParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            chipParams.marginEnd = dp(6)
            quickActionsContainer.addView(chip, chipParams)
        }
    }

    private fun handleQuery() {
        val query = queryInput.text.toString().trim()
        if (query.isEmpty()) return

        queryInput.text.clear()

        // Add user bubble
        addUserBubble(query)

        // Search knowledge base
        val result = knowledgeBase.search(query)

        if (result != null) {
            val response = knowledgeBase.formatResponse(result)
            addAiBubble(response)
        } else {
            addAiBubble(
                "I couldn't find a specific match for \"$query\".\n\n" +
                "Try asking about:\n" +
                "• CPR or cardiac arrest\n" +
                "• Bleeding or wounds\n" +
                "• Burns treatment\n" +
                "• Snake bite first aid\n" +
                "• Earthquake or flood safety\n" +
                "• Choking or drowning\n" +
                "• Heat stroke\n" +
                "• Emergency contacts India\n\n" +
                "📞 Universal Emergency: 112\n" +
                "🚑 Ambulance: 102 / 108"
            )
        }

        // Scroll to bottom
        chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addUserBubble(text: String) {
        val ctx = requireContext()
        val dp = { v: Int -> UiUtils.dp(ctx, v) }

        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = dp(8)
            layoutParams = params
        }

        val bubble = TextView(ctx).apply {
            this.text = text
            textSize = 14f
            setTextColor(UiColors.textPrimary)
            background = UiUtils.roundedBackground(UiColors.accentBlue, 16, ctx)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            val bubbleParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            bubbleParams.marginStart = dp(48)
            layoutParams = bubbleParams
        }

        wrapper.addView(bubble)
        chatContainer.addView(wrapper)
    }

    private fun addAiBubble(text: String) {
        val ctx = requireContext()
        val dp = { v: Int -> UiUtils.dp(ctx, v) }

        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = dp(8)
            layoutParams = params
        }

        val labelRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }

        val aiLabel = TextView(ctx).apply {
            this.text = "🤖 AI First Aid"
            textSize = 12f
            setTextColor(UiColors.accentCyan)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        }
        labelRow.addView(aiLabel)
        wrapper.addView(labelRow)

        val bubble = TextView(ctx).apply {
            this.text = text
            textSize = 14f
            setTextColor(UiColors.textPrimary)
            setLineSpacing(0f, 1.3f)
            background = UiUtils.outlineBackground(
                UiColors.bgCard, UiColors.accentCyan, 1, 16, ctx
            )
            setPadding(dp(14), dp(10), dp(14), dp(10))
            val bubbleParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            bubbleParams.marginEnd = dp(24)
            layoutParams = bubbleParams
        }

        wrapper.addView(bubble)
        chatContainer.addView(wrapper)
    }
}
