package com.example.disastermesh.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.example.disastermesh.MainActivity
import com.example.disastermesh.data.PeerState
import com.example.disastermesh.mesh.MeshListener

class SettingsFragment : Fragment(), MeshListener {

    private lateinit var nameInput: EditText
    private lateinit var readinessContainer: LinearLayout
    private lateinit var meshStatusText: TextView
    private lateinit var peerCountText: TextView

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

        root.addView(UiUtils.makeTitle(ctx, "⚙️ Settings"))
        root.addView(UiUtils.makeSubtitle(ctx, "Configure your mesh node"))

        val scrollContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Node name section
        scrollContent.addView(UiUtils.makeSectionHeader(ctx, "👤 NODE IDENTITY"))

        val nameCard = UiUtils.makeCard(ctx)

        val nameLabel = TextView(ctx).apply {
            text = "Your node name (visible to other devices):"
            textSize = 13f
            setTextColor(UiColors.textSecondary)
            setPadding(0, 0, 0, dp(6))
        }
        nameCard.addView(nameLabel)

        nameInput = EditText(ctx).apply {
            textSize = 16f
            setTextColor(UiColors.textPrimary)
            setHintTextColor(UiColors.textDim)
            hint = "Enter your node name"
            background = UiUtils.roundedBackground(UiColors.bgInput, 10, ctx)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        nameCard.addView(nameInput)

        val currentName = loadNodeName(ctx)
        if (currentName != null) {
            nameInput.setText(currentName)
        } else if (isBound) {
            nameInput.setText(meshManager.nodeName)
        } else {
            nameInput.setText("Node")
        }

        val saveButton = UiUtils.makeStyledButton(ctx, "💾 SAVE NAME", UiColors.accentBlue)
        saveButton.setOnClickListener { saveName() }
        val saveBtnParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        saveBtnParams.topMargin = dp(8)
        nameCard.addView(saveButton, saveBtnParams)

        scrollContent.addView(nameCard)

        // Device readiness section
        scrollContent.addView(UiUtils.makeSectionHeader(ctx, "📡 DEVICE STATUS"))

        readinessContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        val readinessCard = UiUtils.makeCard(ctx)
        readinessCard.addView(readinessContainer)
        scrollContent.addView(readinessCard)

        // Mesh status section
        scrollContent.addView(UiUtils.makeSectionHeader(ctx, "🔗 MESH NETWORK"))

        val meshCard = UiUtils.makeCard(ctx)

        meshStatusText = TextView(ctx).apply {
            textSize = 14f
            setTextColor(UiColors.textPrimary)
        }
        meshCard.addView(meshStatusText)

        peerCountText = TextView(ctx).apply {
            textSize = 14f
            setTextColor(UiColors.textSecondary)
            setPadding(0, dp(4), 0, dp(8))
        }
        meshCard.addView(peerCountText)

        scrollContent.addView(meshCard)

        // Action buttons
        scrollContent.addView(UiUtils.makeSectionHeader(ctx, "🛠️ ACTIONS"))

        val restartBtn = UiUtils.makeStyledButton(ctx, "🔄 RESTART MESH", UiColors.accentOrange)
        restartBtn.setOnClickListener {
            meshManager.restartMesh()
            Toast.makeText(ctx, "Mesh restarted", Toast.LENGTH_SHORT).show()
            refreshReadiness()
            refreshMeshStatus()
        }
        val restartParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        restartParams.bottomMargin = dp(8)
        scrollContent.addView(restartBtn, restartParams)

        val demoBtn = UiUtils.makeStyledButton(ctx, "🎮 RUN DEMO MODE", UiColors.accentPurple)
        demoBtn.setOnClickListener {
            meshManager.runDemoMode()
            Toast.makeText(ctx, "Demo mode started", Toast.LENGTH_SHORT).show()
        }
        val demoParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        demoParams.bottomMargin = dp(8)
        scrollContent.addView(demoBtn, demoParams)

        val clearBtn = UiUtils.makeStyledButton(ctx, "🗑️ CLEAR ALL DATA", UiColors.accentRedDark)
        clearBtn.setOnClickListener {
            meshManager.clearAllData()
            Toast.makeText(ctx, "All data cleared", Toast.LENGTH_SHORT).show()
        }
        val clearParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        clearParams.bottomMargin = dp(8)
        scrollContent.addView(clearBtn, clearParams)

        val settingsBtn = UiUtils.makeStyledButton(ctx, "📱 ANDROID SETTINGS", UiColors.bgCard)
        settingsBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
        val settingsParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        settingsParams.bottomMargin = dp(16)
        scrollContent.addView(settingsBtn, settingsParams)

        // Version info
        val versionCard = UiUtils.makeCard(ctx, UiColors.accentPurple)
        val versionText = TextView(ctx).apply {
            text = "DisasterMesh v3.0\n" +
                   "OmniSight-XR • Part 2\n" +
                   "Snapdragon Multiverse Hackathon 2026\n\n" +
                   "Offline emergency mesh network with\n" +
                   "AI-powered first aid assistance 🇮🇳"
            textSize = 13f
            setTextColor(UiColors.textSecondary)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        }
        versionCard.addView(versionText)
        scrollContent.addView(versionCard)

        root.addView(UiUtils.wrapInScroll(ctx, scrollContent))

        return root
    }

