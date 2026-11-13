package it.uhde.periph.chips.adc_dac

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

import java.util.concurrent.TimeoutException

/**
 * MCP3422 — 2-channel 18-bit delta-sigma ADC with PGA and 2.048 V reference
 * (Microchip) — minimal interface.
 *
 * <p>Two differential inputs, an on-board 2.048 V reference, a PGA (x1, x2, x4,
 * x8) and an on-board oscillator. The resolution is selected together with the
 * data rate: 12 bit / 240 SPS, 14 bit / 60 SPS, 16 bit / 15 SPS, 18 bit /
 * 3.75 SPS. The full-scale differential input range is ±2.048 V / gain. The chip
 * has no register map: a write is one configuration byte
 * ({@code RDY C1 C0 O/C S1 S0 G1 G0}) and a read returns the 2 (12/14/16 bit) or
 * 3 (18 bit) result bytes followed by the configuration byte, whose RDY bit
 * (0 = fresh result) says whether the conversion has finished. The driver
 * therefore uses the plain {@link Connection} {@code write} / {@code read} calls.
 *
 * <p>Defaults: one-shot mode (lowest power), 16 bit / 15 SPS (LSB 62.5 µV), gain
 * x1. The constructor writes that configuration with RDY = 0, so nothing is
 * started and the chip goes to standby.
 */
@CompileStatic
class MCP3422Minimal {

    /** 7-bit I²C address of the standard part (order code A0). */
    static final int DEFAULT_ADDRESS = 0x68

    /** Whether to really wait for conversions (tests set this to false). */
    static volatile boolean realWaits = true

    protected static final int CFG_RDY = 0x80
    protected static final int CFG_CONT = 0x10
    protected static final double VREF = 2.048d

    protected final Connection connection
    protected int bitsV = 16
    protected int gainV = 1
    protected int channelV = 0
    protected boolean continuousV = false
    protected boolean discardV = false

    /**
     * Construct the driver and write the default configuration.
     *
     * @param connection I²C connection bound to the device (0x68–0x6F)
     */
    MCP3422Minimal(Connection connection) {
        this.connection = connection
        connection.write([config(false)] as byte[])
    }

    protected int config(boolean rdy) {
        int s = bitsV == 12 ? 0 : bitsV == 14 ? 1 : bitsV == 16 ? 2 : 3
        int g = gainV == 1 ? 0 : gainV == 2 ? 1 : gainV == 4 ? 2 : 3
        int c = (channelV << 5) | (s << 2) | g
        if (rdy) c |= CFG_RDY
        if (continuousV) c |= CFG_CONT
        return c
    }

    protected int nbytes() {
        return bitsV == 18 ? 3 : 2
    }

    protected double convSeconds() {
        return bitsV == 12 ? 1.0d / 240 : bitsV == 14 ? 1.0d / 60 : bitsV == 16 ? 1.0d / 15 : 1.0d / 3.75d
    }

    protected static void sleepMs(long ms) {
        if (!realWaits) return
        try {
            Thread.sleep(ms)
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt()
        }
    }

    protected int decode(byte[] data) {
        long raw = 0L
        for (int i = 0; i < nbytes(); i++) raw = (raw << 8) | (data[i] & 0xFF)
        long span = 1L << bitsV
        raw &= span - 1
        if (raw >= span / 2) raw -= span
        return (int) raw
    }

    protected int waitReady() {
        int polls = (int) ((2 * convSeconds() + 0.010d) / 0.005d) + 1
        int n = nbytes()
        for (int i = 0; i < polls; i++) {
            byte[] data = connection.read(n + 1)
            if ((data[n] & CFG_RDY) == 0) return decode(data)
            sleepMs(5L)
        }
        throw new TimeoutException('MCP3422 conversion did not finish')
    }

    protected int convert(int ch) {
        channelV = ch < 1 ? 0 : 1
        continuousV = false
        discardV = false
        connection.write([config(true)] as byte[])
        sleepMs((long) (convSeconds() * 1100.0d) + 1L)
        return waitReady()
    }

    /**
     * Run a one-shot conversion on a channel and return the signed code.
     *
     * @param ch 0 (CH1) or 1 (CH2); values outside 0–1 are clamped
     * @return signed code, −2^(N−1) … 2^(N−1) − 1 (N = resolution bits)
     */
    int readRaw(int ch) {
        return convert(ch)
    }

    /**
     * Run a one-shot conversion on a channel and return the differential voltage.
     *
     * @param ch 0 (CH1) or 1 (CH2); values outside 0–1 are clamped
     * @return {@code CHn+ − CHn−} in volts, {@code raw * lsb / gain}
     */
    double readVoltage(int ch) {
        return ((double) convert(ch)) * lsb() / gainV
    }

    /**
     * Convert both channels, one conversion each.
     *
     * @return {@code {CH1, CH2}} voltages in volts
     */
    double[] readAll() {
        double a = readVoltage(0)
        double b = readVoltage(1)
        return [a, b] as double[]
    }

    /**
     * Size of one code before the gain.
     *
     * @return {@code 2 * 2.048 / 2^bits} in volts
     */
    double lsb() {
        return 2.0d * VREF / (double) (1L << bitsV)
    }
}
