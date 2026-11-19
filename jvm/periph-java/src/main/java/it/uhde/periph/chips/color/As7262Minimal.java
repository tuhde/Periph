package it.uhde.periph.chips.color;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * AS7262 — 6-channel visible spectral sensor (ams OSRAM), minimal driver. I²C address 0x49.
 *
 * <p>Measures violet (450 nm), blue (500 nm), green (550 nm), yellow (570 nm), orange (600 nm) and
 * red (650 nm). Every sensor register is a <em>virtual register</em> reached through three hardware
 * registers (STATUS 0x00, WRITE 0x01, READ 0x02); this class implements the handshake.
 *
 * <h2>Configuration defaults (written by {@link #init()})</h2>
 * <ul>
 *   <li>Soft reset first</li>
 *   <li>Gain 16x, {@code INT_T = 0x3B} (165.2 ms), one-shot bank mode 3</li>
 *   <li>Both LED outputs off, INT pin disabled</li>
 * </ul>
 *
 * <p>The constructor does no bus access; call {@link #init()} once.
 */
public class As7262Minimal {

    /** Six calibrated channel values (floats as delivered by the chip). */
    public static final class Channels {
        public final float violet, blue, green, yellow, orange, red;

        public Channels(float violet, float blue, float green, float yellow, float orange, float red) {
            this.violet = violet; this.blue = blue; this.green = green;
            this.yellow = yellow; this.orange = orange; this.red = red;
        }

        /** The channels in the order V, B, G, Y, O, R. */
        public float[] toArray() {
            return new float[]{violet, blue, green, yellow, orange, red};
        }
    }

    /** Six raw 16-bit channel counts. */
    public static final class RawChannels {
        public final int violet, blue, green, yellow, orange, red;

        public RawChannels(int violet, int blue, int green, int yellow, int orange, int red) {
            this.violet = violet; this.blue = blue; this.green = green;
            this.yellow = yellow; this.orange = orange; this.red = red;
        }

        /** The counts in the order V, B, G, Y, O, R. */
        public int[] toArray() {
            return new int[]{violet, blue, green, yellow, orange, red};
        }
    }

    protected static final int HW_STATUS = 0x00;
    protected static final int HW_WRITE = 0x01;
    protected static final int HW_READ = 0x02;
    protected static final int TX_VALID = 0x02;
    protected static final int RX_VALID = 0x01;

    protected static final int REG_HW_VERSION = 0x00;
    protected static final int REG_FW_VERSION = 0x02;
    protected static final int REG_CONTROL = 0x04;
    protected static final int REG_INT_T = 0x05;
    protected static final int REG_TEMP = 0x06;
    protected static final int REG_LED = 0x07;
    protected static final int REG_RAW = 0x08;
    protected static final int REG_CAL = 0x14;

    protected static final int CTRL_RST = 0x80;
    protected static final int CTRL_INT = 0x40;
    protected static final int CTRL_DATA_RDY = 0x02;

    protected static final int INT_T_DEFAULT = 0x3B;
    protected static final int GAIN_DEFAULT = 2;
    protected static final double STEP_MS = 2.8;

    protected final RegisterConnection connection;

    /**
     * Create the driver; does no bus access.
     *
     * @param connection register-capable I²C connection bound to 0x49
     */
    public As7262Minimal(RegisterConnection connection) {
        this.connection = connection;
    }

    protected static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int hwRead(int reg) throws IOException {
        return connection.read(reg, 1)[0] & 0xFF;
    }

    private void waitStatus(int mask, int want) throws IOException {
        long deadline = System.currentTimeMillis() + 2000;
        while (true) {
            if ((hwRead(HW_STATUS) & mask) == want) return;
            if (System.currentTimeMillis() >= deadline) {
                throw new IOException("AS7262 virtual-register handshake time-out");
            }
        }
    }

    /**
     * Read one virtual register through the STATUS/WRITE/READ handshake.
     *
     * @param reg virtual register address (0x00–0x2B)
     * @return the register byte (0–255)
     * @throws IOException on a bus error or a handshake time-out
     */
    public int readVirtual(int reg) throws IOException {
        waitStatus(TX_VALID, 0);
        if ((hwRead(HW_STATUS) & RX_VALID) != 0) hwRead(HW_READ); // drain a stale byte
        connection.write(HW_WRITE, new byte[]{(byte) (reg & 0x7F)});
        waitStatus(RX_VALID, RX_VALID);
        return hwRead(HW_READ);
    }

    /**
     * Write one virtual register through the STATUS/WRITE handshake.
     *
     * @param reg   virtual register address (0x04, 0x05 or 0x07 are writable)
     * @param value byte to write
     * @throws IOException on a bus error or a handshake time-out
     */
    public void writeVirtual(int reg, int value) throws IOException {
        waitStatus(TX_VALID, 0);
        connection.write(HW_WRITE, new byte[]{(byte) ((reg & 0x7F) | 0x80)});
        waitStatus(TX_VALID, 0);
        connection.write(HW_WRITE, new byte[]{(byte) value});
    }

    private void waitBoot() throws IOException {
        long deadline = System.currentTimeMillis() + 2000;
        while (true) {
            try {
                if (readVirtual(REG_HW_VERSION) == 0x40) return;
            } catch (IOException e) {
                // retry until the chip answers
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new IOException("AS7262 not found (HW_VERSION != 0x40)");
            }
            sleepMs(10);
        }
    }

    /**
     * Soft-reset and wait until {@code CONTROL_SETUP.RST} clears (time-out 1 s).
     *
     * @throws IOException on a bus error or time-out
     */
    protected void reset() throws IOException {
        writeVirtual(REG_CONTROL, CTRL_RST);
        long deadline = System.currentTimeMillis() + 1000;
        while (true) {
            sleepMs(10);
            try {
                if ((readVirtual(REG_CONTROL) & CTRL_RST) == 0) return;
            } catch (IOException e) {
                // retry until the chip answers
            }
            if (System.currentTimeMillis() >= deadline) throw new IOException("AS7262 reset time-out");
        }
    }

    /**
     * Wait for the chip to boot, soft-reset it and write the Minimal defaults.
     *
     * @throws IOException if the chip is missing or does not answer
     */
    public void init() throws IOException {
        waitBoot();
        reset();
        writeVirtual(REG_INT_T, INT_T_DEFAULT);
        writeVirtual(REG_LED, 0x00);
        writeVirtual(REG_CONTROL, GAIN_DEFAULT << 4);
    }

    protected void startOneShot() throws IOException {
        int ctrl = readVirtual(REG_CONTROL);
        writeVirtual(REG_CONTROL, (ctrl & 0x70) | (3 << 2));
    }

    protected void waitData() throws IOException {
        long budgetMs = (long) (2 * readVirtual(REG_INT_T) * STEP_MS) + 1000;
        long deadline = System.currentTimeMillis() + budgetMs;
        while (true) {
            if ((readVirtual(REG_CONTROL) & CTRL_DATA_RDY) != 0) return;
            if (System.currentTimeMillis() >= deadline) throw new IOException("AS7262 conversion time-out");
            sleepMs(5);
        }
    }

    protected float readFloat(int firstReg) throws IOException {
        int bits = 0;
        for (int i = 0; i < 4; i++) bits = (bits << 8) | readVirtual(firstReg + i);
        return Float.intBitsToFloat(bits);
    }

    protected Channels readCalibrated() throws IOException {
        float[] v = new float[6];
        for (int ch = 0; ch < 6; ch++) v[ch] = readFloat(REG_CAL + 4 * ch);
        return new Channels(v[0], v[1], v[2], v[3], v[4], v[5]);
    }

    /**
     * Run one one-shot conversion and return the six calibrated channels.
     *
     * @return calibrated channel values
     * @throws IOException on a bus error or a conversion time-out
     */
    public Channels read() throws IOException {
        startOneShot();
        waitData();
        return readCalibrated();
    }

    /**
     * Read the device temperature.
     *
     * @return temperature in °C
     * @throws IOException on bus error
     */
    public double temperature() throws IOException {
        return readVirtual(REG_TEMP);
    }
}
