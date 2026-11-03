package it.uhde.periph.chips.accelerometer;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * LIS3DH — 3-axis ultra-low-power MEMS accelerometer (STMicroelectronics) —
 * minimal interface.
 *
 * <p>Triaxial accelerometer with a 16-bit left-justified output and four
 * selectable full-scale ranges (±2, ±4, ±8, ±16 *g*). Three operating modes
 * trade resolution for current — low-power (8-bit), normal (10-bit) and
 * high-resolution (12-bit) — across output data rates from 1 Hz to
 * 5.376 kHz.
 *
 * <p>Communicates over I²C at address 0x18 (SA0 = GND) or 0x19
 * (SA0 = VDD_IO). The chip also supports 3-/4-wire SPI, which is out of
 * scope here. After the slave address the master sends an 8-bit
 * sub-address: the 7 LSbs are the register address and the MSb enables the
 * address auto-increment, so every multi-byte transfer ORs {@code 0x80} into
 * the register address (single-register accesses do not).
 *
 * <p>The constructor runs the datasheet Initialization Sequence: it verifies
 * {@code WHO_AM_I} (0x0F) == 0x33, puts the control registers into a known
 * state (the chip has no soft reset), configures ±2 *g*, 100 Hz ODR,
 * high-resolution 12-bit mode with {@code BDU} on, and waits 7/ODR (70 ms)
 * for the converter to turn on.
 *
 * <p>Default configuration (baked in at construction):
 * <ul>
 *   <li>Range ±2 *g* (1 mg/LSB)</li>
 *   <li>100 Hz output data rate</li>
 *   <li>High-resolution (12-bit) mode, all three axes enabled</li>
 *   <li>{@code BDU} = 1 (block data update)</li>
 *   <li>High-pass filter bypassed, FIFO bypassed</li>
 *   <li>All interrupt routing cleared</li>
 *   <li>Auxiliary ADC and temperature sensor off</li>
 *   <li>{@code CTRL_REG0} untouched</li>
 * </ul>
 */
public class Lis3dhMinimal {

    /** Register map of the LIS3DH. */
    protected static final int REG_STATUS_AUX   = 0x07;
    protected static final int REG_OUT_ADC1_L   = 0x08;
    protected static final int REG_OUT_ADC1_H   = 0x09;
    protected static final int REG_OUT_ADC2_L   = 0x0A;
    protected static final int REG_OUT_ADC2_H   = 0x0B;
    protected static final int REG_OUT_ADC3_L   = 0x0C;
    protected static final int REG_OUT_ADC3_H   = 0x0D;
    protected static final int REG_WHO_AM_I     = 0x0F;
    protected static final int REG_CTRL_REG0    = 0x1E;
    protected static final int REG_TEMP_CFG     = 0x1F;
    protected static final int REG_CTRL_REG1    = 0x20;
    protected static final int REG_CTRL_REG2    = 0x21;
    protected static final int REG_CTRL_REG3    = 0x22;
    protected static final int REG_CTRL_REG4    = 0x23;
    protected static final int REG_CTRL_REG5    = 0x24;
    protected static final int REG_CTRL_REG6    = 0x25;
    protected static final int REG_REFERENCE    = 0x26;
    protected static final int REG_STATUS       = 0x27;
    protected static final int REG_OUT_X_L      = 0x28;
    protected static final int REG_OUT_X_H      = 0x29;
    protected static final int REG_OUT_Y_L      = 0x2A;
    protected static final int REG_OUT_Y_H      = 0x2B;
    protected static final int REG_OUT_Z_L      = 0x2C;
    protected static final int REG_OUT_Z_H      = 0x2D;
    protected static final int REG_FIFO_CTRL    = 0x2E;
    protected static final int REG_FIFO_SRC     = 0x2F;
    protected static final int REG_INT1_CFG     = 0x30;
    protected static final int REG_INT1_SRC     = 0x31;
    protected static final int REG_INT1_THS     = 0x32;
    protected static final int REG_INT1_DUR     = 0x33;
    protected static final int REG_INT2_CFG     = 0x34;
    protected static final int REG_INT2_SRC     = 0x35;
    protected static final int REG_INT2_THS     = 0x36;
    protected static final int REG_INT2_DUR     = 0x37;
    protected static final int REG_CLICK_CFG    = 0x38;
    protected static final int REG_CLICK_SRC    = 0x39;
    protected static final int REG_CLICK_THS    = 0x3A;
    protected static final int REG_TIME_LIMIT   = 0x3B;
    protected static final int REG_TIME_LATENCY = 0x3C;
    protected static final int REG_TIME_WINDOW  = 0x3D;
    protected static final int REG_ACT_THS      = 0x3E;
    protected static final int REG_ACT_DUR      = 0x3F;

