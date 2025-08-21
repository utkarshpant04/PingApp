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

    private lateinit var etHost: EditText
    private lateinit var spProtocol: Spinner
    private lateinit var etInterval: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvLog: TextView

    // Location components
    private lateinit var locationManager: LocationManager
    private var currentLocation: Location? = null
    private val LOCATION_PERMISSION_REQUEST_CODE = 1001

    // Configurable defaults
    private var packetSize = 32
    private var timeout = 1000
    private var tcpPort = 80
    private var udpPort = 5001

    private var pingService: PingService? = null
    private var isServiceBound = false

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

        etHost = findViewById(R.id.etHost)
        spProtocol = findViewById(R.id.spProtocol)
        etInterval = findViewById(R.id.etInterval)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvLog = findViewById(R.id.tvLog)

        val protocols = arrayOf("ICMP", "TCP", "UDP")
        spProtocol.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, protocols)

        btnStart.setOnClickListener { startPinging() }
        btnStop.setOnClickListener { stopPinging() }

        // Initialize location manager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Initialize REST API client
        apiClient = RestApiClient(this)

        // Request location permissions if not granted
        requestLocationPermissions()

        // Connect to server
        connectToServer()

        // Bind to the service
        val serviceIntent = Intent(this, PingService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
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

    private fun connectToServer() {
        lifecycleScope.launch {
            try {
                // First test server connectivity
                val pingResult = apiClient.pingServer()
                when (pingResult) {
                    is ApiResponse.Success -> {
                        // Server is reachable, now connect with device info
                        val location = getCurrentLocationString()
                        val connectResult = apiClient.connectToServer(location)
                        when (connectResult) {
                            is ApiResponse.Success -> {
                                Toast.makeText(this@MainActivity, "Connected to server successfully", Toast.LENGTH_SHORT).show()
                                // Start periodic heartbeats
                                startHeartbeats()
                            }
                            is ApiResponse.Error -> {
                                Toast.makeText(this@MainActivity, "Server connection failed: ${connectResult.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    is ApiResponse.Error -> {
                        Toast.makeText(this@MainActivity, "Server unreachable: ${pingResult.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Connection error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startHeartbeats() {
        lifecycleScope.launch {
            while (true) {
                try {
                    val location = getCurrentLocationString()
                    apiClient.sendHeartbeat("running", location)
                } catch (e: Exception) {
                    // Silently continue if heartbeat fails
                }
                delay(300000) // Send heartbeat every 5 minutes
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
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

    private fun startPinging() {
        val host = etHost.text.toString().trim()
        val interval = etInterval.text.toString().toLongOrNull() ?: 1000L
        val protocol = spProtocol.selectedItem.toString()
        val location = getCurrentLocationString()

        if (host.isEmpty()) {
            Toast.makeText(this, getString(R.string.enter_host_error), Toast.LENGTH_SHORT).show()
            return
        }

        if (!isServiceBound || pingService == null) {
            Toast.makeText(this, getString(R.string.service_not_ready), Toast.LENGTH_SHORT).show()
            return
        }

        tvLog.text = ""
        pingService?.startPinging(host, protocol, interval, packetSize, timeout, tcpPort, udpPort, location)
        updateUI()
    }

    private fun stopPinging() {
        lifecycleScope.launch {
            pingService?.stopPinging()
            // Wait a moment for the service to fully stop
            delay(100)
            updateUI()
        }
    }

    private fun updateUI() {
        lifecycleScope.launch {
            // Small delay to ensure service state is updated
            delay(50)
            val isRunning = pingService?.isRunning() ?: false
            runOnUiThread {
                btnStart.isEnabled = !isRunning
                btnStop.isEnabled = isRunning

                if (isRunning) {
                    btnStart.text = getString(R.string.running)
                } else {
                    btnStart.text = getString(R.string.start_ping)
                }
            }
        }
    }
}