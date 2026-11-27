package it.uhde.periph.chips.gas

import it.uhde.periph.connection.Connection
import java.io.IOException

/** RRH46410 protocol or status error; [status] is the status byte or -1 (checksum / timeout / identity). */
class Rrh46410Exception(message: String, val status: Int = -1) : IOException(message)

/**
 * A decoded RRH46410 measurement (Get Measurement Results).
 *
 * @property sample sample counter (0–255, wraps)
 * @property iaq IAQ index, UBA level 1.0–5.0 (0 in PBAQ mode)
 * @property tvocMgM3 TVOC in mg/m³
 * @property etohPpm ethanol equivalent in ppm
 * @property eco2Ppm estimated CO₂ in ppm (0 in PBAQ mode)
 * @property relativeIaq relative IAQ (0 in PBAQ mode)
 * @property warmedUp true after the warm-up samples of the mode
 * @property pbaq true when decoded in PBAQ mode (only TVOC and ethanol valid)
 */
data class Rrh46410Measurement(
    val sample: Int,
    val iaq: Double,
    val tvocMgM3: Double,
    val etohPpm: Double,
    val eco2Ppm: Int,
    val relativeIaq: Int,
    val warmedUp: Boolean,
    val pbaq: Boolean,
)

/**
 * RRH46410 — indoor air quality gas sensor module (Renesas), minimal driver.
 *
 * One call returns the IAQ index, TVOC, ethanol equivalent, estimated CO₂ and relative IAQ of the
 * IAQ 2nd Gen algorithm. The host sends a request `[command, params…, checksum]` in one I²C write
 * and reads the response `[status, params…, checksum]` in a separate read, where
 * `checksum = ~(sum of the other bytes)`. The module stretches the I²C clock; the address is fixed
 * at 0x38. The constructor does no bus access; [init] retries Get Product ID for up to 1 s,
 * verifies 0x2310 and selects the IAQ 2nd Gen mode (one sample every 3 s; the first 100 samples,
 * about 5 minutes, are the warm-up). The module's default humidity (50 %RH) is kept.
 *
 * @param connection plain I²C connection bound to address 0x38
 */
open class Rrh46410Minimal(protected val connection: Connection) {
    companion object {
        /** Fixed 7-bit I²C address. */
        const val ADDRESS = 0x38
        /** Expected Get Product ID value. */
        const val PRODUCT_ID = 0x2310

        /** No measurement (default after power-up and reset). */
        const val MODE_SUSPEND = 0x00
        /** IAQ 2nd Gen: one sample every 3 s, 100 warm-up samples. */
        const val MODE_IAQ_2ND_GEN = 0x01
        /** Ultra-low power: one sample every 90 s, 10 warm-up samples. */
        const val MODE_ULP = 0x02
        /** Public-building air quality: TVOC and ethanol every 5 s, 60 warm-up samples. */
        const val MODE_PBAQ = 0x05
        /** One-time sensor cleaning (1 minute). */
        const val MODE_CLEANING = 0x80

        internal const val CMD_GET_PRODUCT_ID = 0x00
        internal const val CMD_GET_TRACKING_NUMBER = 0x01
        internal const val CMD_GET_OPERATION_MODE = 0x10
        internal const val CMD_SET_OPERATION_MODE = 0x11
        internal const val CMD_SET_HUMIDITY = 0x12
        internal const val CMD_GET_MEASUREMENT_RESULTS = 0x18
        internal const val CMD_CONFIG_GPIO = 0x20
        internal const val CMD_GET_GPIO = 0x21
        internal const val CMD_SET_GPIO = 0x22
        internal const val CMD_CLEAR_GPIO = 0x23
        internal const val CMD_RESET = 0x8F
        internal const val STATUS_NOT_COMPLETE = 0x10

        internal fun checksum(data: ByteArray, len: Int): Int {
            var s = 0
            for (i in 0 until len) s += data[i].toInt() and 0xFF
            return s.inv() and 0xFF
        }

        internal fun period(mode: Int): Double = when (mode) {
            MODE_IAQ_2ND_GEN -> 3.0
            MODE_ULP -> 90.0
            MODE_PBAQ -> 5.0
            else -> 0.0
        }

        internal fun warmup(mode: Int): Int = when (mode) {
            MODE_IAQ_2ND_GEN -> 100
            MODE_ULP -> 10
            MODE_PBAQ -> 60
            else -> 0
        }
    }

    protected var currentMode = MODE_SUSPEND
    protected var lastStatus = 0
    protected var lastCounter = -1
    protected var samples = 0
    protected var last: Rrh46410Measurement? = null
    /** Result polling interval in ms while waiting for a sample. */
    internal var pollMs = 200L

