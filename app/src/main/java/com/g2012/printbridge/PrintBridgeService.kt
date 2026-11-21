package com.g2012.printbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import kotlin.concurrent.thread

class PrintBridgeService : Service() {

    companion object {
        const val CHANNEL_ID = "G2012PrintBridgeChannel"
        const val NOTIF_ID = 101
        const val PORT = 9100
        const val ACTION_STATUS = "com.g2012.printbridge.STATUS_UPDATE"
        const val EXTRA_STATUS = "status_text"
        @Volatile var isRunning = false
        @Volatile var lastStatus = "Stopped"
    }

    private var serverSocket: ServerSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) {
            broadcastStatus(lastStatus)
            return START_STICKY
        }

        createNotificationChannel()
        val ip = getLocalIpAddress()
        val notif = buildNotification("Ready on $ip:$PORT")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notif,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    0
                }
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }

        acquireLocks()
        isRunning = true
        startServerThread()
        return START_STICKY
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "G2012PrintBridge::WakeLock")
        wakeLock?.acquire()

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wm.createWifiLock(lockMode, "G2012PrintBridge::WifiLock")
        wifiLock?.acquire()
    }

    private fun startServerThread() {
        thread(name = "PrintServerThread") {
            try {
                serverSocket = ServerSocket(PORT)
                val ip = getLocalIpAddress()
                updateStatus("READY! Waiting for print jobs...\n\nIP Address: $ip\nPort: $PORT")

                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    try {
                        updateStatus("Receiving print job from ${client.inetAddress.hostAddress}...")
                        val totalBytes = forwardToUsbPrinter(client.getInputStream())
                        client.close()
                        val kb = totalBytes / 1024
                        val currentIp = getLocalIpAddress()
                        if (totalBytes > 0) {
                            updateStatus("Printed successfully ($kb KB)!\n\nREADY on $currentIp:$PORT")
                        } else {
                            updateStatus("Connected but printer not ready or 0 bytes sent.\n\nREADY on $currentIp:$PORT")
                        }
                    } catch (e: Exception) {
                        try { client.close() } catch (_: Exception) {}
                        updateStatus("Print error: ${e.message}\n\nREADY on ${getLocalIpAddress()}:$PORT")
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    updateStatus("Server error: ${e.message}")
                }
            }
        }
    }

    private fun findPrinter(usbManager: UsbManager): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { device ->
            device.vendorId == 0x04A9 || (0 until device.interfaceCount).any { idx ->
                device.getInterface(idx).interfaceClass == UsbConstants.USB_CLASS_PRINTER
            }
        }
    }

    private fun forwardToUsbPrinter(inputStream: InputStream): Long {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val device = findPrinter(usbManager)
            ?: throw IllegalStateException("Canon G2012 not detected over USB OTG.")

        if (!usbManager.hasPermission(device)) {
            throw IllegalStateException("USB permission missing. Open app and tap Start again.")
        }

        var usbInterface: UsbInterface? = null
        var outEndpoint: UsbEndpoint? = null

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                for (j in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.direction == UsbConstants.USB_DIR_OUT
                    ) {
                        usbInterface = intf
                        outEndpoint = ep
                        break
                    }
                }
            }
            if (outEndpoint != null) break
        }

        if (usbInterface == null || outEndpoint == null) {
            throw IllegalStateException("USB Printer Bulk OUT endpoint not found.")
        }

        val connection = usbManager.openDevice(device)
            ?: throw IllegalStateException("Failed to open USB connection to printer.")

        var totalBytes = 0L
        try {
            if (!connection.claimInterface(usbInterface, true)) {
                throw IllegalStateException("Could not claim USB printer interface.")
            }

            val buffer = ByteArray(16384)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (bytesRead > 0) {
                    var offset = 0
                    while (offset < bytesRead) {
                        val chunkLen = bytesRead - offset
                        val chunk = if (offset == 0 && chunkLen == buffer.size) {
                            buffer
                        } else {
                            buffer.copyOfRange(offset, bytesRead)
                        }
                        val sent = connection.bulkTransfer(outEndpoint, chunk, chunk.size, 15000)
                        if (sent < 0) {
                            throw IllegalStateException("USB transfer failed (check cable/printer power).")
                        }
                        offset += sent
                        totalBytes += sent
                    }
                }
            }
        } finally {
            try { connection.releaseInterface(usbInterface) } catch (_: Exception) {}
            try { connection.close() } catch (_: Exception) {}
        }
        return totalBytes
    }

    private fun updateStatus(msg: String) {
        lastStatus = msg
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(msg.lineSequence().firstOrNull() ?: "Running"))
        broadcastStatus(msg)
    }

    private fun broadcastStatus(msg: String) {
        val intent = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, msg)
        }
        sendBroadcast(intent)
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (!ip.startsWith("127.")) return ip
                    }
                }
            }
        } catch (_: Exception) {}
        return "Connect to Wi-Fi/Hotspot"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "G2012 Print Server",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Canon G2012 Wi-Fi Print Bridge")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_printer)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        lastStatus = "Server stopped."
        broadcastStatus(lastStatus)
        super.onDestroy()
    }
}
