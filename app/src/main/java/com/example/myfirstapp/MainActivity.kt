package com.example.myfirstapp

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.*
import kotlin.system.measureTimeMillis

class MainActivity : AppCompatActivity() {

    private lateinit var etHost: EditText
    private lateinit var spProtocol: Spinner
    private lateinit var etInterval: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvLog: TextView

    // Configurable defaults
    private var packetSize = 32
    private var timeout = 1000
    private var tcpPort = 80
    private var udpPort = 5001

    private var pingJob: Job? = null
    private var packetsSent = 0
    private var packetsReceived = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: androidx.appcompat.widget.Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "Ping App"

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
            .setTitle("Settings")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                packetSize = etPacketSize.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: packetSize
                timeout = etTimeout.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: timeout
                tcpPort = etTcpPort.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: tcpPort
                udpPort = etUdpPort.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: udpPort
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun log(message: String) {
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

    private fun startPinging() {
        val host = etHost.text.toString().trim()
        val interval = etInterval.text.toString().toLongOrNull() ?: 1000L
        val protocol = spProtocol.selectedItem.toString()

        if (host.isEmpty()) {
            Toast.makeText(this, "Enter a host", Toast.LENGTH_SHORT).show()
            return
        }

        packetsSent = 0
        packetsReceived = 0
        tvLog.text = ""

        pingJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                packetsSent++
                val success: Boolean
                val rtt = measureTimeMillis {
                    success = when (protocol) {
                        "ICMP" -> icmpPingCmd(host) // more reliable
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
                delay(interval)
            }
        }
    }

    private fun stopPinging() {
        lifecycleScope.launch {
            pingJob?.cancelAndJoin() // wait for job to finish
            val loss = if (packetsSent > 0)
                ((packetsSent - packetsReceived) * 100) / packetsSent
            else 0
            log("Ping stopped. Packet loss: $loss%")
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
