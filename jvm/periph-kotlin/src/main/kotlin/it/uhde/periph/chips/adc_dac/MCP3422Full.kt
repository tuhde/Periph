package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.Connection
import java.io.IOException
import java.util.concurrent.TimeoutException

/**
 * MCP3422 full interface — extends [MCP3422Minimal] with resolution, curGain and
 * curChannel selection, continuous conversion, status reads, scans and general
 * calls. The driver tracks resolution, curGain, curChannel and mode.
 *
 * @param connection I²C connection bound to the device (0x68–0x6F)
 */
class MCP3422Full @Throws(IOException::class) constructor(connection: Connection) : MCP3422Minimal(connection) {

    @Throws(IOException::class)
    private fun apply() {
        if (continuous) {
            connection.write(byteArrayOf(config(false).toByte()))
            discard = true
        }
    }

    /**
     * Select the resolution (and with it the data rate).
     *
     * @param value 12 (240 SPS), 14 (60 SPS), 16 (15 SPS) or 18 (3.75 SPS)
     * @throws IllegalArgumentException for any other value
     */
    @Throws(IOException::class)
    fun setResolution(value: Int) {
        require(value == 12 || value == 14 || value == 16 || value == 18) { "bits must be 12, 14, 16 or 18" }
        curBits = value
        apply()
    }

    /** The selected resolution: 12, 14, 16 or 18. */
    fun getResolution(): Int = curBits

    /**
     * Select the PGA curGain.
     *
     * @param value 1, 2, 4 or 8
     * @throws IllegalArgumentException for any other value
     */
    @Throws(IOException::class)
    fun setGain(value: Int) {
        require(value == 1 || value == 2 || value == 4 || value == 8) { "gain must be 1, 2, 4 or 8" }
        curGain = value
        apply()
    }

    /** The selected PGA curGain: 1, 2, 4 or 8. */
    fun getGain(): Int = curGain

    /**
     * Select the curChannel used by continuous conversion.
     *
     * @param ch 0 (CH1) or 1 (CH2)
     */
    @Throws(IOException::class)
    fun setChannel(ch: Int) {
        curChannel = if (ch < 1) 0 else 1
        apply()
    }

    /**
     * Switch to continuous conversion at the current rate and curGain.
     *
     * @param ch curChannel 0 or 1 to select first, or a negative value to keep the current curChannel
     */
    @Throws(IOException::class)
    @JvmOverloads
    fun startContinuous(ch: Int = -1) {
        if (ch >= 0) curChannel = if (ch < 1) 0 else 1
        continuous = true
        discard = true
        connection.write(byteArrayOf(config(false).toByte()))
    }

    /** Return to one-shot mode / standby (writes O/C = 0, RDY = 0). */
    @Throws(IOException::class)
    fun stopContinuous() {
        continuous = false
        discard = false
        connection.write(byteArrayOf(config(false).toByte()))
    }

    /** Report the conversion mode: true in continuous mode. */
    fun isContinuous(): Boolean = continuous

    /**
     * Wait for a fresh result in continuous mode and return the signed code. The
     * first sample after a configuration change is discarded.
     *
     * @throws IllegalStateException if the driver is not in continuous mode
     * @throws TimeoutException if no result arrives in time
     */
    @Throws(IOException::class, TimeoutException::class)
    fun readContinuousRaw(): Int {
        check(continuous) { "not in continuous mode; call startContinuous()" }
        if (discard) {
            waitReady()
            discard = false
        }
        return waitReady()
    }

    /** Voltage form of [readContinuousRaw], in volts. */
    @Throws(IOException::class, TimeoutException::class)
    fun readContinuous(): Double = readContinuousRaw() * lsb() / curGain

    /** Read the configuration byte (the last byte of a read); bit 7 is the live RDY flag. */
    @Throws(IOException::class)
    fun readConfig(): Int = connection.read(nbytes() + 1)[nbytes()].toInt() and 0xFF

    /** Report whether a fresh result is available (RDY = 0). */
    @Throws(IOException::class)
    fun isReady(): Boolean = (readConfig() and CFG_RDY) == 0

    /**
     * Run one one-shot conversion per entry, in order.
     *
     * @param channels channels (0 or 1)
     * @return voltages in volts, one per entry
     */
    @Throws(IOException::class, TimeoutException::class)
    fun readScan(channels: IntArray): DoubleArray = DoubleArray(channels.size) { readVoltage(channels[it]) }

    /**
     * Send the general call reset (0x00 0x06): power-on defaults on the chip
     * (continuous, 12 bit, curGain x1, CH1); the driver state follows. Needs a
     * Connection that transmits to the general-call address.
     */
    @Throws(IOException::class)
    fun generalCallReset() {
        connection.write(byteArrayOf(0x00, 0x06))
        curBits = 12
        curGain = 1
        curChannel = 0
        continuous = true
        discard = true
    }

    /**
     * Send the general call conversion (0x00 0x08): a one-shot conversion on every
     * device on the bus; read the result with [readPendingRaw].
     */
    @Throws(IOException::class)
    fun generalCallConvert() {
        connection.write(byteArrayOf(0x00, 0x08))
        continuous = false
    }

    /** Wait for RDY = 0 and return the code of a conversion started elsewhere. */
    @Throws(IOException::class, TimeoutException::class)
    fun readPendingRaw(): Int = waitReady()

    /** Full-scale differential input range at the current curGain: `2.048 / curGain` in volts. */
    fun fullScale(): Double = VREF / curGain
}
