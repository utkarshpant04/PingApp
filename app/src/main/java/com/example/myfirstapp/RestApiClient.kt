package com.example.myfirstapp

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

class ApiService : Service() {

    companion object {
        private const val TAG = "ApiService"
        private const val SERVER_BASE_URL = "http://dragon.wag.org.in:12345/api" // Change for physical device
        private const val CONNECT_TIMEOUT = 30000
        private const val READ_TIMEOUT = 30000
        private const val HEARTBEAT_INTERVAL_MS = 2 * 60 * 1000L // 2 minutes
        private const val RECONNECT_INTERVAL_MS = 2 * 60 * 1000L // 2 minutes
        private const val MAX_FAILED_UPLOADS = 50 // Maximum number of failed uploads to store

        // Notification constants
        private const val NOTIFICATION_CHANNEL_ID = "api_service_channel"
        private const val NOTIFICATION_CHANNEL_NAME = "API Service"
        private const val ONGOING_NOTIFICATION_ID = 1
        private const val STATUS_NOTIFICATION_ID = 2

        // Intent actions (optional usage)
        const val ACTION_CONNECT = "com.example.myfirstapp.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.example.myfirstapp.ACTION_DISCONNECT"
        const val ACTION_START_HEARTBEAT = "com.example.myfirstapp.ACTION_START_HEARTBEAT"
        const val ACTION_STOP_HEARTBEAT = "com.example.myfirstapp.ACTION_STOP_HEARTBEAT"
    }

    private val binder = ApiBinder()

    // Use Default dispatcher for background work (not Main)
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val deviceId: String by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    private var clientId: String? = null
    private var heartbeatJob: Job? = null
    private var isHeartbeatActive = false
    private var onInstructionReceived: ((ServerInstruction) -> Unit)? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Connection state
    private var isConnectedToServer = false
    private var isInReconnectMode = false
    private var lastLocation = "N/A"

    // Failed upload queue - thread-safe
    private val failedUploadQueue = ConcurrentLinkedQueue<FailedUploadItem>()

    // Callbacks
    private var statusCallback: ((String, String) -> Unit)? = null
    private var locationCallback: (() -> String)? = null

    // Notification manager
    private lateinit var notificationManager: NotificationManagerCompat

