package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException
import java.util.function.IntConsumer
import kotlin.math.ln
import kotlin.math.max

/**
 * HTS221 full driver — extends [Hts221Minimal] with single values, raw outputs, output data rate,
 * averaging, power modes, heater, DRDY pin, calibration access, dew point and data-ready
 * interrupt support.
 *
 * @param conn I²C register connection bound to 0x5F
 */
open class Hts221Full(conn: RegisterConnection) : Hts221Minimal(conn) {
    companion object {
        /** `STATUS_REG` flag: humidity data available. */
        const val STATUS_H_DA = 0x02
        /** `STATUS_REG` flag: temperature data available. */
        const val STATUS_T_DA = 0x01
        /** Expected `WHO_AM_I` value. */
        const val WHO_AM_I_VALUE = 0xBC

        private val ODR_HZ = doubleArrayOf(0.0, 1.0, 7.0, 12.5)

        private fun log2Index(value: Int, min: Int, max: Int): Int {
            if (value < min || value > max || (value and (value - 1)) != 0) return -1
            return Integer.numberOfTrailingZeros(value) - Integer.numberOfTrailingZeros(min)
        }
    }

    @Volatile private var callback: IntConsumer? = null
    private var intPin: InputPin? = null
    private var pollThread: Thread? = null
    @Volatile private var polling = false
    private val edgeHandler = EdgeHandler { handleEdge() }

    /**
     * Measure the relative humidity only (one-shot).
     *
     * @return relative humidity in %rH, clamped to 0–100
     */
    fun readHumidity(): Double = humidityFromRaw(oneShotRaw()[0])

    /**
     * Measure the temperature only (one-shot).
     *
     * @return temperature in °C
     */
    fun readTemperature(): Double = temperatureFromRaw(oneShotRaw()[1])

    /**
     * Measure and return the raw humidity output (one-shot).
     *
     * @return signed 16-bit raw value
     */
    fun readRawHumidity(): Int = oneShotRaw()[0]

    /**
     * Measure and return the raw temperature output (one-shot).
     *
     * @return signed 16-bit raw value
     */
    fun readRawTemperature(): Int = oneShotRaw()[1]

    /**
     * Read the latest sample without starting a conversion (continuous output data rate).
     *
     * @return {temperature in °C, relative humidity in %rH}
     */
    fun readContinuous(): DoubleArray {
        val raw = readRawPair()
        return doubleArrayOf(temperatureFromRaw(raw[1]), humidityFromRaw(raw[0]))
    }

    /**
     * Measure once and compute the dew point (Magnus formula, over water).
     *
     * @return dew point in °C
     */
    fun dewPoint(): Double {
        val m = read()
        val t = m[0]
        val h = max(m[1], 0.01)
        val g = ln(h / 100.0) + 17.62 * t / (243.12 + t)
        return 243.12 * g / (17.62 - g)
    }

    /**
     * Set the output data rate.
     *
     * @param hz 0 (one-shot), 1, 7 or 12.5
     * @throws IllegalArgumentException for any other value
     */
    fun setOutputDataRate(hz: Double) {
        val code = ODR_HZ.indexOfFirst { it == hz }
        require(code >= 0) { "HTS221: output data rate must be 0, 1, 7 or 12.5 Hz" }
        val ctrl1 = readReg(REG_CTRL_REG1)
        writeReg(REG_CTRL_REG1, (ctrl1 and CTRL1_ODR_MASK.inv()) or code)
        // Clear flags left over from continuous sampling so the next one-shot wait does not return early.
        readRawPair()
    }

    /**
     * Read the output data rate.
     *
     * @return 0, 1, 7 or 12.5 Hz
     */
    fun getOutputDataRate(): Double = ODR_HZ[readReg(REG_CTRL_REG1) and CTRL1_ODR_MASK]

    /**
     * Set the internal averaging (`AV_CONF`).
     *
     * @param temperatureSamples 2, 4, 8, 16, 32, 64, 128 or 256
     * @param humiditySamples 4, 8, 16, 32, 64, 128, 256 or 512
     * @throws IllegalArgumentException for any other value
     */
    fun setAveraging(temperatureSamples: Int, humiditySamples: Int) {
        val t = log2Index(temperatureSamples, 2, 256)
        val h = log2Index(humiditySamples, 4, 512)
        require(t >= 0) { "HTS221: temperatureSamples must be a power of two, 2..256" }
        require(h >= 0) { "HTS221: humiditySamples must be a power of two, 4..512" }
        writeReg(REG_AV_CONF, (t shl 3) or h)
    }

    /**
     * Read the internal averaging.
     *
     * @return {temperature samples, humidity samples}
     */
    fun getAveraging(): IntArray {
        val v = readReg(REG_AV_CONF)
        return intArrayOf(2 shl ((v shr 3) and 0x07), 4 shl (v and 0x07))
    }

    /**
     * Enable or disable block data update (`CTRL_REG1.BDU`).
     *
     * @param enabled true to keep the output registers stable between reading LSB and MSB
     */
    fun setBlockDataUpdate(enabled: Boolean) {
        val ctrl1 = readReg(REG_CTRL_REG1)
        writeReg(REG_CTRL_REG1, if (enabled) ctrl1 or CTRL1_BDU else ctrl1 and CTRL1_BDU.inv())
    }

