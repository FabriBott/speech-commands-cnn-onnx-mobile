package com.example.voicesmarthome

import android.content.pm.PackageManager
import android.os.Bundle
import android.Manifest
import android.annotation.SuppressLint
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.graphics.toColorInt

private const val REQUEST_RECORD_AUDIO_PERMISSION = 200
class MainActivity : AppCompatActivity() {
    private lateinit var recorder: MelSpectrogramRecorder
    private lateinit var container: FrameLayout
    private lateinit var recordButton: Button
    private var selectedScreen = 0
    private val numberOfCols = 2
    private var currentContext = "dashboard"
    private var confirmAction = currentContext
    private var selectedAction = ""
    private var selectedActionIndex = 0
    private val appStatus = mutableMapOf<String, Boolean>()

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

        //Command tester clicks
        findViewById<Button>(R.id.yes_button).setOnClickListener { runCommand("yes") }
        findViewById<Button>(R.id.no_button).setOnClickListener { runCommand("no") }
        findViewById<Button>(R.id.up_button).setOnClickListener { runCommand("up") }
        findViewById<Button>(R.id.down_button).setOnClickListener { runCommand("down") }
        findViewById<Button>(R.id.left_button).setOnClickListener { runCommand("left") }
        findViewById<Button>(R.id.right_button).setOnClickListener { runCommand("right") }
        findViewById<Button>(R.id.on_button).setOnClickListener { runCommand("on") }
        findViewById<Button>(R.id.off_button).setOnClickListener { runCommand("off") }
        findViewById<Button>(R.id.stop_button).setOnClickListener { runCommand("stop") }
        findViewById<Button>(R.id.go_button).setOnClickListener { runCommand("go") }

        recordButton.setOnClickListener {  }

