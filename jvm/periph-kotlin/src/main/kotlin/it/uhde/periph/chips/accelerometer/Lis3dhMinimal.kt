package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * LIS3DH — 3-axis ultra-low-power MEMS accelerometer (STMicroelectronics) —
 * minimal interface.
 *
 * Triaxial accelerometer with a 16-bit left-justified output and four
 * selectable full-scale ranges (±2, ±4, ±8, ±16 *g*). Three operating modes
 * trade resolution for current — low-power (8-bit), normal (10-bit) and
 * high-resolution (12-bit) — across output data rates from 1 Hz to 5.376 kHz.
 *
 * Communicates over I²C at address 0x18 (SA0 = GND) or 0x19 (SA0 = VDD_IO).
 * The chip also supports 3-/4-wire SPI, which is out of scope here. After the
 * slave address the master sends an 8-bit sub-address: the 7 LSbs are the
 * register address and the MSb enables the address auto-increment, so every
 * multi-byte transfer ORs `0x80` into the register address (single-register
 * accesses do not).
 *
 * The constructor runs the datasheet Initialization Sequence: it verifies
 * `WHO_AM_I` (0x0F) == 0x33, puts the control registers into a known state
 * (the chip has no soft reset), configures ±2 *g*, 100 Hz ODR,
 * high-resolution 12-bit mode with `BDU` on, and waits 7/ODR (70 ms) for the
 * converter to turn on.
 *
 * Default configuration (baked in at construction):
 * - Range ±2 *g* (1 mg/LSB)
 * - 100 Hz output data rate
 * - High-resolution (12-bit) mode, all three axes enabled
 * - `BDU` = 1 (block data update)
 * - High-pass filter bypassed, FIFO bypassed
 * - All interrupt routing cleared
 * - Auxiliary ADC and temperature sensor off
 * - `CTRL_REG0` untouched
 */
