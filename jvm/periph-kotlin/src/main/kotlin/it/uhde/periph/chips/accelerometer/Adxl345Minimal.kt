package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * ADXL345 — 3-axis MEMS accelerometer (Analog Devices) — minimal interface.
 *
 * Reads X, Y, Z acceleration in *g* with sensible defaults; no configuration
 * is required beyond the connection. Supports I²C, SMBus, and SPI
 * through the shared [RegisterConnection] (for SPI, construct the connection with
 * readBit=0x80, multiByteBit=0x40 — the ADXL345 R/W|MB|A5..A0 command byte).
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
open class Adxl345Minimal(
    protected val connection: RegisterConnection
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
        val rx = Register.toSigned((raw[0].toInt() and 0xFF) or ((raw[1].toInt() and 0xFF) shl 8), 16)
        val ry = Register.toSigned((raw[2].toInt() and 0xFF) or ((raw[3].toInt() and 0xFF) shl 8), 16)
        val rz = Register.toSigned((raw[4].toInt() and 0xFF) or ((raw[5].toInt() and 0xFF) shl 8), 16)
        val scale = if (fullRes)
            FULL_RES_SCALE_G_PER_LSB
        else
            arrayOf(3.9e-3, 7.8e-3, 15.6e-3, 31.2e-3)[rangeBits and 0x03]
        return doubleArrayOf(rx * scale, ry * scale, rz * scale)
    }

    @Throws(IOException::class)
    protected fun writeReg(reg: Int, value: Int) {
        connection.write(reg, byteArrayOf(value.toByte()))
    }

    @Throws(IOException::class)
    protected fun readReg(reg: Int): Int {
        return connection.read(reg, 1)[0].toInt() and 0xFF
    }

    @Throws(IOException::class)
    protected fun readBurst(reg: Int, n: Int): ByteArray {
        return connection.read(reg, n)
    }

    companion object {
        internal const val REG_DEVID         = 0x00
        internal const val REG_THRESH_TAP    = 0x1D
        internal const val REG_OFSX          = 0x1E
        internal const val REG_OFSY          = 0x1F
        internal const val REG_OFSZ          = 0x20
        internal const val REG_DUR           = 0x21
        internal const val REG_LATENT        = 0x22
        internal const val REG_WINDOW        = 0x23
        internal const val REG_THRESH_ACT    = 0x24
        internal const val REG_THRESH_INACT  = 0x25
        internal const val REG_TIME_INACT    = 0x26
        internal const val REG_ACT_INACT_CTL = 0x27
        internal const val REG_THRESH_FF     = 0x28
        internal const val REG_TIME_FF       = 0x29
        internal const val REG_TAP_AXES      = 0x2A
        internal const val REG_BW_RATE       = 0x2C
        internal const val REG_POWER_CTL     = 0x2D
        internal const val REG_INT_ENABLE    = 0x2E
        internal const val REG_INT_MAP       = 0x2F
        internal const val REG_INT_SOURCE    = 0x30
        internal const val REG_DATA_FORMAT   = 0x31
        internal const val REG_DATAX0        = 0x32
        internal const val REG_FIFO_CTL      = 0x38
        internal const val REG_FIFO_STATUS   = 0x39

        internal const val DEVID_VALUE        = 0xE5
        internal const val DATA_FORMAT_DEFAULT = 0x08
        internal const val BW_RATE_DEFAULT     = 0x0A
        internal const val POWER_CTL_DEFAULT   = 0x08
        internal const val FULL_RES_SCALE_G_PER_LSB = 3.9e-3

        // BW_RATE codes (Rate bits 3:0) and their actual output data rates.
        internal val RATE_CODES = arrayOf(
            doubleArrayOf(0x0F.toDouble(), 3200.0), doubleArrayOf(0x0E.toDouble(), 1600.0), doubleArrayOf(0x0D.toDouble(), 800.0),
            doubleArrayOf(0x0C.toDouble(),  400.0), doubleArrayOf(0x0B.toDouble(),  200.0), doubleArrayOf(0x0A.toDouble(), 100.0),
            doubleArrayOf(0x09.toDouble(),   50.0), doubleArrayOf(0x08.toDouble(),   25.0), doubleArrayOf(0x07.toDouble(), 12.5),
            doubleArrayOf(0x06.toDouble(), 6.25)
        )
    }
}