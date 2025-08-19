// Updated RestApiClient.kt with data collection and upload

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
import java.util.*

class RestApiClient(private val context: Context) {

    companion object {
        private const val TAG = "RestApiClient"
        private const val SERVER_BASE_URL = "http://10.0.2.2:8080/api" // Change to your server IP
        private const val CONNECT_TIMEOUT = 10000
        private const val READ_TIMEOUT = 15000
    }

    private val deviceId: String by lazy {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    private var clientId: String? = null

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

                Log.i(TAG, "Ping response: $responseCode - $response")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    ApiResponse.Success(JSONObject(response))
                } else {
                    ApiResponse.Error(responseCode, response)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Ping failed", e)
                ApiResponse.Error(-1, "Network error: ${e.message}")
            }
        }
    }

    /**
     * Connect to server with device information and get client ID
     */
    suspend fun connectToServer(): ApiResponse {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$SERVER_BASE_URL/connect")
                val connection = createConnection(url, "POST")

                // Prepare device info JSON
                val deviceInfo = JSONObject().apply {
                    put("device_id", deviceId)
                    put("app_version", "1.0.0")
                    put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
                    put("android_version", Build.VERSION.RELEASE)
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

                Log.i(TAG, "Connect response: $responseCode - $response")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val responseJson = JSONObject(response)
                    clientId = responseJson.optString("client_id")
                    ApiResponse.Success(responseJson)
                } else {
                    ApiResponse.Error(responseCode, response)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                ApiResponse.Error(-1, "Network error: ${e.message}")
            }
        }
    }

    /**
     * Send heartbeat to server
     */
    suspend fun sendHeartbeat(appStatus: String = "running"): ApiResponse {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$SERVER_BASE_URL/heartbeat")
                val connection = createConnection(url, "POST")

                val heartbeatData = JSONObject().apply {
                    put("device_id", deviceId)
                    put("client_id", clientId)
                    put("app_status", appStatus)
                    put("timestamp", System.currentTimeMillis())
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

                Log.i(TAG, "Heartbeat response: $responseCode")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    ApiResponse.Success(JSONObject(response))
                } else {
                    ApiResponse.Error(responseCode, response)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Heartbeat failed", e)
                ApiResponse.Error(-1, "Network error: ${e.message}")
            }
        }
    }

    /**
     * Upload ping session data to server
     */
    suspend fun uploadPingSession(sessionData: PingSessionData): ApiResponse {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$SERVER_BASE_URL/upload-session")
                val connection = createConnection(url, "POST")

                // Prepare session data JSON
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
            setRequestProperty("User-Agent", "PingApp-Android/1.0")

            if (method == "POST") {
                doOutput = true
            }
        }
        return connection
    }
}

/**
 * Data classes for ping session information
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
    val errorMessage: String = ""
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