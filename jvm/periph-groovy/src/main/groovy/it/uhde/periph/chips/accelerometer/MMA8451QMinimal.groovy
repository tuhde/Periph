package it.uhde.periph.chips.accelerometer

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection

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
@CompileStatic
class MMA8451QMinimal {

    // Register map.
    static final int REG_STATUS        = 0x00
    static final int REG_OUT_X_MSB     = 0x01
    static final int REG_OUT_X_LSB     = 0x02
    static final int REG_OUT_Y_MSB     = 0x03
    static final int REG_OUT_Y_LSB     = 0x04
    static final int REG_OUT_Z_MSB     = 0x05
    static final int REG_OUT_Z_LSB     = 0x06
    static final int REG_F_SETUP       = 0x09
    static final int REG_TRIG_CFG      = 0x0A
    static final int REG_SYSMOD        = 0x0B
    static final int REG_INT_SOURCE    = 0x0C
    static final int REG_WHO_AM_I      = 0x0D
    static final int REG_XYZ_DATA_CFG  = 0x0E
    static final int REG_HP_FILTER     = 0x0F
    static final int REG_PL_STATUS     = 0x10
    static final int REG_PL_CFG        = 0x11
    static final int REG_PL_COUNT      = 0x12
    static final int REG_PL_BF_ZCOMP   = 0x13
    static final int REG_P_L_THS       = 0x14
    static final int REG_FF_MT_CFG     = 0x15
    static final int REG_FF_MT_SRC     = 0x16
    static final int REG_FF_MT_THS     = 0x17
    static final int REG_FF_MT_COUNT   = 0x18
    static final int REG_TRANSIENT_CFG = 0x1D
    static final int REG_TRANSIENT_SRC = 0x1E
    static final int REG_TRANSIENT_THS = 0x1F
    static final int REG_TRANSIENT_CNT = 0x20
    static final int REG_PULSE_CFG     = 0x21
    static final int REG_PULSE_SRC     = 0x22
    static final int REG_PULSE_THSX    = 0x23
    static final int REG_PULSE_THSY    = 0x24
    static final int REG_PULSE_THSZ    = 0x25
    static final int REG_PULSE_TMLT    = 0x26
    static final int REG_PULSE_LTCY    = 0x27
    static final int REG_PULSE_WIND    = 0x28
    static final int REG_ASLP_COUNT    = 0x29
    static final int REG_CTRL_REG1     = 0x2A
    static final int REG_CTRL_REG2     = 0x2B
    static final int REG_CTRL_REG3     = 0x2C
    static final int REG_CTRL_REG4     = 0x2D
    static final int REG_CTRL_REG5     = 0x2E
    static final int REG_OFF_X         = 0x2F
    static final int REG_OFF_Y         = 0x30
    static final int REG_OFF_Z         = 0x31

    /** WHO_AM_I (0x0D) identity value. */
    static final int WHO_AM_I_VALUE = 0x1A

    /** STATUS register bit 3 — `ZYXDR`, all three axes have new data. */
    static final int STATUS_ZYXDR = 0x08

    /** CTRL_REG1 bit 0 — `ACTIVE`. */
    static final int CTRL1_ACTIVE = 0x01

    /** CTRL_REG1 bit 1 — `F_READ`, fast read (8-bit output). */
    static final int CTRL1_F_READ = 0x02

    /** CTRL_REG1 bit 2 — `LNOISE`, reduced-noise mode. */
    static final int CTRL1_LNOISE = 0x04

    /** CTRL_REG2 bit 6 — `RST`, software reset. */
    static final int CTRL2_RST = 0x40

    /** CTRL_REG2 bit 7 — `ST`, electrostatic self-test. */
    static final int CTRL2_ST = 0x80

    /** Default full-scale range in *g*. */
    static final int DEFAULT_RANGE_G = 2

    /** Default output data rate in Hz. */
    static final double DEFAULT_ODR_HZ = 100.0d

    /** Default output resolution in bits. */
    static final int DEFAULT_BITS = 14

    /** Counts per *g* at 14-bit resolution, indexed by full-scale range. */
    static final double[] SCALE_14BIT = [4096.0d, 2048.0d, 1024.0d] as double[]

    /** Counts per *g* at 8-bit (fast read) resolution, indexed by full-scale range. */
    static final double[] SCALE_8BIT = [64.0d, 32.0d, 16.0d] as double[]

    protected final RegisterConnection connection

    /** Cached full-scale range in *g* (2, 4 or 8). */
    protected int rangeG = DEFAULT_RANGE_G

    /** Cached output data rate in Hz. */
    protected double odrHz = DEFAULT_ODR_HZ

    /** Cached output resolution in bits (14 or 8). */
    protected int bits = DEFAULT_BITS

    /** `true` while low-noise (reduced-noise) mode is enabled. */
    protected boolean lowNoiseEnabled = false

    /** Cached oversampling mode in WAKE / ACTIVE (0 normal … 3 low power). */
    protected int oversamplingMode = 0

    /** Cached oversampling mode in SLEEP (0 normal … 3 low power). */
    protected int sleepOversamplingMode = 0

    /** Cached FIFO mode (0 disabled, 1 circular, 2 fill, 3 trigger). */
    protected int fifoMode = 0

