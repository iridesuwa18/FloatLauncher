package com.example.floatlauncher

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import kotlin.math.abs

class FloatingService : Service() {

    private class AppEntry(val label: String, val pkg: String, val icon: Drawable)

    private lateinit var wm: WindowManager
    private var bubble: TextView? = null
    private var panel: View? = null
    private var apps: List<AppEntry> = emptyList()
    private val main = Handler(Looper.getMainLooper())
    private val bubbleLp = WindowManager.LayoutParams()

    private val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun screen(): Point = Point().also { wm.defaultDisplay.getRealSize(it) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startInForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        addBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        hidePanel()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        super.onDestroy()
    }

    // ---- foreground notification ----

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Floating launcher", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Floating launcher is running")
            .setContentText("Tap Stop to remove the bubble")
            .setSmallIcon(android.R.drawable.ic_menu_more)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_delete), "Stop", stop
                ).build()
            )
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    // ---- bubble ----

    private fun addBubble() {
        val b = TextView(this).apply {
            text = "\u2630"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            alpha = 0.9f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC7C4DFF"))
            }
        }
        bubbleLp.apply {
            width = dp(52)
            height = dp(52)
            type = overlayType
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(200)
        }
        b.setOnTouchListener(DragListener())
        wm.addView(b, bubbleLp)
        bubble = b
    }

    private inner class DragListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = bubbleLp.x; startY = bubbleLp.y
                    touchX = e.rawX; touchY = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        val s = screen()
                        bubbleLp.x = (startX + dx.toInt()).coerceIn(0, s.x - bubbleLp.width)
                        bubbleLp.y = (startY + dy.toInt()).coerceIn(0, s.y - bubbleLp.height)
                        wm.updateViewLayout(v, bubbleLp)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) togglePanel()
            }
            return true
        }
    }

    // ---- app list panel ----

    private fun togglePanel() {
        if (panel != null) hidePanel() else showPanel()
    }

    private fun hidePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    private fun headerBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        setTextColor(Color.WHITE)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener { onClick() }
    }

    private fun showPanel() {
        val s = screen()
        val pw = minOf(dp(300), s.x - dp(24))
        val ph = minOf(dp(420), s.y - dp(80))

        val adapter = AppAdapter()
        val list = ListView(this).apply {
            divider = null
            this.adapter = adapter
            setOnItemClickListener { _, _, pos, _ -> launchApp(apps[pos]) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "Open in floating window"
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(dp(8), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(headerBtn("Stop") { stopSelf() })
            addView(headerBtn("\u2715") { hidePanel() })
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#F2202030"))
            }
            addView(header)
            addView(list)
        }

        val lp = WindowManager.LayoutParams(
            pw, ph, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val bs = bubbleLp.width
            val wantX = if (bubbleLp.x + bs / 2 < s.x / 2) bubbleLp.x + bs + dp(8)
                        else bubbleLp.x - pw - dp(8)
            x = wantX.coerceIn(dp(4), s.x - pw - dp(4))
            y = bubbleLp.y.coerceIn(dp(24), s.y - ph - dp(24))
        }

        wm.addView(root, lp)
        panel = root

        loadApps { adapter.notifyDataSetChanged() }
    }

    private fun loadApps(done: () -> Unit) {
        if (apps.isNotEmpty()) { done(); return }
        Thread {
            val pm = packageManager
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val result = pm.queryIntentActivities(query, 0)
                .filter { it.activityInfo.packageName != packageName }
                .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm)) }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
            main.post { apps = result; done() }
        }.start()
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(position: Int): Any = apps[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView as? LinearLayout ?: LinearLayout(this@FloatingService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
                addView(ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                })
                addView(TextView(context).apply {
                    textSize = 15f
                    maxLines = 1
                    setTextColor(Color.WHITE)
                    setPadding(dp(12), 0, 0, 0)
                })
            }
            val app = apps[position]
            (row.getChildAt(0) as ImageView).setImageDrawable(app.icon)
            (row.getChildAt(1) as TextView).text = app.label
            return row
        }
    }

    // ---- launching ----

    private fun launchApp(app: AppEntry) {
        val intent = packageManager.getLaunchIntentForPackage(app.pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val s = screen()
        val landscape = s.x > s.y
        val w = if (landscape) (s.x * 0.5f).toInt() else (s.x * 0.92f).toInt()
        val h = if (landscape) (s.y * 0.8f).toInt() else (s.y * 0.45f).toInt()
        val left = (s.x - w) / 2
        val top = (s.y - h) / 2

        val opts = ActivityOptions.makeBasic()
        opts.setLaunchBounds(Rect(left, top, left + w, top + h))
        // Ask for freeform windowing mode (5). Hidden API, so it may be refused on some builds.
        runCatching {
            ActivityOptions::class.java
                .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(opts, 5)
        }

        hidePanel()
        try {
            startActivity(intent, opts.toBundle())
        } catch (t: Throwable) {
            startActivity(intent)
        }
    }

    companion object {
        private const val CHANNEL = "float_launcher"
        private const val ACTION_STOP = "com.example.floatlauncher.STOP"
    }
}
