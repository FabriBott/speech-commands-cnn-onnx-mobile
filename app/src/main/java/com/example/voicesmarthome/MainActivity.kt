package com.example.voicesmarthome

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.Manifest
import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

private const val REQUEST_RECORD_AUDIO_PERMISSION = 200
class MainActivity : AppCompatActivity() {

    private lateinit var recorder: MelSpectrogramRecorder
    private lateinit var container: FrameLayout
    private lateinit var recordButton: Button
    //private lateinit var recorder = MelSpectrogramRecorder(this, {}, {})

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        recorder = MelSpectrogramRecorder(this, {}, {})

        ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION) //request recording permission

        setContentView(R.layout.activity_main)

        // Referencia al contenedor
        container = findViewById(R.id.content_container)

        // Referencia al boton de grabacion
        recordButton = findViewById(R.id.record_button)

        // Pantalla inicial
        loadScreen(R.layout.dashboard_layout)

        // Referencias a tabs del top bar
        val tabDashboard = findViewById<TextView>(R.id.tab_dashboard)
        val tabDevice = findViewById<TextView>(R.id.tab_device)
        val tabRoutine = findViewById<TextView>(R.id.tab_routine)
        val tabConfirm = findViewById<TextView>(R.id.tab_confirm)

        // Clicks
        tabDashboard.setOnClickListener {
            loadScreen(R.layout.dashboard_layout)
            selectTab(tabDashboard)
        }

        tabDevice.setOnClickListener {
            loadScreen(R.layout.device_layout)
            selectTab(tabDevice)
        }

        tabRoutine.setOnClickListener {
            loadScreen(R.layout.routine_layout)
            selectTab(tabRoutine)
        }

        tabConfirm.setOnClickListener {
            loadScreen(R.layout.confirm_layout)
            selectTab(tabConfirm)
        }

        recordButton.setOnClickListener {  }

        recordButton.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    recorder.start()
                    true
                }

                MotionEvent.ACTION_UP -> {
                    recorder.stop()
                    true
                }
                else -> false
            }
        }
    }

    // Requesting permission to RECORD_AUDIO
    private var permissionToRecordAccepted = false
    private var permissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionToRecordAccepted = if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }
        if (!permissionToRecordAccepted) finish()
    }

    // Cambiar pantalla
    private fun loadScreen(layoutId: Int) {
        val view = LayoutInflater.from(this).inflate(layoutId, null)
        container.removeAllViews()
        container.addView(view)
    }

    // Manejar selección visual del tab
    private fun selectTab(selected: TextView) {

        val tabs = listOf(
            findViewById<TextView>(R.id.tab_dashboard),
            findViewById<TextView>(R.id.tab_device),
            findViewById<TextView>(R.id.tab_routine),
            findViewById<TextView>(R.id.tab_confirm)
        )

        for (tab in tabs) {
            tab.setBackgroundResource(R.drawable.tab_unselected)
            tab.setTextColor(resources.getColor(android.R.color.darker_gray))
        }

        selected.setBackgroundResource(R.drawable.tab_selected)
        selected.setTextColor(resources.getColor(android.R.color.white))
    }
}