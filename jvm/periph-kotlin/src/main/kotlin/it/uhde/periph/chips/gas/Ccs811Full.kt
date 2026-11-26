package it.uhde.periph.chips.gas

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException
import java.util.function.IntConsumer
import kotlin.math.roundToInt

/**
 * CCS811 full driver — extends [Ccs811Minimal] with drive modes, raw sensor data, environment
 * compensation, the NTC input, thresholds, baseline save/restore, versions, soft reset and the nINT
 * interrupt (active low, open-drain, released when ALG_RESULT_DATA is read).
 *
 * @param connection I²C register connection bound to address 0x5A or 0x5B
 */
class Ccs811Full(connection: RegisterConnection) : Ccs811Minimal(connection) {
    companion object {
        /** Interrupt source: new sample (MEAS_MODE.INT_DATARDY). */
        const val SOURCE_DATA_READY = 0x01
        /** Interrupt source: eCO₂ threshold crossing (INT_DATARDY + INT_THRESH). */
        const val SOURCE_THRESHOLD  = 0x02

        private const val INT_DATARDY = 0x08
        private const val INT_THRESH  = 0x04

        private fun intBits(source: Int): Int {
            var bits = 0
            if (source and SOURCE_DATA_READY != 0) bits = bits or INT_DATARDY
            if (source and SOURCE_THRESHOLD != 0) bits = bits or INT_DATARDY or INT_THRESH
            return bits
        }
    }

    /** Firmware version (major.minor.trivial). */
    data class Version(val major: Int, val minor: Int, val trivial: Int)

    /** Bootloader and application firmware versions. */
    data class FirmwareVersion(val boot: Version, val app: Version)

    @Volatile private var callback: IntConsumer? = null
    private var subscribedPin: InputPin? = null
    @Volatile private var polling = false
    private var pollThread: Thread? = null
    private val edgeHandler = EdgeHandler { handleEdge() }

    private fun writeMeasMode() = write(REG_MEAS_MODE, measMode)

    /** Wait for the next sample and return the equivalent CO₂ in ppm. */
    @Throws(IOException::class)
    fun readEco2(): Int = read().eco2Ppm

    /** Wait for the next sample and return the total VOC in ppb. */
    @Throws(IOException::class)
    fun readTvoc(): Int = read().tvocPpb

    /**
     * Read RAW_DATA.
     *
     * @return sense current (0–63 µA) and the voltage across the sensor in volts (ADC × 1.65 / 1023)
     */
    @Throws(IOException::class)
    fun readRaw(): Pair<Int, Double> {
        val raw = read16(REG_RAW_DATA)
        return Pair(raw shr 10, (raw and 0x3FF) * 1.65 / 1023)
    }

    /** Whether a new sample is waiting (STATUS.DATA_READY). */
    @Throws(IOException::class)
    fun dataReady(): Boolean = status() and STATUS_DATA_READY != 0

    /**
     * Select the drive mode, keeping the interrupt bits: 0 idle, 1 every second, 2 every 10 s,
     * 3 every 60 s, 4 every 250 ms (raw data only). When moving to a slower mode, idle (mode 0)
     * first; the first sample at the new rate may be stale.
     *
     * @param mode drive mode 0–4
     * @throws IllegalArgumentException if mode is not 0–4
     */
    @Throws(IOException::class)
    fun setMode(mode: Int) {
        require(mode in 0..4) { "mode must be 0..4" }
        measMode = (measMode and 0x0C) or (mode shl 4)
        writeMeasMode()
    }

    /**
     * Write ambient humidity and temperature (ENV_DATA) for the compensation.
     *
     * @param humidityRh relative humidity in % (0–100)
     * @param temperatureC temperature in °C (-25 to 102)
     * @throws IllegalArgumentException if a value is out of range
     */
    @Throws(IOException::class)
    fun setEnvironment(humidityRh: Double, temperatureC: Double) {
        require(humidityRh in 0.0..100.0) { "humidityRh must be 0..100" }
        require(temperatureC in -25.0..102.0) { "temperatureC must be -25..102" }
        val h = (humidityRh * 512).roundToInt()
        val t = ((temperatureC + 25) * 512).roundToInt()
        write(REG_ENV_DATA, h shr 8, h and 0xFF, t shr 8, t and 0xFF)
    }

    /** Read the NTC circuit voltages across R_REF and R_NTC in mV. */
    @Throws(IOException::class)
    fun readNtc(): Pair<Int, Int> {
        val b = connection.read(REG_NTC, 4)
        val u = { i: Int -> b[i].toInt() and 0xFF }
        return Pair((u(0) shl 8) or u(1), (u(2) shl 8) or u(3))
    }

    /**
     * NTC resistance V_NTC × R_REF / V_REF.
     *
     * @param rRefOhm reference resistor in ohms
     * @return NTC resistance in ohms
     * @throws IOException on I²C error or when V_REF reads 0 (no NTC circuit)
     */
    @Throws(IOException::class)
    fun ntcResistance(rRefOhm: Double): Double {
        val (vRef, vNtc) = readNtc()
        if (vRef == 0) throw IOException("CCS811 NTC reference voltage is 0")
        return vNtc * rRefOhm / vRef
    }

