package com.example.disastermesh

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.disastermesh.data.DeviceReadiness
import com.example.disastermesh.data.MeshDatabase
import com.example.disastermesh.mesh.MeshManager
import com.example.disastermesh.ui.*
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    lateinit var meshManager: MeshManager
        private set

    private lateinit var database: MeshDatabase
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var fragmentContainer: FrameLayout

    private val broadcastFragment = BroadcastFragment()
    private val messagesFragment = MessagesFragment()
    private val aiHelperFragment = AiHelperFragment()
    private val settingsFragment = SettingsFragment()

    private var activeFragment: Fragment = broadcastFragment

    companion object {
        private const val CONTAINER_ID = 0x7F100001
        private const val PERMISSION_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        database = MeshDatabase.getDatabase(this)
        meshManager = MeshManager(this, database)

        // Load saved node name
        val savedName = SettingsFragment.loadNodeName(this)
        if (savedName != null) {
            meshManager.nodeName = savedName
        }

        requestPermissionsIfNeeded()
        buildUi()

        // Load stored data
        meshManager.loadStoredData()
    }

    override fun onResume() {
        super.onResume()

        // Auto-start mesh if ready and not yet started
        val readiness = getDeviceReadiness()
        if (readiness.canStart && !meshManager.meshStarted) {
            meshManager.startMesh()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (meshManager.meshStarted) {
            meshManager.stopMesh()
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(13, 13, 15))
        }

        // Fragment container
        fragmentContainer = FrameLayout(this).apply {
            id = CONTAINER_ID
            setBackgroundColor(Color.rgb(13, 13, 15))
        }

        root.addView(fragmentContainer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // Bottom navigation
        bottomNav = BottomNavigationView(this).apply {
            setBackgroundColor(Color.rgb(15, 15, 20))
            itemIconTintList = android.content.res.ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf()
                ),
                intArrayOf(
                    Color.rgb(255, 61, 61),     // active
                    Color.rgb(107, 107, 128)     // inactive
                )
            )
            itemTextColor = android.content.res.ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf()
                ),
                intArrayOf(
                    Color.rgb(255, 61, 61),
                    Color.rgb(107, 107, 128)
                )
            )
            labelVisibilityMode = BottomNavigationView.LABEL_VISIBILITY_LABELED
            inflateMenu(R.menu.bottom_nav)
        }

        root.addView(bottomNav, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)

        // Setup fragments
        setupFragments()

        // Navigation listener
        bottomNav.setOnItemSelectedListener { item ->
            val fragment = when (item.itemId) {
                R.id.nav_broadcast -> broadcastFragment
                R.id.nav_messages -> messagesFragment
                R.id.nav_ai -> aiHelperFragment
                R.id.nav_settings -> settingsFragment
                else -> broadcastFragment
            }
            switchFragment(fragment)
            true
        }
    }

    private fun setupFragments() {
        val fm = supportFragmentManager
        val transaction = fm.beginTransaction()

        transaction.add(CONTAINER_ID, broadcastFragment, "broadcast")
        transaction.add(CONTAINER_ID, messagesFragment, "messages")
        transaction.add(CONTAINER_ID, aiHelperFragment, "ai")
        transaction.add(CONTAINER_ID, settingsFragment, "settings")

        transaction.hide(messagesFragment)
        transaction.hide(aiHelperFragment)
        transaction.hide(settingsFragment)

        transaction.commit()
    }

    private fun switchFragment(target: Fragment) {
        if (target == activeFragment) return

        val fm = supportFragmentManager
        fm.beginTransaction()
            .hide(activeFragment)
            .show(target)
            .commit()

        activeFragment = target
    }

    // ── Permissions ──

    private fun requiredPermissions(): List<String> {
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

        return permissions
    }

    private fun requestPermissionsIfNeeded() {
        val missing = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                val readiness = getDeviceReadiness()
                if (readiness.canStart && !meshManager.meshStarted) {
                    meshManager.startMesh()
                }
            } else {
                Toast.makeText(
                    this,
                    "Some permissions were denied. Mesh may not work correctly.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ── Device readiness (exposed for SettingsFragment) ──

    @SuppressLint("MissingPermission")
    fun getDeviceReadiness(): DeviceReadiness {
        val bluetoothOn = try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            adapter != null && adapter.isEnabled
        } catch (e: Exception) { false }

        val wifiOn = try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiManager.isWifiEnabled
        } catch (e: Exception) { false }

        val locationOn = try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) { false }

        val permissionsGranted = requiredPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

        val batterySaverOff = try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isPowerSaveMode == false
        } catch (e: Exception) { true }

        return DeviceReadiness(
            bluetoothOn = bluetoothOn,
            wifiOn = wifiOn,
            locationOn = locationOn,
            permissionsGranted = permissionsGranted,
            batterySaverOff = batterySaverOff
        )
    }
}