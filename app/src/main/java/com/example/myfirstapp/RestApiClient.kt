// Fixed RestApiClient.kt with proper 5-minute heartbeat interval and improved logging

package com.example.myfirstapp

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class RestApiClient(private val context: Context) {

    companion object {
        private const val TAG = "RestApiClient"
        private const val SERVER_BASE_URL = "http://10.0.2.2:8080/api" // Change to your server IP
        private const val CONNECT_TIMEOUT = 10000
        private const val READ_TIMEOUT = 15000
        private const val HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes in milliseconds
    }

    private val deviceId: String by lazy {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    private var clientId: String? = null
    private var heartbeatJob: Job? = null
    private var isHeartbeatActive = false
    private var onInstructionReceived: ((ServerInstruction) -> Unit)? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /**
     * Test server connection with ping
     */
    suspend fun pingServer(): ApiResponse {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$SERVER_BASE_URL/ping")
                val connection = createConnection(url, "GET")

                val responseCode = connection.responseCode
                val response = if (responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                }

                Log.i(TAG, " Server ping response: $responseCode - $response")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    ApiResponse.Success(JSONObject(response))
                } else {
                    ApiResponse.Error(responseCode, response)
                }

            } catch (e: Exception) {
                Log.e(TAG, " Server ping failed", e)
                ApiResponse.Error(-1, "Network error: ${e.message}")
            }
        }
    }

    /**
     * Connect to server with device information and get client ID
     */
    suspend fun connectToServer(location: String = "N/A"): ApiResponse {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$SERVER_BASE_URL/connect")
                val connection = createConnection(url, "POST")

                // Prepare device info JSON with location
                val deviceInfo = JSONObject().apply {
                    put("device_id", deviceId)
                    put("app_version", "2.0.0")
                    put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
                    put("android_version", Build.VERSION.RELEASE)
                    put("location", location)
                    put("timestamp", System.currentTimeMillis())
                }

                // Send JSON data
                connection.outputStream.use { os ->
                    os.write(deviceInfo.toString().toByteArray())
                }

                val responseCode = connection.responseCode
                val response = if (responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                }

                Log.i(TAG, "🔗 Server connect response: $responseCode - $response")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val responseJson = JSONObject(response)
                    clientId = responseJson.optString("client_id")
                    Log.i(TAG, "Connected to server with client_id: $clientId")
                    ApiResponse.Success(responseJson)
                } else {
                    ApiResponse.Error(responseCode, response)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Server connection failed", e)
                ApiResponse.Error(-1, "Network error: ${e.message}")
            }
        }
    }

    /**
     * Start automatic heartbeat every 5 minutes
     */
    fun startHeartbeat(
        scope: CoroutineScope,
        location: String = "N/A",
        onInstruction: ((ServerInstruction) -> Unit)? = null
    ) {
        if (isHeartbeatActive) {
            Log.w(TAG, "Heartbeat already active, stopping existing one first")
            stopHeartbeat()
        }

        onInstructionReceived = onInstruction
        isHeartbeatActive = true

        Log.i(TAG, "Starting 5-minute heartbeat system at ${dateFormat.format(Date())}")

        heartbeatJob = scope.launch {
            var heartbeatCount = 0

            // Send immediate first heartbeat
            sendSingleHeartbeat(location, ++heartbeatCount)

            while (isActive && isHeartbeatActive) {
                Log.d(TAG, " Waiting 5 minutes until next heartbeat...")
                delay(HEARTBEAT_INTERVAL_MS)

                if (isActive && isHeartbeatActive) {
                    sendSingleHeartbeat(location, ++heartbeatCount)
                }
            }

            Log.i(TAG, "Heartbeat coroutine finished")
        }
    }

    /**
     * Stop automatic heartbeat
     */
    fun stopHeartbeat() {
        val wasActive = isHeartbeatActive
        Log.i(TAG, " Stopping heartbeat at ${dateFormat.format(Date())} - was active: $wasActive")

        isHeartbeatActive = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        onInstructionReceived = null

        if (wasActive) {
            Log.i(TAG, "Heartbeat system stopped successfully")
        }
    }

    /**
     * Send a single heartbeat and check for instructions
     */
    private suspend fun sendSingleHeartbeat(location: String = "N/A", count: Int = 0) {
        val currentTime = dateFormat.format(Date())

        try {
            Log.d(TAG, "Sending heartbeat #$count at $currentTime")

            val response = sendHeartbeatAndCheckInstructions("connected", location)

            when (response) {
                is ApiResponse.Success -> {
                    val data = response.data
                    Log.i(TAG, "Heartbeat #$count successful at $currentTime")

                    // Check for server instructions
                    if (data.optBoolean("send_ping", false)) {
                        val instruction = ServerInstruction(
                            sendPing = true,
                            host = data.getStringOrDefault("ping_host"),
                            protocol = data.getStringOrDefault("ping_protocol", "TCP"),
                            durationSeconds = data.optInt("ping_duration_seconds", 60),
                            intervalMs = data.optLong("ping_interval_ms", 1000L)
                        )

                        Log.i(TAG, " Heartbeat #$count received server instruction: ${instruction.host} (${instruction.protocol}) for ${instruction.durationSeconds}s")
                        onInstructionReceived?.invoke(instruction)
                    } else {
                        Log.d(TAG, " Heartbeat #$count: No server instruction - standing by")
                        // Still notify that heartbeat was received but no instruction
                        onInstructionReceived?.invoke(ServerInstruction(sendPing = false))
                    }
                }
                is ApiResponse.Error -> {
                    Log.e(TAG, "Heartbeat #$count failed at $currentTime: ${response.code} - ${response.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, " Error during heartbeat #$count at $currentTime", e)
        }
    }

    /**
     * Send heartbeat to server with location and check for ping instructions
     */
    suspend fun sendHeartbeatAndCheckInstructions(appStatus: String = "connected", location: String = "N/A"): ApiResponse {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$SERVER_BASE_URL/heartbeat")
                val connection = createConnection(url, "POST")

                val heartbeatData = JSONObject().apply {
                    put("device_id", deviceId)
                    put("client_id", clientId ?: "unknown")
                    put("app_status", appStatus)
                    put("location", location)
                    put("timestamp", System.currentTimeMillis())
                    put("request_instructions", true) // Request server instructions
                    put("heartbeat_interval_ms", HEARTBEAT_INTERVAL_MS) // Inform server of our heartbeat interval
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

                Log.d(TAG, " Heartbeat response: $responseCode")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val responseJson = JSONObject(response)
                    // Log server instructions if present
                    if (responseJson.optBoolean("send_ping", false)) {
                        Log.i(TAG, " Server instruction in heartbeat: Ping ${responseJson.optString("ping_host")} (${responseJson.optString("ping_protocol")})")
                    } else {
                        Log.d(TAG, " Heartbeat response: No server instructions")
                    }
                    ApiResponse.Success(responseJson)
                } else {
                    ApiResponse.Error(responseCode, response)
                }

            } catch (e: Exception) {
                Log.e(TAG, " Heartbeat request failed", e)
                ApiResponse.Error(-1, "Network error: ${e.message}")
            }
        }
    }

    /**
     * Upload ping session data to server with location information (for server-instructed pings)
     */
    suspend fun uploadPingSession(sessionData: PingSessionData): ApiResponse {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$SERVER_BASE_URL/upload-session")
                val connection = createConnection(url, "POST")

                // Prepare session data JSON with location and server instruction flag
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
                    put("server_instructed", sessionData.sessionId.contains("server_session")) // Flag for server-instructed pings

                    // Settings used
                    val settingsJson = JSONObject().apply {
                        put("packet_size", sessionData.settings.packetSize)
                        put("timeout", sessionData.settings.timeout)
                        put("interval", sessionData.settings.interval)
                        put("tcp_port", sessionData.settings.tcpPort)
                        put("udp_port", sessionData.settings.udpPort)
                    }
                    put("settings", settingsJson)

                    // Individual ping results (optional, for detailed analysis)
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

                // Send JSON data
                connection.outputStream.use { os ->
                    os.write(sessionJson.toString().toByteArray())
                }

                val responseCode = connection.responseCode
                val response = if (responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                }

                Log.i(TAG, "Session upload response: $responseCode")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    ApiResponse.Success(JSONObject(response))
                } else {
                    ApiResponse.Error(responseCode, response)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Session upload failed", e)
                ApiResponse.Error(-1, "Network error: ${e.message}")
            }
        }
    }

    /**
     * Get client ID (must connect first)
     */
    fun getClientId(): String? = clientId

    /**
     * Check if heartbeat is currently active
     */
    fun isHeartbeatRunning(): Boolean = isHeartbeatActive && heartbeatJob?.isActive == true

    /**
     * Get heartbeat interval in milliseconds
     */
    fun getHeartbeatIntervalMs(): Long = HEARTBEAT_INTERVAL_MS

    /**
     * Get heartbeat status for debugging
     */
    fun getHeartbeatStatus(): String {
        return "Heartbeat active: $isHeartbeatActive, Job active: ${heartbeatJob?.isActive}, Interval: ${HEARTBEAT_INTERVAL_MS / 1000 / 60} minutes"
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
            setRequestProperty("User-Agent", "PingApp-Android-5MinHeartbeat/2.0")

            if (method == "POST") {
                doOutput = true
            }
        }
        return connection
    }
}

/**
 * Data classes for ping session information with location data and server instruction support
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

/**
 * Server instruction data class
 */
data class ServerInstruction(
    val sendPing: Boolean,
    val host: String = "",
    val protocol: String = "ICMP",
    val durationSeconds: Int = 60,
    val intervalMs: Long = 1000L
)

/**
 * API Response sealed class
 */
sealed class ApiResponse {
    data class Success(val data: JSONObject) : ApiResponse()
    data class Error(val code: Int, val message: String) : ApiResponse()
}

/**
 * Extension function to safely get string from JSONObject
 */
fun JSONObject.getStringOrDefault(key: String, default: String = ""): String {
    return if (has(key)) getString(key) else default
}