        recordButton.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    recorder.start()
                    true
                }

                MotionEvent.ACTION_UP -> {
                    recorder.stop()
                    runCommand(recorder.inferenceResult)
                    true
                }
                else -> false
            }
        }

        appStatus["Lights"] = true
        appStatus["Fan"] = false
        appStatus["TV"] = false
        appStatus["Study Mode"] = false
        appStatus["Night Mode"] = false
        appStatus["Welcome Home"] = false
        navigateDashboardOption(3)
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
        selectedScreen = layoutId
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

    //Dashboard
    private fun navigateDashboardOption(option: Int) {
        val options = listOf(
            findViewById<LinearLayout>(R.id.lights_card),
            findViewById<LinearLayout>(R.id.fan_card),
            findViewById<LinearLayout>(R.id.tv_card),
            findViewById<LinearLayout>(R.id.routine_card)
        )

        for (card in options) {
            card.setBackgroundResource(R.drawable.card_normal)
        }

        val selectedCard = options[option]
        selectedCard.setBackgroundResource(R.drawable.card_selected)
        selectedAction = selectedCard.findViewWithTag<TextView>("action_name").text.toString()
        selectedActionIndex = option
    }

    private fun selectDashboardOption() {
        if (selectedAction == "Routine") {
            openRoutines()
        } else {
            openDevice()
        }
    }

    private fun openDashboard() {
        loadScreen(R.layout.dashboard_layout)
        currentContext = "dashboard"
        navigateDashboardOption(0)
        updateDashboardLayoutData()
        //selectedActionIndex = 0
        //selectedAction = "Lights"
    }

    private fun updateDashboardLayoutData() {
        val options = listOf(
            findViewById<LinearLayout>(R.id.lights_card),
            findViewById<LinearLayout>(R.id.fan_card),
            findViewById<LinearLayout>(R.id.tv_card),
            findViewById<LinearLayout>(R.id.routine_card)
        )

        for (card in options) {
            val cardName = card.findViewWithTag<TextView>("action_name").text.toString()
            val statusText = card.findViewWithTag<TextView>("action_status")
            if (statusText != null) {
                if (appStatus[cardName] == true) {
                    statusText.setTextColor("#22C55E".toColorInt())
                    statusText.text = "ON"
                } else {
                    statusText.setTextColor("#EF4444".toColorInt())
                    statusText.text = "OFF"
                }
            }
        }

        navigateDashboardOption(selectedActionIndex)
    }

    //Device
    private fun openDevice() {
        loadScreen(R.layout.device_layout)
        currentContext = "device"
        updateDeviceLayoutData()
    }

    private fun updateDeviceLayoutData() {
        findViewById<TextView>(R.id.selected_device_title).text = selectedAction
        val statusText = findViewById<TextView>(R.id.device_status)
        if (appStatus[selectedAction] == true) {
            statusText.setTextColor("#22C55E".toColorInt())
            statusText.text = "ON"
        } else {
            statusText.setTextColor("#EF4444".toColorInt())
            statusText.text = "OFF"
        }
    }

    private fun toggleAppStatus(actionName: String, option: Boolean) {
        appStatus[actionName] = option
    }

    //Routines

    private fun openRoutines() {
        loadScreen(R.layout.routine_layout)
        currentContext = "routines"
        //selectedActionIndex = 0
        //selectedAction = "Study Mode"
        navigateRoutineOption(0)
        updateRoutinesLayoutData()
    }

    private fun navigateRoutineOption(option: Int) {
        val options = listOf(
            findViewById<LinearLayout>(R.id.study_card),
            findViewById<LinearLayout>(R.id.night_mode_card),
            findViewById<LinearLayout>(R.id.welcome_home_card)
        )

        for (card in options) {
            card.setBackgroundResource(R.drawable.card_normal)
        }

        val selectedCard = options[option]
        selectedCard.setBackgroundResource(R.drawable.card_selected)
        selectedAction = selectedCard.findViewWithTag<TextView>("action_name").text.toString()
        selectedActionIndex = option
    }

    private fun updateRoutinesLayoutData() {
        val options = listOf(
            findViewById<LinearLayout>(R.id.study_card),
            findViewById<LinearLayout>(R.id.night_mode_card),
            findViewById<LinearLayout>(R.id.welcome_home_card)
        )

        for (card in options) {
            val cardName = card.findViewWithTag<TextView>("action_name").text.toString()
            val statusText = card.findViewWithTag<TextView>("action_status")
            if (statusText != null) {
                if (appStatus[cardName] == true) {
                    statusText.setTextColor("#3B82F6".toColorInt())
                    statusText.text = "GO"
                } else {
                    statusText.setTextColor("#EF4444".toColorInt())
                    statusText.text = "STOP"
                }
            }
        }

        navigateRoutineOption(selectedActionIndex)
    }

    //Confirm
    private fun openConfirm() {
        loadScreen(R.layout.confirm_layout)
        confirmAction = selectedAction
        currentContext = "confirm"
    }


    private fun runCommand(command: String) {
        Log.i("MainApp", "Current context: $currentContext Command: $command")
        when(currentContext) {
            "dashboard" ->
                when(command) {
                    "up" -> {
                        selectedActionIndex -= 2
                        if (selectedActionIndex < 0) {
                            selectedActionIndex += 4
                        }
                        updateDashboardLayoutData()
                    }
                    "down" -> {
                        selectedActionIndex += 2
                        if (selectedActionIndex > 3) {
                            selectedActionIndex -= 4
                        }
                        updateDashboardLayoutData()
                    }
                    "left" -> {
                        selectedActionIndex -= 1
                        if (selectedActionIndex < 0) {
                            selectedActionIndex += 4
                        }
                        updateDashboardLayoutData()
                    }
                    "right" -> {
                        selectedActionIndex += 1
                        if (selectedActionIndex > 3) {
                            selectedActionIndex -= 4
                        }
                        updateDashboardLayoutData()
                    }
                    "yes" -> {
                        selectDashboardOption()
                    }
                }
            "device" ->
                when(command) {
                    "on" -> {
                        //toggleAppStatus(confirmAction,true)
                        updateDeviceLayoutData()
                        if (appStatus[selectedAction] != true) {
                            openConfirm()
                        }
                    }
                    "off" -> {
                        //toggleAppStatus(false)
                        updateDeviceLayoutData()
                        if (appStatus[selectedAction] != false) {
                            openConfirm()
                        }
                    }
                    "no" -> {
                        openDashboard()
                    }
                }
            "routines" ->
                when(command) {
                    "up" -> {
                        selectedActionIndex -= 1
                        if (selectedActionIndex < 0) {
                            selectedActionIndex += 3
                        }
                        updateRoutinesLayoutData()
                    }
                    "down" -> {
                        selectedActionIndex += 1
                        if (selectedActionIndex > 2) {
                            selectedActionIndex -= 3
                        }
                        updateRoutinesLayoutData()
                    }
                    "go" -> {
                        toggleAppStatus(selectedAction, true)
                        updateRoutinesLayoutData()
                    }
                    "stop" -> {
                        toggleAppStatus(selectedAction, false)
                        updateRoutinesLayoutData()
                    }
                    "no" -> {
                        openDashboard()
                    }
                }
            "confirm" ->
                when(command) {
                    "yes" -> {
                        toggleAppStatus(selectedAction, appStatus[selectedAction]?.not() ?: false)
                        openDevice()
                    }
                    "no" -> {
                        openDevice()
                    }
                }
        }
    }

}