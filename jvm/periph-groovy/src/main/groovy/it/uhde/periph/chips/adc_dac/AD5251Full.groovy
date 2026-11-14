package it.uhde.periph.chips.adc_dac

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection

/**
 * AD5251 — full interface: extends {@link AD5251Minimal} with ratio and W–A
 * helpers, quick commands (±1 step, ±6 dB), EEMEM store/restore/reset, user
 * EEMEM and the factory RAB tolerance. Methods taking a channel have an
 * overload without it that addresses both channels. EEMEM writes poll with the
 * NOP quick command until the chip acknowledges again (timeout 100 ms).
 */
@CompileStatic
class AD5251Full extends AD5251Minimal {

    private static final int EEMEM_FLAG = 0x20
    private static final int[] TOL_INT = [0x3A, 0x3E] as int[]

    private static final int Q_NOP = 0x80
    private static final int Q_RESTORE = 0x88
    private static final int Q_STORE = 0x90
    private static final int Q_HALVE = 0x98
    private static final int Q_DEC_STEP = 0xA8
    private static final int Q_INC_6DB = 0xC0
    private static final int Q_INC_STEP = 0xD0
    private static final int Q_DEC_6DB_ALL = 0xA0
    private static final int Q_DEC_STEP_ALL = 0xB0
    private static final int Q_RESET = 0xB8
    private static final int Q_INC_6DB_ALL = 0xC8
    private static final int Q_INC_STEP_ALL = 0xD8

    /**
     * Construct the driver with the default 50 kΩ / 75 Ω resistance model.
     *
     * @param connection configured I²C connection bound to the device (0x2C–0x2F)
     */
    AD5251Full(RegisterConnection connection) {
        super(connection)
    }

    /**
     * Construct the driver.
     *
     * @param connection configured I²C connection bound to the device (0x2C–0x2F)
     * @param rAb        end-to-end resistance RAB in Ω
     * @param rWiper     wiper resistance RW in Ω
     */
    AD5251Full(RegisterConnection connection, double rAb, double rWiper) {
        super(connection, rAb, rWiper)
    }

    private void quickByte(int b) {
        connection.write(new byte[]{(byte) b})
    }

    private void quick(int base, int channel) {
        checkChannel(channel)
        quickByte(base | RDAC[channel])
    }

    private void waitReady() {
        for (int i = 0; i < 51; i++) {
            try {
                quickByte(Q_NOP)
                return
            } catch (IOException ignored) {
                Thread.sleep(2)
            }
        }
        throw new IOException('AD5251 busy: EEMEM write did not finish')
    }

    /**
     * Write both wipers in one consecutive-write transaction.
     *
     * @param code0 channel 0 wiper position 0–63
     * @param code1 channel 1 wiper position 0–63
     */
    void setBoth(int code0, int code1) {
        connection.write(RDAC[0], new byte[]{(byte) clampCode(code0), (byte) clampCode(code1)})
    }

    /**
     * Set the wiper by divider ratio V(W−B)/V(A−B).
     *
     * @param channel 0 or 1
     * @param ratio   0.0–1.0; the code is {@code round(ratio × 64)} clamped to 63
     */
    void setRatio(int channel, double ratio) {
        writeCode(channel, (int) Math.floor(ratio * 64.0d + 0.5d))
    }

    /**
     * Read the divider ratio of a channel.
     *
     * @param channel 0 or 1
     * @return {@code code / 64}
     */
    double getRatio(int channel) {
        return readCode(channel) / 64.0d
    }

    /**
     * Set the W–A resistance (rheostat mode, B open) to the nearest code.
     *
     * @param channel 0 or 1
     * @param ohms    target W–A resistance in Ω
     */
    void setResistanceWa(int channel, double ohms) {
        int steps = (int) Math.floor((ohms - rWOhms) / rAbOhms * 64.0d + 0.5d)
        writeCode(channel, 64 - steps)
    }

    /**
     * Read the W–A resistance for the current wiper code.
     *
     * @param channel 0 or 1
     * @return resistance in Ω, {@code (64 − D)/64 · RAB + RW}
     */
    double getResistanceWa(int channel) {
        return (64 - readCode(channel)) / 64.0d * rAbOhms + rWOhms
    }

    /**
     * Increment the wiper by one step (quick command).
     *
     * @param channel 0 or 1
     */
    void increment(int channel) {
        quick(Q_INC_STEP, channel)
    }

    /** Increment both wipers by one step (quick command). */
    void increment() {
        quickByte(Q_INC_STEP_ALL)
    }

