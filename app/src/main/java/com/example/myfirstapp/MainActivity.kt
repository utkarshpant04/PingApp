package com.example.myfirstapp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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
    private val BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE = 1002
    private var locationPermissionGranted = false
    private var isLocationUpdatesActive = false

    // Configurable defaults for ping settings
    private var packetSize = 32
    private var timeout = 1000
    private var tcpPort = 80
    private var udpPort = 5001

    // Location sharing setting
    private var enableLocationSharing = false
    private lateinit var sharedPreferences: SharedPreferences
    private val PREF_LOCATION_SHARING = "location_sharing_enabled"

    private var pingService: PingService? = null
    private var isServiceBound = false
    private var isConnectedToServer = false

    // Permission monitoring job to avoid conflicts
    private var permissionMonitoringJob: Job? = null

    // REST API client for server communication
    private lateinit var apiClient: RestApiClient

    // Key for saving instance state
    private val KEY_IS_CONNECTED = "is_connected_to_server"

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PingService.PingBinder
            pingService = binder.getService()
            isServiceBound = true

            // Set up log observer
            pingService?.setLogCallback { message ->
                runOnUiThread {
                    safeAppendToLog(message)
                }
            }

            // Update service with current location sharing setting
            pingService?.updateLocationSharingSetting(enableLocationSharing)

            // Update UI based on service state
            updateUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            pingService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: androidx.appcompat.widget.Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        // Initialize SharedPreferences for settings
        sharedPreferences = getSharedPreferences("PingAppSettings", Context.MODE_PRIVATE)
        enableLocationSharing = sharedPreferences.getBoolean(PREF_LOCATION_SHARING, false)

        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        tvServerInstructions = findViewById(R.id.tvServerInstructions)

        btnConnect.setOnClickListener { connectToServerAndStartHeartbeat() }
        btnDisconnect.setOnClickListener { disconnectFromServer() }

        // Restore the state after initializing views
        if (savedInstanceState != null) {
            isConnectedToServer = savedInstanceState.getBoolean(KEY_IS_CONNECTED, false)
        }

        // Initialize location manager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Initialize REST API client
        apiClient = RestApiClient(this)

        // Only request location permissions if location sharing is enabled
        if (enableLocationSharing) {
            requestLocationPermissions()
        }

        // Bind to the service
        val serviceIntent = Intent(this, PingService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        updateUI()

        // Log current location sharing setting
        safeAppendToLog("Location sharing: ${if (enableLocationSharing) "Enabled" else "Disabled"}")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Save the connection status to handle process termination on permission revocation
        outState.putBoolean(KEY_IS_CONNECTED, isConnectedToServer)
    }

    override fun onResume() {
        super.onResume()
        // Check for permission changes when returning to the app
        if (enableLocationSharing) {
            checkLocationPermissionStatus()
        }
        updateUI()
    }

    override fun onStart() {
        super.onStart()
        // Also check when app becomes visible
        if (enableLocationSharing) {
            checkLocationPermissionStatus()
        }
    }

    /**
     * Safe method to append text to log without causing UI exceptions
     */
    private fun safeAppendToLog(message: String) {
        try {
            if (!isFinishing && !isDestroyed) {
                tvLog.append("$message\n")
                tvLog.layout?.let { layout ->
                    val scrollAmount = layout.getLineTop(tvLog.lineCount)
                    if (scrollAmount > tvLog.height) {
                        tvLog.scrollTo(0, scrollAmount - tvLog.height)
                    }
                }
            }
        } catch (e: Exception) {
            // Silently handle any UI exceptions to prevent crashes
        }
    }

    /**
     * Check current location permission status and restart location updates if needed
     */
    private fun checkLocationPermissionStatus() {
        if (!enableLocationSharing) return

        try {
            val hasLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

            if (hasLocationPermission && !locationPermissionGranted) {
                // Permission was granted since last check
                locationPermissionGranted = true
                startLocationUpdates()
                safeAppendToLog("Location permission granted - location updates enabled")

                // Update service with new permission status
                pingService?.updateLocationPermissionStatus(true)

                Toast.makeText(this, "Location permission granted - tracking enabled", Toast.LENGTH_SHORT).show()

            } else if (!hasLocationPermission && locationPermissionGranted) {
                // Permission was revoked since last check
                handlePermissionRevocation()
            }
        } catch (e: Exception) {
            // Handle any exceptions during permission checking
            safeAppendToLog("Error checking location permissions: ${e.message}")
        }
    }

    /**
     * Safely handle permission revocation without causing crashes
     * Only update service once to avoid redundant calls
     */
    private fun handlePermissionRevocation() {
        try {
            // Only proceed if permission was actually granted before
            if (!locationPermissionGranted) return

            locationPermissionGranted = false
            currentLocation = null
            isLocationUpdatesActive = false

            // Stop location updates safely
            stopLocationUpdates()

            safeAppendToLog("Location permission revoked - location will show as N/A")

            // Update service with new permission status - ONLY ONCE
            pingService?.updateLocationPermissionStatus(false)

            // Show toast safely
            if (!isFinishing && !isDestroyed) {
                Toast.makeText(this, "Location permission revoked - location unavailable", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            // Handle any exceptions during permission revocation
            safeAppendToLog("Error handling permission revocation: ${e.message}")
        }
    }

    /**
     * Periodically check permission status during active operations
     * Minimize service calls and avoid redundant operations
     */
    private fun startPermissionMonitoring() {
        // Cancel existing monitoring job to avoid conflicts
        permissionMonitoringJob?.cancel()

        if (isConnectedToServer && enableLocationSharing) {
            permissionMonitoringJob = lifecycleScope.launch {
                try {
                    while (isConnectedToServer && isActive) {
                        delay(30000) // Check every 30 seconds during active connection
                        if (isActive && locationPermissionGranted) { // Only check if we think we still have permission
                            val hasLocationPermission = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

                            // Only handle revocation, don't duplicate other checks
                            if (!hasLocationPermission) {
                                handlePermissionRevocation()
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    // Expected when job is cancelled
                } catch (e: Exception) {
                    safeAppendToLog("Error in permission monitoring: ${e.message}")
                }
            }
        }
    }

    private fun requestLocationPermissions() {
        if (!enableLocationSharing) {
            safeAppendToLog("Location sharing disabled - skipping permission request")
            return
        }

        val hasFineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasBackgroundLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

        locationPermissionGranted = hasFineLocation || hasCoarseLocation

        when {
            !locationPermissionGranted -> {
                // First request foreground location permissions
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    LOCATION_PERMISSION_REQUEST_CODE
                )
            }
            locationPermissionGranted && !hasBackgroundLocation && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q -> {
                // If foreground permissions granted but not background, explain and request background
                showBackgroundLocationExplanation()
            }
            else -> {
                // All permissions granted
                startLocationUpdates()
                safeAppendToLog("All location permissions granted - background location available")
            }
        }
    }

    private fun showBackgroundLocationExplanation() {
        AlertDialog.Builder(this)
            .setTitle("Background Location Permission")
            .setMessage("To accurately track location during ping operations when the app is in the background, we need background location permission. This ensures precise location data is included with ping results even when you're not actively using the app.")
            .setPositiveButton("Grant Permission") { _, _ ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                        BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE
                    )
                }
            }
            .setNegativeButton("Skip") { _, _ ->
                Toast.makeText(this, "Background location will be limited. Location accuracy may be reduced when app is backgrounded.", Toast.LENGTH_LONG).show()
                startLocationUpdates()
            }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                try {
                    val permissionGranted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    locationPermissionGranted = permissionGranted

                    if (permissionGranted && enableLocationSharing) {
                        startLocationUpdates()
                        safeAppendToLog("Foreground location permission granted - location tracking enabled")
                        pingService?.updateLocationPermissionStatus(true)

                        // Check if we should request background location
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            val hasBackgroundLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
                            if (!hasBackgroundLocation) {
                                showBackgroundLocationExplanation()
                            }
                        }
                    } else {
                        Toast.makeText(this, "Location permission denied. Location will show as N/A", Toast.LENGTH_LONG).show()
                        safeAppendToLog("Foreground location permission denied - location will show as N/A")
                        pingService?.updateLocationPermissionStatus(false)
                    }
                } catch (e: Exception) {
                    safeAppendToLog("Error processing foreground location permission result: ${e.message}")
                }
            }
            BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE -> {
                try {
                    val backgroundPermissionGranted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED

                    if (backgroundPermissionGranted) {
                        Toast.makeText(this, "Background location permission granted - full location tracking enabled", Toast.LENGTH_SHORT).show()
                        safeAppendToLog("Background location permission granted - app can track location when backgrounded")
                    } else {
                        Toast.makeText(this, "Background location denied - location tracking will be limited when app is backgrounded", Toast.LENGTH_LONG).show()
                        safeAppendToLog("Background location permission denied - location accuracy may be reduced in background")
                    }

                    // Start location updates regardless - we at least have foreground permission
                    startLocationUpdates()
                } catch (e: Exception) {
                    safeAppendToLog("Error processing background location permission result: ${e.message}")
                }
            }
        }
    }

    private fun startLocationUpdates() {
        if (!enableLocationSharing || !locationPermissionGranted || isLocationUpdatesActive) return

        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

                // Stop any existing location updates first
                stopLocationUpdates()

                // Request location updates from both GPS and Network providers
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        10000L, // 10 seconds
                        10f,    // 10 meters
                        this
                    )
                }

                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        10000L,
                        10f,
                        this
                    )
                }

                isLocationUpdatesActive = true

                // Get last known location as initial value
                val lastKnownGPS = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                val lastKnownNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

                currentLocation = when {
                    lastKnownGPS != null -> lastKnownGPS
                    lastKnownNetwork != null -> lastKnownNetwork
                    else -> null
                }

                safeAppendToLog("Location updates started - current location: ${getCurrentLocationString()}")

            }
        } catch (e: SecurityException) {
            // This catches the case where permission was revoked between the check and the call
            safeAppendToLog("Location permission was revoked during startup - stopping location tracking")
            if (!isFinishing && !isDestroyed) {
                Toast.makeText(this, "Location permission was revoked", Toast.LENGTH_SHORT).show()
            }
            handlePermissionRevocation()
        } catch (e: Exception) {
            safeAppendToLog("Error starting location updates: ${e.message}")
        }
    }

    private fun stopLocationUpdates() {
        try {
            if (isLocationUpdatesActive) {
                locationManager.removeUpdates(this)
                isLocationUpdatesActive = false
            }
        } catch (e: SecurityException) {
            // Permission was already revoked - this is expected
        } catch (e: Exception) {
            // Handle any other exceptions
            safeAppendToLog("Error stopping location updates: ${e.message}")
        }
    }

    // LocationListener methods
    override fun onLocationChanged(location: Location) {
        try {
            // If location sharing is disabled, ignore location updates
            if (!enableLocationSharing) return

            // If permission was already detected as revoked, ignore this callback
            if (!locationPermissionGranted) return

            // Double-check permissions before processing location update
            val hasLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

            if (hasLocationPermission) {
                currentLocation = location

                // Notify service of location change - ONLY when permission is valid
                pingService?.updateCurrentLocation(getCurrentLocationString())

                // Log location update (only if we're actively monitoring)
                if (isConnectedToServer) {
                    safeAppendToLog("Location updated: ${getCurrentLocationString()}")
                }
            } else {
                // Permission was revoked - handle gracefully, but ONLY ONCE
                safeAppendToLog("Location permission detected as revoked during location update")
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this, "Location permission revoked", Toast.LENGTH_SHORT).show()
                }
                handlePermissionRevocation()
            }
        } catch (e: Exception) {
            safeAppendToLog("Error processing location change: ${e.message}")
        }
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onProviderEnabled(provider: String) {
        try {
            if (enableLocationSharing) {
                safeAppendToLog("Location provider enabled: $provider")
            }
        } catch (e: Exception) {
            // Handle silently
        }
    }

    override fun onProviderDisabled(provider: String) {
        try {
            if (enableLocationSharing) {
                safeAppendToLog("Location provider disabled: $provider")
            }
        } catch (e: Exception) {
            // Handle silently
        }
    }

    /**
     * Synchronize location with service for background accuracy
     */
    private fun syncLocationWithService() {
        lifecycleScope.launch {
            try {
                // Get location from service (which handles background updates)
                val serviceLocation = pingService?.getCurrentLocation() ?: "N/A"

                // Update UI with service location if it's different from our local location
                if (serviceLocation != getCurrentLocationString()) {
                    safeAppendToLog("Using service location: $serviceLocation (app may be backgrounded)")
                    updateUI()
                }
            } catch (e: Exception) {
                safeAppendToLog("Error syncing location with service: ${e.message}")
            }
        }
    }

    fun getCurrentLocationString(): String {
        try {
            // If location sharing is disabled, always return N/A
            if (!enableLocationSharing) {
                return "N/A"
            }

            // Always check permissions before returning location
            val hasLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

            return if (hasLocationPermission && locationPermissionGranted) {
                // If we're connected to server, prefer service location (handles background better)
                if (isConnectedToServer && pingService != null) {
                    val serviceLocation = pingService?.getCurrentLocation() ?: "N/A"
                    if (serviceLocation != "N/A") {
                        return serviceLocation
                    }
                }

                // Fallback to local location if service doesn't have location
                currentLocation?.let {
                    "%.6f,%.6f".format(it.latitude, it.longitude)
                } ?: "N/A"
            } else {
                // Handle permission revocation detection
                if (locationPermissionGranted && !hasLocationPermission) {
                    lifecycleScope.launch {
                        try {
                            safeAppendToLog("Location permission detected as revoked when accessing location")
                            if (!isFinishing && !isDestroyed) {
                                Toast.makeText(this@MainActivity, "Location permission was revoked", Toast.LENGTH_SHORT).show()
                            }
                            handlePermissionRevocation()
                        } catch (e: Exception) {
                            // Handle silently to prevent crashes
                        }
                    }
                }
                "N/A"
            }
        } catch (e: Exception) {
            safeAppendToLog("Error getting location string: ${e.message}")
            return "N/A"
        }
    }

    private fun connectToServerAndStartHeartbeat() {
        if (isConnectedToServer) return

        lifecycleScope.launch {
            try {
                tvStatus.text = "Connecting to server..."
                val location = getCurrentLocationString()
                val connectResult = apiClient.connectToServer(location)

                when (connectResult) {
                    is ApiResponse.Success -> {
                        isConnectedToServer = true
                        tvStatus.text = "Connected to server"
                        Toast.makeText(this@MainActivity, "Connected to server successfully", Toast.LENGTH_SHORT).show()

                        // Clear log and show connection info
                        tvLog.text = "Connected to server successfully\nLocation: $location\nLocation sharing: ${if (enableLocationSharing) "Enabled" else "Disabled"}\nHeartbeat: Every 5 minutes\nWaiting for server instructions...\n"

                        // Start service for server-controlled operations
                        pingService?.startServerControlledMode()

                        // Start 5-minute heartbeat with server instruction checking
                        startProperHeartbeat()

                        // Start periodic permission monitoring during active connection
                        startPermissionMonitoring()

                        // Sync with service location for background accuracy
                        syncLocationWithService()

                        updateUI()
                    }
                    is ApiResponse.Error -> {
                        tvStatus.text = "Connection failed"
                        Toast.makeText(this@MainActivity, "Server connection failed: ${connectResult.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                tvStatus.text = "Connection error"
                Toast.makeText(this@MainActivity, "Connection error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Start proper 5-minute heartbeat using RestApiClient's built-in system
     * Location is dynamically retrieved on each heartbeat
     */
    private fun startProperHeartbeat() {
        // Use the RestApiClient's proper 5-minute heartbeat system with dynamic location
        apiClient.startHeartbeat(
            scope = lifecycleScope,
            locationProvider = { getCurrentLocationString() } // Dynamic location provider
        ) { instruction ->
            // Handle server instructions received via heartbeat
            runOnUiThread {
                try {
                    if (instruction.sendPing) {
                        tvServerInstructions.text = "Server instruction: Ping ${instruction.host} (${instruction.protocol}) for ${instruction.durationSeconds}s"
                        safeAppendToLog("Heartbeat received server instruction: Ping ${instruction.host} (${instruction.protocol}) for ${instruction.durationSeconds}s")

                        // Execute ping as instructed by server with current location
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
                        safeAppendToLog("Heartbeat sent - waiting for instructions")
                    }
                } catch (e: Exception) {
                    safeAppendToLog("Error handling server instruction: ${e.message}")
                }
            }
        }
    }

    private fun disconnectFromServer() {
        lifecycleScope.launch {
            try {
                isConnectedToServer = false
                tvStatus.text = "Disconnected"
                tvServerInstructions.text = "Not connected to server"

                // Stop permission monitoring
                permissionMonitoringJob?.cancel()

                // Stop the proper 5-minute heartbeat
                apiClient.stopHeartbeat()

                // Stop service operations
                pingService?.stopServerControlledMode()

                // Send final heartbeat to notify server of disconnection
                try {
                    val location = getCurrentLocationString()
                    apiClient.sendHeartbeatAndCheckInstructions("disconnected", location)
                    safeAppendToLog("Sent disconnection notification to server")
                } catch (e: Exception) {
                    safeAppendToLog("Failed to notify server of disconnection: ${e.message}")
                }

                safeAppendToLog("Disconnected from server - heartbeat stopped")
                Toast.makeText(this@MainActivity, "Disconnected from server", Toast.LENGTH_SHORT).show()
                updateUI()
            } catch (e: Exception) {
                safeAppendToLog("Error during disconnection: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            // Cancel permission monitoring
            permissionMonitoringJob?.cancel()

            // Disconnect from server and stop heartbeat
            if (isConnectedToServer) {
                isConnectedToServer = false
                apiClient.stopHeartbeat()
            }

            if (isServiceBound) {
                unbindService(serviceConnection)
                isServiceBound = false
            }

            // Stop location updates
            stopLocationUpdates()
        } catch (e: Exception) {
            // Handle any cleanup exceptions silently
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
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val etPacketSize = dialogView.findViewById<EditText>(R.id.etPacketSize)
        val etTimeout = dialogView.findViewById<EditText>(R.id.etTimeout)
        val etTcpPort = dialogView.findViewById<EditText>(R.id.etTcpPort)
        val etUdpPort = dialogView.findViewById<EditText>(R.id.etUdpPort)
        val switchLocationSharing = dialogView.findViewById<Switch>(R.id.switchLocationSharing)

        etPacketSize.setText(packetSize.toString())
        etTimeout.setText(timeout.toString())
        etTcpPort.setText(tcpPort.toString())
        etUdpPort.setText(udpPort.toString())
        switchLocationSharing.isChecked = enableLocationSharing

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_title))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                packetSize = etPacketSize.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: packetSize
                timeout = etTimeout.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: timeout
                tcpPort = etTcpPort.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: tcpPort
                udpPort = etUdpPort.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: udpPort

                val newLocationSharingSetting = switchLocationSharing.isChecked

                // Handle location sharing setting change
                if (newLocationSharingSetting != enableLocationSharing) {
                    enableLocationSharing = newLocationSharingSetting

                    // Save setting to SharedPreferences
                    sharedPreferences.edit()
                        .putBoolean(PREF_LOCATION_SHARING, enableLocationSharing)
                        .apply()

                    // Update service with new setting
                    pingService?.updateLocationSharingSetting(enableLocationSharing)

                    if (enableLocationSharing) {
                        safeAppendToLog("Location sharing enabled - requesting permissions")
                        requestLocationPermissions()
                    } else {
                        safeAppendToLog("Location sharing disabled - stopping location updates")
                        stopLocationUpdates()
                        locationPermissionGranted = false
                        currentLocation = null
                        pingService?.updateLocationPermissionStatus(false)
                    }

                    Toast.makeText(this,
                        "Location sharing ${if (enableLocationSharing) "enabled" else "disabled"}",
                        Toast.LENGTH_SHORT).show()
                }

                // Update service settings
                pingService?.updateSettings(packetSize, timeout, tcpPort, udpPort)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun updateUI() {
        lifecycleScope.launch {
            try {
                // Small delay to ensure service state is updated
                delay(50)
                val isPingingActive = pingService?.isExecutingServerInstruction() ?: false

                runOnUiThread {
                    try {
                        if (!isFinishing && !isDestroyed) {
                            btnConnect.isEnabled = !isConnectedToServer && !isPingingActive
                            btnDisconnect.isEnabled = isConnectedToServer

                            if (isConnectedToServer && !isPingingActive) {
                                val heartbeatStatus = if (apiClient.isHeartbeatRunning()) " - 5min heartbeat active" else ""
                                val locationStatus = if (enableLocationSharing) {
                                    if (locationPermissionGranted) " | Location: ${getCurrentLocationString()}" else " | Location: N/A"
                                } else {
                                    " | Location: Disabled"
                                }
                                tvStatus.text = "Connected - Awaiting server instructions$heartbeatStatus$locationStatus"
                            } else if (isConnectedToServer && isPingingActive) {
                                tvStatus.text = "Connected - Executing ping instruction"
                            } else {
                                tvStatus.text = "Disconnected"
                                tvServerInstructions.text = "Not connected to server"
                            }
                        }
                    } catch (e: Exception) {
                        // Handle UI update exceptions
                    }
                }
            } catch (e: Exception) {
                // Handle coroutine exceptions
            }
        }
    }
}