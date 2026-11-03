package it.uhde.periph.chips.adc_dac

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * HY3131 — 50,000-count DMM analog front end with SPI interface (minimal driver).
 *
 * Reads AD1 as a differential voltage with sensible defaults. The SPI framing is
 * non-standard (7-bit address + R/W bit, one blank clock on reads, auto-increment),
 * so the driver uses the bare {@link Connection}'s {@code write} and {@code writeRead}.
 * Data-ready is polled via {@code INTF} over SPI; no IRQ pin is needed.
 *
 * Sensible defaults baked in at construction: crystal oscillator on, bandgap REFO
 * (1.2 V), AD1 input PB2 (+) vs AGND (−), reference REFO/AGND, input gain 0.9,
 * reference gain 1.0, buffers off, no chop, OSR 32768 (5 Hz).
 */
@CompileStatic
class Hy3131Minimal {

    protected static final int REG_AD1 = 0x00
    protected static final int REG_AD2 = 0x03
    protected static final int REG_LPF = 0x06
    protected static final int REG_RMS = 0x09
    protected static final int REG_PKHMIN = 0x0E
    protected static final int REG_PKHMAX = 0x11
    protected static final int REG_CTSTA = 0x14
    protected static final int REG_CTC = 0x15
    protected static final int REG_CTB = 0x18
    protected static final int REG_CTA = 0x1B
    protected static final int REG_INTF = 0x1E
    protected static final int REG_INTE = 0x1F
    protected static final int REG_R20 = 0x20
    protected static final int REG_R22 = 0x22
    protected static final int REG_R23 = 0x23
    protected static final int REG_R24 = 0x24
    protected static final int REG_R25 = 0x25
    protected static final int REG_R26 = 0x26
    protected static final int REG_R27 = 0x27
    protected static final int REG_R28 = 0x28
    protected static final int REG_R29 = 0x29
    protected static final int REG_R2A = 0x2A
    protected static final int REG_R2F = 0x2F
    protected static final int REG_R30 = 0x30
    protected static final int REG_R31 = 0x31
    protected static final int REG_R33 = 0x33
    protected static final int REG_R34 = 0x34
    protected static final int REG_R35 = 0x35
    protected static final int REG_R37 = 0x37

    /** INTF flag: frequency counter measurement done. */
    static final int FLAG_CTF = 0x01
    /** INTF flag: AD2/AD3 conversion done. */
    static final int FLAG_AD2F = 0x02
    /** INTF flag: AD1 conversion done. */
    static final int FLAG_AD1F = 0x04
    /** INTF flag: low-pass filter result updated. */
    static final int FLAG_LPFF = 0x08
    /** INTF flag: RMS result updated. */
    static final int FLAG_RMSF = 0x10
    /** INTF flag: brown-out. */
    static final int FLAG_BORF = 0x80

    private static final double[] AD1_GAINS = [0.9d, 1.8d, 2.7d, 3.6d] as double[]
    private static final long TIMEOUT_MS = 2000L

    protected final Connection connection
    protected final float vref
    protected int flags = 0
    protected int ig = 0
    protected int rg = 0
    protected double dcos = 0.0d

    /**
     * Create the driver and run the initialisation sequence.
     *
     * @param connection SPI connection (Mode 0, MSB first, CS active low)
     * @param vref voltage on REFO in V (1.2 V from the internal bandgap)
     */
    Hy3131Minimal(Connection connection, float vref) {
        this.connection = connection
        this.vref = vref
        init()
    }

    /** Reset the chip and apply the Minimal defaults. */
    protected void init() {
        writeRegister(REG_R37, 0x60)
        sleepMs(5L)
        writeRegister(REG_R33, 0x28)
        sleepMs(10L)
        writeRegister(REG_R31, 0xC0)
        writeRegister(REG_R30, 0x00)
        sleepMs(5L)
        writeRegister(REG_R2F, 0x80)
        writeRegister(REG_R2A, new byte[5])
        int[] regs = [0x32, REG_R20, 0x21, REG_R34, REG_R35, 0x36, REG_R29, REG_R26, REG_R27, REG_INTE] as int[]
        for (int reg : regs) {
            writeRegister(reg, 0x00)
        }
        writeRegister(REG_R24, 0xA3)
        writeRegister(REG_R28, 0x11)
        ig = 0
        rg = 0
        dcos = 0.0d
        writeRegister(REG_R25, 0x00)
        writeRegister(REG_R22, 0x07)
        writeRegister(REG_R23, 0x80)
        readRegister(REG_INTF, 1)
        flags = 0
    }

    private static void sleepMs(long ms) {
        try {
            Thread.sleep(ms)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
            throw new IOException("interrupted", e)
        }
    }

    /**
     * Read {@code n} consecutive registers starting at {@code reg} (auto-increment).
     *
     * @param reg start address 0x00–0x37
     * @param n number of bytes
     * @return register contents
     */
    byte[] readRegister(int reg, int n) {
        byte[] cmd = new byte[1]
        cmd[0] = (byte) (((reg & 0x7F) << 1) | 1)
        byte[] rx = connection.writeRead(cmd, n + 1)
        byte[] out = new byte[n]
        for (int i = 0; i < n; i++) {
            out[i] = (byte) (((rx[i] & 0xFF) << 1) | ((rx[i + 1] & 0xFF) >> 7))
        }
        return out
    }

    /** Write {@code data} starting at {@code reg} (auto-increment). */
    void writeRegister(int reg, byte[] data) {
        byte[] buf = new byte[1 + data.length]
        buf[0] = (byte) ((reg & 0x7F) << 1)
        System.arraycopy(data, 0, buf, 1, data.length)
        connection.write(buf)
    }

    /** Write a single byte {@code value} to {@code reg}. */
    void writeRegister(int reg, int value) {
        byte[] one = new byte[1]
        one[0] = (byte) value
        writeRegister(reg, one)
    }

    protected int read1(int reg) {
        return readRegister(reg, 1)[0] & 0xFF
    }

    protected void waitFlag(int mask) {
        long deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000L
        while (true) {
            flags |= read1(REG_INTF)
            if ((flags & mask) != 0) {
                flags &= ~mask
                return
            }
            if (System.nanoTime() > deadline) {
                throw new IOException('HY3131 conversion timeout')
            }
            sleepMs(1L)
        }
    }

    protected long read24(int reg) {
        byte[] b = readRegister(reg, 3)
        return (b[0] & 0xFFL) | ((b[1] & 0xFFL) << 8) | ((b[2] & 0xFFL) << 16)
    }

    protected static long sext(long value, int bits) {
        long sign = 1L << (bits - 1)
        return (value & sign) != 0L ? value - (sign << 1) : value
    }

    /** Report whether a new AD1 conversion is pending (latches INTF). */
    boolean dataReady() {
        flags |= read1(REG_INTF)
        return (flags & FLAG_AD1F) != 0
    }

    /** Wait for a conversion and return the signed 24-bit AD1 code. */
    int readRaw() {
        waitFlag(FLAG_AD1F)
        return (int) sext(read24(REG_AD1), 24)
    }

    /** Read AD1 as a differential input voltage in V. */
    float readVoltage() {
        int code = readRaw()
        double rgv = rg == 0 ? 1.0d : 0.3333333333d
        return (float) (((code / 8388608.0d) * rgv - dcos) * (double) vref / AD1_GAINS[ig])
    }
}