    /** Content {@code WHO_AM_I} (0x0F) must have for the chip to be recognised. */
    protected static final int WHO_AM_I_VALUE = 0x33;

    /** Initialization sequence: {@code BDU} = 1, {@code FS} = 00 (±2 *g*), {@code HR} = 1. */
    protected static final int CTRL_REG4_DEFAULT = 0x88;

    /** Initialization sequence: {@code ODR} = 0101 (100 Hz), {@code LPen} = 0, X/Y/Z enabled. */
    protected static final int CTRL_REG1_DEFAULT = 0x57;

    /** MSb of the I²C sub-address — enables the register auto-increment. */
    protected static final int AUTO_INCREMENT = 0x80;

    /** Time the power-on boot procedure needs before any register may be accessed. */
    protected static final int BOOT_DELAY_MS = 5;

    /** Low-power mode: 8-bit output. */
    public static final int MODE_LOW_POWER = 0;

    /** Normal mode: 10-bit output. */
    public static final int MODE_NORMAL = 1;

    /** High-resolution mode: 12-bit output. */
    public static final int MODE_HIGH_RESOLUTION = 2;

    /** ±2 *g* full-scale range. */
    public static final int RANGE_2G = 2;

    /** ±4 *g* full-scale range. */
    public static final int RANGE_4G = 4;

    /** ±8 *g* full-scale range. */
    public static final int RANGE_8G = 8;

    /** ±16 *g* full-scale range. */
    public static final int RANGE_16G = 16;

    /** The register connection bound to the chip. */
    protected final RegisterConnection connection;

    /** Currently configured full-scale range in *g*. */
    protected int rangeG = RANGE_2G;

    /** Currently configured operating mode (0 low-power, 1 normal, 2 high-resolution). */
    protected int mode = MODE_HIGH_RESOLUTION;

    /** Currently configured output data rate in Hz (0 = power-down). */
    protected int rateHz = 100;

    /**
     * Construct the driver and run the datasheet Initialization Sequence.
     *
     * @param connection configured I²C, SMBus, or SPI register connection bound
     *                   to the chip's address (0x18 with SA0 = GND, 0x19 with
     *                   SA0 = VDD_IO)
     * @throws IOException on bus error or when {@code WHO_AM_I} is not 0x33
     */
    public Lis3dhMinimal(RegisterConnection connection) throws IOException {
        this.connection = connection;
        runInitSequence();
    }

    /**
     * Run the datasheet Initialization Sequence (steps 1–6).
     *
     * @throws IOException on bus error or when {@code WHO_AM_I} is not 0x33
     */
    protected void runInitSequence() throws IOException {
        // Step 1: the boot procedure needs ~5 ms; no register access before
        // it completed.
        sleepMs(BOOT_DELAY_MS);

        // Step 2: verify WHO_AM_I.
        int id = readReg(REG_WHO_AM_I);
        if (id != WHO_AM_I_VALUE) {
            throw new IOException("LIS3DH WHO_AM_I: expected 0x33, got 0x"
                    + Integer.toHexString(id));
        }

        // Step 3: the chip has no soft reset — put the control registers into
        // a known state. CTRL_REG0 and the interrupt-generator registers are
        // left untouched, reserved registers are never written.
        writeReg(REG_CTRL_REG2, 0x00);
        writeReg(REG_CTRL_REG3, 0x00);
        writeReg(REG_CTRL_REG5, 0x00);
        writeReg(REG_CTRL_REG6, 0x00);
        writeReg(REG_FIFO_CTRL, 0x00);
        writeReg(REG_TEMP_CFG, 0x00);

        // Step 4: block data update, ±2 g, high-resolution 12-bit mode.
        writeReg(REG_CTRL_REG4, CTRL_REG4_DEFAULT);

        // Step 5: leave power-down — this starts the conversions.
        writeReg(REG_CTRL_REG1, CTRL_REG1_DEFAULT);

        // Step 6: wait for the high-resolution turn-on (7/ODR = 70 ms at
        // 100 Hz) — the longer of the turn-on time and one ODR period.
        sleepMs(settleMs());
    }

    /**
     * Write one byte to a register.
     *
     * @param reg   register address
     * @param value value to write (only the low 8 bits are used)
     * @throws IOException on bus error
     */
    protected void writeReg(int reg, int value) throws IOException {
        connection.write(reg, new byte[]{(byte) value});
    }

