package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * MMA8451Q — 3-axis MEMS accelerometer (NXP / Freescale) — minimal interface.
 *
 * Triaxial low-g accelerometer with 14-bit digital output and ±2/±4/±8 *g*
 * selectable full-scale range. Communicates over I²C at address 0x1C (SA0 = GND)
 * or 0x1D (SA0 = VDDIO).
 *
 * Default configuration (baked in at construction):
 * - Range ±2 *g* (4096 counts/g)
 * - Output data rate 100 Hz
 * - 14-bit output resolution (normal oversampling)
 * - Low-noise mode off, high-pass output off
 * - FIFO disabled, all detectors and interrupt routing off
 * - Interrupts active-low, push-pull; offsets 0
 *
 * The constructor runs the chip's initialization sequence: it verifies
 * `WHO_AM_I` (0x1A), performs a software reset and configures the chip for
 * ±2 *g* at 100 Hz in ACTIVE mode.
 */
open class MMA8451QMinimal constructor(
    protected val conn: RegisterConnection
) {
    companion object {
        // Register map.
        const val REG_STATUS        = 0x00
        const val REG_OUT_X_MSB     = 0x01
        const val REG_OUT_X_LSB     = 0x02
        const val REG_OUT_Y_MSB     = 0x03
        const val REG_OUT_Y_LSB     = 0x04
        const val REG_OUT_Z_MSB     = 0x05
        const val REG_OUT_Z_LSB     = 0x06
        const val REG_F_SETUP       = 0x09
        const val REG_TRIG_CFG      = 0x0A
        const val REG_SYSMOD        = 0x0B
        const val REG_INT_SOURCE    = 0x0C
        const val REG_WHO_AM_I      = 0x0D
        const val REG_XYZ_DATA_CFG  = 0x0E
        const val REG_HP_FILTER     = 0x0F
        const val REG_PL_STATUS     = 0x10
        const val REG_PL_CFG        = 0x11
        const val REG_PL_COUNT      = 0x12
        const val REG_PL_BF_ZCOMP   = 0x13
        const val REG_P_L_THS       = 0x14
        const val REG_FF_MT_CFG     = 0x15
        const val REG_FF_MT_SRC     = 0x16
        const val REG_FF_MT_THS     = 0x17
        const val REG_FF_MT_COUNT   = 0x18
        const val REG_TRANSIENT_CFG = 0x1D
        const val REG_TRANSIENT_SRC = 0x1E
        const val REG_TRANSIENT_THS = 0x1F
        const val REG_TRANSIENT_CNT = 0x20
        const val REG_PULSE_CFG     = 0x21
        const val REG_PULSE_SRC     = 0x22
        const val REG_PULSE_THSX    = 0x23
        const val REG_PULSE_THSY    = 0x24
        const val REG_PULSE_THSZ    = 0x25
        const val REG_PULSE_TMLT    = 0x26
        const val REG_PULSE_LTCY    = 0x27
        const val REG_PULSE_WIND    = 0x28
        const val REG_ASLP_COUNT    = 0x29
        const val REG_CTRL_REG1     = 0x2A
        const val REG_CTRL_REG2     = 0x2B
        const val REG_CTRL_REG3     = 0x2C
        const val REG_CTRL_REG4     = 0x2D
        const val REG_CTRL_REG5     = 0x2E
        const val REG_OFF_X         = 0x2F
        const val REG_OFF_Y         = 0x30
        const val REG_OFF_Z         = 0x31

        /** WHO_AM_I (0x0D) identity value. */
        const val WHO_AM_I_VALUE = 0x1A

        /** STATUS register bit 3 — `ZYXDR`, all three axes have new data. */
        const val STATUS_ZYXDR = 0x08

        /** CTRL_REG1 bit 0 — `ACTIVE`. */
        const val CTRL1_ACTIVE = 0x01

        /** CTRL_REG1 bit 1 — `F_READ`, fast read (8-bit output). */
        const val CTRL1_F_READ = 0x02

        /** CTRL_REG1 bit 2 — `LNOISE`, reduced-noise mode. */
        const val CTRL1_LNOISE = 0x04

        /** CTRL_REG2 bit 6 — `RST`, software reset. */
        const val CTRL2_RST = 0x40

        /** CTRL_REG2 bit 7 — `ST`, electrostatic self-test. */
        const val CTRL2_ST = 0x80

        /** Default full-scale range in *g*. */
        const val DEFAULT_RANGE_G = 2

        /** Default output data rate in Hz. */
        const val DEFAULT_ODR_HZ = 100.0

        /** Default output resolution in bits. */
        const val DEFAULT_BITS = 14

        /** Counts per *g* at 14-bit resolution, indexed by full-scale range. */
        val SCALE_14BIT = doubleArrayOf(4096.0, 2048.0, 1024.0)

        /** Counts per *g* at 8-bit (fast read) resolution, indexed by full-scale range. */
        val SCALE_8BIT = doubleArrayOf(64.0, 32.0, 16.0)
    }

    /** Cached full-scale range in *g* (2, 4 or 8). */
    protected var rangeG: Int = DEFAULT_RANGE_G

    /** Cached output data rate in Hz. */
    protected var odrHz: Double = DEFAULT_ODR_HZ

    /** Cached output resolution in bits (14 or 8). */
    protected var bits: Int = DEFAULT_BITS

    /** `true` while low-noise (reduced-noise) mode is enabled. */
    protected var lowNoiseEnabled: Boolean = false

    /** Cached oversampling mode in WAKE / ACTIVE (0 normal … 3 low power). */
    protected var oversampling: Int = 0

    /** Cached oversampling mode in SLEEP (0 normal … 3 low power). */
    protected var sleepOversampling: Int = 0

    /** Cached FIFO mode (0 disabled, 1 circular, 2 fill, 3 trigger). */
    protected var fifoMode: Int = 0

    init {
        init()
    }

    /**
     * Run the chip's initialization sequence.
     *
     * Verifies `WHO_AM_I` (0x1A), performs a software reset, then configures
     * ±2 *g*, 100 Hz, 14-bit output, normal oversampling and switches the chip
     * to ACTIVE.
     *
     * @throws IOException on bus error, a wrong `WHO_AM_I` value, or an
     *         interrupted wait
     */
    protected fun init() {
        // 1. Power-up delay (I²C is ready ~350 us after VDDIO is on).
        delay(1)
        // 2. Identity check.
        val who = readReg(REG_WHO_AM_I)
        if (who != WHO_AM_I_VALUE) {
            throw IOException("MMA8451Q WHO_AM_I: expected 0x${Integer.toHexString(WHO_AM_I_VALUE)}, got 0x${Integer.toHexString(who and 0xFF)}")
        }
        // 3. Software reset — the I²C interface is reset by it, so the write
        //    may NACK; wait and re-read CTRL_REG2 until it reads back 0.
        writeReg(REG_CTRL_REG2, CTRL2_RST)
        delay(1)
        waitResetDone()
        // 4. ±2 g, no high-pass output.
        writeReg(REG_XYZ_DATA_CFG, 0x00)
        // 5. Normal oversampling in WAKE and SLEEP, Auto-SLEEP off.
        writeReg(REG_CTRL_REG2, 0x00)
        // 6. 100 Hz, LNOISE off, F_READ off, ACTIVE.
        writeReg(REG_CTRL_REG1, 0x19)
        // 7. Wait for the STANDBY -> ACTIVE turn-on time (2/ODR + 2 ms).
        waitTurnOn()

        rangeG = DEFAULT_RANGE_G
        odrHz = DEFAULT_ODR_HZ
        bits = DEFAULT_BITS
        lowNoiseEnabled = false
        oversampling = 0
        sleepOversampling = 0
        fifoMode = 0
    }

    /**
     * Read 3-axis linear acceleration.
     *
     * Burst-reads the six data bytes (0x01–0x06) so the X, Y and Z samples
     * come from a single measurement.
     *
     * @return Triple of (x, y, z) acceleration in *g*.
     */
    fun read(): DoubleArray {
        val raw = readBurst(REG_OUT_X_MSB, 6)
        return decode(raw)
    }

    /**
     * Write one byte to a register.
     *
     * @param reg register address
     * @param value byte value to write
     */
    protected fun writeReg(reg: Int, value: Int) {
        conn.write(reg, byteArrayOf((value and 0xFF).toByte()))
    }

    /**
     * Read one byte from a register.
     *
     * @param reg register address
     * @return the register value (0..255)
     */
    protected fun readReg(reg: Int): Int = conn.read(reg, 1)[0].toInt() and 0xFF

    /**
     * Read `n` consecutive bytes starting at a register.
     *
     * @param reg register address
     * @param n number of bytes to read
     * @return the bytes received
     */
    protected fun readBurst(reg: Int, n: Int): ByteArray = conn.read(reg, n)

    /**
     * Convert raw output bytes to X, Y, Z acceleration in *g*.
     *
     * @param raw the six output bytes starting at 0x01
     * @return Triple of (x, y, z) acceleration in *g*.
     */
    protected fun decode(raw: ByteArray): DoubleArray {
        val scaleIndex = when (rangeG) {
            4 -> 1
            8 -> 2
            else -> 0
        }
        val cx: Int
        val cy: Int
        val cz: Int
        val scale: Double
        if (bits == 8) {
            scale = SCALE_8BIT[scaleIndex]
            cx = Register.toSigned(raw[0].toInt() and 0xFF, 8)
            cy = Register.toSigned(raw[2].toInt() and 0xFF, 8)
            cz = Register.toSigned(raw[4].toInt() and 0xFF, 8)
        } else {
            scale = SCALE_14BIT[scaleIndex]
            val rx = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
            val ry = ((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)
            val rz = ((raw[4].toInt() and 0xFF) shl 8) or (raw[5].toInt() and 0xFF)
            // 14-bit data is left-justified; bits 1:0 of the LSB byte are 0.
            cx = Register.toSigned(rx shr 2, 14)
            cy = Register.toSigned(ry shr 2, 14)
            cz = Register.toSigned(rz shr 2, 14)
        }
        return doubleArrayOf(cx / scale, cy / scale, cz / scale)
    }

    /**
     * Sleep for the given number of milliseconds.
     *
     * @param ms milliseconds to sleep
     * @throws IOException if the wait is interrupted
     */
    protected fun delay(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("interrupted while waiting", e)
        }
    }

    /**
     * Poll `CTRL_REG2` until the software reset has completed.
     */
    protected fun waitResetDone() {
        for (waited in 0 until 20) {
            try {
                if (readReg(REG_CTRL_REG2) == 0x00) {
                    return
                }
            } catch (e: IOException) {
                // The I²C interface is reset by RST — a NACK is expected here.
            }
            delay(1)
        }
    }

    /**
     * @return the STANDBY to ACTIVE turn-on time in ms (2/ODR + 2)
     */
    protected fun turnOnTimeMs(): Double = (2.0 / odrHz) * 1000.0 + 2.0

    /**
     * Wait for the STANDBY to ACTIVE turn-on time.
     */
    protected fun waitTurnOn() {
        delay(kotlin.math.ceil(turnOnTimeMs()).toLong())
    }

    /**
     * Write a STANDBY-only register, restoring ACTIVE afterwards.
     *
     * @param reg register address
     * @param value byte value to write
     */
    protected fun standbyWrite(reg: Int, value: Int) {
        standbyWriteMulti(reg, value, null)
    }

    /**
     * Write a STANDBY-only register plus optional extra registers.
     *
     * When the chip is ACTIVE it is switched to STANDBY first, the
     * register(s) are written, and `ACTIVE` is restored afterwards. The
     * turn-on time is awaited before returning.
     *
     * @param reg register address of the primary write
     * @param value byte value for `reg`
     * @param extra optional array of `intArrayOf(register, value)` pairs written as well
     */
    protected fun standbyWriteMulti(reg: Int, value: Int, extra: Array<IntArray>?) {
        val ctrl = readReg(REG_CTRL_REG1)
        val wasActive = (ctrl and CTRL1_ACTIVE) != 0
        if (wasActive) {
            writeReg(REG_CTRL_REG1, ctrl and CTRL1_ACTIVE.inv() and 0xFF)
        }
        if (reg == REG_CTRL_REG1) {
            // Every CTRL_REG1 field except ACTIVE may only change in STANDBY.
            writeReg(REG_CTRL_REG1, value and CTRL1_ACTIVE.inv() and 0xFF)
        } else {
            writeReg(reg, value)
        }
        if (extra != null) {
            for (pair in extra) {
                writeReg(pair[0], pair[1])
            }
        }
        if (wasActive) {
            // The final CTRL_REG1 value: the new one when CTRL_REG1 was the
            // target, the original one otherwise.
            val finalCtrl = if (reg == REG_CTRL_REG1) {
                value or CTRL1_ACTIVE
            } else {
                ctrl or CTRL1_ACTIVE
            }
            writeReg(REG_CTRL_REG1, finalCtrl)
            waitTurnOn()
        }
    }
}
