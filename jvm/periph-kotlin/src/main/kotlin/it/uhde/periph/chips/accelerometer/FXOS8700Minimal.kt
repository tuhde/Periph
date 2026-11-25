package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * FXOS8700CQ — 6-axis e-compass: 14-bit accelerometer + 16-bit magnetometer
 * (NXP) — minimal interface.
 *
 * The FXOS8700CQ combines a three-axis MEMS accelerometer (±2 / ±4 / ±8 *g*,
 * 0.244 / 0.488 / 0.976 mg/LSB) and a three-axis magnetometer (fixed ±1200 µT
 * range, 0.1 µT/LSB) behind **one I²C address** (0x1C-0x1F, selected by SA1 /
 * SA0) and one shared ADC. In hybrid mode both sensors run at half the
 * programmed output data rate and all twelve data bytes can be read in one
 * burst. Do not leave SA0 floating: a floating SA0 at power-up selects SPI.
 *
 * Default configuration (baked in at construction):
 * - Hybrid mode, system ODR 200 Hz (100 Hz per sensor), ±2 *g* (4096
 *   counts per *g*), 14-bit output, normal accelerometer oversampling,
 *   magnetometer OSR 7, low-noise off, `hyb_autoinc_mode` on
 * - No FIFO, no detectors, no interrupt routing, interrupts active-low
 *   push-pull
 *
 * @param connection RegisterConnection (I²C) bound to 0x1C-0x1F
 */
