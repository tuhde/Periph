package it.uhde.periph.chips.light;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * TSL2561 two-channel ambient light sensor (TAOS / ams) — minimal interface.
 *
 * <p>Channel 0 responds to visible and infrared light, channel 1 mainly to
 * infrared; the two 16-bit integrating ADC counts are combined with the
 * datasheet's empirical formula into lux. The I²C address is 0x29 (ADDR SEL =
 * GND), 0x39 (floating) or 0x49 (VDD).
 *
 * <p>Every register access starts with a command byte ({@code 0x80 | addr}
 * for a byte, {@code 0xA0 | addr} for a little-endian word); both channels are
 * read in one 4-byte block read ({@code 0x9C}) so the pair belongs to one
 * integration. The constructor powers the chip up with 1× gain and 402 ms
 * integration and waits one integration.
 */
public class TSL2561Minimal {

    /** 7-bit I²C address with ADDR SEL floating (default of most breakout boards). */
    public static final int DEFAULT_ADDRESS = 0x39;

    /** 7-bit I²C address with ADDR SEL = GND. */
    public static final int ADDRESS_GND = 0x29;

    /** 7-bit I²C address with ADDR SEL = VDD. */
    public static final int ADDRESS_VDD = 0x49;

    /** Scale for every integration wait in percent (default 100; tests set 0 to skip the sleeps). */
    public static volatile int waitPercent = 100;

    /** Thrown when a channel count is at or above the full-scale limit of the integration time. */
    public static class SaturatedException extends IOException {
        /** Channel 0 count. */
        public final int ch0;
        /** Channel 1 count. */
        public final int ch1;

        /**
         * @param ch0 channel 0 count
         * @param ch1 channel 1 count
         */
        public SaturatedException(int ch0, int ch1) {
            super("TSL2561: channel saturated (ch0=" + ch0 + ", ch1=" + ch1 + ")");
            this.ch0 = ch0;
            this.ch1 = ch1;
        }
    }

    protected static final int CMD = 0x80;
    protected static final int CMD_CLEAR = 0xC0;
    protected static final int CMD_WORD = 0xA0;
    protected static final int CMD_DATA_BLOCK = 0x9C;
    protected static final int REG_CONTROL = 0x0;
    protected static final int REG_TIMING = 0x1;
    protected static final int REG_THRESH_LOW = 0x2;
    protected static final int REG_THRESH_HIGH = 0x4;
    protected static final int REG_INTERRUPT = 0x6;
    protected static final int REG_ID = 0xA;
    protected static final int POWER_ON = 0x03;
    protected static final int INTEG_MANUAL = 0x03;

    // Per INTEG code 0..2: nominal ms, scale, full-scale count, settle wait (one integration + ±6 % + margin).
    protected static final int[] INTEG_MS = {14, 101, 402};
    protected static final double[] INTEG_SCALE = {0.034, 0.252, 1.0};
    protected static final int[] INTEG_FULL_SCALE = {5047, 37177, 65535};
    protected static final int[] INTEG_SETTLE_MS = {20, 120, 450};

    private static final double[][] COEFF_TFNCL = {
        {0.50, 0.0304, 0.062}, {0.61, 0.0224, 0.031}, {0.80, 0.0128, 0.0153}, {1.30, 0.00146, 0.00112}};
    private static final double[][] COEFF_CS = {
        {0.52, 0.0315, 0.0593}, {0.65, 0.0229, 0.0291}, {0.80, 0.0157, 0.0180}, {1.30, 0.00338, 0.00260}};

    protected final RegisterConnection connection;
    protected int gain = 1;
    protected int integ = 2;
    protected String pkg = "T";

    /**
     * Construct the driver and run the initialization sequence: CONTROL ← 0x03,
     * read it back, TIMING ← 0x02 (1×, 402 ms) and wait one integration (450 ms).
     *
     * @param connection I²C register connection bound to the device (0x29, 0x39 or 0x49)
     * @throws IOException on bus error, or if CONTROL does not read back as powered up
     */
    public TSL2561Minimal(RegisterConnection connection) throws IOException {
        this.connection = connection;
        writeByte(REG_CONTROL, POWER_ON);
        if ((readByte(REG_CONTROL) & 0x03) != POWER_ON) {
            throw new IOException("TSL2561: CONTROL did not read back as powered up");
        }
        writeTiming(false);
        sleepMs(settleMs());
    }