    /**
     * Decrement the wiper by one step (quick command).
     *
     * @param channel 0 or 1
     */
    void decrement(int channel) {
        quick(Q_DEC_STEP, channel)
    }

    /** Decrement both wipers by one step (quick command). */
    void decrement() {
        quickByte(Q_DEC_STEP_ALL)
    }

    /**
     * Increase the level by 6 dB — the code doubles (quick command).
     *
     * @param channel 0 or 1
     */
    void doubleCode(int channel) {
        quick(Q_INC_6DB, channel)
    }

    /** Increase both wipers by 6 dB (quick command). */
    void doubleCode() {
        quickByte(Q_INC_6DB_ALL)
    }

    /**
     * Decrease the level by 6 dB — the code halves (quick command).
     *
     * @param channel 0 or 1
     */
    void halve(int channel) {
        quick(Q_HALVE, channel)
    }

    /** Decrease both wipers by 6 dB (quick command). */
    void halve() {
        quickByte(Q_DEC_6DB_ALL)
    }

    /**
     * Store the wiper in EEMEM (restored at power-on) and wait for the ≈ 26 ms
     * write cycle by acknowledge polling.
     *
     * @param channel 0 or 1
     * @throws IOException if the chip does not acknowledge within 100 ms
     */
    void store(int channel) {
        quick(Q_STORE, channel)
        waitReady()
    }

    /** Store both wipers in EEMEM, waiting after each. */
    void store() {
        store(0)
        store(1)
    }

    /**
     * Restore the wiper from EEMEM, then send a NOP to return to idle.
     *
     * @param channel 0 or 1
     */
    void restore(int channel) {
        quick(Q_RESTORE, channel)
        nop()
    }

    /** Restore both wipers from EEMEM, then send a NOP. */
    void restore() {
        quick(Q_RESTORE, 0)
        quick(Q_RESTORE, 1)
        nop()
    }

    /**
     * Quick-command reset ({@code 0xB8}): restore both wipers from EEMEM and
     * wait for the chip to acknowledge again.
     *
     * @throws IOException if the chip does not acknowledge within 100 ms
     */
    void reset() {
        quickByte(Q_RESET)
        waitReady()
    }

    /**
     * Read a user EEMEM byte.
     *
     * @param index 4–15
     * @return the stored byte 0–63
     * @throws IllegalArgumentException if index is outside 4–15
     */
    int readEemem(int index) {
        checkIndex(index)
        return connection.read(EEMEM_FLAG | index, 1)[0] & 0xFF
    }

    /**
     * Write a user EEMEM byte and wait for the ≈ 26 ms write cycle.
     *
     * @param index 4–15
     * @param value byte 0–63
     * @throws IllegalArgumentException if index is outside 4–15
     * @throws IOException if the chip does not acknowledge within 100 ms
     */
    void writeEemem(int index, int value) {
        checkIndex(index)
        connection.write(EEMEM_FLAG | index, new byte[]{(byte) value})
        waitReady()
    }

    private static void checkIndex(int index) {
        if (index < 4 || index > 15) {
            throw new IllegalArgumentException('index must be 4..15')
        }
    }

    /**
     * Read the factory-stored RAB tolerance of a channel.
     *
     * @param channel 0 or 1
     * @return tolerance in percent (sign-magnitude integer byte plus decimal byte / 256)
     */
    double readTolerance(int channel) {
        checkChannel(channel)
        byte[] raw = connection.read(TOL_INT[channel], 2)
        int hi = raw[0] & 0xFF
        double value = (hi & 0x7F) + (raw[1] & 0xFF) / 256.0d
        return (hi & 0x80) != 0 ? -value : value
    }

    /**
     * Actual RAB of a channel from its stored tolerance.
     *
     * @param channel 0 or 1
     * @return {@code rAb × (1 + tolerance / 100)} in Ω
     */
    double rabActual(int channel) {
        return rAbOhms * (1.0d + readTolerance(channel) / 100.0d)
    }

    /** Send the NOP quick command ({@code 0x80}), returning the chip to idle. */
    void nop() {
        quickByte(Q_NOP)
    }

    /**
     * Change the resistance parameters used by conversions (no bus traffic).
     *
     * @param rAb    end-to-end resistance RAB in Ω
     * @param rWiper wiper resistance RW in Ω
     */
    void setRab(double rAb, double rWiper) {
        this.rAbOhms = rAb
        this.rWOhms = rWiper
    }

    /**
     * Change RAB only; RW is kept.
     *
     * @param rAb end-to-end resistance RAB in Ω
     */
    void setRab(double rAb) {
        this.rAbOhms = rAb
    }
}
