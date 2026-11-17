package it.uhde.periph.chips.power

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * LTC4151 — full driver. Extends [Ltc4151Minimal] with all three channels,
 * snapshot conversions and CONTROL register access.
 *
 * Channel constants: [SENSE] = 0, [VIN] = 1, [ADIN] = 2.
 *
 * @param connection I²C connection bound to the LTC4151 device address
 * @param rSense sense resistor in ohms; must be greater than 0
 */
class Ltc4151Full(
    connection: RegisterConnection,
    rSense: Double = 0.02
) : Ltc4151Minimal(connection, rSense) {

    companion object {
        /** Channel: voltage across the sense resistor. */
        const val SENSE = 0
        /** Channel: supply voltage at VIN. */
        const val VIN = 1
        /** Channel: ADIN pin. */
        const val ADIN = 2

        private const val CTRL_SNAPSHOT  = 0x80
        private const val CTRL_TEST      = 0x10
        private const val CTRL_PAGE_RW   = 0x08
        private const val CTRL_STUCK_BUS = 0x04
        private const val SNAPSHOT_TIMEOUT_MS = 100L

        /**
         * Convert an ADIN/VIN code pair to temperature (datasheet NTC example circuit,
         * valid for roughly 20–60 °C only).
         *
         * @return temperature in °C, 58.82 × (adinCode / vinCode − 0.1066)
         */
        fun temperatureFromAdin(adinCode: Int, vinCode: Int): Double =
            58.82 * (adinCode.toDouble() / vinCode - 0.1066)
    }

    /** Result of [readAll]; all values in SI units (V, A, W). */
    data class Readings(val vin: Double, val senseV: Double, val current: Double, val power: Double, val adin: Double)

    private fun checkChannel(channel: Int) = require(channel in 0..2) { "channel must be 0, 1 or 2" }

    /**
     * Read the voltage across the sense resistor.
     *
     * @return SENSE+ − SENSE− in V (code × 20 µV)
     */
    fun senseVoltage(): Double = code(readBlock(REG_SENSE, 2), 0) * LSB_SENSE

    /**
     * Read the ADIN pin voltage.
     *
     * @return ADIN in V (code × 0.5 mV)
     */
    fun adinVoltage(): Double = code(readBlock(REG_ADIN, 2), 0) * LSB_ADIN

    /**
     * Read all three channels from one block read of registers 0x00–0x05.
     *
     * @return VIN, sense voltage, current, power and ADIN
     */
    fun readAll(): Readings {
        val d = readBlock(REG_SENSE, 6)
        val senseV = code(d, 0) * LSB_SENSE
        val vin = code(d, 2) * LSB_VIN
        val adin = code(d, 4) * LSB_ADIN
        val current = senseV / rSense
        return Readings(vin, senseV, current, vin * current, adin)
    }

    /**
     * Read the 12-bit code of a channel.
     *
     * @param channel [SENSE], [VIN] or [ADIN]
     * @return 12-bit conversion code, 0–4095
     */
    fun readRaw(channel: Int): Int {
        checkChannel(channel)
        return code(readBlock(channel * 2, 2), 0)
    }

    /**
     * Set the sense resistor value used for current and power.
     *
     * @param ohms sense resistor in ohms; must be greater than 0
     */
    fun setSenseResistor(ohms: Double) {
        require(ohms > 0) { "ohms must be > 0" }
        rSense = ohms
    }

    /**
     * Get the sense resistor value.
     *
     * @return sense resistor in ohms
     */
    fun getSenseResistor(): Double = rSense

    /**
     * Run one snapshot conversion of a channel, then return to continuous scan.
     *
     * Clears the snapshot bit, writes the channel with the snapshot bit set, polls the
     * channel's BUSY bit (timeout 100 ms), reads the result and restores continuous scan.
     *
     * @param channel [SENSE], [VIN] or [ADIN]
     * @return converted value in V (sense voltage, VIN or ADIN)
     * @throws IOException if the conversion does not finish within 100 ms
     */
    fun snapshot(channel: Int): Double {
        checkChannel(channel)
        val base = readControlRaw() and (CTRL_PAGE_RW or CTRL_STUCK_BUS)
        writeControlRaw(base)
        writeControlRaw(base or CTRL_SNAPSHOT or (channel shl 5))
        val start = System.nanoTime()
        while (isBusy(channel)) {
            if ((System.nanoTime() - start) / 1_000_000 > SNAPSHOT_TIMEOUT_MS) {
                writeControlRaw(base)
                throw IOException("LTC4151 snapshot timeout")
            }
            Thread.sleep(2)
        }
        val raw = readRaw(channel)
        writeControlRaw(base)
        return raw * when (channel) { SENSE -> LSB_SENSE; VIN -> LSB_VIN; else -> LSB_ADIN }
    }

    /** Switch to continuous scan mode (snapshot bit cleared). */
    fun setContinuous() {
        writeControlRaw(readControlRaw() and (CTRL_SNAPSHOT or CTRL_TEST).inv())
    }

    /**
     * Read the snapshot bit of CONTROL.
     *
     * @return true if the chip is in snapshot mode
     */
    fun isSnapshotMode(): Boolean = (readControlRaw() and CTRL_SNAPSHOT) != 0

    /**
     * Read the BUSY bit of a channel (snapshot mode).
     *
     * @param channel [SENSE], [VIN] or [ADIN]
     * @return true while that channel is being converted
     */
    fun isBusy(channel: Int): Boolean {
        checkChannel(channel)
        return (connection.read(channel * 2 + 1, 1)[0].toInt() and 0x08) != 0
    }

    /**
     * Enable or disable the stuck-bus reset timer (CONTROL.G2, read-modify-write).
     *
     * @param enabled true to enable (power-on default)
     */
    fun setStuckBusTimer(enabled: Boolean) {
        val v = readControlRaw() and CTRL_TEST.inv()
        writeControlRaw(if (enabled) v or CTRL_STUCK_BUS else v and CTRL_STUCK_BUS.inv())
    }

    /**
     * Enable or disable I²C page read/write (CONTROL.G3, read-modify-write). When
     * disabled the driver reads each register separately.
     *
     * @param enabled true to enable (power-on default)
     */
    fun setPageRw(enabled: Boolean) {
        val v = readControlRaw() and CTRL_TEST.inv()
        writeControlRaw(if (enabled) v or CTRL_PAGE_RW else v and CTRL_PAGE_RW.inv())
        pageEnabled = enabled
    }

    /**
     * Read the CONTROL register.
     *
     * @return raw CONTROL value (power-on default 0x0C)
     */
    fun readControl(): Int = readControlRaw()

    /**
     * Write the CONTROL register. Test mode (bit 4) is always masked to 0.
     *
     * @param value raw CONTROL value
     */
    fun writeControl(value: Int) {
        val v = value and CTRL_TEST.inv() and 0xFF
        writeControlRaw(v)
        pageEnabled = (v and CTRL_PAGE_RW) != 0
    }
}
