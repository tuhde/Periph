package it.uhde.periph.chips.magnetometer

import it.uhde.periph.connection.Connection

/**
 * HMC5883L — full driver. Extends [Hmc5883lMinimal] with configuration,
 * single-shot mode, self-test, identification, and status access.
 */
class Hmc5883lFull(connection: Connection) : Hmc5883lMinimal(connection) {

    /**
     * Write Configuration Registers A and B.
     *
     * @param odr        Data output rate in Hz (continuous mode). Valid: 0.75, 1.5, 3, 7.5, 15, 30, 75.
     * @param averaging  Samples averaged per output. Valid: 1, 2, 4, 8.
     * @param gain       Gain index 0–7.
     */
    fun configure(odr: Double, averaging: Int, gain: Int) {
        val ma = when (averaging) {
            1 -> 0b00
            2 -> 0b01
            4 -> 0b10
            8 -> 0b11
            else -> throw IllegalArgumentException("averaging must be 1, 2, 4, or 8")
        }

        val doBits = when {
            Math.abs(odr - 0.75) < 0.01 -> 0b000
            Math.abs(odr - 1.5) < 0.01  -> 0b001
            Math.abs(odr - 3.0) < 0.01  -> 0b010
            Math.abs(odr - 7.5) < 0.01  -> 0b011
            Math.abs(odr - 15.0) < 0.01 -> 0b100
            Math.abs(odr - 30.0) < 0.01 -> 0b101
            Math.abs(odr - 75.0) < 0.01 -> 0b110
            else -> throw IllegalArgumentException("odr must be 0.75, 1.5, 3, 7.5, 15, 30, or 75")
        }

        if (gain !in 0..7) {
            throw IllegalArgumentException("gain must be 0–7")
        }

        val configA = (ma shl 5) or (doBits shl 2)
        writeReg8(REG_CONFIG_A, configA)

        val configB = gain shl 5
        writeReg8(REG_CONFIG_B, configB)

        this.gain = gain
        this.gainLsbPerGauss = GAIN_LSB_PER_GAUSS[gain]
    }

    /**
     * Update the gain setting (GN bits in Config B).
     *
     * @param gain Gain index 0–7.
     */
    fun setGain(gain: Int) {
        if (gain !in 0..7) {
            throw IllegalArgumentException("gain must be 0–7")
        }
        writeReg8(REG_CONFIG_B, gain shl 5)
        this.gain = gain
        this.gainLsbPerGauss = GAIN_LSB_PER_GAUSS[gain]
    }

    /**
     * Set the operating mode.
     *
     * @param mode "continuous", "single", or "idle".
     */
    fun setMode(mode: String) {
        val md = when (mode) {
            "continuous" -> 0b00
            "single"     -> 0b01
            "idle"       -> 0b10
            else -> throw IllegalArgumentException("mode must be 'continuous', 'single', or 'idle'")
        }
        writeReg8(REG_MODE, md)
    }

    /**
     * Check if new measurement data is ready.
     *
     * @return true if RDY bit is set in Status Register.
     */
    fun dataReady(): Boolean {
        return (readReg8(REG_STATUS) and 0x01) != 0
    }

    /**
     * Read the raw Status Register.
     *
     * @return Raw STATUS register byte (RDY in bit 0, LOCK in bit 1).
     */
    fun status(): Int {
        return readReg8(REG_STATUS)
    }

    /**
     * Take a single measurement in single-shot mode.
     *
     * Writes single-measurement mode, waits 6 ms, then reads all three axes.
     *
     * @return array [x, y, z] magnetic field strength in Tesla.
     *         Returns null for any axis that overflows (raw == -4096).
     */
    fun singleMeasurement(): Array<Double?> {
        writeReg8(REG_MODE, 0x01)
        Thread.sleep(6)
        return magneticField()
    }

    /**
     * Read the identification registers.
     *
     * @return array [id_a, id_b, id_c] — expected (0x48, 0x34, 0x33) = ASCII "H43".
     */
    fun identify(): IntArray {
        val idA = readReg8(REG_ID_A)
        val idB = readReg8(REG_ID_B)
        val idC = readReg8(REG_ID_C)
        return intArrayOf(idA, idB, idC)
    }

    /**
     * Run self-test with positive or negative bias.
     *
     * @param positive true for positive bias (MS=01), false for negative bias (MS=10).
     * @return array [x, y, z] magnetic field deflection in Tesla during self-test.
     *         Returns null for any axis that overflows.
     */
    fun selfTest(positive: Boolean): Array<Double?> {
        val configA = readReg8(REG_CONFIG_A)
        val ms = if (positive) 0b01 else 0b10
        writeReg8(REG_CONFIG_A, (configA and 0xFC) or ms)

        writeReg8(REG_MODE, 0x01)
        Thread.sleep(6)
        val result = magneticField()

        writeReg8(REG_CONFIG_A, (configA and 0xFC) or 0b00)
        return result
    }
}