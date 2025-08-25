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

    // Configurable defaults for ping settings
    private var packetSize = 32
    private var timeout = 1000
    private var tcpPort = 80
    private var udpPort = 5001

    private var pingService: PingService? = null
    private var isServiceBound = false
    private var isConnectedToServer = false

    // REST API client for server communication
    private lateinit var apiClient: RestApiClient

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PingService.PingBinder
            pingService = binder.getService()
            isServiceBound = true

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

        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        tvServerInstructions = findViewById(R.id.tvServerInstructions)

        btnConnect.setOnClickListener { connectToServerAndStartHeartbeat() }
        btnDisconnect.setOnClickListener { disconnectFromServer() }

        // Initialize location manager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Initialize REST API client
        apiClient = RestApiClient(this)

        // Request location permissions if not granted
        requestLocationPermissions()

        // Bind to the service
        val serviceIntent = Intent(this, PingService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        updateUI()
    }

    private fun requestLocationPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
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
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startLocationUpdates()
                } else {
                    Toast.makeText(this, "Location permission denied. Location will show as N/A", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

            try {
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

                // Get last known location as initial value
                val lastKnownGPS = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                val lastKnownNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

                currentLocation = when {
                    lastKnownGPS != null -> lastKnownGPS
                    lastKnownNetwork != null -> lastKnownNetwork
                    else -> null
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

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    fun getCurrentLocationString(): String {
        return currentLocation?.let {
            "%.6f,%.6f".format(it.latitude, it.longitude)
        } ?: "N/A"
    }

    private fun connectToServerAndStartHeartbeat() {
        if (isConnectedToServer) return

        lifecycleScope.launch {
            try {
                // First test server connectivity
                tvStatus.text = "Connecting to server..."
                val pingResult = apiClient.pingServer()

                when (pingResult) {
                    is ApiResponse.Success -> {
                        // Server is reachable, now connect with device info
                        val location = getCurrentLocationString()
                        val connectResult = apiClient.connectToServer(location)

                        when (connectResult) {
                            is ApiResponse.Success -> {
                                isConnectedToServer = true
                                tvStatus.text = "Connected to server"
                                Toast.makeText(this@MainActivity, "Connected to server successfully", Toast.LENGTH_SHORT).show()

                                // Clear log and show connection info
                                tvLog.text = "Connected to server successfully\nLocation: $location\nHeartbeat: Every 5 minutes\nWaiting for server instructions...\n"

                                // Start service for server-controlled operations
                                pingService?.startServerControlledMode()

                                // Start 5-minute heartbeat with server instruction checking
                                startProperHeartbeat()
                                updateUI()
                            }
                            is ApiResponse.Error -> {
                                tvStatus.text = "Connection failed"
                                Toast.makeText(this@MainActivity, "Server connection failed: ${connectResult.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    is ApiResponse.Error -> {
                        tvStatus.text = "Server unreachable"
                        Toast.makeText(this@MainActivity, "Server unreachable: ${pingResult.message}", Toast.LENGTH_LONG).show()
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
     */
    private fun startProperHeartbeat() {
        // Use the RestApiClient's proper 5-minute heartbeat system
        apiClient.startHeartbeat(
            scope = lifecycleScope,
            location = getCurrentLocationString()
        ) { instruction ->
            // Handle server instructions received via heartbeat
            runOnUiThread {
                if (instruction.sendPing) {
                    tvServerInstructions.text = "Server instruction: Ping ${instruction.host} (${instruction.protocol}) for ${instruction.durationSeconds}s"
                    tvLog.append("💓 Heartbeat received server instruction: Ping ${instruction.host} (${instruction.protocol}) for ${instruction.durationSeconds}s\n")

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
                    tvLog.append("💓 Heartbeat sent - waiting for instructions\n")
                }
            }
        }
    }

    private fun disconnectFromServer() {
        lifecycleScope.launch {
            isConnectedToServer = false
            tvStatus.text = "Disconnected"
            tvServerInstructions.text = "Not connected to server"

            // Stop the proper 5-minute heartbeat
            apiClient.stopHeartbeat()

            // Stop service operations
            pingService?.stopServerControlledMode()

            // Send final heartbeat to notify server of disconnection
            try {
                val location = getCurrentLocationString()
                apiClient.sendHeartbeatAndCheckInstructions("disconnected", location)
                tvLog.append("Sent disconnection notification to server\n")
            } catch (e: Exception) {
                tvLog.append("Failed to notify server of disconnection: ${e.message}\n")
            }

            tvLog.append("Disconnected from server - heartbeat stopped\n")
            Toast.makeText(this@MainActivity, "Disconnected from server", Toast.LENGTH_SHORT).show()
            updateUI()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

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
        try {
            locationManager.removeUpdates(this)
        } catch (e: SecurityException) {
            // Permission was revoked
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

        etPacketSize.setText(packetSize.toString())
        etTimeout.setText(timeout.toString())
        etTcpPort.setText(tcpPort.toString())
        etUdpPort.setText(udpPort.toString())

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_title))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                packetSize = etPacketSize.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: packetSize
                timeout = etTimeout.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: timeout
                tcpPort = etTcpPort.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: tcpPort
                udpPort = etUdpPort.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: udpPort

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
            val isPingingActive = pingService?.isExecutingServerInstruction() ?: false

            runOnUiThread {
                btnConnect.isEnabled = !isConnectedToServer && !isPingingActive
                btnDisconnect.isEnabled = isConnectedToServer

                if (isConnectedToServer && !isPingingActive) {
                    val heartbeatStatus = if (apiClient.isHeartbeatRunning()) " - 5min heartbeat active" else ""
                    tvStatus.text = "Connected - Awaiting server instructions$heartbeatStatus"
                } else if (isConnectedToServer && isPingingActive) {
                    tvStatus.text = "Connected - Executing ping instruction"
                } else {
                    tvStatus.text = "Disconnected"
                    tvServerInstructions.text = "Not connected to server"
                }
            }
        }
    }
}