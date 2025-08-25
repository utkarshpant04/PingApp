package com.example.myfirstapp

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.*
import kotlin.system.measureTimeMillis

class PingService : Service() {

    private val CHANNEL_ID = "PingServiceChannel"
    private val NOTIFICATION_ID = 1

    private val binder = PingBinder()
    private var pingJob: Job? = null
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Server-controlled mode variables
    private var isInServerControlledMode = false
    private var isExecutingPingInstruction = false

    // Session tracking
    private var packetsSent = 0
    private var packetsReceived = 0
    private var currentHost = ""
    private var currentProtocol = ""
    private var currentLocation = "N/A"
    private var totalBytesTransferred = 0L
    private var startTime = 0L
    private var sessionId = ""
    private var pingResults = mutableListOf<PingResult>()
    private var minRtt = Double.MAX_VALUE
    private var maxRtt = 0.0
    private var totalRtt = 0.0
    private var startLocation = "N/A"

    // Settings
    private var packetSize = 32
    private var timeout = 1000
    private var tcpPort = 80
    private var udpPort = 5001

    // Callback for logging
    private var logCallback: ((String) -> Unit)? = null
    // API client for server communication
    private var apiClient: RestApiClient? = null

    inner class PingBinder : Binder() {
        fun getService(): PingService = this@PingService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Initialize API client for server communication
        apiClient = RestApiClient(this)
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY // Service will be restarted if killed
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAllOperations()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.ping_service_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(title: String, content: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        if (!isExecutingPingInstruction) return

        val loss = if (packetsSent > 0) {
            ((packetsSent - packetsReceived) * 100) / packetsSent
        } else 0

        val bandwidth = calculateBandwidth()

        val notification = createNotification(
            getString(R.string.executing_server_instruction, currentHost, currentProtocol),
            "Sent: $packetsSent, Received: $packetsReceived, Loss: $loss%, BW: ${formatBandwidth(bandwidth)}"
        )

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun setLogCallback(callback: (String) -> Unit) {
        logCallback = callback
    }

    fun updateSettings(packetSize: Int, timeout: Int, tcpPort: Int, udpPort: Int) {
        this.packetSize = packetSize
        this.timeout = timeout
        this.tcpPort = tcpPort
        this.udpPort = udpPort
    }

    fun startServerControlledMode() {
        isInServerControlledMode = true

        // Start foreground service for server-controlled mode
        val notification = createNotification(
            getString(R.string.server_controlled_mode),
            getString(R.string.awaiting_server_instructions)
        )
        startForeground(NOTIFICATION_ID, notification)

        log("Entered server-controlled mode - awaiting instructions")
    }

    fun stopServerControlledMode() {
        isInServerControlledMode = false

        // Stop any ongoing ping instruction
        serviceScope.launch {
            pingJob?.cancelAndJoin()
            pingJob = null
            isExecutingPingInstruction = false

            stopForeground(true)
            log("Exited server-controlled mode")
        }
    }

    fun executePingInstruction(
        host: String, protocol: String, interval: Long, durationSeconds: Int,
        packetSize: Int, timeout: Int, tcpPort: Int, udpPort: Int, location: String
    ) {
        if (!isInServerControlledMode) {
            log("Cannot execute ping instruction - not in server-controlled mode")
            return
        }

        if (isExecutingPingInstruction) {
            log("Cannot execute ping instruction - already executing another instruction")
            return
        }

        // Cancel any existing ping job
        serviceScope.launch {
            pingJob?.cancelAndJoin()
            startPingInstruction(host, protocol, interval, durationSeconds, packetSize, timeout, tcpPort, udpPort, location)
        }
    }

    private suspend fun startPingInstruction(
        host: String, protocol: String, interval: Long, durationSeconds: Int,
        packetSize: Int, timeout: Int, tcpPort: Int, udpPort: Int, location: String
    ) {
        // Initialize session data
        currentHost = host
        currentProtocol = protocol
        currentLocation = location
        startLocation = location
        packetsSent = 0
        packetsReceived = 0
        totalBytesTransferred = 0L
        startTime = System.currentTimeMillis()
        sessionId = "server_session_${System.currentTimeMillis()}_${(1000..9999).random()}"
        pingResults.clear()
        minRtt = Double.MAX_VALUE
        maxRtt = 0.0
        totalRtt = 0.0

        this.packetSize = packetSize
        this.timeout = timeout
        this.tcpPort = tcpPort
        this.udpPort = udpPort

        isExecutingPingInstruction = true

        // Update notification
        val notification = createNotification(
            getString(R.string.executing_server_instruction, host, protocol),
            getString(R.string.ping_duration_remaining, durationSeconds)
        )
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)

        // Log initial information
        log("Executing server ping instruction: $host ($protocol) for ${durationSeconds}s - Session: $sessionId - Location: $location")

        pingJob = serviceScope.launch {
            val endTime = System.currentTimeMillis() + (durationSeconds * 1000)
            var sequenceNumber = 0

            while (isActive && System.currentTimeMillis() < endTime && isExecutingPingInstruction) {
                packetsSent++
                sequenceNumber++
                val success: Boolean
                val rtt = measureTimeMillis {
                    success = when (protocol) {
                        "ICMP" -> icmpPingCmd(host)
                        "TCP" -> tcpPing(host, tcpPort)
                        "UDP" -> udpPing(host, udpPort, timeout)
                        else -> false
                    }
                }

                // Record ping result
                val pingResult = PingResult(
                    timestamp = System.currentTimeMillis().toString(),
                    sequence = sequenceNumber,
                    success = success,
                    rttMs = rtt.toDouble(),
                    location = location,
                    errorMessage = if (!success) "Request timed out" else ""
                )
                pingResults.add(pingResult)

                if (success) {
                    packetsReceived++
                    // Update RTT statistics
                    totalRtt += rtt.toDouble()
                    if (rtt.toDouble() < minRtt) minRtt = rtt.toDouble()
                    if (rtt.toDouble() > maxRtt) maxRtt = rtt.toDouble()

                    // Count bytes for bandwidth calculation (approximate)
                    totalBytesTransferred += when (protocol) {
                        "ICMP" -> packetSize.toLong() * 2 // sent + received
                        "TCP" -> 64L // TCP handshake overhead
                        "UDP" -> packetSize.toLong() * 2 // sent + received if response
                        else -> 0L
                    }
                }

                val loss = if (packetsSent > 0) {
                    ((packetsSent - packetsReceived) * 100) / packetsSent
                } else 0

                val bandwidth = calculateBandwidth()
                val remainingTime = ((endTime - System.currentTimeMillis()) / 1000).coerceAtLeast(0)

                if (success) {
                    log("Reply from $host: time=${rtt}ms | Sent: $packetsSent, Received: $packetsReceived, Loss: $loss%, BW: ${formatBandwidth(bandwidth)} | Remaining: ${remainingTime}s")
                } else {
                    log("Request timed out | Sent: $packetsSent, Received: $packetsReceived, Loss: $loss%, BW: ${formatBandwidth(bandwidth)} | Remaining: ${remainingTime}s")
                }

                // Update notification every 5 pings or on first ping
                if (packetsSent == 1 || packetsSent % 5 == 0) {
                    updateNotification()
                }

                delay(interval)
            }

            // Ping instruction completed
            finishPingInstruction()
        }
    }

    private suspend fun finishPingInstruction() {
        isExecutingPingInstruction = false

        val endTime = System.currentTimeMillis()
        val duration = (endTime - startTime) / 1000 // seconds

        val loss = if (packetsSent > 0) {
            ((packetsSent - packetsReceived) * 100.0) / packetsSent
        } else 0.0

        val avgRtt = if (packetsReceived > 0) totalRtt / packetsReceived else 0.0
        val bandwidth = calculateBandwidth()

        log("Server ping instruction completed | Final stats - Sent: $packetsSent, Received: $packetsReceived, Loss: ${loss.toInt()}%, Avg BW: ${formatBandwidth(bandwidth)} | Final Location: $currentLocation")

        // Prepare session data for server upload
        val sessionData = PingSessionData(
            sessionId = sessionId,
            host = currentHost,
            protocol = currentProtocol,
            startTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(startTime)),
            endTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(endTime)),
            durationSeconds = duration,
            packetsSent = packetsSent,
            packetsReceived = packetsReceived,
            packetLossPercent = loss,
            avgRttMs = avgRtt,
            minRttMs = if (minRtt == Double.MAX_VALUE) 0.0 else minRtt,
            maxRttMs = maxRtt,
            totalBytes = totalBytesTransferred,
            avgBandwidthBps = bandwidth,
            startLocation = startLocation,
            endLocation = currentLocation,
            settings = PingSettings(
                packetSize = packetSize,
                timeout = timeout,
                interval = 1000L, // Default interval
                tcpPort = tcpPort,
                udpPort = udpPort
            ),
            pingResults = pingResults.toList()
        )

