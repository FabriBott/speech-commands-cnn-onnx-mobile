package com.example.voicesmarthome

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var container: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Referencia al contenedor
        container = findViewById(R.id.content_container)

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