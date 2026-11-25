package it.uhde.periph.chips.accelerometer

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection

/**
 * FXOS8700CQ — 6-axis e-compass: 14-bit accelerometer + 16-bit magnetometer
 * (NXP) — minimal interface.
 *
 * <p>The FXOS8700CQ combines a three-axis MEMS accelerometer (±2 / ±4 / ±8
 * <i>g</i>, 0.244 / 0.488 / 0.976 mg/LSB) and a three-axis magnetometer (fixed
 * ±1200 µT range, 0.1 µT/LSB) behind <b>one I²C address</b> (0x1C-0x1F,
 * selected by SA1 / SA0) and one shared ADC. In hybrid mode both sensors run
 * at half the programmed output data rate and all twelve data bytes can be
 * read in one burst. Do not leave SA0 floating: a floating SA0 at power-up
 * selects SPI mode.
 *
 * <p>I²C framing is the plain register-pointer protocol (write the register
 * address, repeated START, read); multi-byte transfers auto-increment within
 * one transaction.
 *
 * <p>Default configuration (baked in at construction):
 * <ul>
 *   <li>Hybrid mode, system ODR 200 Hz (100 Hz per sensor), ±2 <i>g</i>
 *       (4096 counts/<i>g</i>), 14-bit output, normal accelerometer
 *       oversampling, magnetometer OSR 7, low-noise off,
 *       {@code hyb_autoinc_mode} on</li>
 *   <li>No FIFO, no detectors, no interrupt routing, interrupts active-low
 *       push-pull</li>
 * </ul>
 */
@CompileStatic
class FXOS8700Minimal {

    // --- Registers -----------------------------------------------------------
    static final int REG_STATUS = 0x00
    static final int REG_OUT_X_MSB = 0x01
    static final int REG_F_SETUP = 0x09
    static final int REG_TRIG_CFG = 0x0A
    static final int REG_SYSMOD = 0x0B
    static final int REG_INT_SOURCE = 0x0C
    static final int REG_WHO_AM_I = 0x0D
    static final int REG_XYZ_DATA_CFG = 0x0E
    static final int REG_HP_FILTER_CUTOFF = 0x0F
    static final int REG_PL_STATUS = 0x10
    static final int REG_PL_CFG = 0x11
    static final int REG_PL_COUNT = 0x12
    static final int REG_PL_BF_ZCOMP = 0x13
    static final int REG_PL_THS = 0x14
    static final int REG_A_FFMT_CFG = 0x15
    static final int REG_A_FFMT_SRC = 0x16
    static final int REG_A_FFMT_THS = 0x17
    static final int REG_A_FFMT_COUNT = 0x18
    static final int REG_TRANSIENT_CFG = 0x1D
    static final int REG_TRANSIENT_SRC = 0x1E
    static final int REG_TRANSIENT_THS = 0x1F
    static final int REG_TRANSIENT_COUNT = 0x20
    static final int REG_PULSE_CFG = 0x21
    static final int REG_PULSE_SRC = 0x22
    static final int REG_PULSE_THSX = 0x23
    static final int REG_PULSE_TMLT = 0x26
    static final int REG_PULSE_LTCY = 0x27
    static final int REG_PULSE_WIND = 0x28
    static final int REG_ASLP_COUNT = 0x29
    static final int REG_CTRL1 = 0x2A
    static final int REG_CTRL2 = 0x2B
    static final int REG_CTRL3 = 0x2C
    static final int REG_CTRL4 = 0x2D
    static final int REG_CTRL5 = 0x2E
    static final int REG_OFF_X = 0x2F
    static final int REG_M_DR_STATUS = 0x32
    static final int REG_M_OUT_X_MSB = 0x33
    static final int REG_M_OFF_X_MSB = 0x3F
    static final int REG_MAX_X_MSB = 0x45
    static final int REG_MIN_X_MSB = 0x4B
    static final int REG_TEMP = 0x51
    static final int REG_M_CTRL1 = 0x5B
    static final int REG_M_CTRL2 = 0x5C
    static final int REG_M_CTRL3 = 0x5D
    // --- Bit masks -------------------------------------------------------------
    static final int CTRL1_ACTIVE = 0x01
    static final int CTRL1_F_READ = 0x02
    static final int CTRL1_LNOISE = 0x04
    static final int CTRL1_DR_MASK = 0x38
    static final int CTRL1_ASLP_MASK = 0xC0
    static final int CTRL2_ST = 0x80
    static final int CTRL2_RST = 0x40
    static final int CTRL2_SMODS_MASK = 0x18
    static final int CTRL2_SLPE = 0x04
    static final int CTRL2_MODS_MASK = 0x03
    static final int STATUS_ZYXDR = 0x08
    static final int M_CTRL1_ACAL = 0x80
    static final int M_CTRL1_RST = 0x40
    static final int M_CTRL1_OST = 0x20
    static final int M_CTRL1_OS_MASK = 0x1C
    static final int M_CTRL1_HMS_MASK = 0x03
    static final int M_CTRL2_HYB_AUTOINC = 0x20
    /** I²C address for SA1 = 0, SA0 = 0 (default); 0x1C-0x1F are selectable. */
    static final int I2C_ADDRESS = 0x1E
    /** System output data rates selected by CTRL_REG1.dr (Hz). */
    static final double[] ODR_HZ = [800.0, 400.0, 200.0, 100.0, 50.0, 12.5, 6.25, 1.5625] as double[]
    /** Counts per g in 14-bit mode for ±2 / ±4 / ±8 g. */
    private static final double[] COUNTS_PER_G = [4096.0, 2048.0, 1024.0] as double[]
    protected final RegisterConnection connection
    // Cached configuration, kept in sync with the registers.
    protected int mode = 2;          // 0 accel only, 1 mag only, 2 hybrid
    protected int dr = 2;            // CTRL_REG1.dr code
    protected int fsG = 2
    protected int mods = 0;          // accelerometer oversampling (wake)
    protected boolean lnoise = false
    protected boolean fRead = false; // true = 8-bit fast read
    protected int fifoMode = 0
    protected boolean hyb = true;    // hyb_autoinc_mode