        // Upload session data to server
        try {
            apiClient?.let { client ->
                val result = client.uploadPingSession(sessionData)
                when (result) {
                    is ApiResponse.Success -> {
                        log("Server instruction results uploaded successfully")
                    }
                    is ApiResponse.Error -> {
                        log("Failed to upload server instruction results: ${result.message}")
                    }
                }
            }
        } catch (e: Exception) {
            log("Error uploading server instruction results: ${e.message}")
        }

        // Reset notification to server-controlled mode if still in that mode
        if (isInServerControlledMode) {
            val notification = createNotification(
                getString(R.string.server_controlled_mode),
                getString(R.string.awaiting_server_instructions)
            )
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun stopAllOperations() {
        serviceScope.launch {
            pingJob?.cancelAndJoin()
            pingJob = null
            isExecutingPingInstruction = false
            isInServerControlledMode = false

            // Stop foreground service
            stopForeground(true)
        }
    }

    fun isExecutingServerInstruction(): Boolean = isExecutingPingInstruction

    fun getCurrentLocation(): String = currentLocation

    private fun log(message: String) {
        logCallback?.invoke(message)
    }

    private fun calculateBandwidth(): Double {
        val elapsedTimeSeconds = (System.currentTimeMillis() - startTime) / 1000.0
        return if (elapsedTimeSeconds > 0) {
            (totalBytesTransferred * 8) / elapsedTimeSeconds // bits per second
        } else {
            0.0
        }
    }

    private fun formatBandwidth(bps: Double): String {
        return when {
            bps >= 1_000_000_000 -> "%.2f Gbps".format(bps / 1_000_000_000)
            bps >= 1_000_000 -> "%.2f Mbps".format(bps / 1_000_000)
            bps >= 1_000 -> "%.2f Kbps".format(bps / 1_000)
            else -> "%.0f bps".format(bps)
        }
    }

    /** ICMP ping via system ping command for Android reliability */
    private fun icmpPingCmd(host: String): Boolean {
        return try {
            val process = ProcessBuilder()
                .command("ping", "-c", "1", "-W", (timeout / 1000).toString(), host)
                .redirectErrorStream(true)
                .start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            val exitCode = process.waitFor()
            exitCode == 0 && output.contains("bytes from")
        } catch (e: Exception) {
            false
        }
    }

    private fun tcpPing(host: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeout)
                true
            }
        } catch (e: IOException) {
            false
        }
    }

    private fun udpPing(host: String, port: Int, timeout: Int): Boolean {
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeout
                val sendData = ByteArray(packetSize) { 'A'.code.toByte() }
                val packet = DatagramPacket(sendData, sendData.size, InetAddress.getByName(host), port)
                socket.send(packet)

                val buffer = ByteArray(1024)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                true
            }
        } catch (e: SocketTimeoutException) {
            false
        } catch (e: IOException) {
            false
        }
    }
}