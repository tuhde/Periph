package it.uhde.periph.chips.adc_dac

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

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
@CompileStatic
class DS1881Minimal {

    /** 7-bit I²C address with A2 = A1 = A0 = 0 (0x28–0x2F with the address pins). */
    public static final int DEFAULT_ADDRESS = 0x28

    protected static final int CMD_WIPER1 = 0x40
    protected static final int CMD_CONFIG = 0x80
    protected static final int CFG_VOLATILE = 0x04
    protected static final int CFG_ZC = 0x02
    protected static final int CFG_OPTION2 = 0x01
    protected static final int CFG_MASK = 0x07
    /** Volatile, zero-crossing on, Option 1. */
    protected static final int CFG_DEFAULT = 0x06
    protected static final double MUTE_DB = 80.0d

    protected final Connection connection
    protected int cfgBits = CFG_DEFAULT
    protected int optionNo = 1

    /**
     * Construct the driver; performs no bus traffic.
     *
     * @param connection configured I²C connection bound to the device (0x28–0x2F)
     */
    DS1881Minimal(Connection connection) {
        this.connection = connection
    }

    /**
     * Run the initialization sequence: wait the 1 ms power-up time, read the
     * three bytes and rewrite the configuration (volatile, zero-crossing on,
     * Option 1) only if it differs, waiting for the EEPROM write that this
     * triggers. The wipers are not touched.
     */
    void init() {
        sleepMs(1L)
        byte[] raw = connection.read(3)
        if (((raw[2] & 0xFF) & CFG_MASK) != CFG_DEFAULT) {
            connection.write([(byte) (CMD_CONFIG | CFG_DEFAULT)] as byte[])
            doWaitReady(0.05d)
        }
        cfgBits = CFG_DEFAULT
        optionNo = 1
    }

    protected static void sleepMs(long ms) {
        try {
            Thread.sleep(ms)
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt()
        }
    }

    /** Acknowledge-poll with a one-byte read until the device answers or the timeout passes. */
    protected void doWaitReady(double timeoutS) {
        int attempts = (int) (timeoutS / 0.001d) + 1
        IOException last = null
        for (int i = 0; i < attempts; i++) {
            try {
                connection.read(1)
                return
            } catch (IOException e) {
                last = e
                sleepMs(1L)
            }
        }
        throw new IOException('DS1881 busy: EEPROM write did not finish', last)
    }

    protected void writeCommands(byte[] data) {
        connection.write(data)
        if ((cfgBits & CFG_VOLATILE) == 0) doWaitReady(0.05d)
    }

    protected static void checkChannel(int channel) {
        if (channel != 0 && channel != 1) throw new IllegalArgumentException('channel must be 0 or 1')
    }

    protected int mutePosition() {
        return optionNo == 1 ? 63 : 33
    }

    protected double positionToDbImpl(int position) {
        if (position >= mutePosition()) return MUTE_DB
        if (optionNo == 1 || position <= 12) return (double) position
        if (position <= 24) return (double) (12 + 2 * (position - 12))
        return (double) (36 + 3 * (position - 24))
    }

    /** Nearest non-mute position for {@code db}, clamped to the table. */
    protected int nearestPosition(double db) {
        if (db <= 0.0d) return 0
        if (optionNo == 1) return Math.min((int) (db + 0.5d), 62)
        int best = 0
        double bestErr = db
        for (int p = 1; p <= 32; p++) {
            double err = Math.abs(positionToDbImpl(p) - db)
            if (err < bestErr) {
                best = p
                bestErr = err
            }
        }
        return best
    }

    /** Position for an attenuation; Full overrides this to map ≥ 70 dB to mute. */
    protected int dbToPos(double db) {
        return nearestPosition(db)
    }

    protected void writePosition(int channel, int position) {
        checkChannel(channel)
        int value = Math.max(0, Math.min(position, mutePosition()))
        writeCommands([(byte) ((channel << 6) | value)] as byte[])
    }

    protected int[] readAllRaw() {
        byte[] raw = connection.read(3)
        return [raw[0] & 0x3F, raw[1] & 0x3F, raw[2] & 0xFF] as int[]
    }

    /**
     * Set a channel's attenuation to the nearest position (rounded to the
     * nearest step, clamped to 0…62 dB in Option 1).
     *
     * @param channel 0 or 1
     * @param db attenuation in dB (positive = quieter)
     */
    void setAttenuation(int channel, double db) {
        checkChannel(channel)
        writePosition(channel, dbToPos(db))
    }

    /**
     * Read a channel's attenuation.
     *
     * @param channel 0 or 1
     * @return attenuation in dB ({@code 80.0} for mute)
     */
    double getAttenuation(int channel) {
        return positionToDbImpl(getPosition(channel))
    }

    /**
     * Mute a channel (position 63 in Option 1).
     *
     * @param channel 0 or 1
     */
    void mute(int channel) {
        writePosition(channel, mutePosition())
    }

    /**
     * Write a raw wiper position.
     *
     * @param channel 0 or 1
     * @param position 0–63 (63 = mute); clamped
     */
    void setPosition(int channel, int position) {
        writePosition(channel, position)
    }

    /**
     * Read a wiper position with the three-byte round-robin read.
     *
     * @param channel 0 or 1
     * @return position (low 6 bits of the read-back byte)
     */
    int getPosition(int channel) {
        checkChannel(channel)
        return readAllRaw()[channel]
    }
}
