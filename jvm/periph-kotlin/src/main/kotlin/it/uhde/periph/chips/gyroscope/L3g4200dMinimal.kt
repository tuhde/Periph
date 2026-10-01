package it.uhde.periph.chips.gyroscope

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * L3G4200D three-axis MEMS gyroscope — minimal driver.
 *
 * Provides angular rate readings on the X, Y, and Z axes with no
 * configuration beyond the connection. I²C address is 0x68 (SA0=GND) or
 * 0x69 (SA0=VDD). SPI uses Mode 3 (CPOL=CPHA=1) by default.
 *
 * Default configuration: 100 Hz ODR, 12.5 Hz LPF2 cutoff, ±250 dps
 * full scale, BDU=1, all axes enabled, FIFO disabled, HPF disabled.
 *
 * @property connection I²C, SMBus, or SPI register connection bound to the device
 *                   (SPI: read bit 0xC0, no multi-byte bit).
 * @constructor Creates an L3G4200D driver and runs the init sequence.
 * @throws IOException on I²C error or wrong chip ID.
 */
open class L3g4200dMinimal(protected val connection: RegisterConnection) {
    protected var fullScaleDps: Int = 250

    init {
        val id = readReg(REG_WHO_AM_I, 1)
        if ((id[0].toInt() and 0xFF) != WHO_AM_I_EXPECTED) {
            throw IOException("L3G4200D not found: WHO_AM_I expected 0x"
                    + Integer.toHexString(WHO_AM_I_EXPECTED) + ", got 0x" + Integer.toHexString(id[0].toInt() and 0xFF))
        }
        connection.write(REG_CTRL_REG4, byteArrayOf(CTRL_REG4_DEFAULT.toByte()))
        connection.write(REG_CTRL_REG1, byteArrayOf(CTRL_REG1_DEFAULT.toByte()))
    }

    /**
     * Read [n] bytes starting at [reg]. I²C needs bit 7 of the sub-address set
     * for multi-byte auto-increment; on SPI the connection's read bit 0xC0
     * already ORs it in (idempotent).
     */
    protected fun readReg(reg: Int, n: Int): ByteArray =
        connection.read(if (n > 1) reg or 0x80 else reg, n)

    /**
     * Read angular rate on all three axes as a single burst transaction.
     *
     * @return Triple of (x, y, z) angular rates in rad/s.
     * @throws IOException on I²C error.
     */
    open fun angularRate(): Triple<Float, Float, Float> {
        val raw: ByteArray = readReg(REG_OUT_X_L, 6)
        val sens = sensitivity(fullScaleDps)
        val k = (Math.PI / 180.0).toFloat()
        val x = int16Le(raw, 0) * sens * k
        val y = int16Le(raw, 2) * sens * k
        val z = int16Le(raw, 4) * sens * k
        return Triple(x, y, z)
    }

    companion object {
        /** Register addresses. */
        const val REG_WHO_AM_I     = 0x0F
        const val REG_CTRL_REG1   = 0x20
        const val REG_CTRL_REG2   = 0x21
        const val REG_CTRL_REG3   = 0x22
        const val REG_CTRL_REG4   = 0x23
        const val REG_CTRL_REG5   = 0x24
        const val REG_OUT_TEMP    = 0x26
        const val REG_STATUS      = 0x27
        const val REG_OUT_X_L     = 0x28
        const val REG_OUT_X_H     = 0x29
        const val REG_OUT_Y_L     = 0x2A
        const val REG_OUT_Y_H     = 0x2B
        const val REG_OUT_Z_L     = 0x2C
        const val REG_OUT_Z_H     = 0x2D
        const val REG_FIFO_CTRL   = 0x2E
        const val REG_FIFO_SRC    = 0x2F
        const val REG_INT1_CFG    = 0x30
        const val REG_INT1_SRC    = 0x31
        const val REG_INT1_THS_XH = 0x32
        const val REG_INT1_THS_XL = 0x33
        const val REG_INT1_THS_YH = 0x34
        const val REG_INT1_THS_YL = 0x35
        const val REG_INT1_THS_ZH = 0x36
        const val REG_INT1_THS_ZL = 0x37
        const val REG_INT1_DURATION = 0x38

        const val WHO_AM_I_EXPECTED = 0xD3
        const val CTRL_REG1_DEFAULT = 0x0F
        const val CTRL_REG4_DEFAULT = 0x80

        @JvmStatic
        fun sensitivity(fullScale: Int): Float = when (fullScale) {
            250 -> 8.75e-3f
            500 -> 17.5e-3f
            2000 -> 70.0e-3f
            else -> 8.75e-3f
        }

        @JvmStatic
        fun int16Le(data: ByteArray, offset: Int): Float {
            val v = (data[offset].toInt() and 0xFF) or
                    ((data[offset + 1].toInt() and 0xFF) shl 8)
            return v.toShort().toFloat()
        }
    }
}