    /**
     * Set the eCO₂ boundaries for the threshold interrupt.
     *
     * @param lowMediumPpm low-to-medium boundary in ppm
     * @param mediumHighPpm medium-to-high boundary in ppm
     * @param hysteresisPpm hysteresis in ppm (0–255)
     * @throws IllegalArgumentException if a value is out of range or low ≥ high
     */
    @Throws(IOException::class)
    fun setThresholds(lowMediumPpm: Int = 1500, mediumHighPpm: Int = 2500, hysteresisPpm: Int = 50) {
        require(lowMediumPpm >= 0 && lowMediumPpm < mediumHighPpm && mediumHighPpm <= 0xFFFF) {
            "thresholds must satisfy 0 <= lowMedium < mediumHigh <= 65535"
        }
        require(hysteresisPpm in 0..0xFF) { "hysteresisPpm must be 0..255" }
        write(REG_THRESHOLDS, lowMediumPpm shr 8, lowMediumPpm and 0xFF, mediumHighPpm shr 8,
              mediumHighPpm and 0xFF, hysteresisPpm)
    }

    /** Read the encoded algorithm baseline (save it to non-volatile storage). */
    @Throws(IOException::class)
    fun getBaseline(): Int = read16(REG_BASELINE)

    /**
     * Restore a saved baseline (only after about 20 min of operation).
     *
     * @param baseline value from [getBaseline]
     * @throws IllegalArgumentException if baseline is not 0–65535
     */
    @Throws(IOException::class)
    fun setBaseline(baseline: Int) {
        require(baseline in 0..0xFFFF) { "baseline must be 0..65535" }
        write(REG_BASELINE, baseline shr 8, baseline and 0xFF)
    }

    /** Read STATUS (STATUS_* bits). */
    @Throws(IOException::class)
    fun status(): Int = readByte(REG_STATUS)

    /** Read ERROR_ID (bit 0 write-invalid … bit 5 heater supply). */
    @Throws(IOException::class)
    fun error(): Int = readByte(REG_ERROR_ID)

    /** Read the hardware ID (0x81) and version (0x1X). */
    @Throws(IOException::class)
    fun hardwareVersion(): Pair<Int, Int> = Pair(readByte(REG_HW_ID), readByte(REG_HW_VERSION))

    private fun version(reg: Int): Version {
        val v = read16(reg)
        return Version(v shr 12, (v shr 8) and 0x0F, v and 0xFF)
    }

    /** Read the bootloader and application firmware versions. */
    @Throws(IOException::class)
    fun firmwareVersion(): FirmwareVersion = FirmwareVersion(version(REG_FW_BOOT_VERSION), version(REG_FW_APP_VERSION))

    /**
     * SW_RESET into boot mode, then start the application and mode 1 again ([init]). Drive mode,
     * interrupt bits, thresholds and environment data return to their defaults.
     */
    @Throws(IOException::class)
    fun softReset() {
        try {
            write(REG_SW_RESET, 0x11, 0xE5, 0x72, 0x8A)
        } catch (e: IOException) {
            // the chip may not acknowledge while it resets
        }
        sleepMs(20)
        init()
    }

    /**
     * Enable nINT for a source, keeping the drive mode.
     *
     * @param source SOURCE_DATA_READY and/or SOURCE_THRESHOLD (which also sets INT_DATARDY)
     */
    @Throws(IOException::class)
    fun enableInterrupt(source: Int) {
        measMode = measMode or intBits(source)
        writeMeasMode()
    }

    /**
     * Disable nINT for a source, keeping the drive mode.
     *
     * @param source SOURCE_DATA_READY and/or SOURCE_THRESHOLD
     */
    @Throws(IOException::class)
    fun disableInterrupt(source: Int) {
        measMode = measMode and intBits(source).inv() and 0xFF
        writeMeasMode()
    }

    /** Read STATUS; STATUS_DATA_READY marks a new sample. Does not release nINT. */
    @Throws(IOException::class)
    fun pollInterrupt(): Int = status()

    /**
     * Subscribe to the interrupt: falling edge on [intPin] (default the connection's INT pin), or a
     * 20 ms polling thread when there is none.
     *
     * @param callback receives the STATUS byte when DATA_READY is set; it should read the sample to release nINT
     * @param intPin pin wired to nINT, or null for the polling fallback
     */
    fun onInterrupt(callback: IntConsumer, intPin: InputPin? = connection.intPin()) {
        offInterrupt()
        this.callback = callback
        if (intPin != null) {
            subscribedPin = intPin
            intPin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            polling = true
            pollThread = Thread({
                while (polling) {
                    handleEdge()
                    try {
                        Thread.sleep(20)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                }
            }, "ccs811-poll").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** Unsubscribe and stop delivery. */
    fun offInterrupt() {
        callback = null
        subscribedPin?.offEdge(edgeHandler)
        subscribedPin = null
        polling = false
        pollThread?.interrupt()
        pollThread = null
    }

    private fun handleEdge() {
        val cb = callback ?: return
        try {
            val status = pollInterrupt()
            if (status and STATUS_DATA_READY != 0) cb.accept(status)
        } catch (e: IOException) {
            // a failed poll is retried on the next edge / tick
        }
    }
}
