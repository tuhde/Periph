package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * ADXL345 — 3-axis MEMS accelerometer (Analog Devices) — minimal interface.
 *
 * Reads X, Y, Z acceleration in *g* with sensible defaults; no configuration
 * is required beyond the connection. Supports I²C and SPI.
 *
 * Default configuration (baked in at construction):
 * - Full-resolution mode (3.9 mg/LSB at any range)
 * - ±2 g measurement range
 * - 100 Hz output data rate, normal power
 * - FIFO bypass, all interrupts disabled, no offsets
 *
 * I²C address is 0x53 (SDO=GND) or 0x1D (SDO=VDDIO). SPI mode uses
 * CPOL=1/CPHA=1 (mode 3), max 5 MHz, MSB first, CS active low.
 */
open class Adxl345Minimal @JvmOverloads constructor(
    protected val connection: Connection,
    protected val busType: Int = BUS_I2C
) {
    init {
        writeReg(REG_DATA_FORMAT, DATA_FORMAT_DEFAULT)
        writeReg(REG_BW_RATE, BW_RATE_DEFAULT)
        writeReg(REG_POWER_CTL, POWER_CTL_DEFAULT)
        val devid = readReg(REG_DEVID)
        if (devid != DEVID_VALUE) {
            throw IOException("ADXL345 DEVID: expected 0x" +
                    Integer.toHexString(DEVID_VALUE) + ", got 0x" +
                    Integer.toHexString(devid))
        }
    }

    protected var rangeBits: Int = 0
    protected var fullRes: Boolean = true

    /**
     * Read 3-axis linear acceleration.
     *
     * Reads all six data bytes (DATAX0..DATAZ1) in one burst so the X, Y,
     * Z samples are guaranteed to come from a single measurement.
     *
     * @return array of three doubles — X, Y, Z acceleration in *g*
     */
    @Throws(IOException::class)
    fun read(): DoubleArray {
        val raw = readBurst(REG_DATAX0, 6)
        var rx = (raw[0].toInt() and 0xFF) or ((raw[1].toInt() and 0xFF) shl 8)
        var ry = (raw[2].toInt() and 0xFF) or ((raw[3].toInt() and 0xFF) shl 8)
        var rz = (raw[4].toInt() and 0xFF) or ((raw[5].toInt() and 0xFF) shl 8)
        if (rx and 0x8000 != 0) rx -= 0x10000
        if (ry and 0x8000 != 0) ry -= 0x10000
        if (rz and 0x8000 != 0) rz -= 0x10000
        val scale = if (fullRes)
            FULL_RES_SCALE_G_PER_LSB
        else
            arrayOf(3.9e-3, 7.8e-3, 15.6e-3, 31.2e-3)[rangeBits and 0x03]
        return doubleArrayOf(rx * scale, ry * scale, rz * scale)
    }

    @Throws(IOException::class)
    protected fun writeReg(reg: Int, value: Int) {
        if (busType == BUS_SPI) {
            val cmd = cmdByte(reg, false, false)
            connection.write(byteArrayOf(cmd.toByte(), value.toByte()))
        } else {
            connection.write(byteArrayOf(reg.toByte(), value.toByte()))
        }
    }

    @Throws(IOException::class)
    protected fun readReg(reg: Int): Int {
        if (busType == BUS_SPI) {
            val cmd = cmdByte(reg, true, false)
            return connection.writeRead(byteArrayOf(cmd.toByte()), 1)[0].toInt() and 0xFF
        }
        return connection.writeRead(byteArrayOf(reg.toByte()), 1)[0].toInt() and 0xFF
    }

    @Throws(IOException::class)
    protected fun readBurst(reg: Int, n: Int): ByteArray {
        if (busType == BUS_SPI) {
            val cmd = cmdByte(reg, true, n > 1)
            return connection.writeRead(byteArrayOf(cmd.toByte()), n)
        }
        return connection.writeRead(byteArrayOf(reg.toByte()), n)
    }

    companion object {
        const val BUS_I2C = 0
        const val BUS_SPI = 1

        protected const val REG_DEVID         = 0x00
        protected const val REG_THRESH_TAP    = 0x1D
        protected const val REG_OFSX          = 0x1E
        protected const val REG_OFSY          = 0x1F
        protected const val REG_OFSZ          = 0x20
        protected const val REG_DUR           = 0x21
        protected const val REG_LATENT        = 0x22
        protected const val REG_WINDOW        = 0x23
        protected const val REG_THRESH_ACT    = 0x24
        protected const val REG_THRESH_INACT  = 0x25
        protected const val REG_TIME_INACT    = 0x26
        protected const val REG_ACT_INACT_CTL = 0x27
        protected const val REG_THRESH_FF     = 0x28
        protected const val REG_TIME_FF       = 0x29
        protected const val REG_TAP_AXES      = 0x2A
        protected const val REG_BW_RATE       = 0x2C
        protected const val REG_POWER_CTL     = 0x2D
        protected const val REG_INT_ENABLE    = 0x2E
        protected const val REG_INT_MAP       = 0x2F
        protected const val REG_INT_SOURCE    = 0x30
        protected const val REG_DATA_FORMAT   = 0x31
        protected const val REG_DATAX0        = 0x32
        protected const val REG_FIFO_CTL      = 0x38
        protected const val REG_FIFO_STATUS   = 0x39

        protected const val DEVID_VALUE        = 0xE5
        protected const val DATA_FORMAT_DEFAULT = 0x08
        protected const val BW_RATE_DEFAULT     = 0x0A
        protected const val POWER_CTL_DEFAULT   = 0x08
        protected const val FULL_RES_SCALE_G_PER_LSB = 3.9e-3

        // BW_RATE codes (Rate bits 3:0) and their actual output data rates.
        protected val RATE_CODES = arrayOf(
            intArrayOf(0x0F, 3200), intArrayOf(0x0E, 1600), intArrayOf(0x0D, 800),
            intArrayOf(0x0C,  400), intArrayOf(0x0B,  200), intArrayOf(0x0A, 100),
            intArrayOf(0x09,   50), intArrayOf(0x08,   25), intArrayOf(0x07,  12),
            intArrayOf(0x06,    6)
        )

        private fun cmdByte(reg: Int, read: Boolean, multi: Boolean): Int {
            var addr = reg and 0x3F
            if (multi) addr = addr or 0x40
            if (read)  addr = addr or 0x80
            return addr
        }
    }
}