    MMA8451QMinimal(RegisterConnection connection) {
        this.connection = connection
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
    protected void init() {
        // 1. Power-up delay (I²C is ready ~350 us after VDDIO is on).
        delay(1)
        // 2. Identity check.
        int who = readReg(REG_WHO_AM_I)
        if (who != WHO_AM_I_VALUE) {
            throw new IOException("MMA8451Q WHO_AM_I: expected 0x" +
                    Integer.toHexString(WHO_AM_I_VALUE) + ", got 0x" +
                    Integer.toHexString(who & 0xFF))
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

        this.rangeG = DEFAULT_RANGE_G
        this.odrHz = DEFAULT_ODR_HZ
        this.bits = DEFAULT_BITS
        this.lowNoiseEnabled = false
        this.oversamplingMode = 0
        this.sleepOversamplingMode = 0
        this.fifoMode = 0
    }

    /**
     * Read 3-axis linear acceleration.
     *
     * Burst-reads the six data bytes (0x01–0x06) so the X, Y and Z samples
     * come from a single measurement.
     *
     * @return array of three doubles — X, Y, Z acceleration in *g*
     */
    double[] read() {
        byte[] raw = readBurst(REG_OUT_X_MSB, 6)
        return decode(raw)
    }

    /**
     * Write one byte to a register.
     *
     * @param reg register address
     * @param value byte value to write
     */
    protected void writeReg(int reg, int value) {
        connection.write(reg, [(byte) value] as byte[])
    }

    /**
     * Read one byte from a register.
     *
     * @param reg register address
     * @return the register value (0..255)
     */
    protected int readReg(int reg) {
        return connection.read(reg, 1)[0] & 0xFF
    }

    /**
     * Read `n` consecutive bytes starting at a register.
     *
     * @param reg register address
     * @param n number of bytes to read
     * @return the bytes received
     */
    protected byte[] readBurst(int reg, int n) {
        return connection.read(reg, n)
    }

    /**
     * Convert raw output bytes to X, Y, Z acceleration in *g*.
     *
     * @param raw the six output bytes starting at 0x01
     * @return array of three doubles — X, Y, Z acceleration in *g*
     */
    protected double[] decode(byte[] raw) {
        int scaleIndex = rangeG == 4 ? 1 : (rangeG == 8 ? 2 : 0)
        int cx
        int cy
        int cz
        double scale
        if (bits == 8) {
            scale = SCALE_8BIT[scaleIndex]
            cx = Register.toSigned(raw[0] & 0xFF, 8)
            cy = Register.toSigned(raw[2] & 0xFF, 8)
            cz = Register.toSigned(raw[4] & 0xFF, 8)
        } else {
            scale = SCALE_14BIT[scaleIndex]
            int rx = ((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF)
            int ry = ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF)
            int rz = ((raw[4] & 0xFF) << 8) | (raw[5] & 0xFF)
            // 14-bit data is left-justified; bits 1:0 of the LSB byte are 0.
            cx = Register.toSigned(rx >> 2, 14)
            cy = Register.toSigned(ry >> 2, 14)
            cz = Register.toSigned(rz >> 2, 14)
        }
        return [cx / scale, cy / scale, cz / scale] as double[]
    }

    /**
     * Sleep for the given number of milliseconds.
     *
     * @param ms milliseconds to sleep
     * @throws IOException if the wait is interrupted
     */
    protected static void delay(long ms) {
        try {
            Thread.sleep(ms)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
            throw new IOException("interrupted while waiting", e)
        }
    }

    /**
     * Poll `CTRL_REG2` until the software reset has completed.
     */
    protected void waitResetDone() {
        for (int waited = 0; waited < 20; waited++) {
            try {
                if (readReg(REG_CTRL_REG2) == 0x00) {
                    return
                }
            } catch (IOException ignored) {
                // The I²C interface is reset by RST — a NACK is expected here.
            }
            delay(1)
        }
    }

    /**
     * @return the STANDBY to ACTIVE turn-on time in ms (2/ODR + 2)
     */
    protected double turnOnTimeMs() {
        return (2.0d / odrHz) * 1000.0d + 2.0d
    }

    /**
     * Wait for the STANDBY to ACTIVE turn-on time.
     */
    protected void waitTurnOn() {
        delay((long) Math.ceil(turnOnTimeMs()))
    }

    /**
     * Write a STANDBY-only register, restoring ACTIVE afterwards.
     *
     * @param reg register address
     * @param value byte value to write
     */
    protected void standbyWrite(int reg, int value) {
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
     * @param extra optional array of `int[]{register, value}` pairs written as well
     */
    protected void standbyWriteMulti(int reg, int value, int[][] extra) {
        int ctrl = readReg(REG_CTRL_REG1)
        boolean wasActive = (ctrl & CTRL1_ACTIVE) != 0
        if (wasActive) {
            writeReg(REG_CTRL_REG1, ctrl & ~CTRL1_ACTIVE & 0xFF)
        }
        if (reg == REG_CTRL_REG1) {
            // Every CTRL_REG1 field except ACTIVE may only change in STANDBY.
            writeReg(REG_CTRL_REG1, value & ~CTRL1_ACTIVE & 0xFF)
        } else {
            writeReg(reg, value)
        }
        if (extra != null) {
            for (int[] pair : extra) {
                writeReg(pair[0], pair[1])
            }
        }
        if (wasActive) {
            // The final CTRL_REG1 value: the new one when CTRL_REG1 was the
            // target, the original one otherwise.
            int finalCtrl = reg == REG_CTRL_REG1 ? (value | CTRL1_ACTIVE) : (ctrl | CTRL1_ACTIVE)
            writeReg(REG_CTRL_REG1, finalCtrl)
            waitTurnOn()
        }
    }
}