    inner class ApiBinder : Binder() {
        fun getService(): ApiService = this@ApiService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ApiService created")

        notificationManager = NotificationManagerCompat.from(this)
        createNotificationChannel()

        // Start as foreground immediately so system treats this as a true FGS
        try {
            startForeground(ONGOING_NOTIFICATION_ID, createOngoingNotification("Starting…"))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start foreground in onCreate: ${e.message}")
        }

        // Optional: one-time status notification
        showServiceNotification("Service Starting", "Initializing API service...")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Ensure we are foreground (safe to call again)
        try {
            startForeground(ONGOING_NOTIFICATION_ID, createOngoingNotification())
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed in onStartCommand: ${e.message}")
        }

        // Handle action-based control (Activity or Notification may send these)
        when (intent?.action) {
            ACTION_CONNECT -> serviceScope.launch { connectToServer() }
            ACTION_DISCONNECT -> serviceScope.launch { disconnectFromServer() }
            ACTION_START_HEARTBEAT -> startHeartbeat()
            ACTION_STOP_HEARTBEAT -> stopHeartbeat()
        }

        // Restart if killed
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "ApiService destroying")
        stopHeartbeat()
        serviceScope.cancel()
        notificationManager.cancelAll()
    }

    /**
     * Create notification channel for Android O and above
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for API service status and ping operations"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * Create ongoing notification for foreground service
     */
    private fun createOngoingNotification(statusOverride: String? = null): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val queueSize = failedUploadQueue.size
        val queueStatus = if (queueSize > 0) " • $queueSize pending uploads" else ""

        val status = statusOverride ?: when {
            isInReconnectMode -> "Reconnecting...$queueStatus"
            isConnectedToServer && isHeartbeatActive -> "Connected — Heartbeat Active$queueStatus"
            isConnectedToServer -> "Connected — Heartbeat Stopped$queueStatus"
            else -> "Starting…$queueStatus"
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Ping App - API Service")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * Show general service notification
     */
    private fun showServiceNotification(title: String, message: String, autoCancel: Boolean = true) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setAutoCancel(autoCancel)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            notificationManager.notify(STATUS_NOTIFICATION_ID, notification)
        }
    }

    /**
     * Show server instruction notification
     */
    private fun showInstructionNotification(instruction: ServerInstruction) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val title = if (instruction.sendPing) {
            "Server Instruction Received"
        } else {
            "Heartbeat Sent"
        }

        val message = if (instruction.sendPing) {
            "Ping ${instruction.host} (${instruction.protocol}) for ${instruction.durationSeconds}s"
        } else {
            "Waiting for server instructions..."
        }

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            notificationManager.notify(STATUS_NOTIFICATION_ID, notification)
        }
    }

    private fun updateOngoingNotification() {
        try {
            val notification = createOngoingNotification()
            notificationManager.notify(ONGOING_NOTIFICATION_ID, notification)
        } catch (se: SecurityException) {
            Log.e(TAG, "Notification permission not granted, cannot update ongoing notification", se)
        }
    }

    /**
     * Set callback for status updates
     */
    fun setStatusCallback(callback: (status: String, message: String) -> Unit) {
        statusCallback = callback
    }

    /**
     * Set callback for server instructions
     */
    fun setInstructionCallback(callback: (ServerInstruction) -> Unit) {
        onInstructionReceived = callback
    }

    /**
     * Set callback for location updates
     */
    fun setLocationCallback(callback: () -> String) {
        locationCallback = callback
    }

    /**
     * Get connection status
     */
    fun isConnected(): Boolean = isConnectedToServer

    /**
     * Get client ID
     */
    fun getClientId(): String? = clientId

    /**
     * Get failed upload queue size
     */
    fun getFailedUploadQueueSize(): Int = failedUploadQueue.size

    /**
     * Test server connection with ping
     */
    suspend fun pingServer(): ApiResponse {
        return withContext(Dispatchers.IO) {
            if (!isNetworkAvailable()) return@withContext ApiResponse.Error(-2, "No network")

            val url = URL("$SERVER_BASE_URL/ping")
            try {
                val connection = createConnection(url, "GET")
                val responseCode = connection.responseCode
                val response = if (responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                }
                connection.disconnect()

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    statusCallback?.invoke("server_reachable", "Server is reachable")
                    showServiceNotification("Server Status", "Server is reachable")
                    return@withContext ApiResponse.Success(JSONObject(response))
                } else {
                    statusCallback?.invoke("server_error", "Server error: $responseCode")
                    showServiceNotification("Server Error", "Server error: $responseCode")
                    return@withContext ApiResponse.Error(responseCode, response)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Ping failed: ${e.message}")
                return@withContext ApiResponse.Error(-1, "Network error: ${e.message}")
            }
        }
    }

    /**
     * Connect to server with device information
     */
    suspend fun connectToServer(location: String = "N/A"): ApiResponse {
        return withContext(Dispatchers.IO) {
            if (!isNetworkAvailable()) return@withContext ApiResponse.Error(-2, "No network")

            // Use location callback if available, otherwise use provided location
            val currentLocation = locationCallback?.invoke() ?: location
            val url = URL("$SERVER_BASE_URL/connect")

            try {
                statusCallback?.invoke("connecting", "Connecting to server...")
                val connection = createConnection(url, "POST")

                val deviceInfo = JSONObject().apply {
                    put("device_id", deviceId)
                    put("app_version", "2.0.0")
                    put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
                    put("android_version", Build.VERSION.RELEASE)
                    put("location", currentLocation)
                    put("timestamp", System.currentTimeMillis())
                }

                connection.outputStream.use { os ->
                    os.write(deviceInfo.toString().toByteArray())
                }

                val responseCode = connection.responseCode
                val response = if (responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                }
                connection.disconnect()

                Log.i(TAG, "🔗 Server connect response: $responseCode - $response")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val responseJson = JSONObject(response)
                    clientId = responseJson.optString("client_id")
                    isConnectedToServer = true
                    isInReconnectMode = false
                    lastLocation = currentLocation

                    Log.i(TAG, "Connected to server with client_id: $clientId")
                    statusCallback?.invoke("connected", "Connected to server successfully")

                    // Show connection notification and ensure foreground
                    showServiceNotification("Connected", "Successfully connected to server")
                    try { startForeground(ONGOING_NOTIFICATION_ID, createOngoingNotification()) } catch (_: Exception) {}

                    return@withContext ApiResponse.Success(responseJson)
                } else {
                    statusCallback?.invoke("connection_failed", "Connection failed: $responseCode")
                    showServiceNotification("Connection Failed", "Failed to connect: $responseCode")
                    return@withContext ApiResponse.Error(responseCode, response)
                }

            } catch (e: Exception) {
                Log.w(TAG, "Connect failed: ${e.message}")
                statusCallback?.invoke("connection_error", "Connection error: ${e.message}")
                showServiceNotification("Connection Error", "Connection error: ${e.message}")
                return@withContext ApiResponse.Error(-1, "Network error: ${e.message}")
            }
        }
    }

    /**
     * Disconnect from server
     */
    suspend fun disconnectFromServer(location: String = "N/A") {
        withContext(Dispatchers.IO) {
            try {
                if (isConnectedToServer) {
                    statusCallback?.invoke("disconnecting", "Disconnecting from server...")

                    // Stop heartbeat first
                    stopHeartbeat()

                    // Use location callback if available, otherwise use provided location
                    val currentLocation = locationCallback?.invoke() ?: location

                    // Send final heartbeat to notify server of disconnection
                    sendHeartbeatAndCheckInstructions("disconnected", currentLocation)

                    isConnectedToServer = false
                    isInReconnectMode = false
                    clientId = null

                    statusCallback?.invoke("disconnected", "Disconnected from server")
                    showServiceNotification("Disconnected", "Disconnected from server")

                    // Stop foreground service
                    stopForeground(true)

                    Log.i(TAG, "Disconnected from server")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during disconnection", e)
                statusCallback?.invoke("disconnect_error", "Disconnect error: ${e.message}")
                showServiceNotification("Disconnect Error", "Error: ${e.message}")
            }
        }
    }

    /**
     * Enter disconnected state due to heartbeat failure
     */
    private fun enterDisconnectedState() {
        isConnectedToServer = false
        isInReconnectMode = true
        clientId = null

        Log.w(TAG, "Entering disconnected state due to heartbeat failure")
        statusCallback?.invoke("disconnected", "Connection lost - entering reconnect mode")
        showServiceNotification("Connection Lost", "Attempting to reconnect...")
        updateOngoingNotification()
    }

    /**
     * Add failed upload to retry queue
     */
    private fun addToFailedUploadQueue(sessionData: PingSessionData) {
        if (failedUploadQueue.size >= MAX_FAILED_UPLOADS) {
            // Remove oldest item to prevent memory issues
            failedUploadQueue.poll()
            Log.w(TAG, "Failed upload queue full, removed oldest item")
        }

        val failedItem = FailedUploadItem(
            sessionData = sessionData,
            failedAttempts = 1,
            firstFailureTime = System.currentTimeMillis(),
            lastAttemptTime = System.currentTimeMillis()
        )

        failedUploadQueue.offer(failedItem)
        Log.i(TAG, "📥 Added failed upload to queue: ${sessionData.sessionId} (Queue size: ${failedUploadQueue.size})")
        updateOngoingNotification()
    }

    /**
     * Retry failed uploads before heartbeat
     */
    private suspend fun retryFailedUploads() {
        if (failedUploadQueue.isEmpty()) return

        val startSize = failedUploadQueue.size
        Log.i(TAG, "🔄 Starting failed upload retry process. Queue size: $startSize")

        val successfulUploads = mutableListOf<FailedUploadItem>()
        val failedRetries = mutableListOf<FailedUploadItem>()

        // Process all items in the queue
        while (failedUploadQueue.isNotEmpty()) {
            val failedItem = failedUploadQueue.poll() ?: break

            Log.d(TAG, "📤 Retrying upload for session: ${failedItem.sessionData.sessionId} (Attempt ${failedItem.failedAttempts + 1})")

            try {
                val result = uploadPingSessionInternal(failedItem.sessionData)

                when (result) {
                    is ApiResponse.Success -> {
                        successfulUploads.add(failedItem)
                        Log.i(TAG, "✅ Successfully reuploaded session: ${failedItem.sessionData.sessionId}")
                    }
                    is ApiResponse.Error -> {
                        // Update failure info and add back to retry list if not too many attempts
                        val updatedItem = failedItem.copy(
                            failedAttempts = failedItem.failedAttempts + 1,
                            lastAttemptTime = System.currentTimeMillis()
                        )

                        if (updatedItem.failedAttempts < 5) { // Max 5 attempts
                            failedRetries.add(updatedItem)
                            Log.w(TAG, "❌ Reupload failed for session: ${failedItem.sessionData.sessionId} (Attempt ${updatedItem.failedAttempts}/5) - Error: ${result.message}")
                        } else {
                            Log.e(TAG, "🗑️ Dropping session after 5 failed attempts: ${failedItem.sessionData.sessionId}")
                        }
                    }
                }
            } catch (e: Exception) {
                val updatedItem = failedItem.copy(
                    failedAttempts = failedItem.failedAttempts + 1,
                    lastAttemptTime = System.currentTimeMillis()
                )

                if (updatedItem.failedAttempts < 5) {
                    failedRetries.add(updatedItem)
                    Log.w(TAG, "❌ Exception during reupload for session: ${failedItem.sessionData.sessionId} - ${e.message}")
                } else {
                    Log.e(TAG, "🗑️ Dropping session after 5 failed attempts due to exceptions: ${failedItem.sessionData.sessionId}")
                }
            }
        }

        // Add failed retries back to queue
        failedRetries.forEach { failedUploadQueue.offer(it) }

        val endSize = failedUploadQueue.size
        val processedCount = startSize - endSize + successfulUploads.size

        Log.i(TAG, "🔄 Failed upload retry completed:")
        Log.i(TAG, "  • Processed: $processedCount items")
        Log.i(TAG, "  • Successful: ${successfulUploads.size}")
        Log.i(TAG, "  • Still pending: $endSize")

        if (successfulUploads.isNotEmpty()) {
            statusCallback?.invoke("reuploads_successful", "Successfully reuploaded ${successfulUploads.size} sessions")
            showServiceNotification("Reupload Success", "Successfully reuploaded ${successfulUploads.size} sessions")
        }

        if (endSize != startSize) {
            updateOngoingNotification()
        }
    }

    /**
     * Start automatic heartbeat every HEARTBEAT_INTERVAL_MS or reconnection attempts every RECONNECT_INTERVAL_MS
     */
    fun startHeartbeat(location: String = "N/A") {
        if (isHeartbeatActive) {
            Log.w(TAG, "Heartbeat already active, stopping existing one first")
            stopHeartbeat()
        }

        lastLocation = location
        isHeartbeatActive = true
        Log.i(TAG, "Starting heartbeat/reconnect system at ${dateFormat.format(Date())}")
        statusCallback?.invoke("heartbeat_started", "Heartbeat/reconnect system started")

        // Show heartbeat started notification and update ongoing notification
        showServiceNotification("System Started", "Heartbeat/reconnect system activated")
        updateOngoingNotification()

        heartbeatJob = serviceScope.launch {
            var heartbeatCount = 0L
            var delayUsed = 0L

            while (isActive && isHeartbeatActive) {
                try {
                    if (isConnectedToServer && !isInReconnectMode) {
                        // Before sending heartbeat, retry failed uploads
                        retryFailedUploads()

                        // Normal heartbeat mode
                        val nextHeartbeatDelay = HEARTBEAT_INTERVAL_MS - delayUsed
                        Log.d(TAG, "Connected mode: Waiting ${nextHeartbeatDelay / 1000}s until next heartbeat")

                        if (nextHeartbeatDelay > 0) {
                            delay(nextHeartbeatDelay)
                        }

                        if (isActive && isHeartbeatActive) {
                            delayUsed = sendSingleHeartbeat(lastLocation, (++heartbeatCount).toInt())
                        }
                    } else {
                        // Reconnection mode
                        Log.i(TAG, "Reconnection mode: Attempting to reconnect...")

                        val connectResult = connectToServer(lastLocation)
                        when (connectResult) {
                            is ApiResponse.Success -> {
                                Log.i(TAG, "🔄 Reconnection successful! Resuming normal heartbeat mode")
                                statusCallback?.invoke("reconnected", "Successfully reconnected to server")
                                showServiceNotification("Reconnected", "Successfully reconnected to server")
                                updateOngoingNotification()
                                delayUsed = 0L // Reset delay for next heartbeat cycle
                            }
                            is ApiResponse.Error -> {
                                Log.w(TAG, "🔄 Reconnection failed: ${connectResult.message}")
                                statusCallback?.invoke("reconnect_failed", "Reconnection failed: ${connectResult.message}")

                                // Wait for reconnection interval before trying again
                                Log.d(TAG, "Waiting ${RECONNECT_INTERVAL_MS / 1000 / 60} minutes before next reconnection attempt")
                                delay(RECONNECT_INTERVAL_MS)
                                delayUsed = 0L
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in heartbeat/reconnect loop", e)
                    statusCallback?.invoke("system_error", "System error: ${e.message}")
                    delay(RECONNECT_INTERVAL_MS) // Wait before retrying
                }
            }

            Log.i(TAG, "Heartbeat/reconnect coroutine finished")
        }
    }

    /**
     * Stop automatic heartbeat
     */
    fun stopHeartbeat() {
        val wasActive = isHeartbeatActive
        Log.i(TAG, "Stopping heartbeat/reconnect system at ${dateFormat.format(Date())} - was active: $wasActive")

        isHeartbeatActive = false
        isInReconnectMode = false
        heartbeatJob?.cancel()
        heartbeatJob = null

        if (wasActive) {
            statusCallback?.invoke("heartbeat_stopped", "Heartbeat/reconnect system stopped")
            showServiceNotification("System Stopped", "Heartbeat/reconnect system deactivated")
            updateOngoingNotification()
            Log.i(TAG, "Heartbeat/reconnect system stopped successfully")
        }
    }

    /**
     * Send a single heartbeat and check for instructions
     * Returns the delay used for executing instructions
     */
    private suspend fun sendSingleHeartbeat(location: String = "N/A", count: Int = 0): Long {
        val currentTime = dateFormat.format(Date())
        var delayUsed = 0L

        try {
            Log.d(TAG, "Sending heartbeat #$count at $currentTime")

            val response = sendHeartbeatAndCheckInstructions("connected", location)

            when (response) {
                is ApiResponse.Success -> {
                    val data = response.data
                    Log.i(TAG, "💓 Heartbeat #$count successful at $currentTime")

                    // Check for server instructions
                    if (data.optBoolean("send_ping", false)) {
                        val delayMs = data.optLong("delay_ms", 0)

                        val instruction = ServerInstruction(
                            sendPing = true,
                            host = data.getStringOrDefault("ping_host"),
                            protocol = data.getStringOrDefault("ping_protocol", "TCP"),
                            durationSeconds = data.optInt("ping_duration_seconds", 60),
                            intervalMs = data.optLong("ping_interval_ms", 1000L),
                            delay = delayMs
                        )

                        Log.i(TAG, "📋 Heartbeat #$count received server instruction: ${instruction.host} (${instruction.protocol}) for ${instruction.durationSeconds}s with ${delayMs}ms delay")

                        // Apply delay before executing instruction
                        if (delayMs > 0) {
                            Log.i(TAG, "⏰ Waiting ${delayMs}ms before executing instruction...")
                            delay(delayMs)
                            delayUsed = delayMs
                            Log.i(TAG, "✅ Delay completed, executing instruction now")
                        }

                        // Show notification for server instruction
                        showInstructionNotification(instruction)
                        onInstructionReceived?.invoke(instruction)
                    } else {
                        Log.d(TAG, "💓 Heartbeat #$count: No server instruction - standing by")
                        // Still notify that heartbeat was received but no instruction
                        val instruction = ServerInstruction(sendPing = false)
                        showInstructionNotification(instruction)
                        onInstructionReceived?.invoke(instruction)
                    }
                }
                is ApiResponse.Error -> {
                    Log.e(TAG, "❌ Heartbeat #$count failed at $currentTime: ${response.code} - ${response.message}")
                    statusCallback?.invoke("heartbeat_error", "Heartbeat failed: ${response.message}")
                    showServiceNotification("Heartbeat Error", "Failed: ${response.message}")

                    // If heartbeat fails, enter disconnected state
                    Log.w(TAG, "Heartbeat failure detected, entering disconnected state")
                    enterDisconnectedState()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during heartbeat #$count at $currentTime", e)
            statusCallback?.invoke("heartbeat_error", "Heartbeat error: ${e.message}")
            showServiceNotification("Heartbeat Error", "Error: ${e.message}")

            // If heartbeat encounters an exception (including timeout), enter disconnected state
            Log.w(TAG, "Heartbeat exception detected, entering disconnected state")
            enterDisconnectedState()
        }

        return delayUsed
    }

    /**
     * Send heartbeat to server with location and check for ping instructions
     */
    suspend fun sendHeartbeatAndCheckInstructions(appStatus: String = "connected", location: String = "N/A"): ApiResponse {
        return withContext(Dispatchers.IO) {
            if (!isNetworkAvailable()) return@withContext ApiResponse.Error(-2, "No network")

            val url = URL("$SERVER_BASE_URL/heartbeat")

            try {
                val connection = createConnection(url, "POST")

                val heartbeatData = JSONObject().apply {
                    put("device_id", deviceId)
                    put("client_id", clientId ?: "unknown")
                    put("app_status", appStatus)
                    put("location", location)
                    put("timestamp", System.currentTimeMillis())
                    put("request_instructions", true)
                    put("heartbeat_interval_ms", HEARTBEAT_INTERVAL_MS)
                }

                connection.outputStream.use { os ->
                    os.write(heartbeatData.toString().toByteArray())
                }

                val responseCode = connection.responseCode
                val response = if (responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                }
                connection.disconnect()

                Log.d(TAG, "Heartbeat response: $responseCode")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val responseJson = JSONObject(response)
                    if (responseJson.optBoolean("send_ping", false)) {
                        val delayMs = responseJson.optLong("delay_ms", 0)
                        Log.i(TAG, "📋 Server instruction in heartbeat: Ping ${responseJson.optString("ping_host")} (${responseJson.optString("ping_protocol")}) with ${delayMs}ms delay")
                    } else {
                        Log.d(TAG, "💓 Heartbeat response: No server instructions")
                    }
                    return@withContext ApiResponse.Success(JSONObject(response))
                } else {
                    return@withContext ApiResponse.Error(responseCode, response)
                }

            } catch (e: Exception) {
                Log.w(TAG, "Heartbeat failed: ${e.message}")
                return@withContext ApiResponse.Error(-1, "Network error: ${e.message}")
            }
        }
    }

    /**
     * Upload ping session data to server (public method with retry queue handling)
     */
    suspend fun uploadPingSession(sessionData: PingSessionData): ApiResponse {
        val result = uploadPingSessionInternal(sessionData)

        when (result) {
            is ApiResponse.Success -> {
                Log.i(TAG, "✅ Session uploaded successfully: ${sessionData.sessionId}")
                return result
            }
            is ApiResponse.Error -> {
                Log.w(TAG, "❌ Session upload failed: ${sessionData.sessionId} - ${result.message}")
                addToFailedUploadQueue(sessionData)
                statusCallback?.invoke("upload_queued", "Upload failed, added to retry queue")
                return result
            }
        }
    }

    /**
     * Internal method to upload ping session data to server (without retry queue handling)
     */
    private suspend fun uploadPingSessionInternal(sessionData: PingSessionData): ApiResponse {
        return withContext(Dispatchers.IO) {
            if (!isNetworkAvailable()) return@withContext ApiResponse.Error(-2, "No network")

            val url = URL("$SERVER_BASE_URL/upload-session")

            try {
                val connection = createConnection(url, "POST")

                val sessionJson = JSONObject().apply {
                    put("session_id", sessionData.sessionId)
                    put("client_id", clientId ?: "unknown")
                    put("host", sessionData.host)
                    put("protocol", sessionData.protocol)
                    put("start_time", sessionData.startTime)
                    put("end_time", sessionData.endTime)
                    put("duration_seconds", sessionData.durationSeconds)
                    put("packets_sent", sessionData.packetsSent)
                    put("packets_received", sessionData.packetsReceived)
                    put("packet_loss_percent", sessionData.packetLossPercent)
                    put("avg_rtt_ms", sessionData.avgRttMs)
                    put("min_rtt_ms", sessionData.minRttMs)
                    put("max_rtt_ms", sessionData.maxRttMs)
                    put("total_bytes", sessionData.totalBytes)
                    put("avg_bandwidth_bps", sessionData.avgBandwidthBps)
                    put("start_location", sessionData.startLocation)
                    put("end_location", sessionData.endLocation)
                    put("server_instructed", sessionData.sessionId.contains("server_session"))

                    val settingsJson = JSONObject().apply {
                        put("packet_size", sessionData.settings.packetSize)
                        put("timeout", sessionData.settings.timeout)
                        put("interval", sessionData.settings.interval)
                        put("tcp_port", sessionData.settings.tcpPort)
                        put("udp_port", sessionData.settings.udpPort)
                    }
                    put("settings", settingsJson)

                    if (sessionData.pingResults.isNotEmpty()) {
                        val resultsArray = JSONArray()
                        sessionData.pingResults.forEach { result ->
                            val resultJson = JSONObject().apply {
                                put("timestamp", result.timestamp)
                                put("sequence", result.sequence)
                                put("success", result.success)
                                put("rtt_ms", result.rttMs)
                                put("location", result.location)
                                put("error_message", result.errorMessage)
                            }
                            resultsArray.put(resultJson)
                        }
                        put("ping_results", resultsArray)
                    }
                }

                connection.outputStream.use { os: OutputStream ->
                    os.write(sessionJson.toString().toByteArray())
                }

                val responseCode = connection.responseCode
                val response = if (responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                }
                connection.disconnect()

                Log.i(TAG, "📤 Session upload response: $responseCode")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    return@withContext ApiResponse.Success(JSONObject(response))
                } else {
                    return@withContext ApiResponse.Error(responseCode, response)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Upload failed: ${e.message}")
                return@withContext ApiResponse.Error(-1, "Network error: ${e.message}")
            }
        }
    }

    /**
     * Check if heartbeat is currently active
     */
    fun isHeartbeatRunning(): Boolean = isHeartbeatActive && heartbeatJob?.isActive == true

    /**
     * Get heartbeat interval in milliseconds
     */
    fun getHeartbeatIntervalMs(): Long = HEARTBEAT_INTERVAL_MS

    /**
     * Get reconnect interval in milliseconds
     */
    fun getReconnectIntervalMs(): Long = RECONNECT_INTERVAL_MS

    /**
     * Check if currently in reconnect mode
     */
    fun isInReconnectMode(): Boolean = isInReconnectMode

    /**
     * Get heartbeat status for debugging
     */
    fun getHeartbeatStatus(): String {
        return "Heartbeat active: $isHeartbeatActive, Job active: ${heartbeatJob?.isActive}, Interval: ${HEARTBEAT_INTERVAL_MS / 1000 / 60} minutes, Connected: $isConnectedToServer, Failed uploads: ${failedUploadQueue.size}"
    }

    /**
     * Clear all failed uploads (for debugging/testing)
     */
    fun clearFailedUploadQueue() {
        val clearedCount = failedUploadQueue.size
        failedUploadQueue.clear()
        Log.i(TAG, "Cleared $clearedCount failed uploads from queue")
        updateOngoingNotification()
    }

    /**
     * Get failed upload queue status
     */
    fun getFailedUploadStatus(): String {
        if (failedUploadQueue.isEmpty()) return "No failed uploads"

        val oldestItem = failedUploadQueue.peek()
        val oldestTime = oldestItem?.firstFailureTime ?: 0L
        val timeSinceOldest = (System.currentTimeMillis() - oldestTime) / 1000 / 60 // minutes

        return "Failed uploads: ${failedUploadQueue.size}, Oldest: ${timeSinceOldest}min ago"
    }

    /**
     * Create HTTP connection with proper headers
     */
    private fun createConnection(url: URL, method: String): HttpURLConnection {
        val connection = url.openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "PingApp-Android-Service/2.0")

            if (method == "POST") {
                doOutput = true
            }
        }
        return connection
    }

    /**
     * Simple network availability check
     */
    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

/**
 * Data class for tracking failed upload attempts
 */
data class FailedUploadItem(
    val sessionData: PingSessionData,
    val failedAttempts: Int,
    val firstFailureTime: Long,
    val lastAttemptTime: Long
)

/**
 * Data classes for ping session data
 */
data class PingSessionData(
    val sessionId: String,
    val host: String,
    val protocol: String,
    val startTime: String,
    val endTime: String,
    val durationSeconds: Long,
    val packetsSent: Int,
    val packetsReceived: Int,
    val packetLossPercent: Double,
    val avgRttMs: Double = 0.0,
    val minRttMs: Double = 0.0,
    val maxRttMs: Double = 0.0,
    val totalBytes: Long = 0,
    val avgBandwidthBps: Double = 0.0,
    val startLocation: String = "N/A",
    val endLocation: String = "N/A",
    val settings: PingSettings,
    val pingResults: List<PingResult> = emptyList()
)

data class PingSettings(
    val packetSize: Int,
    val timeout: Int,
    val interval: Long,
    val tcpPort: Int,
    val udpPort: Int
)

data class PingResult(
    val timestamp: String,
    val sequence: Int,
    val success: Boolean,
    val rttMs: Double,
    val location: String = "N/A",
    val errorMessage: String = ""
)

data class ServerInstruction(
    val sendPing: Boolean,
    val host: String = "",
    val protocol: String = "ICMP",
    val durationSeconds: Int = 60,
    val intervalMs: Long = 1000L,
    val delay: Long = 0L,
)

sealed class ApiResponse {
    data class Success(val data: JSONObject) : ApiResponse()
    data class Error(val code: Int, val message: String) : ApiResponse()
}

fun JSONObject.getStringOrDefault(key: String, default: String = ""): String {
    return if (has(key)) getString(key) else default
}