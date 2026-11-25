package it.uhde.periph.chips.accelerometer;

import it.uhde.periph.connection.Register;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * MMA8451Q — 3-axis MEMS accelerometer (NXP / Freescale) — minimal interface.
 *
 * <p>Triaxial low-g accelerometer with 14-bit digital output and ±2/±4/±8 <i>g</i>
 * selectable full-scale range. Communicates over I²C at address 0x1C (SA0 = GND)
 * or 0x1D (SA0 = VDDIO).
 *
 * <p>Default configuration (baked in at construction):
 * <ul>
 *   <li>Range ±2 <i>g</i> (4096 counts/<i>g</i>)</li>
 *   <li>Output data rate 100 Hz</li>
 *   <li>14-bit output resolution (normal oversampling)</li>
 *   <li>Low-noise mode off, high-pass output off</li>
 *   <li>FIFO disabled, all detectors and interrupt routing off</li>
 *   <li>Interrupts active-low, push-pull; offsets 0</li>
 * </ul>
 *
 * <p>The constructor runs the chip's initialization sequence: it verifies
 * {@code WHO_AM_I} (0x1A), performs a software reset and configures the chip for
 * ±2 <i>g</i> at 100 Hz in ACTIVE mode.
 */
public class MMA8451QMinimal {

    // Register map.
    protected static final int REG_STATUS        = 0x00;
    protected static final int REG_OUT_X_MSB     = 0x01;
    protected static final int REG_OUT_X_LSB     = 0x02;
    protected static final int REG_OUT_Y_MSB     = 0x03;
    protected static final int REG_OUT_Y_LSB     = 0x04;
    protected static final int REG_OUT_Z_MSB     = 0x05;
    protected static final int REG_OUT_Z_LSB     = 0x06;
    protected static final int REG_F_SETUP       = 0x09;
    protected static final int REG_TRIG_CFG      = 0x0A;
    protected static final int REG_SYSMOD        = 0x0B;
    protected static final int REG_INT_SOURCE    = 0x0C;
    protected static final int REG_WHO_AM_I      = 0x0D;
    protected static final int REG_XYZ_DATA_CFG  = 0x0E;
    protected static final int REG_HP_FILTER     = 0x0F;
    protected static final int REG_PL_STATUS     = 0x10;
    protected static final int REG_PL_CFG        = 0x11;
    protected static final int REG_PL_COUNT      = 0x12;
    protected static final int REG_PL_BF_ZCOMP   = 0x13;
    protected static final int REG_P_L_THS       = 0x14;
    protected static final int REG_FF_MT_CFG     = 0x15;
    protected static final int REG_FF_MT_SRC     = 0x16;
    protected static final int REG_FF_MT_THS     = 0x17;
    protected static final int REG_FF_MT_COUNT   = 0x18;
    protected static final int REG_TRANSIENT_CFG = 0x1D;
    protected static final int REG_TRANSIENT_SRC = 0x1E;
    protected static final int REG_TRANSIENT_THS = 0x1F;
    protected static final int REG_TRANSIENT_CNT = 0x20;
    protected static final int REG_PULSE_CFG     = 0x21;
    protected static final int REG_PULSE_SRC     = 0x22;
    protected static final int REG_PULSE_THSX    = 0x23;
    protected static final int REG_PULSE_THSY    = 0x24;
    protected static final int REG_PULSE_THSZ    = 0x25;
    protected static final int REG_PULSE_TMLT    = 0x26;
    protected static final int REG_PULSE_LTCY    = 0x27;
    protected static final int REG_PULSE_WIND    = 0x28;
    protected static final int REG_ASLP_COUNT    = 0x29;
    protected static final int REG_CTRL_REG1     = 0x2A;
    protected static final int REG_CTRL_REG2     = 0x2B;
    protected static final int REG_CTRL_REG3     = 0x2C;
    protected static final int REG_CTRL_REG4     = 0x2D;
    protected static final int REG_CTRL_REG5     = 0x2E;
    protected static final int REG_OFF_X         = 0x2F;
    protected static final int REG_OFF_Y         = 0x30;
    protected static final int REG_OFF_Z         = 0x31;

    /** WHO_AM_I (0x0D) identity value. */
    protected static final int WHO_AM_I_VALUE = 0x1A;

    /** STATUS register bit 3 — {@code ZYXDR}, all three axes have new data. */
    protected static final int STATUS_ZYXDR = 0x08;

    /** CTRL_REG1 bit 0 — {@code ACTIVE}. */
    protected static final int CTRL1_ACTIVE = 0x01;

    /** CTRL_REG1 bit 1 — {@code F_READ}, fast read (8-bit output). */
    protected static final int CTRL1_F_READ = 0x02;

    /** CTRL_REG1 bit 2 — {@code LNOISE}, reduced-noise mode. */
    protected static final int CTRL1_LNOISE = 0x04;

    /** CTRL_REG2 bit 6 — {@code RST}, software reset. */
    protected static final int CTRL2_RST = 0x40;

