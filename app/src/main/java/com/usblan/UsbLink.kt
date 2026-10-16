package com.usblan

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class Role { NONE, HOST, DEVICE }

/** Largest payload we will accept in one frame (IPv4 packets + control text). */
const val MAX_FRAME_PAYLOAD = 1600

/**
 * A duplex link between the two phones.
 *
 * Frames: [uint16 little-endian payload length][flag byte][payload]
 * flag 0 = IPv4 packet, flag 1 = ASCII control line.
 */
interface Link {
    val role: Role
    val rxBytes: Long
    val txBytes: Long

    /** Callbacks set by the owner; invoked on the reader thread. */
    var onPacket: ((ByteArray, Int) -> Unit)?
    var onControl: ((String) -> Unit)?
    var onClosed: (() -> Unit)?

    fun sendControl(text: String)
    fun sendPacket(data: ByteArray, length: Int)
    fun close()
}

/**
 * Reassembles frames from an arbitrary byte stream. USB reads can split or
 * coalesce frames, so this accumulates bytes and emits only complete frames.
 */
private class FrameDecoder(
    private val onPacket: (ByteArray, Int) -> Unit,
    private val onControl: (String) -> Unit,
) {
    private var header = ByteArray(3)
    private var headerFill = 0
    private var partial: ByteArray? = null
    private var partialFlag = 0
    private var partialFill = 0

    fun feed(data: ByteArray, length: Int) {
        var i = 0
        while (i < length) {
            val p = partial
            if (p != null) {
                val take = minOf(length - i, p.size - partialFill)
                System.arraycopy(data, i, p, partialFill, take)
                partialFill += take
                i += take
                if (partialFill >= p.size) {
                    if (partialFlag == 1) {
                        onControl(String(p, Charsets.US_ASCII).trim())
                    } else {
                        onPacket(p, p.size)
                    }
                    partial = null
                }
            } else if (headerFill < 3) {
                val take = minOf(length - i, 3 - headerFill)
                System.arraycopy(data, i, header, headerFill, take)
                headerFill += take
                i += take
                if (headerFill >= 3) {
                    val len = (header[0].toInt() and 0xFF) or ((header[1].toInt() and 0xFF) shl 8)
                    val flag = header[2].toInt() and 0xFF
                    headerFill = 0
                    if (len in 1..MAX_FRAME_PAYLOAD) {
                        partial = ByteArray(len)
                        partialFlag = flag
                        partialFill = 0
                    }
                    // A malformed header desynchronizes the stream; drop it and
                    // hope the peer resends (TCP retransmits, control is periodic).
                }
            } else {
                headerFill = 0
            }
        }
    }
}

/**
 * Common machinery shared by the host-side and accessory-side transports:
 * writer thread with a bounded queue, reader thread, framing, counters.
 */
abstract class BaseLink(override val role: Role) : Link {

    protected val closed = AtomicBoolean(false)
    private val outbox = LinkedBlockingQueue<ByteArray>(256)
    private val rxCtr = AtomicLong(0)
    private val txCtr = AtomicLong(0)
    override val rxBytes: Long get() = rxCtr.get()
    override val txBytes: Long get() = txCtr.get()

    /** Set by the owner before first use. Called on reader thread. */
    override var onPacket: ((ByteArray, Int) -> Unit)? = null
    override var onControl: ((String) -> Unit)? = null
    override var onClosed: (() -> Unit)? = null

    private val decoder = FrameDecoder(
        onPacket = { data, n ->
            rxCtr.addAndGet(n.toLong())
            onPacket?.invoke(data, n)
        },
        onControl = { onControl?.invoke(it) },
    )

    /** Blocking raw read; return -1 on EOF/error, 0 on idle timeout. */
    protected abstract fun readChunk(buffer: ByteArray): Int

    /** Write the whole buffer; return false on failure. */
    protected abstract fun writeChunk(data: ByteArray, length: Int): Boolean

    /** Release transport-specific resources. */
    protected abstract fun onShutdown()

    private val reader = Thread {
        val buffer = ByteArray(4096)
        try {
            while (!closed.get()) {
                val n = readChunk(buffer)
                if (n < 0) break
                if (n > 0) decoder.feed(buffer, n)
            }
        } catch (_: Exception) {
        } finally {
            shutdown()
        }
    }