open class FXOS8700Minimal constructor(
    protected val connection: RegisterConnection
) {
    companion object {
        const val REG_STATUS = 0x00
        const val REG_OUT_X_MSB = 0x01
        const val REG_F_SETUP = 0x09
        const val REG_TRIG_CFG = 0x0A
        const val REG_SYSMOD = 0x0B
        const val REG_INT_SOURCE = 0x0C
        const val REG_WHO_AM_I = 0x0D
        const val REG_XYZ_DATA_CFG = 0x0E
        const val REG_HP_FILTER_CUTOFF = 0x0F
        const val REG_PL_STATUS = 0x10
        const val REG_PL_CFG = 0x11
        const val REG_PL_COUNT = 0x12
        const val REG_PL_BF_ZCOMP = 0x13
        const val REG_PL_THS = 0x14
        const val REG_A_FFMT_CFG = 0x15
        const val REG_A_FFMT_SRC = 0x16
        const val REG_A_FFMT_THS = 0x17
        const val REG_A_FFMT_COUNT = 0x18
        const val REG_TRANSIENT_CFG = 0x1D
        const val REG_TRANSIENT_SRC = 0x1E
        const val REG_TRANSIENT_THS = 0x1F
        const val REG_TRANSIENT_COUNT = 0x20
        const val REG_PULSE_CFG = 0x21
        const val REG_PULSE_SRC = 0x22
        const val REG_PULSE_THSX = 0x23
        const val REG_PULSE_TMLT = 0x26
        const val REG_PULSE_LTCY = 0x27
        const val REG_PULSE_WIND = 0x28
        const val REG_ASLP_COUNT = 0x29
        const val REG_CTRL1 = 0x2A
        const val REG_CTRL2 = 0x2B
        const val REG_CTRL3 = 0x2C
        const val REG_CTRL4 = 0x2D
        const val REG_CTRL5 = 0x2E
        const val REG_OFF_X = 0x2F
        const val REG_M_DR_STATUS = 0x32
        const val REG_M_OUT_X_MSB = 0x33
        const val REG_M_OFF_X_MSB = 0x3F
        const val REG_MAX_X_MSB = 0x45
        const val REG_MIN_X_MSB = 0x4B
        const val REG_TEMP = 0x51
        const val REG_M_CTRL1 = 0x5B
        const val REG_M_CTRL2 = 0x5C
        const val REG_M_CTRL3 = 0x5D

        const val CTRL1_ACTIVE = 0x01
        const val CTRL1_F_READ = 0x02
        const val CTRL1_LNOISE = 0x04
        const val CTRL1_DR_MASK = 0x38
        const val CTRL1_ASLP_MASK = 0xC0
        const val CTRL2_ST = 0x80
        const val CTRL2_RST = 0x40
        const val CTRL2_SMODS_MASK = 0x18
        const val CTRL2_SLPE = 0x04
        const val CTRL2_MODS_MASK = 0x03
        const val STATUS_ZYXDR = 0x08
        const val M_CTRL1_ACAL = 0x80
        const val M_CTRL1_RST = 0x40
        const val M_CTRL1_OST = 0x20
        const val M_CTRL1_OS_MASK = 0x1C
        const val M_CTRL1_HMS_MASK = 0x03
        const val M_CTRL2_HYB_AUTOINC = 0x20

        /** I²C address for SA1 = 0, SA0 = 0 (default); 0x1C-0x1F are selectable. */
        const val I2C_ADDRESS = 0x1E

        /** System output data rates selected by CTRL_REG1.dr (Hz). */
        val ODR_HZ = doubleArrayOf(800.0, 400.0, 200.0, 100.0, 50.0, 12.5, 6.25, 1.5625)
    }

    // Cached configuration, kept in sync with the registers.
    protected var operatingMode: Int = 2        // 0 accel only, 1 mag only, 2 hybrid
    protected var dr: Int = 2                   // CTRL_REG1.dr code
    protected var fsG: Int = 2
    protected var mods: Int = 0                 // accelerometer oversampling (wake)
    protected var lnoise: Boolean = false
    protected var fRead: Boolean = false        // true = 8-bit fast read
    protected var fifoMode: Int = 0
    protected var hyb: Boolean = true           // hyb_autoinc_mode

    init {
        init()
    }

    /**
     * Run the datasheet initialization sequence.
     *
     * Verifies `WHO_AM_I` (0xC7, or 0xC4 on pre-production parts), performs a
     * software reset (the `rst` write is not acknowledged and the resulting
     * error is ignored) and starts hybrid mode at a 200 Hz system ODR, ±2 *g*,
     * magnetometer OSR 7.
     *
     * @throws IOException on bus error or a wrong `WHO_AM_I`
     */
    fun init() {
        sleepMs(1)
        var who = whoAmI()
        if (who != 0xC7 && who != 0xC4) {
            throw IOException(String.format("FXOS8700 not found: unexpected WHO_AM_I 0x%02X", who))
        }
        softReset()
        who = whoAmI()
        if (who != 0xC7 && who != 0xC4) {
            throw IOException(String.format("FXOS8700 not found after reset: WHO_AM_I 0x%02X", who))
        }
        writeReg(REG_CTRL1, 0x00)
        writeReg(REG_M_CTRL1, 0x1F)
        writeReg(REG_M_CTRL2, M_CTRL2_HYB_AUTOINC)
        writeReg(REG_XYZ_DATA_CFG, 0x00)
        writeReg(REG_CTRL2, 0x00)
        writeReg(REG_CTRL1, 0x11)
        operatingMode = 2
        dr = 2
        fsG = 2
        mods = 0
        lnoise = false
        fRead = false
        fifoMode = 0
        hyb = true
        sleepMs(turnOnMs())
    }

    /**
     * Read the acceleration (one burst read of 0x01-0x06).
     *
     * @return `[x, y, z]` in *g*
     * @throws IOException on bus error
     */
    fun readAccel(): DoubleArray {
        val raw = readAccelCounts()
        val scale = accelScale()
        return doubleArrayOf(raw[0] / scale, raw[1] / scale, raw[2] / scale)
    }

    /**
     * Read the magnetic flux density (one burst read of 0x33-0x38; reading
     * `M_OUT_X_MSB` first latches the X / Y / Z set).
     *
     * @return `[x, y, z]` in µT
     * @throws IOException on bus error
     */
    fun readMag(): DoubleArray {
        val raw = readMagCounts()
        return doubleArrayOf(raw[0] * 0.1, raw[1] * 0.1, raw[2] * 0.1)
    }

    // --- Helpers shared with FXOS8700Full ----------------------------------

    /** Sleep for [ms] milliseconds. */
    protected fun sleepMs(ms: Int) {
        try {
            Thread.sleep(ms.toLong())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Read one register (0-255). */
    protected fun readReg(reg: Int): Int = connection.read(reg, 1)[0].toInt() and 0xFF

    /** Write one register. */
    protected fun writeReg(reg: Int, value: Int) {
        connection.write(reg, byteArrayOf(value.toByte()))
    }

    /** Read-modify-write: clear [mask], OR in [value]. */
    protected fun modify(reg: Int, mask: Int, value: Int) {
        val cur = readReg(reg)
        writeReg(reg, (cur and mask.inv() and 0xFF) or (value and mask))
    }

    /** Read `WHO_AM_I`, retrying on a bus error for up to 10 ms. */
    private fun whoAmI(): Int {
        for (i in 0 until 10) {
            try {
                return readReg(REG_WHO_AM_I)
            } catch (e: IOException) {
                sleepMs(1)
            }
        }
        return readReg(REG_WHO_AM_I)
    }

    /** Write `CTRL_REG2.rst` tolerating the missing ACK, then wait 2 ms. */
    protected fun softReset() {
        try {
            writeReg(REG_CTRL2, CTRL2_RST)
        } catch (e: IOException) {
            // The chip resets its I²C interface and does not ACK the write.
        }
        sleepMs(2)
    }

    /** Counts per *g* at the current range and resolution. */
    protected fun accelScale(): Double {
        val cpg = when (fsG) {
            2 -> 4096.0
            4 -> 2048.0
            else -> 1024.0
        }
        return if (fRead) cpg / 64.0 else cpg
    }

    /** Decode three accelerometer axes from [data] starting at [offset]. */
    protected fun decodeAccel(data: ByteArray, offset: Int): IntArray {
        val out = IntArray(3)
        for (i in 0 until 3) {
            out[i] = if (fRead) {
                data[offset + i].toInt()
            } else {
                val raw = ((data[offset + 2 * i].toInt() and 0xFF) shl 8) or
                    (data[offset + 2 * i + 1].toInt() and 0xFF)
                Register.toSigned(raw, 16) shr 2
            }
        }
        return out
    }

    /** Decode three magnetometer axes (16-bit, MSB first). */
    protected fun decodeMag(data: ByteArray, offset: Int): IntArray {
        val out = IntArray(3)
        for (i in 0 until 3) {
            val raw = ((data[offset + 2 * i].toInt() and 0xFF) shl 8) or
                (data[offset + 2 * i + 1].toInt() and 0xFF)
            out[i] = Register.toSigned(raw, 16)
        }
        return out
    }

    /** Read the signed accelerometer counts (14-bit, or 8-bit in fast read). */
    protected fun readAccelCounts(): IntArray =
        decodeAccel(connection.read(REG_OUT_X_MSB, if (fRead) 3 else 6), 0)

    /** Read the signed magnetometer counts. */
    protected fun readMagCounts(): IntArray =
        decodeMag(connection.read(REG_M_OUT_X_MSB, 6), 0)

    /** STANDBY to ACTIVE turn-on time, 2 / ODR + 2 ms (rounded up). */
    protected fun turnOnMs(): Int = (2000.0 / ODR_HZ[dr]).toInt() + 3
}