    /** CTRL_REG2 bit 7 — {@code ST}, electrostatic self-test. */
    protected static final int CTRL2_ST = 0x80;

    /** Default full-scale range in <i>g</i>. */
    protected static final int DEFAULT_RANGE_G = 2;

    /** Default output data rate in Hz. */
    protected static final double DEFAULT_ODR_HZ = 100.0;

    /** Default output resolution in bits. */
    protected static final int DEFAULT_BITS = 14;

    /** Counts per <i>g</i> at 14-bit resolution, indexed by full-scale range. */
    protected static final double[] SCALE_14BIT = {4096.0, 2048.0, 1024.0};

    /** Counts per <i>g</i> at 8-bit (fast read) resolution, indexed by full-scale range. */
    protected static final double[] SCALE_8BIT = {64.0, 32.0, 16.0};

    /** The register connection this driver talks to. */
    protected final RegisterConnection connection;

    /** Cached full-scale range in <i>g</i> (2, 4 or 8). */
    protected int rangeG = DEFAULT_RANGE_G;

    /** Cached output data rate in Hz. */
    protected double odrHz = DEFAULT_ODR_HZ;

    /** Cached output resolution in bits (14 or 8). */
    protected int bits = DEFAULT_BITS;

    /** {@code true} while low-noise (reduced-noise) mode is enabled. */
    protected boolean lowNoise = false;

    /** Cached oversampling mode in WAKE / ACTIVE (0 normal … 3 low power). */
    protected int oversampling = 0;

    /** Cached oversampling mode in SLEEP (0 normal … 3 low power). */
    protected int sleepOversampling = 0;

    /** Cached FIFO mode (0 disabled, 1 circular, 2 fill, 3 trigger). */
    protected int fifoMode = 0;

    /**
     * Construct the driver and run the chip's initialization sequence.
     *
     * <p>The sequence waits for the power-up delay, verifies {@code WHO_AM_I}
     * (0x1A), performs a software reset and configures ±2 <i>g</i>, 100 Hz,
     * 14-bit output with normal oversampling in ACTIVE mode.
     *
     * @param connection configured I²C, SMBus, or SPI register connection
     * @throws IOException on bus error, a wrong {@code WHO_AM_I} value, or an
     *         interrupted wait
     */
    public MMA8451QMinimal(RegisterConnection connection) throws IOException {
        this.connection = connection;
        init();
    }

    /**
     * Run the chip's initialization sequence.
     *
     * <p>Verifies {@code WHO_AM_I} (0x1A), performs a software reset, then
     * configures ±2 <i>g</i>, 100 Hz, 14-bit output, normal oversampling and
     * switches the chip to ACTIVE.
     *
     * @throws IOException on bus error, a wrong {@code WHO_AM_I} value, or an
     *         interrupted wait
     */
    protected void init() throws IOException {
        // 1. Power-up delay (I²C is ready ~350 us after VDDIO is on).
        delay(1);
        // 2. Identity check.
        int who = readReg(REG_WHO_AM_I);
        if (who != WHO_AM_I_VALUE) {
            throw new IOException("MMA8451Q WHO_AM_I: expected 0x"
                    + Integer.toHexString(WHO_AM_I_VALUE) + ", got 0x"
                    + Integer.toHexString(who & 0xFF));
        }
        // 3. Software reset — the I²C interface is reset by it, so the write
        //    may NACK; wait and re-read CTRL_REG2 until it reads back 0.
        writeReg(REG_CTRL_REG2, CTRL2_RST);
        delay(1);
        waitResetDone();
        // 4. ±2 g, no high-pass output.
        writeReg(REG_XYZ_DATA_CFG, 0x00);
        // 5. Normal oversampling in WAKE and SLEEP, Auto-SLEEP off.
        writeReg(REG_CTRL_REG2, 0x00);
        // 6. 100 Hz, LNOISE off, F_READ off, ACTIVE.
        writeReg(REG_CTRL_REG1, 0x19);
        // 7. Wait for the STANDBY -> ACTIVE turn-on time (2/ODR + 2 ms).
        waitTurnOn();

        this.rangeG = DEFAULT_RANGE_G;
        this.odrHz = DEFAULT_ODR_HZ;
        this.bits = DEFAULT_BITS;
        this.lowNoise = false;
        this.oversampling = 0;
        this.sleepOversampling = 0;
        this.fifoMode = 0;
    }

    /**
     * Read 3-axis linear acceleration.
     *
     * <p>Burst-reads the six data bytes (0x01–0x06) so the X, Y and Z samples
     * come from a single measurement.
     *
     * @return array of three doubles — X, Y, Z acceleration in <i>g</i>
     * @throws IOException on bus error
     */
    public double[] read() throws IOException {
        byte[] raw = readBurst(REG_OUT_X_MSB, 6);
        return decode(raw);
    }

    /**
     * Write one byte to a register.
     *
     * @param reg   register address
     * @param value byte value to write
     * @throws IOException on bus error
     */
    protected void writeReg(int reg, int value) throws IOException {
        connection.write(reg, new byte[]{(byte) value});
    }

