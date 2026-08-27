package com.quant.terminal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.quant.terminal.api.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLivePrice: TextView
    private lateinit var bottomNav: BottomNavigationView
    
    private val activityScope = CoroutineScope(Dispatchers.Main + Job())
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (checkOverlayPermission()) {
            startFloatingOverlayService()
        } else {
            Toast.makeText(this, "Izin Overlay diperlukan untuk fitur gelembung mengambang.", Toast.LENGTH_LONG).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Notifikasi alarm dinonaktifkan.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tv_connection_status)
        tvLivePrice = findViewById(R.id.tv_live_price)
        bottomNav = findViewById(R.id.bottom_navigation)

        setupNavigationMenu()
        requestSystemPermissions()
        startLiveMonitoringLoop()
    }

    private fun setupNavigationMenu() {
        bottomNav.menu.apply {
            add(0, 1, 0, getString(R.string.tab_terminal)).setIcon(android.R.drawable.ic_menu_agenda)
            add(0, 2, 1, getString(R.string.tab_radar)).setIcon(android.R.drawable.ic_menu_compass)
            add(0, 3, 2, getString(R.string.tab_control)).setIcon(android.R.drawable.ic_menu_manage)
            add(0, 4, 3, getString(R.string.tab_settings)).setIcon(android.R.drawable.ic_menu_preferences)
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                1 -> Toast.makeText(this, "Tab Terminal Aktif", Toast.LENGTH_SHORT).show()
                2 -> Toast.makeText(this, "Tab Radar Indikator Aktif", Toast.LENGTH_SHORT).show()
                3 -> Toast.makeText(this, "Tab Kontrol Bot Aktif", Toast.LENGTH_SHORT).show()
                4 -> Toast.makeText(this, "Tab Pengaturan Aktif", Toast.LENGTH_SHORT).show()
            }
            true
        }
    }

    private fun requestSystemPermissions() {
        // 1. Izin Notifikasi (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 2. Izin Pengecualian Baterai
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // 3. Izin Floating Overlay Window
        if (checkOverlayPermission()) {
            startFloatingOverlayService()
        } else {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun startFloatingOverlayService() {
        val intent = Intent(this, FloatingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun startLiveMonitoringLoop() {
        activityScope.launch {
            while (isActive) {
                // Ambil link tunnel teraktif dari GitHub Gist
                val activeUrl = ApiClient.resolveActiveUrl()
                
                withContext(Dispatchers.IO) {
                    try {
                        val startTime = System.currentTimeMillis()
                        val req = Request.Builder()
                            .url("$activeUrl/api/market-pulse")
                            .get()
                            .build()

                        client.newCall(req).execute().use { resp ->
                            val latency = System.currentTimeMillis() - startTime
                            if (resp.isSuccessful) {
                                val bodyStr = resp.body?.string() ?: "{}"
                                val json = JSONObject(bodyStr)
                                
                                val liveTick = json.optJSONObject("live_tick")
                                val bid = liveTick?.optDouble("bid", 0.0) ?: 0.0
                                val spread = liveTick?.optDouble("spread_pts", 0.0) ?: 0.0

                                withContext(Dispatchers.Main) {
                                    tvStatus.text = "● Terhubung (${latency}ms) | Spread: ${spread.toInt()} pts"
                                    tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.bull_green))
                                    if (bid > 0) {
                                        tvLivePrice.text = String.format("XAUUSD: %.2f", bid)
                                    }
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    tvStatus.text = "● Server Standby (HTTP ${resp.code})"
                                    tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.gold_amber))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            tvStatus.text = "● Mencari Server Tunnel..."
                            tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.bear_red))
                        }
                    }
                }
                delay(3000) // Polling interval setiap 3 detik
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
