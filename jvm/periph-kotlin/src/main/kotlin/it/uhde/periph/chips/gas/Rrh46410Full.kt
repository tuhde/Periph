package it.uhde.periph.chips.gas

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import java.io.IOException

/**
 * The decoded status byte of the last RRH46410 response.
 *
 * @property raw raw status byte
 * @property invalidCommand bit 7: invalid command, read without request or bus error
 * @property checksumError bit 6: request checksum wrong
 * @property sensorNack bit 5: internal gas-sensor I²C NACK
 * @property notComplete bit 4: asynchronous command not complete
 * @property modeError bits 3:0: 0 OK, 0xA sensor damaged
 */
data class Rrh46410Status(
    val raw: Int,
    val invalidCommand: Boolean,
    val checksumError: Boolean,
    val sensorNack: Boolean,
    val notComplete: Boolean,
    val modeError: Int,
)

/**
 * RRH46410 product ID (0x2310) and firmware version.
 *
 * @property productId 0x2310
 * @property major firmware major version
 * @property minor firmware minor version
 * @property patch firmware patch version
 */
data class Rrh46410ProductId(val productId: Int, val major: Int, val minor: Int, val patch: Int)

/**
 * RRH46410 — full driver: identity, operation modes (IAQ 2nd Gen, ULP, PBAQ, cleaning), humidity
 * compensation, GPIOs, status, reset, raw commands and the INT interrupt API. INT is active high,
 * push-pull: high when a new result is available, low after Get Measurement Results.
 *
 * @param connection plain I²C connection bound to address 0x38
 */
class Rrh46410Full(connection: Connection) : Rrh46410Minimal(connection) {
    @Volatile private var callback: ((Rrh46410Measurement) -> Unit)? = null
    private var subscribedPin: InputPin? = null
    @Volatile private var polling = false
    private var pollThread: Thread? = null
    private val edgeHandler = EdgeHandler { dispatch() }

    /** Get Product ID: product ID (0x2310) and firmware version. */
    @Synchronized
    fun productId(): Rrh46410ProductId {
        val d = transact(CMD_GET_PRODUCT_ID, ByteArray(0), 5, true)
        return Rrh46410ProductId(((d[1].toInt() and 0xFF) shl 8) or (d[2].toInt() and 0xFF),
            d[3].toInt() and 0xFF, d[4].toInt() and 0xFF, d[5].toInt() and 0xFF)
    }

    /** Get Tracking Number: the 6-byte ASIC tracking number. */
    @Synchronized
    fun trackingNumber(): ByteArray = transact(CMD_GET_TRACKING_NUMBER, ByteArray(0), 6, true).copyOfRange(1, 7)

    /** Get Operation Mode: one of the MODE_* constants (other values behave as SUSPEND). */
    @Synchronized
    fun getMode(): Int {
        currentMode = transact(CMD_GET_OPERATION_MODE, ByteArray(0), 1, true)[1].toInt() and 0xFF
        return currentMode
    }

    /**
     * Set Operation Mode; stops a running measurement, restarts the warm-up and sets INT low. Use
     * [cleanSensor] for the cleaning mode.
     *
     * @param newMode MODE_SUSPEND, MODE_IAQ_2ND_GEN, MODE_ULP or MODE_PBAQ
     * @throws IllegalArgumentException for another mode
     */
    @Synchronized
    fun setMode(newMode: Int) {
        require(newMode in intArrayOf(MODE_SUSPEND, MODE_IAQ_2ND_GEN, MODE_ULP, MODE_PBAQ)) {
            "mode must be MODE_SUSPEND, MODE_IAQ_2ND_GEN, MODE_ULP or MODE_PBAQ"
        }
        setModeRaw(newMode)
    }

    /** Stops measuring (SUSPEND, about 0.7 µA). */
    @Synchronized
    fun suspend() = setModeRaw(MODE_SUSPEND)

    /** Reads and decodes the latest result without waiting; null before the first sample of the mode. */
    @Synchronized
    fun readResults(): Rrh46410Measurement? = fetch()?.let { decode(it) }

    /**
     * Waits until the sample counter changes (or INT goes high); the new sample is returned by
     * [lastResult].
     *
     * @param timeout maximum wait in s
     * @return true when a new sample arrived
     */
    fun waitForSample(timeout: Double): Boolean = waitSample(timeout)

    /** The result decoded by the last read, readResults or waitForSample (null before). */
    @Synchronized
    fun lastResult(): Rrh46410Measurement? = last

    /**
     * Set Humidity: the ambient relative humidity, sent as round(percent / 100 × 255).
     *
     * @param percent relative humidity in % (0–100)
     */
    @Synchronized
    fun setHumidity(percent: Double) {
        require(percent in 0.0..100.0) { "percent must be 0..100" }
        transact(CMD_SET_HUMIDITY, byteArrayOf(Math.round(percent / 100 * 255).toByte()), 0, true)
    }

    /** Sample period in s: 3 (IAQ 2nd Gen), 90 (ULP), 5 (PBAQ), 0 otherwise; [m] defaults to the current mode. */
    fun samplePeriod(m: Int = currentMode): Double = period(m)

    /** Warm-up samples: 100 (IAQ 2nd Gen), 10 (ULP), 60 (PBAQ), 0 otherwise; [m] defaults to the current mode. */
    fun warmupSamples(m: Int = currentMode): Int = warmup(m)