    /**
     * Convert raw channel counts into lux with the datasheet's empirical formula.
     *
     * @param ch0           channel 0 count (visible + infrared)
     * @param ch1           channel 1 count (infrared)
     * @param gain          1 or 16
     * @param integrationMs 14, 101 or 402 (any other value is treated as 402 ms)
     * @param pkg           "T", "FN", "CL" or "CS"
     * @return lux; 0 for ch0 = 0 or a ratio above 1.30
     */
    public static double calculateLux(int ch0, int ch1, int gain, int integrationMs, String pkg) {
        int code = integrationMs == 14 ? 0 : integrationMs == 101 ? 1 : 2;
        double factor = (16.0 / (gain == 16 ? 16.0 : 1.0)) / INTEG_SCALE[code];
        double ch0n = ch0 * factor;
        double ch1n = ch1 * factor;
        if (ch0n <= 0) return 0.0;
        double r = ch1n / ch0n;
        double[][] c = "CS".equals(pkg) ? COEFF_CS : COEFF_TFNCL;
        double lux;
        if (r <= c[0][0]) lux = c[0][1] * ch0n - c[0][2] * ch0n * Math.pow(r, 1.4);
        else if (r <= c[1][0]) lux = c[1][1] * ch0n - c[1][2] * ch1n;
        else if (r <= c[2][0]) lux = c[2][1] * ch0n - c[2][2] * ch1n;
        else if (r <= c[3][0]) lux = c[3][1] * ch0n - c[3][2] * ch1n;
        else lux = 0.0;
        return Math.max(0.0, lux);
    }

    protected static void sleepMs(int ms) {
        long scaled = (long) ms * waitPercent / 100;
        if (scaled <= 0) return;
        try {
            Thread.sleep(scaled);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected int readByte(int reg) throws IOException {
        return connection.read(CMD | reg, 1)[0] & 0xFF;
    }

    protected void writeByte(int reg, int value) throws IOException {
        connection.write(CMD | reg, new byte[]{(byte) value});
    }

    protected int readWord(int reg) throws IOException {
        byte[] b = connection.read(CMD_WORD | reg, 2);
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8);
    }

    protected void writeWord(int reg, int value) throws IOException {
        connection.write(CMD_WORD | reg, new byte[]{(byte) value, (byte) (value >> 8)});
    }

    protected void writeTiming(boolean manual) throws IOException {
        int v = (gain == 16 ? 0x10 : 0x00) | integ | (manual ? 0x08 : 0x00);
        writeByte(REG_TIMING, v);
    }

    protected int[] readChannels() throws IOException {
        byte[] b = connection.read(CMD_DATA_BLOCK, 4);
        return new int[]{(b[0] & 0xFF) | ((b[1] & 0xFF) << 8), (b[2] & 0xFF) | ((b[3] & 0xFF) << 8)};
    }

    protected int fullScale() { return integ < 3 ? INTEG_FULL_SCALE[integ] : 65535; }

    protected int integrationMs() { return integ < 3 ? INTEG_MS[integ] : 0; }

    protected int settleMs() { return integ < 3 ? INTEG_SETTLE_MS[integ] : 0; }

    protected double luxFrom(int ch0, int ch1) throws SaturatedException {
        int limit = fullScale();
        if (ch0 >= limit || ch1 >= limit) throw new SaturatedException(ch0, ch1);
        return calculateLux(ch0, ch1, gain, integrationMs(), pkg);
    }

    /**
     * Read the illuminance.
     *
     * @return lux (T/FN/CL coefficients unless changed); 0 for a channel ratio above 1.30
     * @throws SaturatedException if a channel is at or above the full-scale limit
     * @throws IOException        on bus error
     */
    public double readLux() throws IOException {
        int[] c = readChannels();
        return luxFrom(c[0], c[1]);
    }
}
