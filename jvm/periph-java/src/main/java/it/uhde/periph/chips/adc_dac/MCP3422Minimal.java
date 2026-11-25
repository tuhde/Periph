package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.Connection;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * MCP3422 — 2-channel 18-bit delta-sigma ADC with PGA and 2.048 V reference
 * (Microchip) — minimal interface.
 *
 * <p>Two differential inputs, an on-board 2.048 V reference, a PGA (x1, x2, x4,
 * x8) and an on-board oscillator. The resolution is selected together with the
 * data rate: 12 bit / 240 SPS, 14 bit / 60 SPS, 16 bit / 15 SPS, 18 bit /
 * 3.75 SPS. The full-scale differential input range is ±2.048 V / gain. The
 * chip has no register map: a write is one configuration byte
 * ({@code RDY C1 C0 O/C S1 S0 G1 G0}) and a read returns the 2 (12/14/16 bit) or
 * 3 (18 bit) result bytes followed by the configuration byte, whose RDY bit
 * (0 = fresh result) says whether the conversion has finished. The driver
 * therefore uses the plain {@link Connection} {@code write} / {@code read} calls.
 *
 * <p>Defaults: one-shot mode (lowest power), 16 bit / 15 SPS (LSB 62.5 µV), gain
 * x1. The constructor writes that configuration with RDY = 0, so nothing is
 * started and the chip goes to standby.
 */
public class MCP3422Minimal {

    /** 7-bit I²C address of the standard part (order code A0). */
    public static final int DEFAULT_ADDRESS = 0x68;

    /** Whether to really wait for conversions (tests set this to false). */
    public static volatile boolean realWaits = true;

    protected static final int CFG_RDY = 0x80;
    protected static final int CFG_CONT = 0x10;
    protected static final double VREF = 2.048;

    protected final Connection connection;
    protected int bits = 16;
    protected int gain = 1;
    protected int channel = 0;
    protected boolean continuous = false;
    protected boolean discard = false;

    /**
     * Construct the driver and write the default configuration.
     *
     * @param connection I²C connection bound to the device (0x68–0x6F)
     * @throws IOException on bus error
     */
    public MCP3422Minimal(Connection connection) throws IOException {
        this.connection = connection;
        connection.write(new byte[]{(byte) config(false)});
    }

    protected int config(boolean rdy) {
        int s = bits == 12 ? 0 : bits == 14 ? 1 : bits == 16 ? 2 : 3;
        int g = gain == 1 ? 0 : gain == 2 ? 1 : gain == 4 ? 2 : 3;
        int c = (channel << 5) | (s << 2) | g;
        if (rdy) c |= CFG_RDY;
        if (continuous) c |= CFG_CONT;
        return c;
    }

    protected int nbytes() {
        return bits == 18 ? 3 : 2;
    }

    protected double convSeconds() {
        return bits == 12 ? 1.0 / 240 : bits == 14 ? 1.0 / 60 : bits == 16 ? 1.0 / 15 : 1.0 / 3.75;
    }

    protected static void sleepMs(long ms) {
        if (!realWaits) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected int decode(byte[] data) {
        long raw = 0;
        for (int i = 0; i < nbytes(); i++) raw = (raw << 8) | (data[i] & 0xFF);
        long span = 1L << bits;
        raw &= span - 1;
        if (raw >= span / 2) raw -= span;
        return (int) raw;
    }

    protected int waitReady() throws IOException, TimeoutException {
        int polls = (int) ((2 * convSeconds() + 0.010) / 0.005) + 1;
        int n = nbytes();
        for (int i = 0; i < polls; i++) {
            byte[] data = connection.read(n + 1);
            if ((data[n] & CFG_RDY) == 0) return decode(data);
            sleepMs(5);
        }
        throw new TimeoutException("MCP3422 conversion did not finish");
    }

    protected int convert(int ch) throws IOException, TimeoutException {
        channel = ch < 1 ? 0 : 1;
        continuous = false;
        discard = false;
        connection.write(new byte[]{(byte) config(true)});
        sleepMs((long) (convSeconds() * 1100.0) + 1);
        return waitReady();
    }

    /**
     * Run a one-shot conversion on a channel and return the signed code.
     *
     * @param ch 0 (CH1) or 1 (CH2); values outside 0–1 are clamped
     * @return signed code, −2^(N−1) … 2^(N−1) − 1 (N = resolution bits)
     * @throws IOException on bus error
     * @throws TimeoutException if the conversion does not finish in time
     */
    public int readRaw(int ch) throws IOException, TimeoutException {
        return convert(ch);
    }

    /**
     * Run a one-shot conversion on a channel and return the differential voltage.
     *
     * @param ch 0 (CH1) or 1 (CH2); values outside 0–1 are clamped
     * @return {@code CHn+ − CHn−} in volts, {@code raw * lsb / gain}
     * @throws IOException on bus error
     * @throws TimeoutException if the conversion does not finish in time
     */
    public double readVoltage(int ch) throws IOException, TimeoutException {
        return convert(ch) * lsb() / gain;
    }

    /**
     * Convert both channels, one conversion each.
     *
     * @return {@code {CH1, CH2}} voltages in volts
     * @throws IOException on bus error
     * @throws TimeoutException if a conversion does not finish in time
     */
    public double[] readAll() throws IOException, TimeoutException {
        return new double[]{readVoltage(0), readVoltage(1)};
    }

    /**
     * Size of one code before the gain.
     *
     * @return {@code 2 * 2.048 / 2^bits} in volts
     */
    public double lsb() {
        return 2.0 * VREF / (1L << bits);
    }
}
