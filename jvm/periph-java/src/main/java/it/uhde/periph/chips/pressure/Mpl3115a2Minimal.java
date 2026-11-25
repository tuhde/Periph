package it.uhde.periph.chips.pressure;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * MPL3115A2 — barometric pressure, altitude and temperature sensor (NXP), minimal driver.
 *
 * <p>One-shot barometer readings with no configuration beyond the connection. I²C address is
 * fixed at {@code 0x60}. The constructor does no bus access; {@link #init()} checks WHO_AM_I
 * ({@code 0xC4}), resets the chip, enables the data-ready flags (PT_DATA_CFG = 0x07) and selects
 * barometer mode with oversample ratio 128 (about 512 ms per sample) in STANDBY.
 */
public class Mpl3115a2Minimal {

    protected static final int REG_DR_STATUS   = 0x06;
    protected static final int REG_OUT_P_MSB   = 0x01;
    protected static final int REG_OUT_P_DELTA = 0x07;
    protected static final int REG_WHO_AM_I    = 0x0C;
    protected static final int REG_F_STATUS    = 0x0D;
    protected static final int REG_F_DATA      = 0x0E;
    protected static final int REG_F_SETUP     = 0x0F;
    protected static final int REG_SYSMOD      = 0x11;
    protected static final int REG_INT_SOURCE  = 0x12;
    protected static final int REG_PT_DATA_CFG = 0x13;
    protected static final int REG_BAR_IN_MSB  = 0x14;
    protected static final int REG_P_TGT_MSB   = 0x16;
    protected static final int REG_T_TGT       = 0x18;
    protected static final int REG_P_WND_MSB   = 0x19;
    protected static final int REG_T_WND       = 0x1B;
    protected static final int REG_P_MIN       = 0x1C;
    protected static final int REG_T_MIN       = 0x1F;
    protected static final int REG_P_MAX       = 0x21;
    protected static final int REG_T_MAX       = 0x24;
    protected static final int REG_CTRL_REG1   = 0x26;
    protected static final int REG_CTRL_REG2   = 0x27;
    protected static final int REG_CTRL_REG3   = 0x28;
    protected static final int REG_CTRL_REG4   = 0x29;
    protected static final int REG_CTRL_REG5   = 0x2A;
    protected static final int REG_OFF_P       = 0x2B;
    protected static final int REG_OFF_T       = 0x2C;
    protected static final int REG_OFF_H       = 0x2D;

    protected static final int CHIP_ID   = 0xC4;
    protected static final int CTRL1_ALT = 0x80;
    protected static final int CTRL1_RST = 0x04;
    protected static final int CTRL1_OST = 0x02;
    protected static final int CTRL1_SBYB = 0x01;
    protected static final int DR_PTDR   = 0x08;

    /** Minimum time between samples in ms for OS = 0..7 (oversample ratio 1..128). */
    protected static final int[] OSR_TIME_MS = {6, 10, 18, 34, 66, 130, 258, 512};

    protected final RegisterConnection connection;
    protected int os = 7;
    protected boolean alt = false;

    /**
     * Create the driver; does no bus access.
     *
     * @param connection I²C register connection bound to address 0x60
     */
    public Mpl3115a2Minimal(RegisterConnection connection) {
        this.connection = connection;
    }

    protected void writeReg(int reg, int value) throws IOException {
        connection.write(reg, new byte[]{(byte) value});
    }

    protected void writeRegs(int reg, int... values) throws IOException {
        byte[] b = new byte[values.length];
        for (int i = 0; i < b.length; i++) b[i] = (byte) values[i];
        connection.write(reg, b);
    }

    protected int readReg(int reg) throws IOException {
        return connection.read(reg, 1)[0] & 0xFF;
    }

    protected int[] readRegs(int reg, int n) throws IOException {
        byte[] b = connection.read(reg, n);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = b[i] & 0xFF;
        return out;
    }

    protected int ctrl1() {
        return (alt ? CTRL1_ALT : 0) | (os << 3);
    }

    protected static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected static double pressureFrom(int[] b, int o) {
        return ((b[o] << 12) | (b[o + 1] << 4) | (b[o + 2] >> 4)) / 4.0;
    }

    protected static double altitudeFrom(int[] b, int o) {
        return (short) ((b[o] << 8) | b[o + 1]) + (b[o + 2] >> 4) / 16.0;
    }

    protected static double temperatureFrom(int[] b, int o) {
        return (byte) b[o] + (b[o + 1] >> 4) / 16.0;
    }

    protected void softReset() throws IOException {
        try {
            writeReg(REG_CTRL_REG1, CTRL1_RST);   // the chip may not ACK while rebooting
        } catch (IOException ignored) {
            // expected
        }
        for (int i = 0; i < 100; i++) {
            sleepMs(2);
            try {
                if ((readReg(REG_CTRL_REG1) & CTRL1_RST) == 0) return;
            } catch (IOException ignored) {
                // still rebooting
            }
        }
        throw new IOException("MPL3115A2 reset timeout");
    }

    protected void applyConfig() throws IOException {
        writeReg(REG_PT_DATA_CFG, 0x07);
        writeReg(REG_CTRL_REG1, ctrl1());
    }

    protected void waitPtdr() throws IOException {
        long deadline = System.currentTimeMillis() + OSR_TIME_MS[os] * 2L + 1000;
        while (true) {
            sleepMs(3);
            if ((readReg(REG_DR_STATUS) & DR_PTDR) != 0) return;
            if (System.currentTimeMillis() > deadline) throw new IOException("MPL3115A2 data not ready");
        }
    }

    protected int[] oneShot() throws IOException {
        writeReg(REG_CTRL_REG1, ctrl1());                 // OST must be 0 before it is set
        writeReg(REG_CTRL_REG1, ctrl1() | CTRL1_OST);
        waitPtdr();
        return readRegs(REG_OUT_P_MSB, 5);
    }

    /**
     * Check WHO_AM_I, soft reset and apply the default configuration.
     *
     * @throws IOException on bus error, a wrong chip ID or a reset timeout
     */
    public void init() throws IOException {
        int id = readReg(REG_WHO_AM_I);
        if (id != CHIP_ID) throw new IOException(String.format("Unexpected chip ID 0x%02X, expected 0xC4", id));
        softReset();
        os = 7;
        alt = false;
        applyConfig();
    }

    /**
     * Run one conversion.
     *
     * @return {@code {pressure in Pa, temperature in °C}}
     * @throws IOException on bus error or timeout
     */
    public double[] read() throws IOException {
        int[] raw = oneShot();
        return new double[]{pressureFrom(raw, 0), temperatureFrom(raw, 3)};
    }

    /**
     * Run one conversion and return the pressure.
     *
     * @return pressure in pascals
     * @throws IOException on bus error or timeout
     */
    public double readPressure() throws IOException {
        return read()[0];
    }

    /**
     * Run one conversion and return the temperature.
     *
     * @return temperature in degrees Celsius
     * @throws IOException on bus error or timeout
     */
    public double readTemperature() throws IOException {
        return read()[1];
    }
}
