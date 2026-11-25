package com.example.floatlauncher

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

        root.addView(TextView(this).apply {
            textSize = 16f
            text = "Float Launcher\n\n" +
                "1. Allow overlay permission.\n" +
                "2. In Developer options, turn on \"Enable freeform windows\" and " +
                "\"Force activities to be resizable\", then reboot.\n" +
                "3. Start the launcher, then tap the bubble to pick an app."
        })
        root.addView(btn("1. Allow display over other apps") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        root.addView(btn("Open Developer options") {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
        })
        root.addView(btn("2. Start floating launcher") { start() })
        root.addView(btn("Stop floating launcher") {
            stopService(Intent(this, FloatingService::class.java))
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow the overlay permission first", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, FloatingService::class.java))
        Toast.makeText(this, "Started. Look for the bubble.", Toast.LENGTH_SHORT).show()
    }
}
