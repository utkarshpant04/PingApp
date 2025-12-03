package com.example.myfirstapp

object Constants {

    // Ping defaults
    const val DEFAULT_TIMEOUT_MS = 1000
    const val DEFAULT_PING_INTERVAL_MS = 100L

    // Listener ports
    const val PING_LISTENER_PORT = 50002
    const val SERVER_UDP_PORT = 50003


    // Heartbeat system defaults
    const val HEARTBEAT_INTERVAL_MS = 60 * 60 * 1000L   // 1 hr
    const val RECONNECT_INTERVAL_MS = 1 * 60 * 1000L    // 1 min
    const val MAX_FAILED_UPLOADS = 5000

    // API config
    const val SERVER_BASE_URL = "https://dragon.wag.org.in:12345/api"
    const val CONNECT_TIMEOUT = 30000
    const val READ_TIMEOUT = 30000
}
