package com.usblan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground [VpnService] that creates a TUN interface and bridges IPv4
 * packets between the TUN and the USB link, giving both phones a shared
 * LAN (10.7.0.0/24) that any other app can use.
 */
class LanVpnService : VpnService() {

    companion object {
        const val SUBNET = "10.7.0"
        const val MTU = 1380

        /** Handoff from MainActivity: the link the service should own. */
        @Volatile
        var pendingLink: Link? = null

        /** The link this service is currently using, for UI state checks. */
        @Volatile
        var activeLink: Link? = null

        /** Link on which the peer reported its TUN up ("TUNUP"); set by MainActivity's callbacks. */
        @Volatile
        var peerTunUpLink: Link? = null

        fun myIp(role: Role): String = if (role == Role.HOST) "$SUBNET.1" else "$SUBNET.2"
        fun peerIp(role: Role): String = if (role == Role.HOST) "$SUBNET.2" else "$SUBNET.1"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stopping = AtomicBoolean(false)
    private var link: Link? = null
    private var tun: ParcelFileDescriptor? = null
    private var tunIn: FileInputStream? = null
    private var tunOut: FileOutputStream? = null

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            handleStart()
        } catch (_: Exception) {
            // Never crash-loop: drop everything and stop.
            stopping.set(true)
            cleanup()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun handleStart() {
        val l = pendingLink
        pendingLink = null

        // Started via startForegroundService(): call startForeground() before
        // any early exit, otherwise the system kills the app with
        // ForegroundServiceDidNotStartInTimeException.
        startForeground(1, buildNotification(l?.role ?: activeLink?.role ?: Role.NONE))

        if (l == null) {
            if (activeLink == null) stopSelf()
            return
        }

        if (activeLink != null && activeLink !== l) {
            // Already serving another link; reject the newcomer without
            // tearing down the active LAN.
            l.close()
            return
        }

        if (activeLink == null) {
            link = l
            activeLink = l
            val fd = createTun(l.role)
            if (fd == null) {
                stopping.set(true)
                cleanup()
                stopSelf()
                return
            }
            tun = fd
            tunIn = FileInputStream(fd.fileDescriptor)
            tunOut = FileOutputStream(fd.fileDescriptor)
            startPumps(l)
            // Tell the peer our TUN is up; its UI shows "Connected" only
            // after it gets this.
            l.sendControl("TUNUP")
        }
    }

    private fun createTun(role: Role): ParcelFileDescriptor? = try {
        Builder()
            .setSession("USB LAN")
            .setMtu(MTU)
            .addAddress(myIp(role), 24)
            .addRoute("$SUBNET.0", 24)
            .setBlocking(true) // the TUN reader thread does blocking reads
            .establish()
    } catch (_: Exception) {
        null
    }

    private fun startPumps(l: Link) {
        // USB -> TUN: packets arriving from the peer are injected into the
        // local network stack so apps on this phone receive them.
        l.onPacket = { data, n ->
            val out = tunOut
            if (out != null) {
                try {
                    out.write(data, 0, n)
                    out.flush()
                } catch (_: Exception) {
                    requestStop()
                }
            }
        }
        // Chain, don't replace: MainActivity's cleanup callback must still run.
        val previousOnClosed = l.onClosed
        l.onClosed = {
            try {
                previousOnClosed?.invoke()
            } finally {
                requestStop()
            }
        }

        // TUN -> USB: packets local apps send to the LAN are forwarded to the peer.
        Thread {
            Thread.currentThread().name = "usblan-tun-reader"
            val buffer = ByteArray(2048)
            val input = tunIn
            if (input == null) {
                requestStop()
                return@Thread
            }
            try {
                while (!stopping.get()) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) l.sendPacket(buffer, n)
                }
            } catch (_: Exception) {
            } finally {
                requestStop()
            }
        }.start()
    }

    private fun requestStop() {
        mainHandler.post {
            if (stopping.compareAndSet(false, true)) stopSelf()
        }
    }

    private fun cleanup() {
        if (peerTunUpLink === link) peerTunUpLink = null
        activeLink = null
        link?.close()
        link = null
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tunIn = null
        tunOut = null
    }

    override fun onDestroy() {
        stopping.set(true)
        cleanup()
        super.onDestroy()
    }

    private fun buildNotification(role: Role): Notification {
        val channelId = "usblan"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "USB LAN", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, channelId)
            .setContentTitle(if (role == Role.NONE) "USB LAN" else "USB LAN is up")
            .setContentText(
                if (role == Role.NONE) "Starting"
                else "This phone ${myIp(role)} - peer ${peerIp(role)}"
            )
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }
}