    /**
     * Create the driver and run the initialization sequence.
     *
     * @param connection RegisterConnection (I²C) bound to 0x1C-0x1F
     * @throws IOException on bus error or if {@code WHO_AM_I} is neither 0xC7 nor 0xC4
     */
    FXOS8700Minimal(RegisterConnection connection) {
        this.connection = connection
        init()
    }

    /**
     * Run the datasheet initialization sequence.
     *
     * <p>Verifies {@code WHO_AM_I} (0xC7, or 0xC4 on pre-production parts),
     * performs a software reset (the {@code rst} write is not acknowledged and
     * the resulting error is ignored) and starts hybrid mode at a 200 Hz system
     * ODR, ±2 <i>g</i>, magnetometer OSR 7.
     *
     * @throws IOException on bus error or a wrong {@code WHO_AM_I}
     */
    void init() {
        sleepMs(1)
        int who = whoAmI()
        if (who != 0xC7 && who != 0xC4) {
            throw new IOException(String.format("FXOS8700 not found: unexpected WHO_AM_I 0x%02X", who))
        }
        softReset()
        who = whoAmI()
        if (who != 0xC7 && who != 0xC4) {
            throw new IOException(String.format("FXOS8700 not found after reset: WHO_AM_I 0x%02X", who))
        }
        writeReg(REG_CTRL1, 0x00)
        writeReg(REG_M_CTRL1, 0x1F)
        writeReg(REG_M_CTRL2, M_CTRL2_HYB_AUTOINC)
        writeReg(REG_XYZ_DATA_CFG, 0x00)
        writeReg(REG_CTRL2, 0x00)
        writeReg(REG_CTRL1, 0x11)
        this.@mode = 2
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
     * @return {@code {x, y, z}} in <i>g</i>
     * @throws IOException on bus error
     */
    double[] readAccel() {
        int[] raw = readAccelCounts()
        double scale = accelScale()
        return [raw[0] / scale, raw[1] / scale, raw[2] / scale] as double[]
    }

    /**
     * Read the magnetic flux density (one burst read of 0x33-0x38; reading
     * {@code M_OUT_X_MSB} first latches the X / Y / Z set).
     *
     * @return {@code {x, y, z}} in µT
     * @throws IOException on bus error
     */
    double[] readMag() {
        int[] raw = readMagCounts()
        return [raw[0] * 0.1, raw[1] * 0.1, raw[2] * 0.1] as double[]
    }

    // --- Helpers shared with FXOS8700Full ------------------------------------

    /**
     * Sleep for the given number of milliseconds.
     *
     * @param ms delay in milliseconds
     */
    protected static void sleepMs(int ms) {
        try {
            Thread.sleep(ms)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Read one register.
     *
     * @param reg register address
     * @return the value (0-255)
     * @throws IOException on bus error
     */
    protected int readReg(int reg) {
        return connection.read(reg, 1)[0] & 0xFF
    }

    /**
     * Write one register.
     *
     * @param reg register address
     * @param value value to write
     * @throws IOException on bus error
     */
    protected void writeReg(int reg, int value) {
        connection.write(reg, [(byte) value] as byte[])
    }

    /**
     * Read-modify-write: clear {@code mask}, OR in {@code value}.
     *
     * @param reg register address
     * @param mask bits to replace
     * @param value new bits
     * @throws IOException on bus error
     */
    protected void modify(int reg, int mask, int value) {
        int cur = readReg(reg)
        writeReg(reg, (cur & ~mask & 0xFF) | (value & mask))
    }

    /**
     * Read {@code WHO_AM_I}, retrying on a bus error for up to 10 ms.
     *
     * @return the identity byte
     * @throws IOException if every attempt fails
     */
    private int whoAmI() {
        for (int i = 0; i < 10; i++) {
            try {
                return readReg(REG_WHO_AM_I)
            } catch (IOException e) {
                sleepMs(1)
            }
        }
        return readReg(REG_WHO_AM_I)
    }

    /** Write {@code CTRL_REG2.rst} tolerating the missing ACK, then wait 2 ms. */
    protected void softReset() {
        try {
            writeReg(REG_CTRL2, CTRL2_RST)
        } catch (IOException e) {
            // The chip resets its I²C interface and does not ACK the write.
        }
        sleepMs(2)
    }

    /** @return counts per <i>g</i> at the current range and resolution */
    protected double accelScale() {
        double cpg = COUNTS_PER_G[fsG == 2 ? 0 : (fsG == 4 ? 1 : 2)]
        return fRead ? cpg / 64.0 : cpg
    }

    /**
     * Decode three accelerometer axes.
     *
     * @param data raw bytes
     * @param offset start index
     * @return signed counts {@code {x, y, z}}
     */
    protected int[] decodeAccel(byte[] data, int offset) {
        int[] out = new int[3]
        for (int i = 0; i < 3; i++) {
            if (fRead) {
                out[i] = (byte) data[offset + i]
            } else {
                int raw = ((data[offset + 2 * i] & 0xFF) << 8) | (data[offset + 2 * i + 1] & 0xFF)
                out[i] = Register.toSigned(raw, 16) >> 2
            }
        }
        return out
    }

    /**
     * Decode three magnetometer axes (16-bit, MSB first).
     *
     * @param data raw bytes
     * @param offset start index
     * @return signed counts {@code {x, y, z}}
     */
    protected static int[] decodeMag(byte[] data, int offset) {
        int[] out = new int[3]
        for (int i = 0; i < 3; i++) {
            int raw = ((data[offset + 2 * i] & 0xFF) << 8) | (data[offset + 2 * i + 1] & 0xFF)
            out[i] = Register.toSigned(raw, 16)
        }
        return out
    }

    /**
     * Read the signed accelerometer counts (14-bit, or 8-bit in fast read).
     *
     * @return {@code {x, y, z}}
     * @throws IOException on bus error
     */
    protected int[] readAccelCounts() {
        return decodeAccel(connection.read(REG_OUT_X_MSB, fRead ? 3 : 6), 0)
    }

    /**
     * Read the signed magnetometer counts.
     *
     * @return {@code {x, y, z}}
     * @throws IOException on bus error
     */
    protected int[] readMagCounts() {
        return decodeMag(connection.read(REG_M_OUT_X_MSB, 6), 0)
    }

    /** @return the STANDBY to ACTIVE turn-on time, 2 / ODR + 2 ms (rounded up) */
    protected int turnOnMs() {
        return (int) (2000.0 / ODR_HZ[dr]) + 3
    }
}
