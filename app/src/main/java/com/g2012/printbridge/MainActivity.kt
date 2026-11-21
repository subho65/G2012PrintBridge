package com.g2012.printbridge

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private val ACTION_USB_PERMISSION = "com.g2012.printbridge.USB_PERMISSION"
    private lateinit var usbManager: UsbManager
    private lateinit var ipText: TextView
    private lateinit var statusText: TextView
    private lateinit var toggleBtn: Button

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        startBridgeService()
                    } else {
                        statusText.text = "USB permission denied. Please tap Start and allow USB access."
                    }
                }
                PrintBridgeService.ACTION_STATUS -> {
                    val msg = intent.getStringExtra(PrintBridgeService.EXTRA_STATUS) ?: return
                    statusText.text = msg
                    updateUiState()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "Canon G2012 Wi-Fi Bridge"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1565C0"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

        ipText = TextView(this).apply {
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#E3F2FD"))
        }

        statusText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.DKGRAY)
            setPadding(0, 32, 0, 32)
            text = "1. Connect spare phone to Wi-Fi or Main Phone Hotspot.\n" +
                   "2. Turn ON Canon G2012 & plug in via OTG.\n" +
                   "3. Tap Start Print Server below."
        }

        toggleBtn = Button(this).apply {
            textSize = 18f
            setPadding(24, 32, 24, 32)
            setOnClickListener {
                if (PrintBridgeService.isRunning) {
                    stopService(Intent(this@MainActivity, PrintBridgeService::class.java))
                    updateUiState()
                } else {
                    requestUsbAndStart()
                }
            }
        }

        val refreshBtn = Button(this).apply {
            text = "Refresh IP Address"
            setOnClickListener { refreshIpDisplay() }
        }

        layout.addView(title)
        layout.addView(ipText)
        layout.addView(statusText)
        layout.addView(toggleBtn)
        layout.addView(refreshBtn)
        setContentView(layout)

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(PrintBridgeService.ACTION_STATUS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        refreshIpDisplay()
        updateUiState()

        // If launched automatically by plugging in the USB printer, start right away
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            requestUsbAndStart()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && !PrintBridgeService.isRunning) {
            requestUsbAndStart()
        }
    }

    private fun requestUsbAndStart() {
        refreshIpDisplay()
        val printer = findPrinter()
        if (printer == null) {
            statusText.text = "No USB printer detected!\nMake sure Canon G2012 is powered ON and OTG cable is plugged in (enable OTG in phone Settings if needed)."
            return
        }

        if (usbManager.hasPermission(printer)) {
            startBridgeService()
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(printer, permissionIntent)
        }
    }

    private fun startBridgeService() {
        val serviceIntent = Intent(this, PrintBridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        statusText.text = "Starting server..."
        updateUiState()
    }

    private fun findPrinter(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { device ->
            device.vendorId == 0x04A9 || (0 until device.interfaceCount).any { idx ->
                device.getInterface(idx).interfaceClass == UsbConstants.USB_CLASS_PRINTER
            }
        }
    }

    private fun refreshIpDisplay() {
        val ip = getLocalIpAddress()
        ipText.text = "IP: $ip\nPort: ${PrintBridgeService.PORT}"
    }

    private fun updateUiState() {
        refreshIpDisplay()
        if (PrintBridgeService.isRunning) {
            toggleBtn.text = "Stop Print Server"
            toggleBtn.setBackgroundColor(Color.parseColor("#C62828"))
            toggleBtn.setTextColor(Color.WHITE)
            statusText.text = PrintBridgeService.lastStatus
        } else {
            toggleBtn.text = "Start Print Server"
            toggleBtn.setBackgroundColor(Color.parseColor("#2E7D32"))
            toggleBtn.setTextColor(Color.WHITE)
        }
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
        return "Not connected to Wi-Fi"
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
    }
}
