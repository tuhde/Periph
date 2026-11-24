package it.uhde.periph.chips.pressure;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * MS5607 — barometric pressure sensor (TE Connectivity), minimal driver.
 *
 * <p>{@link #init()} resets the chip, reads the eight PROM words and verifies the CRC-4. Each
 * {@link #read()} converts the temperature (D2) and then the pressure (D1) at oversampling ratio
 * 4096 (waiting 10 ms each) and applies the first- and second-order compensation. Pressure is in
 * pascals (1 Pa = 0.01 mbar, the chip's native unit). I²C address 0x77 (CSB = GND) or 0x76
 * (CSB = VDD). The chip is command driven (no register pointer), so a plain {@link Connection} is
 * used.
 *
 * <h2>Configuration defaults</h2>
 * <ul>
 *   <li>Nothing is written at construction; {@link #init()} resets the chip and reads the PROM</li>
 *   <li>Oversampling ratio 4096 for both conversions</li>
 *   <li>Second-order temperature compensation applied</li>
 * </ul>
 */
public class Ms5607Minimal {

    protected static final int CMD_RESET      = 0x1E;
    protected static final int CMD_CONVERT_D1 = 0x40;
    protected static final int CMD_CONVERT_D2 = 0x50;
    protected static final int CMD_ADC_READ   = 0x00;
    protected static final int CMD_PROM_READ  = 0xA0;

    /** Supported oversampling ratios. */
    protected static final int[] OSR_VALUES = {256, 512, 1024, 2048, 4096};
    /** Maximum conversion time per OSR in microseconds (datasheet). */
    protected static final int[] T_CONV_US = {600, 1170, 2280, 4540, 9040};

    protected final Connection connection;
    protected int osrIndex = 4;
    protected int[] prom = new int[8];

    /**
     * Create the driver; does no bus access.
     *
     * @param connection I²C connection bound to 0x77 (or 0x76 with CSB high)
     */
    public Ms5607Minimal(Connection connection) {
        this.connection = connection;
    }

    protected static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * CRC-4 of the PROM (application note AN520): polynomial x^4 + x^3 + 1 over the 16 bytes of the
     * eight words, MSB first; the low byte of word 7 (including the CRC nibble) counts as zero.
     *
     * @param words the eight 16-bit PROM words
     * @return the 4-bit CRC
     */
    public static int crc4(int[] words) {
        int[] p = words.clone();
        p[7] &= 0xFF00;
        int rem = 0;
        for (int cnt = 0; cnt < 16; cnt++) {
            rem ^= (cnt & 1) != 0 ? (p[cnt >> 1] & 0x00FF) : (p[cnt >> 1] >> 8);
            for (int b = 0; b < 8; b++) {
                rem = (rem & 0x8000) != 0 ? ((rem << 1) ^ 0x3000) & 0xFFFF : (rem << 1) & 0xFFFF;
            }
        }
        return (rem >> 12) & 0x0F;
    }

    protected int[] readPromWords() throws IOException {
        int[] words = new int[8];
        for (int n = 0; n < 8; n++) {
            connection.write(new byte[]{(byte) (CMD_PROM_READ + 2 * n)});
            byte[] b = connection.read(2);
            words[n] = ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
        }
        return words;
    }

    protected int readAdcValue() throws IOException {
        connection.write(new byte[]{(byte) CMD_ADC_READ});
        byte[] b = connection.read(3);
        int v = ((b[0] & 0xFF) << 16) | ((b[1] & 0xFF) << 8) | (b[2] & 0xFF);
        if (v == 0) throw new IOException("MS5607 ADC returned 0 (conversion not finished or not started)");
        return v;
    }

    protected void start(int kind, int index) throws IOException {
        int base = kind == 1 ? CMD_CONVERT_D2 : CMD_CONVERT_D1;
        connection.write(new byte[]{(byte) (base | (index << 1))});
    }

    protected int convert(int kind) throws IOException {
        start(kind, osrIndex);
        sleepMs((T_CONV_US[osrIndex] + 999) / 1000 + 1);
        return readAdcValue();
    }

    /** Datasheet integer algorithm (64-bit, truncating division); returns {°C, Pa}. */
    protected double[] compute(int d1, int d2, boolean secondOrder) {
        long c1 = prom[1], c2 = prom[2], c3 = prom[3], c4 = prom[4], c5 = prom[5], c6 = prom[6];
        long dt = (long) d2 - c5 * 256;
        long temp = 2000 + dt * c6 / (1L << 23);
        long off = c2 * (1L << 17) + c4 * dt / (1L << 6);
        long sens = c1 * (1L << 16) + c3 * dt / (1L << 7);
        if (secondOrder && temp < 2000) {
            long d = temp - 2000;
            long t2 = dt * dt / (1L << 31);
            long off2 = 61 * d * d / 16;
            long sens2 = 2 * d * d;
            if (temp < -1500) {
                long e = temp + 1500;
                off2 += 15 * e * e;
                sens2 += 8 * e * e;
            }
            temp -= t2;
            off -= off2;
            sens -= sens2;
        }
        long p = ((long) d1 * sens / (1L << 21) - off) / (1L << 15);
        return new double[]{temp / 100.0, (double) p};
    }

    /**
     * Reset (0x1E), wait 3 ms, read the PROM and verify its CRC-4.
     *
     * @throws IOException on bus error or a PROM CRC mismatch
     */
    public void init() throws IOException {
        connection.write(new byte[]{(byte) CMD_RESET});
        sleepMs(3);
        int[] words = readPromWords();
        if (crc4(words) != (words[7] & 0x0F)) throw new IOException("MS5607 PROM CRC mismatch");
        prom = words;
    }

    /**
     * Measure temperature and pressure (OSR 4096, second-order compensation).
     *
     * @return {temperature in °C, pressure in Pa}
     * @throws IOException on bus error or an ADC result of 0
     */
    public double[] read() throws IOException {
        int d2 = convert(1);
        int d1 = convert(0);
        return compute(d1, d2, true);
    }
}
