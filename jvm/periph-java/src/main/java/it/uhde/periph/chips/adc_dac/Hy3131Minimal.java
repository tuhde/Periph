package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * HY3131 — 50,000-count DMM analog front end with SPI interface (minimal driver).
 *
 * <p>Reads AD1 as a differential voltage with sensible defaults. The SPI framing
 * is non-standard (7-bit address + R/W bit, one blank clock on reads,
 * auto-increment), so the driver uses the bare {@link Connection}'s {@code write}
 * and {@code writeRead}. Data-ready is polled via {@code INTF} over SPI; no IRQ
 * pin is needed.
 *
 * <p>Sensible defaults baked in at construction: crystal oscillator on, bandgap
 * REFO (1.2 V), AD1 input PB2 (+) vs AGND (−), reference REFO/AGND, input gain
 * 0.9, reference gain 1.0, buffers off, no chop, OSR 32768 (5 Hz).
 *
 * <p>Full configuration, AD2/AD3, LPF, RMS, peak hold, the frequency counter and
 * interrupt flags are in {@link Hy3131Full}.
 */
public class Hy3131Minimal {

    protected static final int REG_AD1 = 0x00, REG_AD2 = 0x03, REG_LPF = 0x06, REG_RMS = 0x09;
    protected static final int REG_PKHMIN = 0x0E, REG_PKHMAX = 0x11, REG_CTSTA = 0x14;
    protected static final int REG_CTC = 0x15, REG_CTB = 0x18, REG_CTA = 0x1B;
    protected static final int REG_INTF = 0x1E, REG_INTE = 0x1F;
    protected static final int REG_R20 = 0x20, REG_R22 = 0x22, REG_R23 = 0x23, REG_R24 = 0x24;
    protected static final int REG_R25 = 0x25, REG_R26 = 0x26, REG_R27 = 0x27, REG_R28 = 0x28;
    protected static final int REG_R29 = 0x29, REG_R2A = 0x2A, REG_R2F = 0x2F, REG_R30 = 0x30;
    protected static final int REG_R31 = 0x31, REG_R33 = 0x33, REG_R34 = 0x34, REG_R35 = 0x35;
    protected static final int REG_R37 = 0x37;

    /** INTF flag: frequency counter measurement done. */
    public static final int FLAG_CTF = 0x01;
    /** INTF flag: AD2/AD3 conversion done. */
    public static final int FLAG_AD2F = 0x02;
    /** INTF flag: AD1 conversion done. */
    public static final int FLAG_AD1F = 0x04;
    /** INTF flag: low-pass filter result updated. */
    public static final int FLAG_LPFF = 0x08;
    /** INTF flag: RMS result updated. */
    public static final int FLAG_RMSF = 0x10;
    /** INTF flag: brown-out. */
    public static final int FLAG_BORF = 0x80;

    private static final double[] AD1_GAINS = {0.9, 1.8, 2.7, 3.6};
    private static final double[] DCOS = {0, 0.25, 0.5, 0.75, 0, -0.25, -0.5, -0.75};
    private static final long TIMEOUT_MS = 2000;

    protected final Connection connection;
    protected final float vref;
    protected int flags = 0;
    protected int ig = 0;
    protected int rg = 0;
    protected double dcos = 0.0;

    /**
     * Create the driver and run the initialisation sequence.
     *
     * @param connection SPI connection (Mode 0, MSB first, CS active low)
     * @param vref       voltage on REFO in V (1.2 V from the internal bandgap)
     * @throws IOException on SPI error
     */
    public Hy3131Minimal(Connection connection, float vref) throws IOException {
        this.connection = connection;
        this.vref = vref;
        init();
    }

