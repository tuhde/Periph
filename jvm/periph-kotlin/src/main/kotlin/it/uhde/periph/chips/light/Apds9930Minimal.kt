package it.uhde.periph.chips.light

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * APDS-9930 — digital ambient light and proximity sensor (minimal driver).
 *
 * Provides illuminance (lux, IR-compensated) and raw proximity count with
 * no configuration required beyond the connection. Both engines are
 * enabled at construction with sensible defaults that give stable readings
 * under typical indoor/outdoor lighting.
 *
 * Default I²C address: 0x39 (fixed).
 *
 * Configuration defaults:
 * - ATIME: 0xDB (101 ms integration — rejects 50/60 Hz fluorescent flicker)
 * - PTIME: 0xFF (2.73 ms proximity ADC time, datasheet default)
 * - PPULSE: 0x08 (8 LED pulses — factory-calibrated for 100 mm range)
 * - CONTROL: 0x20 (PDIODE=Ch1, PDRIVE=100 mA, PGAIN=1x, AGAIN=1x)
 * - ENABLE: 0x07 (PON + AEN + PEN; wait timer and interrupts disabled)
 */
open class Apds9930Minimal protected constructor(
    protected val connection: Connection,
) {
    /** Read the device ID register (expect 0x39). */
    fun chipId(): Int = readReg(REG_ID)

    /**
     * Read the ambient illuminance.
     *
     * Uses Ch0 (visible + IR) and Ch1 (IR-only) to compensate for the
     * IR component of ambient light, then applies the open-air lux
     * coefficients from the datasheet.
     *
     * @return illuminance in lux
     */
    @Throws(IOException::class)
    fun lux(): Float {
        val ch0 = readReg16(REG_CH0DATAL)
        val ch1 = readReg16(REG_CH1DATAL)
        val ctrl = readReg(REG_CONTROL)
        val cfg = readReg(REG_CONFIG)
        val atime = readReg(REG_ATIME)
        val alsitMs = 2.73f * (256 - atime)
        val againX = againFactor(ctrl and 0x03, (cfg and 0x04) != 0)
        var iac1 = ch0 - 1.862f * ch1
        var iac2 = 0.746f * ch0 - 1.291f * ch1
        var iac = iac1
        if (iac2 > iac) iac = iac2
        if (iac < 0f) iac = 0f
        val lpc = (0.49f * 52.0f) / (alsitMs * againX)
        return iac * lpc
    }

    /**
     * Read the proximity ADC count.
     *
     * Higher counts mean a closer object. Realistically limited to
     * 10 bits (0-1023) at the default PTIME=0xFF (one ADC cycle).
     */
    @Throws(IOException::class)
    fun proximity(): Int = readReg16(REG_PDATAL)

    @Throws(IOException::class)
    protected fun writeReg(reg: Int, value: Int) {
        val buf = byteArrayOf(
            (cmdWrite(reg) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
        connection.write(buf)
    }

    @Throws(IOException::class)
    protected fun readReg(reg: Int): Int {
        val buf = ByteArray(1)
        connection.writeRead(byteArrayOf((cmdRead(reg) and 0xFF).toByte()), buf)
        return buf[0].toInt() and 0xFF
    }

    @Throws(IOException::class)
    protected fun readReg16(reg: Int): Int {
        val buf = ByteArray(2)
        connection.writeRead(byteArrayOf((cmdRead(reg) and 0xFF).toByte()), buf)
        return ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
    }

    @Throws(IOException::class)
    protected fun special(functionCode: Int) {
        connection.write(byteArrayOf((cmdSpecial(functionCode) and 0xFF).toByte()))
    }

    companion object {
        const val REG_ENABLE   = 0x00
        const val REG_ATIME    = 0x01
        const val REG_PTIME    = 0x02
        const val REG_WTIME    = 0x03
        const val REG_AILTL    = 0x04
        const val REG_AILTH    = 0x05
        const val REG_AIHTL    = 0x06
        const val REG_AIHTH    = 0x07
        const val REG_PILTL    = 0x08
        const val REG_PILTH    = 0x09
        const val REG_PIHTL    = 0x0A
        const val REG_PIHTH    = 0x0B
        const val REG_PERS     = 0x0C
        const val REG_CONFIG   = 0x0D
        const val REG_PPULSE   = 0x0E
        const val REG_CONTROL  = 0x0F
        const val REG_ID       = 0x12
        const val REG_STATUS   = 0x13
        const val REG_CH0DATAL = 0x14
        const val REG_CH1DATAL = 0x16
        const val REG_PDATAL   = 0x18
        const val REG_POFFSET  = 0x1E

        const val CFN_CLEAR_PROXIMITY = 0x05
        const val CFN_CLEAR_ALS       = 0x06
        const val CFN_CLEAR_BOTH      = 0x07
        const val CMD_SPECIAL         = 0xE0

        const val ATIME_DEFAULT   = 0xDB
        const val PTIME_DEFAULT   = 0xFF
        const val PPULSE_DEFAULT  = 0x08
        const val CONTROL_DEFAULT = 0x20
        const val ENABLE_DEFAULT  = 0x07

        const val CMD_WRITE = 0x80
        const val CMD_READ  = 0xA0

        fun cmdWrite(reg: Int) = CMD_WRITE or (reg and 0x1F)
        fun cmdRead(reg: Int)  = CMD_READ  or (reg and 0x1F)
        fun cmdSpecial(f: Int) = CMD_SPECIAL or (f and 0x1F)

        private fun againFactor(againIdx: Int, agl: Boolean): Float {
            return if (!agl) {
                when (againIdx and 0x03) {
                    0 -> 1.0f
                    1 -> 8.0f
                    2 -> 16.0f
                    else -> 120.0f
                }
            } else {
                when (againIdx and 0x03) {
                    0 -> 1.0f / 6.0f
                    1 -> 8.0f / 6.0f
                    2 -> 16.0f / 6.0f
                    else -> 20.0f
                }
            }
        }

        private fun sleep(ms: Int) {
            try { Thread.sleep(ms.toLong()) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }

    init {
        Thread.sleep(6)
        val id = readReg(REG_ID)
        if (id != 0x39) throw IOException("APDS-9930 not found (ID=0x${Integer.toHexString(id)}, expected 0x39)")
        writeReg(REG_ENABLE, 0x00)
        writeReg(REG_ATIME, ATIME_DEFAULT)
        writeReg(REG_PTIME, PTIME_DEFAULT)
        writeReg(REG_PPULSE, PPULSE_DEFAULT)
        writeReg(REG_CONTROL, CONTROL_DEFAULT)
        writeReg(REG_ENABLE, ENABLE_DEFAULT)
        Thread.sleep(12)
    }
}