    /**
     * Read one byte from a register.
     *
     * @param reg register address
     * @return the register content (0–255)
     * @throws IOException on bus error
     */
    protected int readReg(int reg) throws IOException {
        return connection.read(reg, 1)[0] & 0xFF;
    }

    /**
     * Read {@code length} bytes starting at register {@code reg}.
     *
     * <p>A multi-byte transfer sets the MSb of the sub-address so the chip
     * auto-increments the register address.
     *
     * @param reg    first register address
     * @param length number of bytes to read
     * @return the bytes received
     * @throws IOException on bus error
     */
    protected byte[] readBurst(int reg, int length) throws IOException {
        return connection.read(length > 1 ? (reg | AUTO_INCREMENT) : reg, length);
    }

    /**
     * Decode one axis of the left-justified 16-bit output pair.
     *
     * <p>The 16-bit pair is sign-extended first and only then shifted right
     * for the current resolution (12-bit high-resolution, 10-bit normal,
     * 8-bit low-power).
     *
     * @param low  low byte of the pair
     * @param high high byte of the pair
     * @return the signed count at the current resolution
     */
    protected int decodeAxis(int low, int high) {
        short raw16 = (short) (((high & 0xFF) << 8) | (low & 0xFF));
        return raw16 >> shift();
    }

    /**
     * Right-shift applied to the left-justified 16-bit output pair.
     *
     * @return 4 for high-resolution, 6 for normal and 8 for low-power mode
     */
    protected int shift() {
        switch (mode) {
            case MODE_LOW_POWER: return 8;
            case MODE_NORMAL:    return 6;
            default:             return 4;
        }
    }

    /**
     * Sensitivity of the current mode and range in mg/LSB (datasheet Table 4).
     *
     * @return the sensitivity in mg per LSB
     */
    protected int sensitivityMg() {
        switch (mode) {
            case MODE_LOW_POWER:
                switch (rangeG) {
                    case RANGE_4G:  return 32;
                    case RANGE_8G:  return 64;
                    case RANGE_16G: return 192;
                    default:        return 16;
                }
            case MODE_NORMAL:
                switch (rangeG) {
                    case RANGE_4G:  return 8;
                    case RANGE_8G:  return 16;
                    case RANGE_16G: return 48;
                    default:        return 4;
                }
            default:
                switch (rangeG) {
                    case RANGE_4G:  return 2;
                    case RANGE_8G:  return 4;
                    case RANGE_16G: return 12;
                    default:        return 1;
                }
        }
    }

    /**
     * Convert a raw count into *g* with the sensitivity of the current mode
     * and range.
     *
     * @param counts signed counts at the current resolution
     * @return the acceleration in *g*
     */
    protected double scale(int counts) {
        return (double) counts * sensitivityMg() / 1000.0;
    }

    /**
     * Time to wait after an ODR or mode change: the longer of the mode's
     * turn-on time (datasheet Table 10) and one ODR period.
     *
     * @return the settle time in milliseconds
     */
    protected int settleMs() {
        if (rateHz <= 0) return 0;
        double periodMs = 1000.0 / rateHz;
        double turnOnMs;
        switch (mode) {
            case MODE_LOW_POWER: turnOnMs = 1.0; break;
            case MODE_NORMAL:    turnOnMs = 1.6; break;
            default:             turnOnMs = 7.0 * periodMs; break;
        }
        return (int) Math.ceil(Math.max(turnOnMs, periodMs));
    }

    /**
     * Round half away from zero, so every language computes the same
     * register codes.
     *
     * @param x the value to round
     * @return the rounded value
     */
    protected static int roundHalfAwayFromZero(double x) {
        return x >= 0 ? (int) Math.floor(x + 0.5) : -(int) Math.floor(-x + 0.5);
    }

    /**
     * Sleep for a number of milliseconds, ignoring interrupts.
     *
     * @param ms the delay in milliseconds
     */
    protected static void sleepMs(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Read the 3-axis linear acceleration.
     *
     * <p>Burst-reads the six output bytes (0x28–0x2D) with auto-increment so
     * X, Y and Z come from a single sample ({@code BDU} = 1), sign-extends
     * the left-justified 16-bit pairs and scales them with the sensitivity
     * of the current mode and range.
     *
     * @return array of three doubles — X, Y, Z acceleration in <i>g</i>
     * @throws IOException on bus error
     */
    public double[] read() throws IOException {
        byte[] raw = readBurst(REG_OUT_X_L, 6);
        return new double[]{
                scale(decodeAxis(raw[0], raw[1])),
                scale(decodeAxis(raw[2], raw[3])),
                scale(decodeAxis(raw[4], raw[5]))};
    }
}
