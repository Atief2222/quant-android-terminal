package com.quant.terminal

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.quant.terminal.api.ApiClient
import com.quant.terminal.api.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var bottomSheetView: View? = null

    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var sheetParams: WindowManager.LayoutParams

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val chatHistory = mutableListOf<ChatMessage>()
    private var isSheetOpen = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundServiceNotification()
        setupBubbleView()
        setupBottomSheetView()
    }

    private fun startForegroundServiceNotification() {
        val channelId = "quant_floating_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Quant Assistant Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Layanan pemantau sinyal & overlay AI aktif"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Quant Terminal Active")
            .setContentText("Gelembung AI Mentor siap digunakan.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1001, notification)
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun setupBubbleView() {
        val inflater = LayoutInflater.from(this)
        bubbleView = inflater.inflate(R.layout.overlay_bubble, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 300
        }

        val display = windowManager.defaultDisplay
        val screenSize = Point()
        display.getSize(screenSize)
        val screenWidth = screenSize.x

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = true

        bubbleView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = bubbleParams.x
                    initialY = bubbleParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isClick = false
                    }
                    bubbleParams.x = initialX + dx
                    bubbleParams.y = initialY + dy
                    windowManager.updateViewLayout(bubbleView, bubbleParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) {
                        toggleBottomSheet()
                    } else {
                        // Fitur Magnetik (Auto-Snap ke tepi terdekat)
                        val middle = screenWidth / 2
                        bubbleParams.x = if (bubbleParams.x + (bubbleView?.width ?: 0) / 2 < middle) {
                            16
                        } else {
                            screenWidth - (bubbleView?.width ?: 150) - 16
                        }
                        windowManager.updateViewLayout(bubbleView, bubbleParams)
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bubbleView, bubbleParams)
    }

    @SuppressLint("InflateParams")
    private fun setupBottomSheetView() {
        val inflater = LayoutInflater.from(this)
        bottomSheetView = inflater.inflate(R.layout.overlay_bottomsheet, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        sheetParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        val btnClose = bottomSheetView?.findViewById<ImageView>(R.id.btn_close_overlay)
        val btnReset = bottomSheetView?.findViewById<Button>(R.id.btn_reset_chat)
        val btnSend = bottomSheetView?.findViewById<Button>(R.id.btn_send_chat)
        val etMessage = bottomSheetView?.findViewById<EditText>(R.id.et_message)

        val chipTrend = bottomSheetView?.findViewById<Button>(R.id.chip_trend)
        val chipSnr = bottomSheetView?.findViewById<Button>(R.id.chip_snr)
        val chipMacro = bottomSheetView?.findViewById<Button>(R.id.chip_macro)

        btnClose?.setOnClickListener {
            toggleBottomSheet()
        }

        btnReset?.setOnClickListener {
            chatHistory.clear()
            val container = bottomSheetView?.findViewById<LinearLayout>(R.id.container_chat_messages)
            container?.removeAllViews()
            addMessageView("model", "Memori percakapan dibersihkan. Silakan ajukan pertanyaan analisis baru.")
        }

        btnSend?.setOnClickListener {
            val query = etMessage?.text?.toString()?.trim() ?: ""
            if (query.isNotEmpty()) {
                etMessage?.setText("")
                processUserChat(query)
            }
        }

        chipTrend?.setOnClickListener {
            processUserChat("Bagaimana struktur tren pasar dan status konfluensi saat ini?")
        }

        chipSnr?.setOnClickListener {
            processUserChat("Tolong validasi level Support/Resistance (Demand & Supply) terdekat.")
        }

        chipMacro?.setOnClickListener {
            processUserChat("Bagaimana tekanan makro (MPI, DXY, Yield) terhadap harga XAUUSD saat ini?")
        }

        addMessageView("model", "Halo! Saya AI Mentor Trading Anda. Klik quick button di atas atau ketik pertanyaan langsung.")
    }

    private fun toggleBottomSheet() {
        if (isSheetOpen) {
            bottomSheetView?.let {
                if (it.windowToken != null) {
                    windowManager.removeView(it)
                }
            }
            isSheetOpen = false
        } else {
            bottomSheetView?.let {
                if (it.windowToken == null) {
                    windowManager.addView(it, sheetParams)
                }
            }
            isSheetOpen = true
        }
    }

    private fun processUserChat(userText: String) {
        addMessageView("user", userText)
        chatHistory.add(ChatMessage("user", userText))

        serviceScope.launch {
            val thinkingView = addMessageView("model", "Menganalisis data live...")
            val reply = ApiClient.sendAiChat(userText, chatHistory)
            chatHistory.add(ChatMessage("model", reply))

            withContext(Dispatchers.Main) {
                thinkingView.text = reply
                val scroll = bottomSheetView?.findViewById<ScrollView>(R.id.scroll_chat)
                scroll?.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun addMessageView(role: String, text: String): TextView {
        val container = bottomSheetView?.findViewById<LinearLayout>(R.id.container_chat_messages)
        val tv = TextView(this).apply {
            this.text = text
            textSize = 13spToPx()
            setTextColor(Color.WHITE)
            setPadding(24, 16, 24, 16)
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 12
            bottomMargin = 4
        }

        if (role == "user") {
            params.gravity = Gravity.END
            tv.setBackgroundResource(R.drawable.bg_chat_user)
        } else {
            params.gravity = Gravity.START
            tv.setBackgroundResource(R.drawable.bg_chat_ai)
        }

        tv.layoutParams = params
        container?.addView(tv)

        val scroll = bottomSheetView?.findViewById<ScrollView>(R.id.scroll_chat)
        scroll?.post { scroll.fullScroll(View.FOCUS_DOWN) }
        return tv
    }

    private fun TextView.13spToPx(): Float = 13f

    override fun onDestroy() {
        super.onDestroy()
        bubbleView?.let { if (it.windowToken != null) windowManager.removeView(it) }
        bottomSheetView?.let { if (it.windowToken != null) windowManager.removeView(it) }
    }
}
