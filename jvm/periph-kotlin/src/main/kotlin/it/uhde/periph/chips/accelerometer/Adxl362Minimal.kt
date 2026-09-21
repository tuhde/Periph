package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * ADXL362 — 3-axis MEMS accelerometer (Analog Devices) — minimal interface (SPI).
 *
 * Reads X, Y, Z acceleration in *g* with sensible defaults; no configuration
 * is required beyond the connection. The ADXL362 is SPI-only — there is no
 * I²C mode.
 *
 * Default configuration (baked in at construction):
 * - ±2 g measurement range
 * - 100 Hz output data rate, ODR/4 antialiasing bandwidth
 * - Normal noise mode (POWER_CTL.LOW_NOISE=00)
 * - Continuous measurement mode (POWER_CTL.MEASURE=10)
 * - FIFO disabled
 * - No interrupts mapped; INT1/INT2 high-impedance
 *
 * SPI mode is 0 (CPOL=0/CPHA=0), max 8 MHz, MSB first, CS active low.
 * The command byte (0x0A write / 0x0B read / 0x0D FIFO read) is always
 * sent as the first byte of each transaction.
 */
open class Adxl362Minimal @JvmOverloads constructor(
    protected val connection: Connection
) {
    init { init() }

    protected var rangeBits: Int = 0x00
    protected var odrHz: Float = 100.0f

    /**
     * Run the chip's full power-up sequence: verify DEVID triple, write
     * FILTER_CTL and POWER_CTL, and wait the ODR turnaround.
     *
     * @throws IOException on bus error or wrong device ID
     */
    @Throws(IOException::class)
    fun init() {
        sleepMs(5)
        val ids = readBurst(REG_DEVID_AD, 3)
        if ((ids[0].toInt() and 0xFF) != DEVID_AD_VALUE) {
            throw IOException("ADXL362 DEVID_AD: expected 0x" +
                    Integer.toHexString(DEVID_AD_VALUE) + ", got 0x" +
                    Integer.toHexString(ids[0].toInt() and 0xFF))
        }
        if ((ids[1].toInt() and 0xFF) != DEVID_MST_VALUE) {
            throw IOException("ADXL362 DEVID_MST: expected 0x" +
                    Integer.toHexString(DEVID_MST_VALUE) + ", got 0x" +
                    Integer.toHexString(ids[1].toInt() and 0xFF))
        }
        if ((ids[2].toInt() and 0xFF) != PARTID_VALUE) {
            throw IOException("ADXL362 PARTID: expected 0x" +
                    Integer.toHexString(PARTID_VALUE) + ", got 0x" +
                    Integer.toHexString(ids[2].toInt() and 0xFF))
        }
        writeReg(REG_FILTER_CTL, FILTER_CTL_DEFAULT)
        writeReg(REG_POWER_CTL, POWER_CTL_MEASURE)
        sleepMs(40)  // 4/ODR at 100 Hz
    }

    /**
     * Read 3-axis linear acceleration.
     *
     * Burst-reads the 12-bit XDATA_L/H, YDATA_L/H, ZDATA_L/H sextet so
     * the X, Y, Z samples come from a single measurement.
     *
     * @return array of three doubles — X, Y, Z acceleration in *g*
     */
    @Throws(IOException::class)
    fun read(): DoubleArray {
        val raw = readBurst(REG_XDATA_L, 6)
        val rx = signExtend12(((raw[1].toInt() and 0x0F) shl 8) or (raw[0].toInt() and 0xFF))
        val ry = signExtend12(((raw[3].toInt() and 0x0F) shl 8) or (raw[2].toInt() and 0xFF))
        val rz = signExtend12(((raw[5].toInt() and 0x0F) shl 8) or (raw[4].toInt() and 0xFF))
        val sens = sensitivity().toDouble()
        return doubleArrayOf(rx * sens, ry * sens, rz * sens)
    }

    protected fun sensitivity(): Float = when (rangeBits) {
        0x40 -> SENSITIVITY_G_PER_LSB[1].toFloat()
        0x80, 0xC0 -> SENSITIVITY_G_PER_LSB[2].toFloat()
        else -> SENSITIVITY_G_PER_LSB[0].toFloat()
    }

    companion object {
        // SPI command bytes (per spec § Transport Configuration / SPI).
        const val CMD_WRITE_REG = 0x0A
        const val CMD_READ_REG  = 0x0B
        const val CMD_READ_FIFO = 0x0D

        /** Soft-reset key (ASCII 'R'). */
        const val SOFT_RESET_KEY = 0x52

        // Per-range sensitivity (g/LSB), typical.
        val SENSITIVITY_G_PER_LSB = doubleArrayOf(0.001, 0.002, 0.004255)

        // Register map (6-bit addresses).
        const val REG_DEVID_AD        = 0x00
        const val REG_DEVID_MST       = 0x01
        const val REG_PARTID          = 0x02
        const val REG_XDATA           = 0x08
        const val REG_YDATA           = 0x09
        const val REG_ZDATA           = 0x0A
        const val REG_STATUS          = 0x0B
        const val REG_FIFO_ENTRIES_L  = 0x0C
        const val REG_FIFO_ENTRIES_H  = 0x0D
        const val REG_XDATA_L         = 0x0E
        const val REG_XDATA_H         = 0x0F
        const val REG_YDATA_L         = 0x10
        const val REG_YDATA_H         = 0x11
        const val REG_ZDATA_L         = 0x12
        const val REG_ZDATA_H         = 0x13
        const val REG_TEMP_L          = 0x14
        const val REG_TEMP_H          = 0x15
        const val REG_SOFT_RESET      = 0x1F
        const val REG_THRESH_ACT_L    = 0x20
        const val REG_THRESH_ACT_H    = 0x21
        const val REG_TIME_ACT        = 0x22
        const val REG_THRESH_INACT_L  = 0x23
        const val REG_THRESH_INACT_H  = 0x24
        const val REG_TIME_INACT_L    = 0x25
        const val REG_TIME_INACT_H    = 0x26
        const val REG_ACT_INACT_CTL   = 0x27
        const val REG_FIFO_CONTROL    = 0x28
        const val REG_FIFO_SAMPLES    = 0x29
        const val REG_INTMAP1         = 0x2A
        const val REG_INTMAP2         = 0x2B
        const val REG_FILTER_CTL      = 0x2C
        const val REG_POWER_CTL       = 0x2D
        const val REG_SELF_TEST       = 0x2E

        const val DEVID_AD_VALUE  = 0xAD
        const val DEVID_MST_VALUE = 0x1D
        const val PARTID_VALUE    = 0xF2

        // FILTER_CTL reset value: RANGE=±2 g, HALF_BW=1, ODR=100 Hz.
        const val FILTER_CTL_DEFAULT = 0x13
        // POWER_CTL measurement-mode value: MEASURE=10.
        const val POWER_CTL_MEASURE  = 0x02

        const val STATUS_AWAKE     = 0x40
        const val STATUS_DATA_READY = 0x01

        @JvmStatic
        protected fun signExtend12(v: Int): Int {
            val x = v and 0x0FFF
            return if ((x and 0x0800) != 0) (x or 0xF000).toShort().toInt() else x
        }

        @JvmStatic
        protected fun sleepMs(ms: Int) {
            try { Thread.sleep(ms.toLong()) }
            catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        }

        @Throws(IOException::class)
        @JvmStatic
        protected fun writeRegStatic(connection: Connection, reg: Int, value: Int) {
            connection.write(byteArrayOf(
                CMD_WRITE_REG.toByte(), (reg and 0x3F).toByte(), (value and 0xFF).toByte(),
            ))
        }

        @Throws(IOException::class)
        @JvmStatic
        protected fun readRegStatic(connection: Connection, reg: Int): Int {
            val out = readBurstStatic(connection, reg, 1)
            return out[0].toInt() and 0xFF
        }

        @Throws(IOException::class)
        @JvmStatic
        protected fun readBurstStatic(connection: Connection, reg: Int, len: Int): ByteArray =
            connection.writeRead(byteArrayOf(CMD_READ_REG.toByte(), (reg and 0x3F).toByte()), len)
    }

    @Throws(IOException::class)
    protected fun writeReg(reg: Int, value: Int) = writeRegStatic(connection, reg, value)

    @Throws(IOException::class)
    protected fun readReg(reg: Int): Int = readRegStatic(connection, reg)

    @Throws(IOException::class)
    protected fun readBurst(reg: Int, len: Int): ByteArray = readBurstStatic(connection, reg, len)

    @Throws(IOException::class)
    protected fun readFifo(len: Int): ByteArray =
        connection.writeRead(byteArrayOf(CMD_READ_FIFO.toByte()), len)

    @Throws(IOException::class)
    protected fun readFifoEntries(): Int {
        val lo = readReg(REG_FIFO_ENTRIES_L)
        val hi = readReg(REG_FIFO_ENTRIES_H)
        return lo or ((hi and 0x03) shl 8)
    }
}