    /**
     * Read one byte from a register.
     *
     * @param reg register address
     * @return the register value (0..255)
     * @throws IOException on bus error
     */
    protected int readReg(int reg) throws IOException {
        return connection.read(reg, 1)[0] & 0xFF;
    }

    /**
     * Read {@code n} consecutive bytes starting at a register.
     *
     * @param reg register address
     * @param n   number of bytes to read
     * @return the bytes received
     * @throws IOException on bus error
     */
    protected byte[] readBurst(int reg, int n) throws IOException {
        return connection.read(reg, n);
    }

    /**
     * Convert raw output bytes to X, Y, Z acceleration in <i>g</i>.
     *
     * @param raw the six output bytes starting at 0x01
     * @return array of three doubles — X, Y, Z acceleration in <i>g</i>
     */
    protected double[] decode(byte[] raw) {
        int scaleIndex = (rangeG == 4) ? 1 : (rangeG == 8 ? 2 : 0);
        int cx;
        int cy;
        int cz;
        double scale;
        if (bits == 8) {
            scale = SCALE_8BIT[scaleIndex];
            cx = Register.toSigned(raw[0] & 0xFF, 8);
            cy = Register.toSigned(raw[2] & 0xFF, 8);
            cz = Register.toSigned(raw[4] & 0xFF, 8);
        } else {
            scale = SCALE_14BIT[scaleIndex];
            int rx = ((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF);
            int ry = ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF);
            int rz = ((raw[4] & 0xFF) << 8) | (raw[5] & 0xFF);
            // 14-bit data is left-justified; bits 1:0 of the LSB byte are 0.
            cx = Register.toSigned(rx >> 2, 14);
            cy = Register.toSigned(ry >> 2, 14);
            cz = Register.toSigned(rz >> 2, 14);
        }
        return new double[]{cx / scale, cy / scale, cz / scale};
    }

    /**
     * Sleep for the given number of milliseconds.
     *
     * @param ms milliseconds to sleep
     * @throws IOException if the wait is interrupted
     */
    protected static void delay(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting", e);
        }
    }

    /**
     * Poll {@code CTRL_REG2} until the software reset has completed.
     *
     * @throws IOException on a persistent bus error
     */
    protected void waitResetDone() throws IOException {
        for (int waited = 0; waited < 20; waited++) {
            try {
                if (readReg(REG_CTRL_REG2) == 0x00) {
                    return;
                }
            } catch (IOException e) {
                // The I²C interface is reset by RST — a NACK is expected here.
            }
            delay(1);
        }
    }

    /**
     * @return the STANDBY to ACTIVE turn-on time in ms (2/ODR + 2)
     */
    protected double turnOnTimeMs() {
        return (2.0 / odrHz) * 1000.0 + 2.0;
    }

    /**
     * Wait for the STANDBY to ACTIVE turn-on time.
     *
     * @throws IOException on bus error or an interrupted wait
     */
    protected void waitTurnOn() throws IOException {
        delay((long) Math.ceil(turnOnTimeMs()));
    }

    /**
     * Write a STANDBY-only register, restoring ACTIVE afterwards.
     *
     * @param reg   register address
     * @param value byte value to write
     * @throws IOException on bus error
     */
    protected void standbyWrite(int reg, int value) throws IOException {
        standbyWriteMulti(reg, value, null);
    }

    /**
     * Write a STANDBY-only register plus optional extra registers.
     *
     * <p>When the chip is ACTIVE it is switched to STANDBY first, the
     * register(s) are written, and {@code ACTIVE} is restored afterwards. The
     * turn-on time is awaited before returning.
     *
     * @param reg   register address of the primary write
     * @param value byte value for {@code reg}
     * @param extra optional array of {@code {register, value}} pairs written as well
     * @throws IOException on bus error
     */
    protected void standbyWriteMulti(int reg, int value, int[][] extra) throws IOException {
        int ctrl = readReg(REG_CTRL_REG1);
        boolean wasActive = (ctrl & CTRL1_ACTIVE) != 0;
        if (wasActive) {
            writeReg(REG_CTRL_REG1, ctrl & ~CTRL1_ACTIVE & 0xFF);
        }
        if (reg == REG_CTRL_REG1) {
            // Every CTRL_REG1 field except ACTIVE may only change in STANDBY.
            writeReg(REG_CTRL_REG1, value & ~CTRL1_ACTIVE & 0xFF);
        } else {
            writeReg(reg, value);
        }
        if (extra != null) {
            for (int[] pair : extra) {
                writeReg(pair[0], pair[1]);
            }
        }
        if (wasActive) {
            // The final CTRL_REG1 value: the new one when CTRL_REG1 was the
            // target, the original one otherwise.
            int finalCtrl = (reg == REG_CTRL_REG1)
                    ? (value | CTRL1_ACTIVE)
                    : (ctrl | CTRL1_ACTIVE);
            writeReg(REG_CTRL_REG1, finalCtrl);
            waitTurnOn();
        }
    }
}
