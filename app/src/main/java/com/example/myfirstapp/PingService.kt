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

    private var packetsSent = 0
    private var packetsReceived = 0
    private var currentHost = ""
    private var currentProtocol = ""

    // Settings
    private var packetSize = 32
    private var timeout = 1000
    private var tcpPort = 80
    private var udpPort = 5001

    // Callback for logging
    private var logCallback: ((String) -> Unit)? = null

    inner class PingBinder : Binder() {
        fun getService(): PingService = this@PingService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY // Service will be restarted if killed
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPinging()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Ping Service Channel",
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
        val loss = if (packetsSent > 0) {
            ((packetsSent - packetsReceived) * 100) / packetsSent
        } else 0

        val notification = createNotification(
            "Pinging $currentHost ($currentProtocol)",
            "Packets: $packetsReceived/$packetsSent, Loss: $loss%"
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

    fun startPinging(host: String, protocol: String, interval: Long,
                     packetSize: Int, timeout: Int, tcpPort: Int, udpPort: Int) {
        if (pingJob?.isActive == true) {
            stopPinging()
        }

        currentHost = host
        currentProtocol = protocol
        packetsSent = 0
        packetsReceived = 0

        this.packetSize = packetSize
        this.timeout = timeout
        this.tcpPort = tcpPort
        this.udpPort = udpPort

        // Start foreground service
        val notification = createNotification(
            "Starting ping to $host",
            "Initializing $protocol ping..."
        )
        startForeground(NOTIFICATION_ID, notification)

        pingJob = serviceScope.launch {
            while (isActive) {
                packetsSent++
                val success: Boolean
                val rtt = measureTimeMillis {
                    success = when (protocol) {
                        "ICMP" -> icmpPingCmd(host)
                        "TCP" -> tcpPing(host, tcpPort)
                        "UDP" -> udpPing(host, udpPort, timeout)
                        else -> false
                    }
                }

                if (success) {
                    packetsReceived++
                    log("Reply from $host: time=${rtt}ms, packets: $packetsReceived/$packetsSent")
                } else {
                    log("Request timed out. packets: $packetsReceived/$packetsSent")
                }

                // Update notification every 5 pings or on first ping
                if (packetsSent == 1 || packetsSent % 5 == 0) {
                    updateNotification()
                }

                delay(interval)
            }
        }
    }

    fun stopPinging() {
        serviceScope.launch {
            pingJob?.cancelAndJoin()
            val loss = if (packetsSent > 0) {
                ((packetsSent - packetsReceived) * 100) / packetsSent
            } else 0
            log("Ping stopped. Packet loss: $loss%")

            // Stop foreground service
            stopForeground(true)
        }
    }

    fun isRunning(): Boolean = pingJob?.isActive == true

    private fun log(message: String) {
        logCallback?.invoke(message)
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