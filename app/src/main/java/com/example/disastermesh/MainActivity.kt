package com.example.disastermesh

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup

class MainActivity : Activity() {

    private lateinit var logText: TextView
    private lateinit var alertCountText: TextView

    private var alertCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissionsIfNeeded()
        buildUi()
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf<String>()

        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        requestPermissions(permissions.toTypedArray(), 100)
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(32, 48, 32, 32)
        root.setBackgroundColor(Color.rgb(255, 245, 245))

        val title = TextView(this)
        title.text = "DisasterMesh"
        title.textSize = 32f
        title.setTextColor(Color.rgb(120, 0, 0))
        title.gravity = Gravity.CENTER
        title.setPadding(0, 0, 0, 12)

        val subtitle = TextView(this)
        subtitle.text = "Offline Emergency Relay Network"
        subtitle.textSize = 16f
        subtitle.gravity = Gravity.CENTER
        subtitle.setTextColor(Color.DKGRAY)
        subtitle.setPadding(0, 0, 0, 32)

        alertCountText = TextView(this)
        alertCountText.text = "Emergency alerts created: 0"
        alertCountText.textSize = 18f
        alertCountText.setTextColor(Color.BLACK)
        alertCountText.setPadding(0, 0, 0, 24)

        val sosButton = Button(this)
        sosButton.text = "BROADCAST SOS"
        sosButton.textSize = 18f
        sosButton.setBackgroundColor(Color.rgb(211, 47, 47))
        sosButton.setTextColor(Color.WHITE)

        sosButton.setOnClickListener {
            alertCount++
            alertCountText.text = "Emergency alerts created: $alertCount"
            addLog("SOS created locally: Need Help near Block C")
        }

        val safeButton = Button(this)
        safeButton.text = "MARK MYSELF SAFE"
        safeButton.textSize = 16f

        safeButton.setOnClickListener {
            alertCount++
            alertCountText.text = "Emergency alerts created: $alertCount"
            addLog("Safety update created locally: I am safe")
        }

        val logTitle = TextView(this)
        logTitle.text = "Local Alert Log"
        logTitle.textSize = 20f
        logTitle.setTextColor(Color.BLACK)
        logTitle.setPadding(0, 32, 0, 12)

        logText = TextView(this)
        logText.text = "No alerts yet."
        logText.textSize = 15f
        logText.setTextColor(Color.DKGRAY)

        val scrollView = ScrollView(this)
        scrollView.addView(logText)

        root.addView(title)
        root.addView(subtitle)
        root.addView(alertCountText)
        root.addView(
            sosButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                150
            )
        )
        root.addView(
            safeButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                130
            )
        )
        root.addView(logTitle)
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
    }

    private fun addLog(message: String) {
        val oldText = logText.text.toString()

        logText.text = if (oldText == "No alerts yet.") {
            "• $message"
        } else {
            "• $message\n$oldText"
        }
    }
}