    /** Reset the chip and apply the Minimal defaults. */
    protected void init() throws IOException {
        writeRegister(REG_R37, 0x60);
        sleep(5);
        writeRegister(REG_R33, 0x28);
        sleep(10);
        writeRegister(REG_R31, 0xC0);
        writeRegister(REG_R30, 0x00);
        sleep(5);
        writeRegister(REG_R2F, 0x80);
        writeRegister(REG_R2A, new byte[5]);
        for (int reg : new int[]{0x32, REG_R20, 0x21, REG_R34, REG_R35, 0x36, REG_R29, REG_R26, REG_R27, REG_INTE}) {
            writeRegister(reg, 0x00);
        }
        writeRegister(REG_R24, 0xA3);
        writeRegister(REG_R28, 0x11);
        ig = 0;
        rg = 0;
        dcos = 0.0;
        writeRegister(REG_R25, 0x00);
        writeRegister(REG_R22, 0x07);
        writeRegister(REG_R23, 0x80);
        readRegister(REG_INTF, 1);
        flags = 0;
    }

    private static void sleep(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    /**
     * Read {@code n} consecutive registers starting at {@code reg} (auto-increment).
     *
     * @param reg start address 0x00–0x37
     * @param n   number of bytes
     * @return register contents
     * @throws IOException on SPI error
     */
    public byte[] readRegister(int reg, int n) throws IOException {
        byte[] rx = connection.writeRead(new byte[]{(byte) (((reg & 0x7F) << 1) | 1)}, n + 1);
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) (((rx[i] & 0xFF) << 1) | ((rx[i + 1] & 0xFF) >> 7));
        }
        return out;
    }

    /**
     * Write bytes starting at {@code reg} (auto-increment).
     *
     * @param reg  start address
     * @param data bytes to write
     * @throws IOException on SPI error
     */
    public void writeRegister(int reg, byte[] data) throws IOException {
        byte[] buf = new byte[1 + data.length];
        buf[0] = (byte) ((reg & 0x7F) << 1);
        System.arraycopy(data, 0, buf, 1, data.length);
        connection.write(buf);
    }

    /**
     * Write a single byte to {@code reg}.
     *
     * @param reg   register address
     * @param value value (low 8 bits used)
     * @throws IOException on SPI error
     */
    public void writeRegister(int reg, int value) throws IOException {
        writeRegister(reg, new byte[]{(byte) value});
    }

    protected int read1(int reg) throws IOException {
        return readRegister(reg, 1)[0] & 0xFF;
    }

    protected void waitFlag(int mask) throws IOException {
        long deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000L;
        while (true) {
            flags |= read1(REG_INTF);
            if ((flags & mask) != 0) {
                flags &= ~mask;
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new IOException("HY3131 conversion timeout");
            }
            sleep(1);
        }
    }

    protected long read24(int reg) throws IOException {
        byte[] b = readRegister(reg, 3);
        return (b[0] & 0xFFL) | ((b[1] & 0xFFL) << 8) | ((b[2] & 0xFFL) << 16);
    }

    protected static long sext(long value, int bits) {
        long sign = 1L << (bits - 1);
        return (value & sign) != 0 ? value - (sign << 1) : value;
    }

    /**
     * Report whether a new AD1 conversion is pending (latches INTF).
     *
     * @return true when AD1F is set
     * @throws IOException on SPI error
     */
    public boolean dataReady() throws IOException {
        flags |= read1(REG_INTF);
        return (flags & FLAG_AD1F) != 0;
    }

    /**
     * Wait for a conversion and return the signed 24-bit AD1 code.
     *
     * @return AD1 code −8388608 … 8388607
     * @throws IOException on SPI error or if no conversion completes within 2 s
     */
    public int readRaw() throws IOException {
        waitFlag(FLAG_AD1F);
        return (int) sext(read24(REG_AD1), 24);
    }

    /**
     * Read AD1 as a differential input voltage.
     *
     * @return voltage in V
     * @throws IOException on SPI error or timeout
     */
    public float readVoltage() throws IOException {
        int code = readRaw();
        double rgv = rg == 0 ? 1.0 : 0.3333333333;
        return (float) ((code / 8388608.0 * rgv - dcos) * vref / AD1_GAINS[ig]);
    }
}
