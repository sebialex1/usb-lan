package com.usblan

import android.os.SystemClock
import kotlin.random.Random

/**
 * Connection test over the control channel, kept process-wide so it
 * survives activity recreation and keeps answering while the UI is gone.
 *
 * - Host picks a random session number (1..100000) after the handshake and
 *   sends "SID:<n>"; both phones show it. Matching numbers on both screens
 *   mean the host's control frames reach the device.
 * - Host sends "PNG:<uptimeMillis>" every couple of seconds; the device
 *   echoes the same value back; the host computes the round trip from its
 *   own clock (SystemClock.uptimeMillis on the host only, so clocks never
 *   need to agree) and sends "RTT:<ms>" so both phones show it. A ping
 *   reply proves data flows both ways.
 */
object LinkSession {
    private const val SID_MIN = 1
    private const val SID_MAX = 100_000
    /** A round-trip value older than this is shown as "ping -". */
    private const val RTT_STALE_MS = 6_000L
    private const val RTT_MAX_MS = 60_000L

    private class State(val link: Link) {
        @Volatile var sid = 0
        @Volatile var rttMs = -1L
        @Volatile var rttAt = 0L
    }

    @Volatile private var state: State? = null

    @Synchronized
    private fun stateFor(l: Link): State {
        val s = state
        if (s != null && s.link === l) return s
        return State(l).also { state = it }
    }

    /** Host, after the handshake: pick the session number (once per link) and announce it. */
    fun hostStart(l: Link) {
        val s = stateFor(l)
        if (s.sid == 0) s.sid = Random.nextInt(SID_MIN, SID_MAX + 1)
        l.sendControl("SID:${s.sid}")
    }

    /** Host, periodically: re-announce the session number and send a ping. */
    fun hostTick(l: Link) {
        val s = stateFor(l)
        if (s.sid != 0) l.sendControl("SID:${s.sid}")
        l.sendControl("PNG:${SystemClock.uptimeMillis()}")
    }

    /** Handle a control line. Returns true if it belonged to the connection test. Reader thread. */
    fun onControl(l: Link, message: String): Boolean {
        val colon = message.indexOf(':')
        if (colon <= 0) return false
        val value = message.substring(colon + 1).toLongOrNull()
        when (message.substring(0, colon)) {
            "SID" -> {
                if (l.role == Role.DEVICE && value != null && value in SID_MIN..SID_MAX) {
                    stateFor(l).sid = value.toInt()
                }
            }
            "PNG" -> {
                if (value == null) return true
                if (l.role == Role.DEVICE) {
                    l.sendControl("PNG:$value") // echo unchanged
                } else if (l.role == Role.HOST) {
                    val rtt = SystemClock.uptimeMillis() - value
                    if (rtt in 0..RTT_MAX_MS) {
                        setRtt(l, rtt)
                        l.sendControl("RTT:$rtt")
                    }
                }
            }
            "RTT" -> {
                if (l.role == Role.DEVICE && value != null && value in 0..RTT_MAX_MS) setRtt(l, value)
            }
            else -> return false
        }
        return true
    }

    private fun setRtt(l: Link, rtt: Long) {
        val s = stateFor(l)
        s.rttMs = rtt
        s.rttAt = SystemClock.uptimeMillis()
    }

    /** Session number for [l], or null if none yet. */
    fun sid(l: Link): Int? = state?.takeIf { it.link === l }?.sid?.takeIf { it != 0 }

    /** Current round trip for [l] in ms, or null if none yet / stale. */
    fun rtt(l: Link): Long? {
        val s = state?.takeIf { it.link === l } ?: return null
        if (s.rttMs < 0 || SystemClock.uptimeMillis() - s.rttAt > RTT_STALE_MS) return null
        return s.rttMs
    }

    /** Forget everything about [l] (link closed). */
    fun clear(l: Link) {
        if (state?.link === l) state = null
    }
}
