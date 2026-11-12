package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * DS1881 — dual nonvolatile audio log-taper digital potentiometer (Maxim) —
 * minimal interface.
 *
 * <p>Two 45 kΩ potentiometers whose wiper positions are steps in an
 * attenuation table. Option 1 gives 64 positions (1 dB per step, 0 to −62 dB,
 * position 63 = mute); Option 2 (DS1808 compatible) gives 34 positions
 * (12 × 1 dB, 12 × 2 dB, 8 × 3 dB, position 33 = mute). The I²C address is
 * 0x28 (A2 = A1 = A0 = 0) to 0x2F.
 *
 * <p>The chip has no register map: a write is one or more command bytes
 * ({@code 00pppppp} wiper 0, {@code 01pppppp} wiper 1, {@code 10xxxVZC}
 * configuration) and a read returns wiper 0, wiper 1 and the configuration
 * register round-robin. The driver therefore uses the plain {@link Connection}
 * {@code write} / {@code read} calls. Wipers and configuration live in EEPROM
 * when V/NV = 0 (50 000 writes); the drivers default to volatile operation.
 * The CE pin is active low and is left to the Connection's enable pin.
 *
 * <p>Defaults: Option 1, volatile wipers (both channels at mute after
 * power-up), zero-crossing detection on. Construction performs no bus
 * traffic; call {@link #init()} once after power-up.
 */
public class DS1881Minimal {

    /** 7-bit I²C address with A2 = A1 = A0 = 0 (0x28–0x2F with the address pins). */
    public static final int DEFAULT_ADDRESS = 0x28;

    protected static final int CMD_WIPER1 = 0x40;
    protected static final int CMD_CONFIG = 0x80;
    protected static final int CFG_VOLATILE = 0x04;
    protected static final int CFG_ZC = 0x02;
    protected static final int CFG_OPTION2 = 0x01;
    protected static final int CFG_MASK = 0x07;
    /** Volatile, zero-crossing on, Option 1. */
    protected static final int CFG_DEFAULT = CFG_VOLATILE | CFG_ZC;
    protected static final double MUTE_DB = 80.0;

    protected final Connection connection;
    protected int config = CFG_DEFAULT;
    protected int option = 1;

    /**
     * Construct the driver; performs no bus traffic.
     *
     * @param connection configured I²C connection bound to the device (0x28–0x2F)
     */
    public DS1881Minimal(Connection connection) {
        this.connection = connection;
    }

    /**
     * Run the initialization sequence: wait the 1 ms power-up time, read the
     * three bytes and rewrite the configuration (volatile, zero-crossing on,
     * Option 1) only if it differs, waiting for the EEPROM write that this
     * triggers. The wipers are not touched.
     *
     * @throws IOException on a bus error
     */
    public void init() throws IOException {
        sleepMs(1);
        byte[] raw = connection.read(3);
        if ((raw[2] & CFG_MASK) != CFG_DEFAULT) {
            connection.write(new byte[] {(byte) (CMD_CONFIG | CFG_DEFAULT)});
            doWaitReady(0.05);
        }
        config = CFG_DEFAULT;
        option = 1;
    }

    static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Acknowledge-poll with a one-byte read until the device answers or the timeout passes. */
    protected void doWaitReady(double timeoutS) throws IOException {
        int attempts = (int) (timeoutS / 0.001) + 1;
        IOException last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                connection.read(1);
                return;
            } catch (IOException e) {
                last = e;
                sleepMs(1);
            }
        }
        throw new IOException("DS1881 busy: EEPROM write did not finish", last);
    }

    protected void writeCommands(byte[] data) throws IOException {
        connection.write(data);
        if ((config & CFG_VOLATILE) == 0) doWaitReady(0.05);
    }

    protected static void checkChannel(int channel) {
        if (channel != 0 && channel != 1) throw new IllegalArgumentException("channel must be 0 or 1");
    }

    protected int mutePosition() {
        return option == 1 ? 63 : 33;
    }

    protected double positionToDbImpl(int position) {
        if (position >= mutePosition()) return MUTE_DB;
        if (option == 1 || position <= 12) return position;
        if (position <= 24) return 12 + 2 * (position - 12);
        return 36 + 3 * (position - 24);
    }

    /** Nearest non-mute position for {@code db}, clamped to the table. */
    protected int nearestPosition(double db) {
        if (db <= 0) return 0;
        if (option == 1) return Math.min((int) (db + 0.5), 62);
        int best = 0;
        double bestErr = db;
        for (int p = 1; p <= 32; p++) {
            double err = Math.abs(positionToDbImpl(p) - db);
            if (err < bestErr) {
                best = p;
                bestErr = err;
            }
        }
        return best;
    }

    /** Position for an attenuation; Full overrides this to map ≥ 70 dB to mute. */
    protected int dbToPos(double db) {
        return nearestPosition(db);
    }

    protected void writePosition(int channel, int position) throws IOException {
        checkChannel(channel);
        int mute = mutePosition();
        if (position < 0) position = 0;
        else if (position > mute) position = mute;
        writeCommands(new byte[] {(byte) ((channel << 6) | position)});
    }

    protected int[] readAllRaw() throws IOException {
        byte[] raw = connection.read(3);
        return new int[] {raw[0] & 0x3F, raw[1] & 0x3F, raw[2] & 0xFF};
    }

    /**
     * Set a channel's attenuation to the nearest position (rounded to the
     * nearest step, clamped to 0…62 dB in Option 1).
     *
     * @param channel 0 or 1
     * @param db attenuation in dB (positive = quieter)
     * @throws IOException on a bus error
     */
    public void setAttenuation(int channel, double db) throws IOException {
        checkChannel(channel);
        writePosition(channel, dbToPos(db));
    }

    /**
     * Read a channel's attenuation.
     *
     * @param channel 0 or 1
     * @return attenuation in dB ({@code 80.0} for mute)
     * @throws IOException on a bus error
     */
    public double getAttenuation(int channel) throws IOException {
        return positionToDbImpl(getPosition(channel));
    }

    /**
     * Mute a channel (position 63 in Option 1).
     *
     * @param channel 0 or 1
     * @throws IOException on a bus error
     */
    public void mute(int channel) throws IOException {
        writePosition(channel, mutePosition());
    }

    /**
     * Write a raw wiper position.
     *
     * @param channel 0 or 1
     * @param position 0–63 (63 = mute); clamped
     * @throws IOException on a bus error
     */
    public void setPosition(int channel, int position) throws IOException {
        writePosition(channel, position);
    }

    /**
     * Read a wiper position with the three-byte round-robin read.
     *
     * @param channel 0 or 1
     * @return position (low 6 bits of the read-back byte)
     * @throws IOException on a bus error
     */
    public int getPosition(int channel) throws IOException {
        checkChannel(channel);
        return readAllRaw()[channel];
    }
}