    /** UBA rating text: "very good" (< 2.0), "good", "medium", "poor", "bad" (≥ 5.0). */
    fun airQualityLabel(iaq: Double): String = when {
        iaq < 2.0 -> "very good"
        iaq < 3.0 -> "good"
        iaq < 4.0 -> "medium"
        iaq < 5.0 -> "poor"
        else -> "bad"
    }

    /**
     * Runs the one-time 1-minute sensor cleaning and waits (up to 90 s) for SUSPEND. Execute it at
     * most once in the sensor's lifetime and do not interrupt it.
     *
     * @param confirm must be true
     */
    @Synchronized
    fun cleanSensor(confirm: Boolean = false) {
        require(confirm) { "cleanSensor runs once per sensor lifetime; pass confirm=true" }
        setModeRaw(MODE_CLEANING)
        val deadline = System.currentTimeMillis() + 90_000
        while (getMode() == MODE_CLEANING) {
            if (System.currentTimeMillis() > deadline) throw Rrh46410Exception("RRH46410 cleaning did not finish")
            Thread.sleep(1000)
        }
        currentMode = MODE_SUSPEND
    }

    private fun mask(v: Int): Byte {
        require(v in 0..3) { "GPIO mask must be 0..3" }
        return v.toByte()
    }

    /** Config GPIO: bit 0 GPIO1 output, bit 1 GPIO2 output (0–3). */
    @Synchronized
    fun configGpio(directions: Int) {
        transact(CMD_CONFIG_GPIO, byteArrayOf(mask(directions)), 0, true)
    }

    /** Get GPIO: bits 0–3 = GPIO1–GPIO4 levels. */
    @Synchronized
    fun getGpio(): Int = transact(CMD_GET_GPIO, ByteArray(0), 1, true)[1].toInt() and 0xFF

    /** Set GPIO: drives the outputs in [m] (0–3) high. */
    @Synchronized
    fun setGpio(m: Int) {
        transact(CMD_SET_GPIO, byteArrayOf(mask(m)), 0, true)
    }

    /** Clear GPIO: drives the outputs in [m] (0–3) low. */
    @Synchronized
    fun clearGpio(m: Int) {
        transact(CMD_CLEAR_GPIO, byteArrayOf(mask(m)), 0, true)
    }

    /** The decoded status byte of the last response. */
    @Synchronized
    fun status(): Rrh46410Status {
        val s = lastStatus
        return Rrh46410Status(s, s and 0x80 != 0, s and 0x40 != 0, s and 0x20 != 0, s and 0x10 != 0, s and 0x0F)
    }

    /** Reset (0x8F, no response), waits 500 ms and verifies the product ID again; the module restarts in SUSPEND. */
    @Synchronized
    fun reset() {
        try {
            connection.write(byteArrayOf(CMD_RESET.toByte(), (CMD_RESET.inv() and 0xFF).toByte()))
        } catch (e: IOException) {
            // the module may not acknowledge while resetting
        }
        Thread.sleep(500)
        waitProductId()
        currentMode = MODE_SUSPEND
        lastCounter = -1
        samples = 0
    }

    /**
     * Raw request with checksums; the status byte is available from [status]. Reset (0x8F) sends
     * the request only.
     *
     * @param command command byte
     * @param params request parameter bytes
     * @param responseLen number of response parameter bytes
     * @return the response parameters (empty for Reset)
     */
    @Synchronized
    fun sendCommand(command: Int, params: ByteArray = ByteArray(0), responseLen: Int = 0): ByteArray {
        if (command == CMD_RESET) {
            val req = byteArrayOf(command.toByte()) + params + byteArrayOf(0)
            req[req.size - 1] = checksum(req, req.size - 1).toByte()
            connection.write(req)
            return ByteArray(0)
        }
        return transact(command, params, responseLen, false).copyOfRange(1, responseLen + 1)
    }

    /**
     * True if the INT pin is high (pin wired), otherwise if the sample counter changed since the last
     * decoded result (the result is not consumed).
     */
    @Synchronized
    fun pollInterrupt(): Boolean {
        connection.intPin()?.let { return it.isHigh() }
        val d = fetch() ?: return false
        return (d[1].toInt() and 0xFF) != lastCounter
    }

    private fun dispatch() {
        val cb = callback ?: return
        val m = synchronized(this) {
            try {
                val d = fetch() ?: return
                if ((d[1].toInt() and 0xFF) == lastCounter) return
                decode(d)
            } catch (e: IOException) {
                return
            }
        }
        cb(m)
    }

    /**
     * Calls [callback] with every new decoded sample: rising edge on [intPin], or a 200 ms poll
     * comparing the sample counter when [intPin] is null. The driver reads the result (which sets
     * INT low).
     */
    fun onInterrupt(callback: (Rrh46410Measurement) -> Unit, intPin: InputPin? = connection.intPin()) {
        offInterrupt()
        this.callback = callback
        if (intPin != null) {
            subscribedPin = intPin
            intPin.onEdge(edgeHandler, EdgeTrigger.RISING)
        } else {
            polling = true
            pollThread = Thread({
                while (polling) {
                    dispatch()
                    try {
                        Thread.sleep(pollMs)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                }
            }, "rrh46410-poll").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** Unsubscribes and stops delivery. */
    fun offInterrupt() {
        callback = null
        subscribedPin?.offEdge(edgeHandler)
        subscribedPin = null
        polling = false
        pollThread?.interrupt()
        pollThread = null
    }
}
