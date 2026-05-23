package it.uhde.periph.chips.magnetometer

import it.uhde.periph.transport.Transport

/**
 * AS5600 — 12-bit programmable contactless rotary position sensor (minimal driver).
 *
 * Reads the absolute angle via the ANGLE register (0x0E-0x0F). The chip has a
 * fixed I²C address of 0x36. No configuration is required for basic angle readout;
 * the factory default (CONF=0x0000, NOM mode, hysteresis off, analog output) is
 * suitable for normal use.
 *
 * ## Initialization
 * The constructor reads the STATUS register and verifies MD=1 (magnet detected).
 * If MD=0, an [IllegalStateException] is thrown — output data is invalid without a magnet.
 */
open class As5600Minimal(
    protected val transport: Transport
) {
    companion object {
        // Register addresses
        const val REG_ZMCO       = 0x00
        const val REG_ZPOS_H     = 0x01
        const val REG_ZPOS_L     = 0x02
        const val REG_MPOS_H     = 0x03
        const val REG_MPOS_L     = 0x04
        const val REG_MANG_H     = 0x05
        const val REG_MANG_L     = 0x06
        const val REG_CONF_H     = 0x07
        const val REG_CONF_L     = 0x08
        const val REG_STATUS     = 0x0B
        const val REG_RAW_ANGLE_H = 0x0C
        const val REG_RAW_ANGLE_L = 0x0D
        const val REG_ANGLE_H    = 0x0E
        const val REG_ANGLE_L    = 0x0F
        const val REG_AGC        = 0x1A
        const val REG_MAGNITUDE_H = 0x1B
        const val REG_MAGNITUDE_L = 0x1C
        const val REG_BURN       = 0xFF

        // STATUS register bit masks
        const val STATUS_MD = 0x08  // bit 3: magnet detected
        const val STATUS_ML = 0x10  // bit 4: magnet too weak
        const val STATUS_MH = 0x20  // bit 5: magnet too strong

        /** Fixed I²C address. */
        const val I2C_ADDR = 0x36
    }

    init {
        val status = readReg8(REG_STATUS)
        if ((status and STATUS_MD) == 0) {
            throw IllegalStateException("AS5600: magnet not detected (MD=0)")
        }
    }

    /**
     * Read the absolute angle in degrees.
     *
     * Reads the ANGLE register (0x0E-0x0F), which respects any OTP-programmed
     * ZPOS/MPOS range. The result is scaled to 0.0–360.0 (exclusive).
     *
     * @return angle in degrees, 0.0–360.0
     */
    fun angle(): Double {
        val raw = angleRaw()
        return raw * 360.0 / 4096.0
    }

    /**
     * Read the raw 12-bit angle count.
     *
     * Reads the ANGLE register (0x0E-0x0F), which respects any OTP-programmed
     * ZPOS/MPOS range. Returns 0–4095.
     *
     * @return raw angle count, 0–4095
     */
    fun angleRaw(): Int = readReg12(REG_ANGLE_H)

    /**
     * Check whether a magnet is detected.
     *
     * Reads the STATUS register and returns the MD flag (bit 3).
     * Output data is valid only when MD=1.
     *
     * @return true if magnet is detected (Bz ≥ 8 mT)
     */
    fun isMagnetDetected(): Boolean = (readReg8(REG_STATUS) and STATUS_MD) != 0

    /**
     * Check whether the magnet is too strong.
     *
     * Reads the STATUS register and returns the MH flag (bit 5).
     * MH=1 means AGC minimum gain overflow (Bz > 90 mT).
     *
     * @return true if magnet is too strong
     */
    fun isMagnetTooStrong(): Boolean = (readReg8(REG_STATUS) and STATUS_MH) != 0

    /**
     * Check whether the magnet is too weak.
     *
     * Reads the STATUS register and returns the ML flag (bit 4).
     * ML=1 means AGC maximum gain overflow (Bz < 30 mT).
     *
     * @return true if magnet is too weak
     */
    fun isMagnetTooWeak(): Boolean = (readReg8(REG_STATUS) and STATUS_ML) != 0

    // ---- low-level helpers ----

    /**
     * Write a single byte to a register.
     *
     * @param reg register address
     * @param val 8-bit value
     */
    protected fun writeReg8(reg: Int, `val`: Int) {
        transport.write(byteArrayOf(reg.toByte(), `val`.toByte()))
    }

    /**
     * Read a single byte from a register.
     *
     * @param reg register address
     * @return unsigned 8-bit value (0–255)
     */
    protected fun readReg8(reg: Int): Int {
        val b = transport.writeRead(byteArrayOf(reg.toByte()), 1)
        return b[0].toInt() and 0xFF
    }

    /**
     * Write a 12-bit value split across two registers (high byte first).
     *
     * Writes the high register (bits 11:8 in bits 3:0) then the low register
     * (bits 7:0). Used for ZPOS, MPOS, MANG.
     *
     * @param regHi high-byte register address
     * @param regLo low-byte register address
     * @param val   12-bit value (0–4095)
     */
    protected fun writeReg12(regHi: Int, regLo: Int, `val`: Int) {
        val v = `val` and 0xFFF
        transport.write(byteArrayOf(
            regHi.toByte(),
            ((v shr 8) and 0x0F).toByte(),
            (v and 0xFF).toByte()
        ))
    }

    /**
     * Read a 12-bit value from two consecutive registers (high byte first).
     *
     * Reads two bytes starting at [regHi]; the high byte contains
     * bits 11:8 in its lower 4 bits.
     *
     * @param regHi high-byte register address
     * @return 12-bit value (0–4095)
     */
    protected fun readReg12(regHi: Int): Int {
        val b = transport.writeRead(byteArrayOf(regHi.toByte()), 2)
        return ((b[0].toInt() and 0x0F) shl 8) or (b[1].toInt() and 0xFF)
    }

    /**
     * Read a 16-bit value from two consecutive registers (big-endian).
     *
     * @param regHi high-byte register address
     * @return unsigned 16-bit value (0–65535)
     */
    protected fun readReg16(regHi: Int): Int {
        val b = transport.writeRead(byteArrayOf(regHi.toByte()), 2)
        return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
    }
}