    override fun onResume() {
        super.onResume()
        if (!isBound) return
        meshManager.addListener(this)
        refreshReadiness()
        refreshMeshStatus()
    }

    override fun onPause() {
        super.onPause()
        if (!isBound) return
        meshManager.removeListener(this)
    }

    private fun saveName() {
        val name = nameInput.text.toString().trim()
        if (name.isBlank()) {
            Toast.makeText(requireContext(), "Name cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }

        val ctx = requireContext()
        val prefs = ctx.getSharedPreferences("disastermesh_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("node_name", name).apply()

        if (isBound) meshManager.nodeName = name

        Toast.makeText(ctx, "Node name saved: $name", Toast.LENGTH_SHORT).show()
    }

    private fun refreshReadiness() {
        if (!::readinessContainer.isInitialized) return

        readinessContainer.removeAllViews()
        val ctx = requireContext()
        val readiness = (requireActivity() as MainActivity).getDeviceReadiness()

        addReadinessRow(ctx, "Bluetooth", readiness.bluetoothOn)
        addReadinessRow(ctx, "Wi-Fi", readiness.wifiOn)
        addReadinessRow(ctx, "Location", readiness.locationOn)
        addReadinessRow(ctx, "Permissions", readiness.permissionsGranted)
        addReadinessRow(ctx, "Battery Saver Off", readiness.batterySaverOff)
    }

    private fun addReadinessRow(ctx: Context, label: String, ok: Boolean) {
        val dp = { v: Int -> UiUtils.dp(ctx, v) }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }

        val icon = TextView(ctx).apply {
            text = if (ok) "✅" else "❌"
            textSize = 16f
            setPadding(0, 0, dp(10), 0)
        }
        row.addView(icon)

        val text = TextView(ctx).apply {
            this.text = "$label: ${if (ok) "OK" else "FAILED"}"
            textSize = 14f
            setTextColor(if (ok) UiColors.accentGreen else UiColors.accentRed)
        }
        row.addView(text)

        readinessContainer.addView(row)
    }

    private fun refreshMeshStatus() {
        if (!::meshStatusText.isInitialized || !isBound) return

        val isRunning = meshManager.meshStarted
        meshStatusText.text = if (isRunning) "🟢 Mesh Active" else "🔴 Mesh Inactive"
        meshStatusText.setTextColor(if (isRunning) UiColors.accentGreen else UiColors.accentRed)

        val count = meshManager.connectedEndpoints.size
        peerCountText.text = "Connected peers: $count"
    }

    override fun onConnectionCountChanged(count: Int) {
        refreshMeshStatus()
    }

    override fun onMeshStatusChanged(started: Boolean) {
        refreshMeshStatus()
    }

    companion object {
        fun loadNodeName(context: Context): String? {
            val prefs = context.getSharedPreferences("disastermesh_prefs", Context.MODE_PRIVATE)
            return prefs.getString("node_name", null)
        }
    }
}
