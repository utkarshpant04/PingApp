package com.example.myfirstapp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.edit
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvServerInstructions: TextView

    // Location components
    private lateinit var locationManager: LocationManager
    private var currentLocation: Location? = null
    private val LOCATION_PERMISSION_REQUEST_CODE = 1001
    private val NOTIFICATION_PERMISSION_REQUEST_CODE = 1002

    // Background location permission for API 29+
    private val BACKGROUND_LOCATION_REQUEST_CODE = 1003

    // Configurable defaults for ping settings
    private var packetSize = 32
    private var timeout = Constants.DEFAULT_TIMEOUT_MS
    private var tcpPort = 80
    private var udpPort = Constants.PING_LISTENER_PORT

    // New location toggle variables and SharedPreferences
    private val PREFS_NAME = "MyPrefs"
    private val PREF_LOCATION_ENABLED = "location_enabled"
    private var isLocationEnabled = true

    // Date formatter for timestamps
    private val dateFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Services
    private var pingService: PingService? = null
    private var apiService: ApiService? = null
    private var isPingServiceBound = false
    private var isApiServiceBound = false

    private val pingServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PingService.PingBinder
            pingService = binder.getService()
            isPingServiceBound = true

            // Set up log observer
            pingService?.setLogCallback { message ->
                runOnUiThread {
                    appendLog(message)
                }
            }

            // Provide location callback to ping service
            pingService?.setLocationCallback { getCurrentLocationString() }

            updateUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            pingService = null
            isPingServiceBound = false
        }
    }

    private val apiServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ApiService.ApiBinder
            apiService = binder.getService()

            // Add this block
            apiService?.setLogCallback { message ->
                runOnUiThread {
                    appendLog(message)
                }
            }

            // Set up status callback
            apiService?.setStatusCallback { status, message ->
                runOnUiThread {
                    updateStatusDisplay(status, message)
                }
            }

            // Set up instruction callback
            apiService?.setInstructionCallback { instruction ->
                runOnUiThread {
                    handleServerInstruction(instruction)
                }
            }

            // Provide location callback to api service
            apiService?.setLocationCallback { getCurrentLocationString() }

            isApiServiceBound = true
            updateUI()

            // Auto-connect to server once the API service is ready
            connectToServerAndStartHeartbeat()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            apiService = null
            isApiServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: androidx.appcompat.widget.Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        tvServerInstructions = findViewById(R.id.tvServerInstructions)

        btnConnect.setOnClickListener { connectToServerAndStartHeartbeat() }
        btnDisconnect.setOnClickListener { disconnectFromServer() }

        // Initialize FileLogger for persistent logging
        FileLogger.initialize(this)

        // Load previous logs from file
        loadPersistentLogs()

        // Load location setting from SharedPreferences
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isLocationEnabled = prefs.getBoolean(PREF_LOCATION_ENABLED, true)

        // Initialize location manager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Request all necessary permissions
        requestAllPermissions()

        // Bind to both services
        // Auto-connect will be triggered from apiServiceConnection.onServiceConnected()
        val pingServiceIntent = Intent(this, PingService::class.java)
        bindService(pingServiceIntent, pingServiceConnection, Context.BIND_AUTO_CREATE)

        val apiServiceIntent = Intent(this, ApiService::class.java)
        bindService(apiServiceIntent, apiServiceConnection, Context.BIND_AUTO_CREATE)

        updateUI()
    }

    // Helper function to append log with auto-scroll
    // Note: Timestamp is already added by the service's log() method for service logs
    // For MainActivity's own logs, we add timestamp here
    private fun appendLog(message: String, addTimestamp: Boolean = false) {
        val logMessage = if (addTimestamp) {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            "[$timestamp] $message"
        } else {
            message // Message already has timestamp from service
        }

        // Write to file
        FileLogger.appendLog(logMessage)

        // Update UI
        tvLog.text = "$logMessage\n${tvLog.text}"

    }

    // Load persistent logs from file on app start
    private fun loadPersistentLogs() {
        val savedLogs = FileLogger.readAllLogs()
        if (savedLogs.isNotEmpty()) {
            tvLog.text = savedLogs
            // Scroll to bottom
            tvLog.layout?.let { layout ->
                val scrollAmount = layout.getLineTop(tvLog.lineCount)
                if (scrollAmount > tvLog.height) {
                    tvLog.scrollTo(0, scrollAmount - tvLog.height)
                }
            }
        }
    }

    private fun requestAllPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // Location permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        // Background location permission (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // Only request background location if we have foreground location permission
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            }
        }

        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        } else {
            startLocationUpdates()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                var locationPermissionGranted = false
                var backgroundLocationPermissionGranted = false
                var notificationPermissionGranted = true // Default true for older Android versions

                for (i in permissions.indices) {
                    when (permissions[i]) {
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION -> {
                            if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                                locationPermissionGranted = true
                            }
                        }
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION -> {
                            backgroundLocationPermissionGranted = grantResults[i] == PackageManager.PERMISSION_GRANTED
                        }
                        Manifest.permission.POST_NOTIFICATIONS -> {
                            notificationPermissionGranted = grantResults[i] == PackageManager.PERMISSION_GRANTED
                        }
                    }
                }

                if (locationPermissionGranted) {
                    startLocationUpdates()

                    // If foreground location was granted but background wasn't, ask for background
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !backgroundLocationPermissionGranted) {
                        // Request background location separately with explanation
                        AlertDialog.Builder(this)
                            .setTitle("Background Location Access")
                            .setMessage("To track location during ping operations when the app is in background, please grant 'Allow all the time' location permission in the next dialog.")
                            .setPositiveButton("OK") { _, _ ->
                                ActivityCompat.requestPermissions(
                                    this,
                                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                                    BACKGROUND_LOCATION_REQUEST_CODE
                                )
                            }
                            .setNegativeButton("Skip") { _, _ ->
                                Toast.makeText(this, "Background location tracking will be limited", Toast.LENGTH_LONG).show()
                            }
                            .show()
                    }
                } else {
                    Toast.makeText(this, "Location permission denied. Location will show as N/A", Toast.LENGTH_LONG).show()
                }

                if (!notificationPermissionGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(this, "Notification permission denied. You won't receive background notifications.", Toast.LENGTH_LONG).show()
                }
            }
            BACKGROUND_LOCATION_REQUEST_CODE -> {
                val backgroundGranted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
                if (backgroundGranted) {
                    Toast.makeText(this, "Background location access granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Background location access denied. Location tracking will be limited when app is backgrounded.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

            try {
                // Request location updates from both GPS and Network providers with more frequent updates
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        5000L, // 5 seconds for more frequent updates
                        5f,    // 5 meters
                        this
                    )
                }

                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        5000L, // 5 seconds
                        5f,    // 5 meters
                        this
                    )
                }

                // Also request passive location updates to catch location changes from other apps
                if (locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.PASSIVE_PROVIDER,
                        1000L, // 1 second
                        0f,    // Any movement
                        this
                    )
                }

                // Get last known location as initial value
                val lastKnownGPS = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                val lastKnownNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

                currentLocation = when {
                    lastKnownGPS != null && (System.currentTimeMillis() - lastKnownGPS.time) < 5 * 60 * 1000 -> lastKnownGPS // GPS location less than 5 minutes old
                    lastKnownNetwork != null && (System.currentTimeMillis() - lastKnownNetwork.time) < 10 * 60 * 1000 -> lastKnownNetwork // Network location less than 10 minutes old
                    lastKnownGPS != null -> lastKnownGPS
                    lastKnownNetwork != null -> lastKnownNetwork
                    else -> null
                }

                if (currentLocation != null) {
                    appendLog("Initial location: ${getCurrentLocationString()}", addTimestamp = true)
                }

            } catch (e: SecurityException) {
                Toast.makeText(this, "Location permission error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // LocationListener methods
    override fun onLocationChanged(location: Location) {
        currentLocation = location
    }

    override fun onProviderEnabled(provider: String) {
        appendLog("Location provider enabled: $provider", addTimestamp = true)
    }
    override fun onProviderDisabled(provider: String) {
        appendLog("Location provider disabled: $provider", addTimestamp = true)
    }
    // Updated getCurrentLocationString to check the location toggle
    fun getCurrentLocationString(): String {
        return if (isLocationEnabled) {
            currentLocation?.let {
                "%.6f,%.6f".format(it.latitude, it.longitude)
            } ?: "N/A"
        } else {
            "N/A"
        }
    }

    private fun connectToServerAndStartHeartbeat() {
        if (apiService?.isConnected() == true) return

        lifecycleScope.launch {
            try {
                // Server is reachable, now connect with device info
                val location = getCurrentLocationString()
                val connectResult = apiService?.connectToServer(location)

                when (connectResult) {
                    is ApiResponse.Success -> {
                        Toast.makeText(this@MainActivity, "Connected to server successfully", Toast.LENGTH_SHORT).show()

                        appendLog("=======================================\nConnected to server successfully\nLocation: $location\n=======================================", addTimestamp = false)
                        // Start service for server-controlled operations
                        pingService?.startServerControlledMode()

                        // Start 5-minute heartbeat
                        apiService?.startHeartbeat(location)
                        updateUI()
                    }
                    is ApiResponse.Error -> {
                        Toast.makeText(this@MainActivity, "Server connection failed: ${connectResult.message}", Toast.LENGTH_LONG).show()
                    }
                    null -> {
                        Toast.makeText(this@MainActivity, "API service not available", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Connection error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun disconnectFromServer() {
        lifecycleScope.launch {
            val location = getCurrentLocationString()
            apiService?.disconnectFromServer(location)

            // Stop service operations
            pingService?.stopServerControlledMode()
            appendLog("=======================================\nDisconnected from server\n=======================================", addTimestamp = false)
            Toast.makeText(this@MainActivity, "Disconnected from server", Toast.LENGTH_SHORT).show()
            updateUI()
        }
    }

    private fun updateStatusDisplay(status: String, message: String) {
        when (status) {
            "connecting" -> {
                tvStatus.text = "Connecting to server..."
            }
            "server_reachable" -> {
                tvStatus.text = "Server is reachable"
            }
            "connected" -> {
                tvStatus.text = "Connected to server"
            }
            "connection_failed" -> {
                tvStatus.text = "Connection failed"
            }
            "connection_error" -> {
                tvStatus.text = "Connection error"
            }
            "server_unreachable" -> {
                tvStatus.text = "Server unreachable"
            }
            "server_error" -> {
                tvStatus.text = "Server error"
            }
            "disconnecting" -> {
                tvStatus.text = "Disconnecting..."
            }
            "disconnected" -> {
                tvStatus.text = "Disconnected"
                tvServerInstructions.text = "Not connected to server"
            }
            "disconnect_error" -> {
                tvStatus.text = "Disconnect error"
            }
            "heartbeat_started" -> {
                // Update status will be handled in updateUI()
            }
            "heartbeat_stopped" -> {
                // Update status will be handled in updateUI()
            }
            "heartbeat_error" -> {
                appendLog("WARNING: $message", addTimestamp = true)
            }
        }

        // Log the message if it's informative
        if (message.isNotEmpty() && status != "heartbeat_error") {
            appendLog("INFO: $message", addTimestamp = true)
        }
    }

    private fun handleServerInstruction(instruction: ServerInstruction) {
        if (instruction.sendPing) {
            tvServerInstructions.text = "Server instruction: Ping ${instruction.host} (${instruction.protocol}) for ${instruction.durationSeconds}s"
//            appendLog("Server instruction: Ping ${instruction.host} (${instruction.protocol}) for ${instruction.durationSeconds}s", addTimestamp = true)
            appendLog("Server instruction Received", addTimestamp = true)

            // Execute ping as instructed by server
            pingService?.executePingInstruction(
                instruction.host,
                instruction.protocol,
                instruction.intervalMs,
                instruction.durationSeconds,
                packetSize,
                timeout,
                tcpPort,
                udpPort,
                getCurrentLocationString()
            )
        } else {
            tvServerInstructions.text = "Heartbeat Sent: Waiting for further instructions"
            appendLog("Heartbeat sent", addTimestamp = true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Disconnect from server if connected
        if (apiService?.isConnected() == true) {
            lifecycleScope.launch {
                apiService?.disconnectFromServer(getCurrentLocationString())
            }
        }

        // Unbind services
        if (isPingServiceBound) {
            unbindService(pingServiceConnection)
            isPingServiceBound = false
        }

        if (isApiServiceBound) {
            unbindService(apiServiceConnection)
            isApiServiceBound = false
        }

        // Stop location updates - check permissions first
        val hasFineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (hasFineLocation || hasCoarseLocation) {
            try {
                locationManager.removeUpdates(this)
            } catch (e: SecurityException) {
                // Permission was revoked during runtime
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()

        // Auto-reconnect if service is available but not connected
        if (isApiServiceBound && apiService?.isConnected() == false) {
            connectToServerAndStartHeartbeat()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                showSettingsDialog()
                true
            }
            R.id.action_clear_logs -> {
                clearLogs()
                true
            }
            R.id.action_export_logs -> {
                exportLogs()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val etPacketSize = dialogView.findViewById<EditText>(R.id.etPacketSize)
        val etTimeout = dialogView.findViewById<EditText>(R.id.etTimeout)
        val swLocationEnabled = dialogView.findViewById<SwitchCompat>(R.id.swLocationEnabled)

        etPacketSize.setText(packetSize.toString())
        etTimeout.setText(timeout.toString())
        swLocationEnabled.isChecked = isLocationEnabled

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_title))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                packetSize = etPacketSize.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: packetSize
                timeout = etTimeout.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: timeout
                isLocationEnabled = swLocationEnabled.isChecked

                // Save to SharedPreferences using the KTX extension
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putBoolean(PREF_LOCATION_ENABLED, isLocationEnabled)
                }

                // Update service settings
                pingService?.updateSettings(packetSize, timeout)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun updateUI() {
        lifecycleScope.launch {
            // Small delay to ensure service state is updated
            delay(50)

            val isConnected = apiService?.isConnected() ?: false
            val isPingingActive = pingService?.isExecutingServerInstruction() ?: false
            val isHeartbeatRunning = apiService?.isHeartbeatRunning() ?: false

            runOnUiThread {
                btnConnect.isEnabled = !isConnected && !isPingingActive
                btnDisconnect.isEnabled = isConnected

                when {
                    isConnected && isPingingActive -> {
                        tvStatus.text = "Connected - Executing ping instruction"
                    }
                    isConnected && isHeartbeatRunning -> {
                        tvStatus.text = "Connected - Heartbeat active - Awaiting server instructions"
                    }
                    isConnected -> {
                        tvStatus.text = "Connected - Heartbeat stopped"
                    }
                    else -> {
                        tvStatus.text = "Disconnected"
                        tvServerInstructions.text = "Not connected to server"
                    }
                }
            }
        }
    }

    private fun clearLogs() {
        // Clear the file storage
        FileLogger.clearLogs()

        // Clear the display
        tvLog.text = ""

        // Add a log entry indicating logs were cleared
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val clearMessage = "[$timestamp] Logs cleared"
        FileLogger.appendLog(clearMessage)
        tvLog.text = clearMessage


    }

    private fun exportLogs() {
        try {
            val logContent = FileLogger.readAllLogs()

            if (logContent.isEmpty()) {
                Toast.makeText(this, "No logs to export", Toast.LENGTH_SHORT).show()
                return
            }

            // Method 1: Share as plain text (no FileProvider needed)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "App Logs - ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                putExtra(Intent.EXTRA_TEXT, logContent)
            }

            startActivity(Intent.createChooser(shareIntent, "Export Logs"))

            Toast.makeText(this, "Logs ready to export", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("MainActivity", "Failed to export logs", e)
        }
    }
}