open class Lis3dhMinimal @JvmOverloads constructor(
    protected val conn: RegisterConnection
) {
    companion object {
        /** Register map of the LIS3DH. */
        const val REG_STATUS_AUX   = 0x07
        const val REG_OUT_ADC1_L   = 0x08
        const val REG_OUT_ADC1_H   = 0x09
        const val REG_OUT_ADC2_L   = 0x0A
        const val REG_OUT_ADC2_H   = 0x0B
        const val REG_OUT_ADC3_L   = 0x0C
        const val REG_OUT_ADC3_H   = 0x0D
        const val REG_WHO_AM_I     = 0x0F
        const val REG_CTRL_REG0    = 0x1E
        const val REG_TEMP_CFG     = 0x1F
        const val REG_CTRL_REG1    = 0x20
        const val REG_CTRL_REG2    = 0x21
        const val REG_CTRL_REG3    = 0x22
        const val REG_CTRL_REG4    = 0x23
        const val REG_CTRL_REG5    = 0x24
        const val REG_CTRL_REG6    = 0x25
        const val REG_REFERENCE    = 0x26
        const val REG_STATUS       = 0x27
        const val REG_OUT_X_L      = 0x28
        const val REG_OUT_X_H      = 0x29
        const val REG_OUT_Y_L      = 0x2A
        const val REG_OUT_Y_H      = 0x2B
        const val REG_OUT_Z_L      = 0x2C
        const val REG_OUT_Z_H      = 0x2D
        const val REG_FIFO_CTRL    = 0x2E
        const val REG_FIFO_SRC     = 0x2F
        const val REG_INT1_CFG     = 0x30
        const val REG_INT1_SRC     = 0x31
        const val REG_INT1_THS     = 0x32
        const val REG_INT1_DUR     = 0x33
        const val REG_INT2_CFG     = 0x34
        const val REG_INT2_SRC     = 0x35
        const val REG_INT2_THS     = 0x36
        const val REG_INT2_DUR     = 0x37
        const val REG_CLICK_CFG    = 0x38
        const val REG_CLICK_SRC    = 0x39
        const val REG_CLICK_THS    = 0x3A
        const val REG_TIME_LIMIT   = 0x3B
        const val REG_TIME_LATENCY = 0x3C
        const val REG_TIME_WINDOW  = 0x3D
        const val REG_ACT_THS      = 0x3E
        const val REG_ACT_DUR      = 0x3F

        /** Content `WHO_AM_I` (0x0F) must have for the chip to be recognised. */
        const val WHO_AM_I_VALUE = 0x33

        /** Initialization sequence: `BDU` = 1, `FS` = 00 (±2 *g*), `HR` = 1. */
        const val CTRL_REG4_DEFAULT = 0x88

        /** Initialization sequence: `ODR` = 0101 (100 Hz), `LPen` = 0, X/Y/Z enabled. */
        const val CTRL_REG1_DEFAULT = 0x57

        /** MSb of the I²C sub-address — enables the register auto-increment. */
        const val AUTO_INCREMENT = 0x80

        /** Time the power-on boot procedure needs before any register may be accessed. */
        const val BOOT_DELAY_MS = 5

        /** Low-power mode: 8-bit output. */
        const val MODE_LOW_POWER = 0

        /** Normal mode: 10-bit output. */
        const val MODE_NORMAL = 1

        /** High-resolution mode: 12-bit output. */
        const val MODE_HIGH_RESOLUTION = 2

        /** ±2 *g* full-scale range. */
        const val RANGE_2G = 2

        /** ±4 *g* full-scale range. */
        const val RANGE_4G = 4

        /** ±8 *g* full-scale range. */
        const val RANGE_8G = 8

        /** ±16 *g* full-scale range. */
        const val RANGE_16G = 16
    }

    /** Currently configured full-scale range in *g*. */
    protected var rangeG: Int = RANGE_2G

    /** Currently configured operating mode (0 low-power, 1 normal, 2 high-resolution). */
    protected var resolutionMode: Int = MODE_HIGH_RESOLUTION

    /** Currently configured output data rate in Hz (0 = power-down). */
    protected var rateHz: Int = 100

    init {
        runInitSequence()
    }

    /**
     * Run the datasheet Initialization Sequence (steps 1–6).
     *
     * @throws IOException on bus error or when `WHO_AM_I` is not 0x33.
     */
    protected fun runInitSequence() {
        // Step 1: the boot procedure needs ~5 ms; no register access before
        // it completed.
        sleepMs(BOOT_DELAY_MS.toLong())

        // Step 2: verify WHO_AM_I.
        val id = readReg(REG_WHO_AM_I)
        if (id != WHO_AM_I_VALUE) {
            throw IOException("LIS3DH WHO_AM_I: expected 0x33, got 0x${Integer.toHexString(id)}")
        }

        // Step 3: the chip has no soft reset — put the control registers into
        // a known state. CTRL_REG0 and the interrupt-generator registers are
        // left untouched, reserved registers are never written.
        writeReg(REG_CTRL_REG2, 0x00)
        writeReg(REG_CTRL_REG3, 0x00)
        writeReg(REG_CTRL_REG5, 0x00)
        writeReg(REG_CTRL_REG6, 0x00)
        writeReg(REG_FIFO_CTRL, 0x00)
        writeReg(REG_TEMP_CFG, 0x00)

        // Step 4: block data update, ±2 g, high-resolution 12-bit mode.
        writeReg(REG_CTRL_REG4, CTRL_REG4_DEFAULT)

        // Step 5: leave power-down — this starts the conversions.
        writeReg(REG_CTRL_REG1, CTRL_REG1_DEFAULT)

        // Step 6: wait for the high-resolution turn-on (7/ODR = 70 ms at
        // 100 Hz) — the longer of the turn-on time and one ODR period.
        sleepMs(settleMs().toLong())
    }

    /**
     * Write one byte to a register.
     *
     * @param reg register address.
     * @param value value to write (only the low 8 bits are used).
     */
    protected fun writeReg(reg: Int, value: Int) {
        conn.write(reg, byteArrayOf((value and 0xFF).toByte()))
    }

    /**
     * Read one byte from a register.
     *
     * @param reg register address.
     * @return the register content (0–255).
     */
    protected fun readReg(reg: Int): Int = conn.read(reg, 1)[0].toInt() and 0xFF

    /**
     * Read `length` bytes starting at register `reg`.
     *
     * A multi-byte transfer sets the MSb of the sub-address so the chip
     * auto-increments the register address.
     *
     * @param reg first register address.
     * @param length number of bytes to read.
     * @return the bytes received.
     */
    protected fun readBurst(reg: Int, length: Int): ByteArray =
        conn.read(if (length > 1) reg or AUTO_INCREMENT else reg, length)

    /**
     * Decode one axis of the left-justified 16-bit output pair.
     *
     * The 16-bit pair is sign-extended first and only then shifted right for
     * the current resolution (12-bit high-resolution, 10-bit normal, 8-bit
     * low-power).
     *
     * @param low low byte of the pair.
     * @param high high byte of the pair.
     * @return the signed count at the current resolution.
     */
    protected fun decodeAxis(low: Int, high: Int): Int {
        val raw16 = (((high and 0xFF) shl 8) or (low and 0xFF)).toShort().toInt()
        return raw16 shr shift()
    }

    /**
     * Right-shift applied to the left-justified 16-bit output pair.
     *
     * @return 4 for high-resolution, 6 for normal and 8 for low-power mode.
     */
    protected fun shift(): Int = when (resolutionMode) {
        MODE_LOW_POWER -> 8
        MODE_NORMAL -> 6
        else -> 4
    }

    /**
     * Sensitivity of the current mode and range in mg/LSB (datasheet Table 4).
     *
     * @return the sensitivity in mg per LSB.
     */
    protected fun sensitivityMg(): Int = when (resolutionMode) {
        MODE_LOW_POWER -> when (rangeG) {
            RANGE_4G -> 32
            RANGE_8G -> 64
            RANGE_16G -> 192
            else -> 16
        }
        MODE_NORMAL -> when (rangeG) {
            RANGE_4G -> 8
            RANGE_8G -> 16
            RANGE_16G -> 48
            else -> 4
        }
        else -> when (rangeG) {
            RANGE_4G -> 2
            RANGE_8G -> 4
            RANGE_16G -> 12
            else -> 1
        }
    }

    /**
     * Convert a raw count into *g* with the sensitivity of the current mode
     * and range.
     *
     * @param counts signed counts at the current resolution.
     * @return the acceleration in *g*.
     */
    protected fun scale(counts: Int): Double = counts.toDouble() * sensitivityMg() / 1000.0

    /**
     * Time to wait after an ODR or mode change: the longer of the mode's
     * turn-on time (datasheet Table 10) and one ODR period.
     *
     * @return the settle time in milliseconds.
     */
    protected fun settleMs(): Int {
        if (rateHz <= 0) return 0
        val periodMs = 1000.0 / rateHz
        val turnOnMs = when (resolutionMode) {
            MODE_LOW_POWER -> 1.0
            MODE_NORMAL -> 1.6
            else -> 7.0 * periodMs
        }
        return ceil(max(turnOnMs, periodMs)).toInt()
    }

    /**
     * Round half away from zero, so every language computes the same
     * register codes.
     *
     * @param x the value to round.
     * @return the rounded value.
     */
    protected fun roundHalfAwayFromZero(x: Double): Int =
        if (x >= 0) floor(x + 0.5).toInt() else -floor(-x + 0.5).toInt()

    /**
     * Sleep for a number of milliseconds, ignoring interrupts.
     *
     * @param ms the delay in milliseconds.
     */
    protected fun sleepMs(ms: Long) {
        if (ms <= 0) return
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Read the 3-axis linear acceleration.
     *
     * Burst-reads the six output bytes (0x28–0x2D) with auto-increment so X,
     * Y and Z come from a single sample (`BDU` = 1), sign-extends the
     * left-justified 16-bit pairs and scales them with the sensitivity of the
     * current mode and range.
     *
     * @return array of three doubles — X, Y, Z acceleration in *g*.
     */
    fun read(): DoubleArray {
        val raw = readBurst(REG_OUT_X_L, 6)
        return doubleArrayOf(
            scale(decodeAxis(raw[0].toInt(), raw[1].toInt())),
            scale(decodeAxis(raw[2].toInt(), raw[3].toInt())),
            scale(decodeAxis(raw[4].toInt(), raw[5].toInt()))
        )
    }
}