    private val writer = Thread {
        try {
            while (!closed.get()) {
                val frame = outbox.poll(250, TimeUnit.MILLISECONDS) ?: continue
                if (writeChunk(frame, frame.size)) {
                    txCtr.addAndGet(frame.size.toLong())
                } else {
                    break
                }
            }
        } catch (_: Exception) {
        } finally {
            shutdown()
        }
    }

    /**
     * Must be called at the END of each subclass init block. Starting the
     * threads from the BaseLink constructor would let them observe subclass
     * fields before they are assigned.
     */
    protected fun startThreads() {
        reader.name = "usblan-reader-${role.name.lowercase()}"
        writer.name = "usblan-writer-${role.name.lowercase()}"
        reader.start()
        writer.start()
    }

    override fun sendControl(text: String) {
        enqueue(1, text.toByteArray(Charsets.US_ASCII))
    }

    override fun sendPacket(data: ByteArray, length: Int) {
        if (length < 20 || length > MAX_FRAME_PAYLOAD) return // min valid IPv4 header
        if ((data[0].toInt() ushr 4) != 4) return             // IPv4 only
        enqueue(0, data.copyOf(length))
    }

    private fun enqueue(flag: Int, payload: ByteArray) {
        if (closed.get() || payload.isEmpty()) return
        val frame = ByteArray(payload.size + 3)
        frame[0] = (payload.size and 0xFF).toByte()
        frame[1] = ((payload.size shr 8) and 0xFF).toByte()
        frame[2] = flag.toByte()
        System.arraycopy(payload, 0, frame, 3, payload.size)
        if (outbox.remainingCapacity() == 0) outbox.poll() // drop oldest under pressure
        outbox.offer(frame)
    }

    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        try {
            onShutdown()
        } catch (_: Exception) {
        }
        onClosed?.invoke()
    }

    override fun close() = shutdown()
}

/** Host side: the phone acting as USB host talks to the peer in AOA mode. */
class HostLink(
    role: Role,
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint,
) : BaseLink(role) {

    init {
        startThreads()
    }

    override fun readChunk(buffer: ByteArray): Int {
        val n = connection.bulkTransfer(endpointIn, buffer, buffer.size, 250)
        if (n >= 0) return n
        // bulkTransfer returns -1 both for timeout and error; only treat it as
        // fatal once we are closing, otherwise it is just a poll timeout.
        return if (closed.get()) -1 else 0
    }

    override fun writeChunk(data: ByteArray, length: Int): Boolean {
        var offset = 0
        while (offset < length) {
            val n = connection.bulkTransfer(endpointOut, data, offset, length - offset, 500)
            if (n <= 0) return false
            offset += n
        }
        return true
    }

    override fun onShutdown() {
        try {
            connection.releaseInterface(usbInterface)
        } catch (_: Exception) {
        }
        try {
            connection.close()
        } catch (_: Exception) {
        }
    }
}

/** Device side: the phone in accessory mode uses the accessory file descriptor. */
class AccessoryLink(
    role: Role,
    private val pfd: ParcelFileDescriptor,
) : BaseLink(role) {

    private val input = FileInputStream(pfd.fileDescriptor)
    private val output = FileOutputStream(pfd.fileDescriptor)

    init {
        startThreads()
    }

    override fun readChunk(buffer: ByteArray): Int = try {
        input.read(buffer)
    } catch (_: Exception) {
        -1
    }

    override fun writeChunk(data: ByteArray, length: Int): Boolean = try {
        output.write(data, 0, length)
        output.flush()
        true
    } catch (_: Exception) {
        false
    }

    override fun onShutdown() {
        try {
            pfd.close()
        } catch (_: Exception) {
        }
    }
}

/** Helper for the host side: find a bulk IN/OUT endpoint pair on a device. */
fun findBulkPair(device: android.hardware.usb.UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
    for (i in 0 until device.interfaceCount) {
        val itf = device.getInterface(i)
        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (j in 0 until itf.endpointCount) {
            val ep = itf.getEndpoint(j)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN && epIn == null) epIn = ep
                if (ep.direction == UsbConstants.USB_DIR_OUT && epOut == null) epOut = ep
            }
        }
        if (epIn != null && epOut != null) {
            return Triple(itf, epIn, epOut)
        }
    }
    return null
}
