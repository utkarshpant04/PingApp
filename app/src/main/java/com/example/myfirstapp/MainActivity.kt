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
    private var timeout = 1000
    private var tcpPort = 80
    private var udpPort = 5001

    // New location toggle variables and SharedPreferences
    private val PREFS_NAME = "MyPrefs"
    private val PREF_LOCATION_ENABLED = "location_enabled"
    private var isLocationEnabled = true

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
                    tvLog.append("$message\n")
                    tvLog.layout?.let { layout ->
                        val scrollAmount = layout.getLineTop(tvLog.lineCount)
                        if (scrollAmount > tvLog.height) {
                            tvLog.scrollTo(0, scrollAmount - tvLog.height)
                        }
                    }
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
            isApiServiceBound = true

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

            updateUI()
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

        // Load location setting from SharedPreferences
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isLocationEnabled = prefs.getBoolean(PREF_LOCATION_ENABLED, true)

        // Initialize location manager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Request all necessary permissions
        requestAllPermissions()

        // Bind to both services
        val pingServiceIntent = Intent(this, PingService::class.java)
        bindService(pingServiceIntent, pingServiceConnection, Context.BIND_AUTO_CREATE)

        val apiServiceIntent = Intent(this, ApiService::class.java)
        bindService(apiServiceIntent, apiServiceConnection, Context.BIND_AUTO_CREATE)

        updateUI()
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
                    tvLog.append("Initial location: ${getCurrentLocationString()}\n")
                }

            } catch (e: SecurityException) {
                Toast.makeText(this, "Location permission error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // LocationListener methods
    override fun onLocationChanged(location: Location) {
        // Only update if the new location is significantly better
        if (isBetterLocation(location, currentLocation)) {
            val previousLocation = currentLocation?.let { "%.6f,%.6f".format(it.latitude, it.longitude) } ?: "N/A"
            currentLocation = location
            val newLocationString = getCurrentLocationString()

            // Log location change
//            tvLog.append("Location updated: $newLocationString (was: $previousLocation)\n")

            // Scroll to bottom
            tvLog.layout?.let { layout ->
                val scrollAmount = layout.getLineTop(tvLog.lineCount)
                if (scrollAmount > tvLog.height) {
                    tvLog.scrollTo(0, scrollAmount - tvLog.height)
                }
            }
        }
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {
        tvLog.append("Location provider enabled: $provider\n")
    }
    override fun onProviderDisabled(provider: String) {
        tvLog.append("Location provider disabled: $provider\n")
    }

    /**
     * Determines whether one location reading is better than the current location fix
     */
    private fun isBetterLocation(location: Location, currentBestLocation: Location?): Boolean {
        if (currentBestLocation == null) {
            // A new location is always better than no location
            return true
        }

        // Check whether the new location fix is newer or older
        val timeDelta = location.time - currentBestLocation.time
        val isSignificantlyNewer = timeDelta > 2 * 60 * 1000 // 2 minutes
        val isSignificantlyOlder = timeDelta < -2 * 60 * 1000
        val isNewer = timeDelta > 0

        // If it's been more than two minutes since the current location, use the new location
        // because the user has likely moved
        if (isSignificantlyNewer) {
            return true
            // If the new location is more than two minutes older, it must be worse
        } else if (isSignificantlyOlder) {
            return false
        }

        // Check whether the new location fix is more or less accurate
        val accuracyDelta = (location.accuracy - currentBestLocation.accuracy).toInt()
        val isLessAccurate = accuracyDelta > 0
        val isMoreAccurate = accuracyDelta < 0
        val isSignificantlyLessAccurate = accuracyDelta > 200

        // Check if the old and new location are from the same provider
        val isFromSameProvider = location.provider == currentBestLocation.provider

        // Determine location quality using a combination of timeliness and accuracy
        if (isMoreAccurate) {
            return true
        } else if (isNewer && !isLessAccurate) {
            return true
        } else if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) {
            return true
        }
        return false
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
                // First test server connectivity
//                val pingResult = apiService?.pingServer()
//
//                when (pingResult) {
//                    is ApiResponse.Success -> {
                // Server is reachable, now connect with device info
                val location = getCurrentLocationString()
                val connectResult = apiService?.connectToServer(location)

                when (connectResult) {
                    is ApiResponse.Success -> {
                        Toast.makeText(this@MainActivity, "Connected to server successfully", Toast.LENGTH_SHORT).show()

                        // Clear log and show connection info
                        tvLog.text = "Connected to server successfully\nLocation: $location\nHeartbeat: Every 5 minutes\nWaiting for server instructions...\n"

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
//                    }
//                    is ApiResponse.Error -> {
//                        Toast.makeText(this@MainActivity, "Server unreachable: ${pingResult.message}", Toast.LENGTH_LONG).show()
//                    }
//                    null -> {
//                        Toast.makeText(this@MainActivity, "API service not available", Toast.LENGTH_LONG).show()
//                    }
//                }
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

            tvLog.append("Disconnected from server - heartbeat stopped\n")
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
                tvLog.append("⚠️ $message\n")
            }
        }

        // Log the message if it's informative
        if (message.isNotEmpty() && status != "heartbeat_error") {
            tvLog.append("ℹ️ $message\n")
        }
    }

    private fun handleServerInstruction(instruction: ServerInstruction) {
        if (instruction.sendPing) {
            tvServerInstructions.text = "Server instruction: Ping ${instruction.host} (${instruction.protocol}) for ${instruction.durationSeconds}s"
            tvLog.append("📋 Heartbeat received server instruction: Ping ${instruction.host} (${instruction.protocol}) for ${instruction.durationSeconds}s\n")

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
            tvServerInstructions.text = "Server instruction: Wait for further instructions"
            tvLog.append("💡 Heartbeat sent - waiting for instructions\n")
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
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val etPacketSize = dialogView.findViewById<EditText>(R.id.etPacketSize)
        val etTimeout = dialogView.findViewById<EditText>(R.id.etTimeout)
        val etTcpPort = dialogView.findViewById<EditText>(R.id.etTcpPort)
        val etUdpPort = dialogView.findViewById<EditText>(R.id.etUdpPort)
        val swLocationEnabled = dialogView.findViewById<SwitchCompat>(R.id.swLocationEnabled)

        etPacketSize.setText(packetSize.toString())
        etTimeout.setText(timeout.toString())
        etTcpPort.setText(tcpPort.toString())
        etUdpPort.setText(udpPort.toString())
        swLocationEnabled.isChecked = isLocationEnabled

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_title))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                packetSize = etPacketSize.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: packetSize
                timeout = etTimeout.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: timeout
                tcpPort = etTcpPort.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: tcpPort
                udpPort = etUdpPort.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: udpPort
                isLocationEnabled = swLocationEnabled.isChecked

                // Save to SharedPreferences using the KTX extension
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putBoolean(PREF_LOCATION_ENABLED, isLocationEnabled)
                }

                // Update service settings
                pingService?.updateSettings(packetSize, timeout, tcpPort, udpPort)
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
}