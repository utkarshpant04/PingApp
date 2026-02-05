package com.example.myfirstapp

import android.content.Context
import java.io.File
import java.io.FileWriter

/**
 * Simple file logger that persists logs to internal storage
 */
object FileLogger {
    private const val LOG_FILE_NAME = "app_logs.txt"
    private lateinit var logFile: File
    
    fun initialize(context: Context) {
        logFile = File(context.filesDir, LOG_FILE_NAME)
        if (!logFile.exists()) {
            logFile.createNewFile()
        }
    }
    
    fun appendLog(message: String) {
        try {
            FileWriter(logFile, true).use { writer ->
                writer.append(message)
                writer.append("\n")
            }
        } catch (e: Exception) {
            // Silently fail to avoid breaking the app
        }
    }
    
    fun readAllLogs(): String {
        return try {
            if (logFile.exists()) {
                logFile.readText()
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }
    
    fun clearLogs() {
        try {
            if (logFile.exists()) {
                logFile.writeText("")
            }
        } catch (e: Exception) {
            // Silently fail
        }
    }
}