    /** Enter power-down mode (`CTRL_REG1.PD = 0`). */
    fun powerDown() = writeReg(REG_CTRL_REG1, readReg(REG_CTRL_REG1) and CTRL1_PD.inv())

    /** Leave power-down mode (`CTRL_REG1.PD = 1`). */
    fun powerUp() = writeReg(REG_CTRL_REG1, readReg(REG_CTRL_REG1) or CTRL1_PD)

    /**
     * Switch the internal heater on or off. Do not measure while it is on.
     *
     * @param on true to heat
     */
    fun setHeater(on: Boolean) {
        val ctrl2 = readReg(REG_CTRL_REG2)
        writeReg(REG_CTRL_REG2, if (on) ctrl2 or CTRL2_HEATER else ctrl2 and CTRL2_HEATER.inv())
    }

    /**
     * Read the heater state.
     *
     * @return true when the heater is on
     */
    fun isHeaterOn(): Boolean = (readReg(REG_CTRL_REG2) and CTRL2_HEATER) != 0

    /**
     * Reload the trimming registers (`CTRL_REG2.BOOT`), wait for it to clear (timeout 100 ms) and
     * re-read the calibration.
     *
     * @throws IOException on bus error or when BOOT does not clear in time
     */
    fun reboot() {
        val ctrl2 = readReg(REG_CTRL_REG2)
        writeReg(REG_CTRL_REG2, ctrl2 or CTRL2_BOOT)
        repeat(TIMEOUT_MS) {
            if ((readReg(REG_CTRL_REG2) and CTRL2_BOOT) == 0) {
                loadCalibration()
                return
            }
            sleepMs(1)
        }
        throw IOException("HTS221 reboot timeout")
    }

    /**
     * Read the `WHO_AM_I` register.
     *
     * @return the device ID (0xBC for the HTS221)
     */
    fun whoAmI(): Int = readReg(REG_WHO_AM_I)

    /**
     * Read `STATUS_REG`.
     *
     * @return raw status ([STATUS_H_DA] / [STATUS_T_DA])
     */
    fun status(): Int = readReg(REG_STATUS)

    /**
     * Check whether new humidity and temperature data are both available.
     *
     * @return true when H_DA and T_DA are both set
     */
    fun dataReady(): Boolean = (status() and STATUS_BOTH) == STATUS_BOTH

    /**
     * Decoded calibration coefficients read at construction (and after [reboot]).
     *
     * @return {H0_rH, H1_rH, T0_degC, T1_degC, H0_T0_OUT, H1_T0_OUT, T0_OUT, T1_OUT}
     */
    fun readCalibration(): DoubleArray = doubleArrayOf(
        h0rH, h1rH, t0degC, t1degC,
        h0t0Out.toDouble(), h1t0Out.toDouble(), t0Out.toDouble(), t1Out.toDouble()
    )

    /**
     * Configure the DRDY output pin (`CTRL_REG3`).
     *
     * @param enabled route data-ready to the DRDY pin
     * @param activeHigh false selects active-low polarity
     * @param openDrain true selects an open-drain output (default push-pull)
     */
    fun configureDrdy(enabled: Boolean, activeHigh: Boolean = true, openDrain: Boolean = false) {
        var v = 0
        if (!activeHigh) v = v or 0x80
        if (openDrain) v = v or 0x40
        if (enabled) v = v or 0x04
        writeReg(REG_CTRL_REG3, v)
    }

    /**
     * Subscribe to data-ready events.
     *
     * Enables DRDY (active high, push-pull). Delivery uses the connection's `intPin()` (rising
     * edge) when one is wired and falls back to a polling thread at the output data rate
     * otherwise. The callback receives `STATUS_REG`.
     *
     * @param callback called with the status
     */
    fun onInterrupt(callback: IntConsumer) {
        offInterrupt()
        configureDrdy(true)
        this.callback = callback
        val pin = conn.intPin()
        if (pin != null) {
            intPin = pin
            pin.onEdge(edgeHandler, EdgeTrigger.RISING)
        } else {
            startPolling()
        }
    }

    /** Unsubscribe from data-ready events and disable the DRDY output. */
    fun offInterrupt() {
        val had = callback != null
        callback = null
        intPin?.let {
            it.offEdge(edgeHandler)
            intPin = null
        }
        stopPolling()
        if (had) configureDrdy(false)
    }

    /**
     * Read `STATUS_REG`; the flags clear when the output registers are read.
     *
     * @return raw status ([STATUS_H_DA] / [STATUS_T_DA])
     */
    fun pollInterrupt(): Int = status()

    private fun startPolling() {
        polling = true
        pollThread = Thread({
            while (polling) {
                try {
                    if (dataReady()) handleEdge()
                    val hz = getOutputDataRate()
                    Thread.sleep(if (hz > 0) (1000 / hz).toLong() else 100L)
                } catch (e: IOException) {
                    // bus error; try again on the next tick
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }, "hts221-poll")
        pollThread!!.isDaemon = true
        pollThread!!.start()
    }

    private fun stopPolling() {
        polling = false
        pollThread?.interrupt()
        pollThread = null
    }

    private fun handleEdge() {
        try {
            val status = pollInterrupt()
            callback?.accept(status)
        } catch (e: IOException) {
            // bus error; wait for the next edge/tick rather than propagating
        }
    }
}
