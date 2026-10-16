package com.usblan

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        private const val ACTION_PERM = "com.usblan.USB_PERMISSION"
        private const val GOOGLE_VID = 0x18D1
        private const val AOA_PID_2D00 = 0x2D00
        private const val AOA_PID_2D01 = 0x2D01
        private const val REQ_GET_PROTOCOL = 51
        private const val REQ_SEND_STRING = 52
        private const val REQ_START = 53
        private const val REQ_VPN_CONSENT = 42
        private const val UI_UPDATE_MS = 400L
    }

    private lateinit var usbManager: UsbManager
    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private lateinit var statsView: TextView
    private lateinit var rootView: LinearLayout
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var link: Link? = null
    @Volatile private var role = Role.NONE
    @Volatile private var handshakeDone = false
    private val aoaAttempted = mutableSetOf<String>()
    private lateinit var permissionPi: PendingIntent

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PERM -> {
                    intent.usbAccessoryExtra()?.let(::onAccessoryAttached)
                    intent.usbDeviceExtra()?.let(::onDeviceAttached)
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                    intent.usbDeviceExtra()?.let(::onDeviceAttached)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> onUsbGone()
                UsbManager.ACTION_USB_ACCESSORY_ATTACHED ->
                    intent.usbAccessoryExtra()?.let(::onAccessoryAttached)
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> onUsbGone()
            }
        }
    }

    // ---------------------------------------------------------------- UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        buildUi()
        permissionPi = PendingIntent.getBroadcast(
            this, 0,
            Intent(ACTION_PERM).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )

        val filter = IntentFilter().apply {
            addAction(ACTION_PERM)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        handler.post(uiLoop)
    }

    private fun buildUi() {
        val pad = (resources.displayMetrics.density * 20).toInt()
        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }
        val title = TextView(this).apply {
            text = "USB LAN"
            textSize = 14f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.15f
            gravity = Gravity.CENTER
        }
        statusView = TextView(this).apply {
            textSize = 24f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, 0)
        }
        detailView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#757575"))
            gravity = Gravity.CENTER
            setLineSpacing(6f, 1f)
            setPadding(0, pad / 2, 0, 0)
        }
        statsView = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#9E9E9E"))
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, 0)
        }
        rootView.addView(title)
        rootView.addView(statusView)
        rootView.addView(detailView)
        rootView.addView(statsView)
        rootView.setOnClickListener { onRootTap() }
        setContentView(rootView)
    }

    private val uiLoop = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, UI_UPDATE_MS)
        }
    }

    private fun render() {
        val l = link
        when {
            l == null -> {
                statusView.text = "No link"
                statusView.setTextColor(Color.parseColor("#9E9E9E"))
                detailView.text =
                    "Connect the two phones with a USB-C cable.\n" +
                        "Keep this app open on at least one phone."
                statsView.text = ""
            }
            !handshakeDone -> {
                statusView.text = "Linking"
                statusView.setTextColor(Color.parseColor("#9E9E9E"))
                detailView.text = "USB connection is up."
                statsView.text = ""
            }
            LanVpnService.activeLink !== l -> {
                statusView.text = "Tap to allow VPN"
                statusView.setTextColor(Color.BLACK)
                detailView.text =
                    "The LAN needs VPN permission (one-time).\n" +
                        "Tap anywhere on this screen to grant it."
                statsView.text = ""
            }
            else -> {
                statusView.text = "Connected"
                statusView.setTextColor(Color.parseColor("#1B7F3B"))
                detailView.text =
                    "This device  ${LanVpnService.myIp(role)}\n" +
                        "Peer        ${LanVpnService.peerIp(role)}"
                statsView.text = "rx ${fmtBytes(l.rxBytes)}  tx ${fmtBytes(l.txBytes)}"
            }
        }
    }

    private fun onRootTap() {
        if (link == null || !handshakeDone) return
        if (LanVpnService.activeLink === link) return
        val consent = VpnService.prepare(this)
        if (consent != null) {
            startActivityForResult(consent, REQ_VPN_CONSENT)
        } else {
            startVpn()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN_CONSENT && resultCode == RESULT_OK) startVpn()
    }

    private fun startVpn() {
        val l = link ?: return
        if (!handshakeDone) return
        if (VpnService.prepare(this) != null) return
        if (LanVpnService.activeLink === l) return
        LanVpnService.pendingLink = l
        startForegroundService(Intent(this, LanVpnService::class.java))
        render()
    }

    private fun fmtBytes(v: Long): String {
        val kb = v / 1024.0
        return if (kb < 1024) String.format(Locale.US, "%.1f KB", kb)
        else String.format(Locale.US, "%.2f MB", kb / 1024)
    }

    // --------------------------------------------------------- USB plumbing

    override fun onResume() {
        super.onResume()
        if (link == null) {
            usbManager.accessoryList?.firstOrNull()?.let(::onAccessoryAttached)
            if (link == null) usbManager.deviceList.values.forEach(::onDeviceAttached)
        }
        render()
    }

    override fun onDestroy() {
        handler.removeCallbacks(uiLoop)
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        // Leave the link and VPN service alive on purpose: the user may keep
        // using the LAN with the app in the background. The service tears
        // everything down when the cable is unplugged.
        super.onDestroy()
    }

    private fun isAccessoryEnumeration(d: UsbDevice): Boolean =
        d.vendorId == GOOGLE_VID && (d.productId == AOA_PID_2D00 || d.productId == AOA_PID_2D01)

    private fun onDeviceAttached(device: UsbDevice) {
        if (link != null || role != Role.NONE) return
        if (isAccessoryEnumeration(device)) {
            connectHost(device)
        } else {
            beginHandshake(device)
        }
    }

    /** Ask the peer to re-enumerate as an Android accessory. */
    private fun beginHandshake(device: UsbDevice) {
        val key = "${device.vendorId}:${device.productId}:${device.serialNumber ?: ""}"
        if (!aoaAttempted.add(key)) return
        if (!usbManager.hasPermission(device)) {
            usbManager.requestPermission(device, permissionPi)
            return
        }
        runAoaHandshake(device)
    }

    private fun runAoaHandshake(device: UsbDevice) {
        val connection: UsbDeviceConnection = usbManager.openDevice(device) ?: return
        Thread {
            Thread.currentThread().name = "usblan-aoa-handshake"
            try {
                val proto = ByteArray(2)
                val n = connection.controlTransfer(0xC0, REQ_GET_PROTOCOL, 0, 0, proto, 2, 2000)
                val version = if (n == 2)
                    (proto[0].toInt() and 0xFF) or ((proto[1].toInt() and 0xFF) shl 8)
                else 0
                if (version >= 1) {
                    sendAoaString(connection, 0, "USBLan") // manufacturer
                    sendAoaString(connection, 1, "USBLan") // model
                    sendAoaString(connection, 3, "1.0")   // version
                    connection.controlTransfer(0x40, REQ_START, 0, 0, null, 0, 2000)
                    // The peer now re-enumerates as an accessory; we will get
                    // a DETACHED (original device, ignored) and an ATTACHED
                    // (accessory enumeration) handled by connectHost().
                }
            } finally {
                try {
                    connection.close()
                } catch (_: Exception) {
                }
            }
        }.start()
    }

    private fun sendAoaString(c: UsbDeviceConnection, index: Int, value: String): Boolean {
        val data = (value + "\u0000").toByteArray(Charsets.UTF_8)
        return c.controlTransfer(0x40, REQ_SEND_STRING, 0, index, data, data.size, 2000) >= 0
    }

    /** This phone is the USB host; talk to the peer which is now an accessory. */
    private fun connectHost(device: UsbDevice) {
        if (link != null || role != Role.NONE) return
        if (!usbManager.hasPermission(device)) {
            usbManager.requestPermission(device, permissionPi)
            return
        }
        val connection = usbManager.openDevice(device) ?: return
        val pair = findBulkPair(device)
        if (pair == null) {
            connection.close()
            return
        }
        val (iface, epIn, epOut) = pair
        if (!connection.claimInterface(iface, true)) {
            connection.close()
            return
        }
        val l = HostLink(Role.HOST, connection, iface, epIn, epOut)
        attachLink(l)
        l.sendControl("HELLO:1")
    }

    /** This phone is in accessory mode; the host opened us. */
    private fun onAccessoryAttached(accessory: UsbAccessory) {
        if (link != null || role != Role.NONE) return
        if (!usbManager.hasPermission(accessory)) {
            usbManager.requestPermission(accessory, permissionPi)
            return
        }
        val pfd = usbManager.openAccessory(accessory) ?: return
        attachLink(AccessoryLink(Role.DEVICE, pfd))
    }

    private fun attachLink(l: Link) {
        link = l
        role = l.role
        handshakeDone = false
        l.onControl = { message -> onControlMessage(message) }
        l.onClosed = {
            handler.post {
                if (link === l) {
                    link = null
                    role = Role.NONE
                    handshakeDone = false
                    render()
                }
            }
        }
        render()
    }

    private fun onControlMessage(message: String) {
        when {
            message == "HELLO:1" && role == Role.DEVICE -> {
                link?.sendControl("READY")
                handler.post {
                    handshakeDone = true
                    startVpn()
                    render()
                }
            }
            message == "READY" && role == Role.HOST -> {
                handler.post {
                    handshakeDone = true
                    startVpn()
                    render()
                }
            }
        }
    }

    private fun onUsbGone() {
        // Any detach while a link exists tears it down; the AOA re-enumeration
        // detach happens before the link exists and is therefore a no-op.
        val l = link ?: return
        link = null
        role = Role.NONE
        handshakeDone = false
        l.close()
        render()
    }

    // ------------------------------------------------------------- helpers

    private fun Intent.usbDeviceExtra(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else
            @Suppress("DEPRECATION") getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private fun Intent.usbAccessoryExtra(): UsbAccessory? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        else
            @Suppress("DEPRECATION") getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
}
