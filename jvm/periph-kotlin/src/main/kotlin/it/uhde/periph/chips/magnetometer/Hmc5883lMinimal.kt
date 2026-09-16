package it.uhde.periph.chips.magnetometer

import it.uhde.periph.connection.Connection

/**
 * HMC5883L — 3-axis magnetometer (minimal driver).
 *
 * Reads magnetic field on all three axes in continuous mode with sensible
 * defaults baked in. The chip has a fixed I²C address of 0x1E.
 *
 * ## Default behaviour (baked into Minimal):
 * - Averaging: 8 samples (MA=11)
 * - ODR: 15 Hz (DO=100)
 * - Gain: ±1.3 Ga (GN=001), 1090 LSb/Gauss
 * - Mode: continuous measurement
 */
open class Hmc5883lMinimal(
    protected val connection: Connection
) {
    companion object {
        // Register addresses
        const val REG_CONFIG_A    = 0x00
        const val REG_CONFIG_B    = 0x01
        const val REG_MODE        = 0x02
        const val REG_DATA_X_MSB  = 0x03
        const val REG_STATUS      = 0x09
        const val REG_ID_A        = 0x0A
        const val REG_ID_B        = 0x0B
        const val REG_ID_C        = 0x0C

        // Fixed I²C address
        const val I2C_ADDR = 0x1E

        // Gain table: LSb per Gauss
        val GAIN_LSB_PER_GAUSS = intArrayOf(
            1370, // GN=0: ±0.88 Ga
            1090, // GN=1: ±1.3 Ga (default)
            820,  // GN=2: ±1.9 Ga
            660,  // GN=3: ±2.5 Ga
            440,  // GN=4: ±4.0 Ga
            390,  // GN=5: ±4.7 Ga
            330,  // GN=6: ±5.6 Ga
            230   // GN=7: ±8.1 Ga
        )
    }

    @JvmField
    protected var gain = 1
    @JvmField
    protected var gainLsbPerGauss = GAIN_LSB_PER_GAUSS[1]

    init {
        initMinimal()
    }

    protected fun initMinimal() {
        writeReg8(REG_CONFIG_A, 0x70)  // 8 avg, 15 Hz, normal
        writeReg8(REG_CONFIG_B, 0x20)  // gain=1 (±1.3 Ga)
        writeReg8(REG_MODE, 0x00)      // continuous mode
        Thread.sleep(6)
    }

    /**
     * Read magnetic field on all three axes.
     *
     * Performs a 6-byte burst read from register 0x03 (X MSB). The output
     * register byte order is X, Z, Y (not X, Y, Z).
     *
     * @return array [x, y, z] magnetic field strength in Tesla.
     *         Returns null for any axis that overflows (raw == -4096).
     */
    fun magneticField(): Array<Double?> {
        val raw = connection.writeRead(byteArrayOf(REG_DATA_X_MSB.toByte()), 6)
        val rawX = (raw[0].toInt() shl 8) or (raw[1].toInt() and 0xFF)
        val rawZ = (raw[2].toInt() shl 8) or (raw[3].toInt() and 0xFF)
        val rawY = (raw[4].toInt() shl 8) or (raw[5].toInt() and 0xFF)
        // Convert to signed 16-bit
        val sx = rawX.toShort()
        val sz = rawZ.toShort()
        val sy = rawY.toShort()

        return arrayOf(
            rawToTesla(sx.toInt()),
            rawToTesla(sy.toInt()),
            rawToTesla(sz.toInt())
        )
    }

    protected fun rawToTesla(raw: Int): Double? {
        if (raw == -4096) return null
        return (raw.toDouble() / gainLsbPerGauss) * 1e-4  // Gauss → Tesla
    }

    // ---- low-level helpers ----

    protected fun writeReg8(reg: Int, `val`: Int) {
        connection.write(byteArrayOf(reg.toByte(), `val`.toByte()))
    }

    protected fun readReg8(reg: Int): Int {
        val b = connection.writeRead(byteArrayOf(reg.toByte()), 1)
        return b[0].toInt() and 0xFF
    }

    protected fun readReg16(regHi: Int): Int {
        val b = connection.writeRead(byteArrayOf(regHi.toByte()), 2)
        return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
    }

    protected fun readReg16Signed(regHi: Int): Int {
        val b = connection.writeRead(byteArrayOf(regHi.toByte()), 2)
        return ((b[0].toInt() and 0xFF) shl 8 or (b[1].toInt() and 0xFF)).toShort().toInt()
    }
}