package com.example.myfirstapp

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object RestApiClient {

    private const val TAG = "RestApiClient"
    private const val SERVER_BASE_URL = "http://170.187.252.25:8080"
    private const val CONNECT_TIMEOUT = 10000
    private const val READ_TIMEOUT = 15000

    /**
     * Data class to represent the result of an API call.
     */
    sealed class Result {
        data class Success(val data: JSONObject) : Result()
        data class Error(val code: Int, val message: String) : Result()
    }

    /**
     * Performs a generic HTTP request.
     *
     * @param endpoint The API endpoint (e.g., "/connect").
     * @param method The HTTP method ("GET", "POST", etc.).
     * @param payload The JSON payload to send with the request (for "POST", "PUT").
     * @return A [Result] object representing the outcome of the API call.
     */
    fun request(endpoint: String, method: String, payload: JSONObject? = null): Result {
        val url = URL("$SERVER_BASE_URL$endpoint")
        var connection: HttpURLConnection? = null

        try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")

                if (method == "POST" || method == "PUT") {
                    doOutput = true
                    payload?.let {
                        val outputStream = OutputStreamWriter(outputStream)
                        outputStream.write(it.toString())
                        outputStream.flush()
                    }
                }
            }

            val responseCode = connection.responseCode
            Log.d(TAG, "Response Code: $responseCode for $method $endpoint")

            val reader = BufferedReader(
                InputStreamReader(
                    if (responseCode in 200..299) connection.inputStream else connection.errorStream
                )
            )
            val response = reader.readText()
            Log.d(TAG, "Response: $response")

            return if (responseCode in 200..299) {
                Result.Success(JSONObject(response))
            } else {
                Result.Error(responseCode, response)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during API call to $endpoint", e)
            return Result.Error(-1, e.message ?: "Unknown error")
        } finally {
            connection?.disconnect()
        }
    }
}
