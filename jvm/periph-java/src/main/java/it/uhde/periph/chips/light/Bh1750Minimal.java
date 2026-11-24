package it.uhde.periph.chips.light;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * BH1750FVI — ambient light sensor (ROHM), minimal driver.
 *
 * <p>Each {@link #read()} starts one one-time H-resolution measurement (opcode 0x20, 1 lx steps),
 * waits the datasheet maximum of 180 ms, reads the 16-bit result and returns the illuminance in
 * lux (raw / 1.2). The chip powers down by itself after the measurement. I²C address 0x23 or 0x5C
 * ({@code ADDR} pin). The chip is command driven (no register pointer), so a plain
 * {@link Connection} is used.
 *
 * <h2>Configuration defaults</h2>
 * <ul>
 *   <li>Nothing is written at construction; {@link #init()} powers the chip on</li>
 *   <li>One-time H-resolution mode, measurement-time register 69 (default)</li>
 * </ul>
 */
public class Bh1750Minimal {

    /** H-resolution mode, 1 lx steps. */
    public static final int MODE_H = 0;
    /** H-resolution mode 2, 0.5 lx steps. */
    public static final int MODE_H2 = 1;
    /** L-resolution mode, 4 lx steps. */
    public static final int MODE_L = 2;

    protected static final int OP_POWER_DOWN = 0x00;
    protected static final int OP_POWER_ON   = 0x01;
    protected static final int OP_RESET      = 0x07;
    protected static final int OP_MT_HIGH    = 0x40;
    protected static final int OP_MT_LOW     = 0x60;
    protected static final int DEFAULT_MTREG = 69;
    protected static final int[] OP_CONTINUOUS = {0x10, 0x11, 0x13};
    protected static final int[] OP_ONE_TIME   = {0x20, 0x21, 0x23};
    /** Maximum measurement time in milliseconds at MTreg = 69, indexed by mode. */
    protected static final double[] T_MAX_MS = {180.0, 180.0, 24.0};

    protected final Connection connection;
    protected int mtreg = DEFAULT_MTREG;
    protected int mode = MODE_H;

    /**
     * Create the driver; does no bus access.
     *
     * @param connection I²C connection bound to 0x23 or 0x5C
     */
    public Bh1750Minimal(Connection connection) {
        this.connection = connection;
    }

    protected static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected void command(int code) throws IOException {
        connection.write(new byte[]{(byte) code});
    }

    /**
     * Power the chip on (opcode 0x01).
     *
     * @throws IOException on bus error
     */
    public void init() throws IOException {
        command(OP_POWER_ON);
    }

    protected long waitMs(int mode) {
        return (long) Math.ceil(T_MAX_MS[mode] * mtreg / DEFAULT_MTREG);
    }

    protected double toLux(int raw, int mode) {
        double lux = raw / 1.2 * ((double) DEFAULT_MTREG / mtreg);
        return mode == MODE_H2 ? lux / 2.0 : lux;
    }

    protected int readWord() throws IOException {
        byte[] data = connection.read(2);
        return ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
    }

    protected int measureRaw(int mode) throws IOException {
        command(OP_ONE_TIME[mode]);
        sleepMs(waitMs(mode));
        return readWord();
    }

    /**
     * Measure the illuminance once.
     *
     * @return illuminance in lux (one-time measurement in the current mode, H-resolution by default)
     * @throws IOException on bus error
     */
    public double read() throws IOException {
        return toLux(measureRaw(mode), mode);
    }
}