    /**
     * Waits for the module (up to 1 s), verifies the product ID and starts IAQ 2nd Gen mode.
     *
     * @throws IOException if the module does not answer or the product ID is not 0x2310
     */
    @Synchronized
    fun init() {
        waitProductId()
        setModeRaw(MODE_IAQ_2ND_GEN)
    }

    /**
     * Waits for the next sample (sample period plus 2 s) and decodes it.
     *
     * @return the decoded measurement
     * @throws IOException on a bus, checksum or status error, or when no new sample arrives
     */
    fun read(): Rrh46410Measurement {
        if (!waitSample(period(currentMode) + 2.0)) throw Rrh46410Exception("RRH46410 sample timeout")
        return synchronized(this) { last!! }
    }

    /** Sends a request and reads `[status, n params]`. */
    protected fun transact(command: Int, params: ByteArray, n: Int, check: Boolean): ByteArray {
        val req = ByteArray(params.size + 2)
        req[0] = command.toByte()
        params.copyInto(req, 1)
        req[req.size - 1] = checksum(req, req.size - 1).toByte()
        connection.write(req)
        val resp = connection.read(n + 2)
        if (resp.size < n + 2 || checksum(resp, n + 1) != (resp[n + 1].toInt() and 0xFF)) {
            throw Rrh46410Exception("RRH46410 response checksum error (command 0x%02X)".format(command))
        }
        lastStatus = resp[0].toInt() and 0xFF
        if (check && lastStatus != 0) {
            throw Rrh46410Exception("RRH46410 status 0x%02X (command 0x%02X)".format(lastStatus, command), lastStatus)
        }
        return resp.copyOf(n + 1)
    }

    protected fun waitProductId() {
        val deadline = System.currentTimeMillis() + 1000
        var d: ByteArray
        while (true) {
            try {
                d = transact(CMD_GET_PRODUCT_ID, ByteArray(0), 5, true)
                break
            } catch (e: IOException) {
                if (System.currentTimeMillis() > deadline) throw Rrh46410Exception("RRH46410 not responding")
                Thread.sleep(50)
            }
        }
        val pid = ((d[1].toInt() and 0xFF) shl 8) or (d[2].toInt() and 0xFF)
        if (pid != PRODUCT_ID) throw Rrh46410Exception("Unexpected RRH46410 product ID 0x%04X, expected 0x2310".format(pid))
    }

    protected fun setModeRaw(newMode: Int) {
        transact(CMD_SET_OPERATION_MODE, byteArrayOf(newMode.toByte()), 0, true)
        currentMode = newMode
        lastCounter = -1
        samples = 0
    }

    /** Raw result `[status, params…]`, or null before the first sample of the mode. */
    protected fun fetch(): ByteArray? {
        val d = transact(CMD_GET_MEASUREMENT_RESULTS, ByteArray(0), if (currentMode == MODE_PBAQ) 5 else 9, false)
        val status = d[0].toInt() and 0xFF
        if (status == STATUS_NOT_COMPLETE) return null
        if (status != 0) throw Rrh46410Exception("RRH46410 status 0x%02X (command 0x18)".format(status), status)
        return d
    }

    private fun u16(d: ByteArray, i: Int) = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)

    protected fun decode(d: ByteArray): Rrh46410Measurement {
        val counter = d[1].toInt() and 0xFF
        samples = if (lastCounter < 0) counter else samples + ((counter - lastCounter) and 0xFF)
        lastCounter = counter
        val warmedUp = samples >= warmup(currentMode)
        val m = if (currentMode == MODE_PBAQ) {
            Rrh46410Measurement(counter, 0.0, u16(d, 2) / 1000.0, u16(d, 4) / 1000.0, 0, 0, warmedUp, true)
        } else {
            Rrh46410Measurement(counter, (d[2].toInt() and 0xFF) / 10.0, u16(d, 3) / 100.0, u16(d, 5) / 100.0,
                u16(d, 7), (d[9].toInt() and 0xFF) * 10, warmedUp, false)
        }
        last = m
        return m
    }

    /** Polls until the sample counter changes; stores the decoded result. Timeout in s. */
    protected fun waitSample(timeout: Double): Boolean {
        val deadline = System.currentTimeMillis() + (timeout * 1000).toLong()
        val pin = connection.intPin()
        val prev = synchronized(this) { lastCounter }
        while (true) {
            if (pin == null || pin.isHigh()) {
                synchronized(this) {
                    val d = fetch()
                    if (d != null && (prev < 0 || (d[1].toInt() and 0xFF) != prev)) {
                        decode(d)
                        return true
                    }
                }
            }
            if (System.currentTimeMillis() > deadline) return false
            Thread.sleep(pollMs)
        }
    }
}
