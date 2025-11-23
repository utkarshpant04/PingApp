package com.example.myfirstapp

import android.app.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.*
import java.util.UUID
import kotlin.system.measureTimeMillis
import java.util.concurrent.ConcurrentHashMap

// Method 1: Using UUID (Most Reliable)
fun generateSessionIdUUID(): String {
    return "session_${UUID.randomUUID()}"
}

data class PendingPing(
    val sequenceNumber: Int,
    val sentTime: Long,
    val location: String,
    val networkType: String
)


// Add these variables to the top of your PingService class
private var udpListenerJob: Job? = null
private var udpSocket: DatagramSocket? = null
class PingService : Service() {

    companion object {
        private const val TAG = "PingService"
        private const val LISTENER_PORT = 50002
    }

    private val CHANNEL_ID = "PingServiceChannel"
    private val NOTIFICATION_ID = 2

    private val binder = PingBinder()
    private var sendPingJob: Job? = null
    private var receiveAckJob: Job? = null
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
    // ADD THIS
    private val resultTimestampFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.getDefault()).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }
    // Ping interval tracking
    private var pingIntervalMs = 100L // Duration between two consecutive pings

    // Settings
    private var packetSize = 32
    private var timeout = 1000
    private var tcpPort = 80
    private var udpPort = 50002

    // Pending pings awaiting ACK (sequence number -> ping data)
    private val pendingPings = ConcurrentHashMap<Int, PendingPing>()

    // Callbacks
    private var logCallback: ((String) -> Unit)? = null
    private var locationCallback: (() -> String)? = null

    // API Service binding
    private var apiService: ApiService? = null
    private var isApiServiceBound = false

    private val apiServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ApiService.ApiBinder
            apiService = binder.getService()
            isApiServiceBound = true
            Log.i(TAG, "Connected to ApiService")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            apiService = null
            isApiServiceBound = false
            Log.i(TAG, "Disconnected from ApiService")
        }
    }

    inner class PingBinder : Binder() {
        fun getService(): PingService = this@PingService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Bind to ApiService for uploading session data
        val apiServiceIntent = Intent(this, ApiService::class.java)
        bindService(apiServiceIntent, apiServiceConnection, Context.BIND_AUTO_CREATE)
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
//        stopUdpListener() // Explicitly stop listener

        // Unbind from ApiService
        if (isApiServiceBound) {
            unbindService(apiServiceConnection)
            isApiServiceBound = false
        }

        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.ping_service_channel),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(title: String, content: String): Notification {
        val notificationIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Background Service")
            .setContentText("Active")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
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

    fun setLocationCallback(callback: () -> String) {
        locationCallback = callback
    }

    fun updateSettings(packetSize: Int, timeout: Int) {
        this.packetSize = packetSize
        this.timeout = timeout
//        log("Settings updated: Packet=$packetSize, Timeout=${timeout}ms")
    }

    fun startServerControlledMode() {
        isInServerControlledMode = true

        val notification = createNotification(
            getString(R.string.server_controlled_mode),
            getString(R.string.awaiting_server_instructions)
        )
        startForeground(NOTIFICATION_ID, notification)
//        startUdpListener() // Start the new listener
        log("Entered server-controlled mode - awaiting instructions")
    }

    fun stopServerControlledMode() {
        isInServerControlledMode = false
//        stopUdpListener() // Stop the new listener

        serviceScope.launch {
            sendPingJob?.cancelAndJoin()
            receiveAckJob?.cancelAndJoin()
            sendPingJob = null
            receiveAckJob = null
            isExecutingPingInstruction = false
            pendingPings.clear()

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
//        log("check 256: pingservice.kt")
        serviceScope.launch {
            sendPingJob?.cancelAndJoin()
            receiveAckJob?.cancelAndJoin()
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
        sessionId = generateSessionIdUUID()
        pingResults.clear()
        minRtt = Double.MAX_VALUE
        maxRtt = 0.0
        totalRtt = 0.0
        pingIntervalMs = interval

        this.packetSize = packetSize
        this.timeout = timeout
        this.tcpPort = tcpPort
        this.udpPort = udpPort

        isExecutingPingInstruction = true
        pendingPings.clear()

        val notification = createNotification(
            getString(R.string.executing_server_instruction, host, protocol),
            getString(R.string.ping_duration_remaining, durationSeconds)
        )
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)

        log("Executing server ping instruction: $host ($protocol) for ${durationSeconds}s ")//- Session: $sessionId - Location: $location - Ping Interval: ${interval}ms")

        val endTime = System.currentTimeMillis() + (durationSeconds * 1000)

        // Launch two concurrent jobs: one for sending pings, one for receiving ACKs
        sendPingJob = serviceScope.launch {
            var sequenceNumber = 0

            while (isActive && System.currentTimeMillis() < endTime && isExecutingPingInstruction) {
                sequenceNumber++
                packetsSent++

                val currentLoc = locationCallback?.invoke() ?: location
                currentLocation = currentLoc
                val networkType = NetworkUtils.getNetworkType(applicationContext)
                val sentTime = System.currentTimeMillis()

                // Record pending ping for ACK tracking
                pendingPings[sequenceNumber] = PendingPing(
                    sequenceNumber = sequenceNumber,
                    sentTime = sentTime,
                    location = currentLoc,
                    networkType = networkType
                )

//                log("SENT UDP Packet - Seq: $sequenceNumber ")// | Host: $host | Protocol: $protocol | Location: $currentLoc | Network: $networkType")

                // Send ping asynchronously without waiting
                serviceScope.launch {
                    val rtt = measureTimeMillis {
                        when (protocol.uppercase()) {
                            "ICMP", "PING" -> icmpPingCmd(host)
                            "TCP" -> tcpPing(host, tcpPort)
                            "UDP" -> udpPing(host, udpPort, timeout, sequenceNumber, sessionId)
                            else -> tcpPing(host, tcpPort)
                        }
                    }

                    // Check if ping is still pending (hasn't timed out yet)
                    val ping = pendingPings[sequenceNumber]
                    if (ping != null) {
                        val timestampStr = resultTimestampFormat.format(java.util.Date(ping.sentTime))

                        if (rtt < timeout) {
                            packetsReceived++
                            totalRtt += rtt.toDouble()
                            if (rtt.toDouble() < minRtt) minRtt = rtt.toDouble()
                            if (rtt.toDouble() > maxRtt) maxRtt = rtt.toDouble()

                            totalBytesTransferred += when (protocol.uppercase()) {
                                "ICMP", "PING" -> packetSize.toLong() * 2
                                "TCP" -> 64L
                                "UDP" -> packetSize.toLong() * 2
                                else -> 64L
                            }

//                            log("RECV ACK - Seq: $sequenceNumber  | RTT: ${rtt}ms")// | Location: ${ping.location} | Network: ${ping.networkType}")

                            // Create the CORRECT PingResult
                            val receivedTime = ping.sentTime + rtt
                            pingResults.add(PingResult(
                                timestamp = timestampStr,
                                sequence = sequenceNumber,
                                success = true,
//                                rttMs = rtt.toDouble(),
                                sentTimestampMs = ping.sentTime,      // <-- ADDED
                                receivedTimestampMs = receivedTime,
                                location = ping.location,
                                networkType = ping.networkType,
                                errorMessage = ""
                            ))

                        } else {
//                            log("TIMEOUT - Seq: $sequenceNumber | (exceeded ${timeout}ms) ")// | Location: ${ping.location}")

                            // Create the CORRECT PingResult for timeout
                            pingResults.add(PingResult(
                                timestamp = timestampStr,
                                sequence = sequenceNumber,
                                success = false,
//                                rttMs = null,
                                sentTimestampMs = ping.sentTime,
                                receivedTimestampMs = null,
                                location = ping.location,
                                networkType = ping.networkType,
                                errorMessage = "Timeout"
                            ))

                        }
                        pendingPings.remove(sequenceNumber)
                    }
                }

                delay(pingIntervalMs)
            }
        }

        receiveAckJob = serviceScope.launch {
            while (isActive && System.currentTimeMillis() < endTime && isExecutingPingInstruction) {
                val currentTime = System.currentTimeMillis()
                val iterator = pendingPings.iterator()

                // Check for timeouts
                while (iterator.hasNext()) {
                    val (seqNum, ping) = iterator.next()
                    val elapsedTime = currentTime - ping.sentTime

                    if (elapsedTime > timeout * 2) { // Allow extra time for async processing
                        val timestampStr = resultTimestampFormat.format(java.util.Date(ping.sentTime))
                        pingResults.add(PingResult(
                            timestamp = timestampStr,
                            sequence = ping.sequenceNumber,
                            success = false,
//                            rttMs = null,
                            sentTimestampMs = ping.sentTime,
                            receivedTimestampMs = null,
                            location = ping.location,
                            networkType = ping.networkType,
                            errorMessage = "Timeout (Cleanup)"
                        ))

//                        log("TIMEOUT - Seq: $seqNum | Waited: ${elapsedTime}ms (exceeded ${timeout}ms) ")// | Location: ${ping.location}")
                        iterator.remove()
                    }
                }

                val loss = if (packetsSent > 0) {
                    ((packetsSent - packetsReceived) * 100) / packetsSent
                } else 0

                val bandwidth = calculateBandwidth()
                val remainingTime = ((endTime - System.currentTimeMillis()) / 1000).coerceAtLeast(0)

//                log("STATS - Sent: $packetsSent, Received: $packetsReceived, Loss: $loss%, BW: ${formatBandwidth(bandwidth)} | Remaining: ${remainingTime}s")

                if (packetsSent % 5 == 0) {
                    updateNotification()
                }

                delay(50) // Check for timeouts every 500ms
            }
        }

        // Wait for both jobs to complete
        sendPingJob?.join()
        receiveAckJob?.join()
        finishPingInstruction()
    }

    private suspend fun finishPingInstruction() {
        isExecutingPingInstruction = false

        val endTime = System.currentTimeMillis()
        val duration = (endTime - startTime) / 1000

        val loss = if (packetsSent > 0) {
            ((packetsSent - packetsReceived) * 100.0) / packetsSent
        } else 0.0

        val avgRtt = if (packetsReceived > 0) totalRtt / packetsReceived else 0.0
        val bandwidth = calculateBandwidth()

        val finalLocation = locationCallback?.invoke() ?: currentLocation
        currentLocation = finalLocation

        log("INSTRUCTION COMPLETE - Sent: $packetsSent, Received: $packetsReceived  , Loss: ${loss.toInt()}%, Avg RTT: ${avgRtt.toInt()}ms, Avg BW: ${formatBandwidth(bandwidth)}")// | Final Location: $finalLocation")

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
            endLocation = finalLocation,
            settings = PingSettings(
                packetSize = packetSize,
                timeout = timeout,
                interval = pingIntervalMs,
                tcpPort = tcpPort,
                udpPort = udpPort
            ),
            pingResults = pingResults.toList()
        )

        try {
            apiService?.let { service ->
                val result = service.uploadPingSession(sessionData)
                when (result) {
                    is ApiResponse.Success -> {
                        log("Server instruction results uploaded successfully")
                    }
                    is ApiResponse.Error -> {
                        log("Failed to upload server instruction results: ${result.message}")
                    }
                }
            } ?: run {
                log("ApiService not available for uploading session data")
            }
        } catch (e: Exception) {
            log("Error uploading server instruction results: ${e.message}")
        }

        if (isInServerControlledMode) {
            val notification = createNotification(
                getString(R.string.server_controlled_mode),
                getString(R.string.awaiting_server_instructions)
            )
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        }

        pendingPings.clear()
    }

    private fun stopAllOperations() {
        serviceScope.launch {
            sendPingJob?.cancelAndJoin()
            receiveAckJob?.cancelAndJoin()
            sendPingJob = null
            receiveAckJob = null
            isExecutingPingInstruction = false
            isInServerControlledMode = false
            pendingPings.clear()

            stopForeground(true)
        }
    }

    fun isExecutingServerInstruction(): Boolean = isExecutingPingInstruction

    fun getCurrentLocation(): String = currentLocation

    private fun log(message: String) {
        Log.i(TAG, message)
        logCallback?.invoke(message)
    }

    private fun calculateBandwidth(): Double {
        val elapsedTimeSeconds = (System.currentTimeMillis() - startTime) / 1000.0
        return if (elapsedTimeSeconds > 0) {
            (totalBytesTransferred * 8) / elapsedTimeSeconds
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

    private fun udpPing(host: String, port: Int, timeout: Int, seq: Int, sessionId: String): Boolean {
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeout
                val dataString = "$sessionId,$seq"
                val sendData = dataString.toByteArray(Charsets.UTF_8)
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