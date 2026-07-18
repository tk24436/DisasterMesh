package com.example.disastermesh.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object UiColors {
    val bgPrimary = Color.rgb(13, 13, 15)
    val bgSurface = Color.rgb(26, 26, 46)
    val bgCard = Color.rgb(35, 35, 64)
    val bgInput = Color.rgb(42, 42, 74)

    val accentRed = Color.rgb(255, 61, 61)
    val accentRedDark = Color.rgb(204, 32, 32)
    val accentOrange = Color.rgb(255, 152, 0)
    val accentGreen = Color.rgb(76, 175, 80)
    val accentBlue = Color.rgb(68, 138, 255)
    val accentPurple = Color.rgb(187, 134, 252)
    val accentCyan = Color.rgb(0, 229, 255)

    val textPrimary = Color.rgb(240, 240, 240)
    val textSecondary = Color.rgb(158, 158, 184)
    val textDim = Color.rgb(107, 107, 128)

    val statusConnected = Color.rgb(76, 175, 80)
    val statusDiscovering = Color.rgb(255, 213, 79)
    val statusDisconnected = Color.rgb(255, 61, 61)
}

object UiUtils {

    fun dp(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    fun roundedBackground(color: Int, radiusDp: Int, context: Context): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.setColor(color)
        drawable.cornerRadius = dp(context, radiusDp).toFloat()
        return drawable
    }

    fun gradientBackground(
        startColor: Int,
        endColor: Int,
        radiusDp: Int,
        context: Context
    ): GradientDrawable {
        val drawable = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(startColor, endColor)
        )
        drawable.cornerRadius = dp(context, radiusDp).toFloat()
        return drawable
    }

    fun outlineBackground(
        fillColor: Int,
        strokeColor: Int,
        strokeWidthDp: Int,
        radiusDp: Int,
        context: Context
    ): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.setColor(fillColor)
        drawable.setStroke(dp(context, strokeWidthDp), strokeColor)
        drawable.cornerRadius = dp(context, radiusDp).toFloat()
        return drawable
    }

    fun makeTitle(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 26f
            setTextColor(UiColors.textPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.START
            setPadding(0, 0, 0, dp(context, 4))
        }
    }

    fun makeSubtitle(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(UiColors.textDim)
            setPadding(0, 0, 0, dp(context, 16))
        }
    }

    fun makeSectionHeader(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 14f
            setTextColor(UiColors.accentCyan)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(0, dp(context, 16), 0, dp(context, 8))
        }
    }

    fun makeCard(context: Context, accentColor: Int? = null): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bg = if (accentColor != null) {
                outlineBackground(UiColors.bgCard, accentColor, 1, 12, context)
            } else {
                roundedBackground(UiColors.bgCard, 12, context)
            }
            background = bg
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = dp(context, 8)
            layoutParams = params
        }
    }

    fun makeStyledButton(
        context: Context,
        text: String,
        bgColor: Int,
        textColor: Int = UiColors.textPrimary,
        radiusDp: Int = 12
    ): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 15f
            setTextColor(textColor)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = roundedBackground(bgColor, radiusDp, context)
            setPadding(dp(context, 20), dp(context, 14), dp(context, 20), dp(context, 14))
            isClickable = true
            isFocusable = true
        }
    }

    fun makeChipButton(
        context: Context,
        text: String,
        bgColor: Int = UiColors.bgInput,
        textColor: Int = UiColors.textPrimary
    ): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(textColor)
            gravity = Gravity.CENTER
            background = roundedBackground(bgColor, 20, context)
            setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8))
            isClickable = true
            isFocusable = true
        }
    }

    fun makeStyledInput(
        context: Context,
        hint: String,
        minLines: Int = 1
    ): EditText {
        return EditText(context).apply {
            this.hint = hint
            this.minLines = minLines
            textSize = 15f
            setTextColor(UiColors.textPrimary)
            setHintTextColor(UiColors.textDim)
            background = roundedBackground(UiColors.bgInput, 10, context)
            setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = dp(context, 8)
            layoutParams = params
        }
    }

    fun makeStatusDot(context: Context, color: Int): View {
        return View(context).apply {
            val size = dp(context, 10)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = dp(context, 8)
            }
            background = roundedBackground(color, 5, context)
        }
    }

    fun wrapInScroll(context: Context, content: LinearLayout): ScrollView {
        return ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }
    }
}
