package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * AD5251 — full interface: extends {@link AD5251Minimal} with ratio and W–A
 * helpers, quick commands (±1 step, ±6 dB), EEMEM store/restore/reset, user
 * EEMEM and the factory RAB tolerance. Methods taking a channel have an
 * overload without it that addresses both channels. EEMEM writes poll with the
 * NOP quick command until the chip acknowledges again (timeout 100 ms).
 */
public class AD5251Full extends AD5251Minimal {

    private static final int EEMEM_FLAG = 0x20;
    private static final int[] TOL_INT = {0x3A, 0x3E};

    private static final int Q_NOP = 0x80;
    private static final int Q_RESTORE = 0x88;
    private static final int Q_STORE = 0x90;
    private static final int Q_HALVE = 0x98;
    private static final int Q_DEC_STEP = 0xA8;
    private static final int Q_INC_6DB = 0xC0;
    private static final int Q_INC_STEP = 0xD0;
    private static final int Q_DEC_6DB_ALL = 0xA0;
    private static final int Q_DEC_STEP_ALL = 0xB0;
    private static final int Q_RESET = 0xB8;
    private static final int Q_INC_6DB_ALL = 0xC8;
    private static final int Q_INC_STEP_ALL = 0xD8;

    /**
     * Construct the driver with the default 50 kΩ / 75 Ω resistance model.
     *
     * @param connection configured I²C connection bound to the device (0x2C–0x2F)
     */
    public AD5251Full(RegisterConnection connection) {
        super(connection);
    }

    /**
     * Construct the driver.
     *
     * @param connection configured I²C connection bound to the device (0x2C–0x2F)
     * @param rAb        end-to-end resistance RAB in Ω
     * @param rWiper     wiper resistance RW in Ω
     */
    public AD5251Full(RegisterConnection connection, double rAb, double rWiper) {
        super(connection, rAb, rWiper);
    }

    private void quickByte(int b) throws IOException {
        connection.write(new byte[]{(byte) b});
    }

    private void quick(int base, int channel) throws IOException {
        checkChannel(channel);
        quickByte(base | RDAC[channel]);
    }

    private void waitReady() throws IOException {
        for (int i = 0; i < 51; i++) {
            try {
                quickByte(Q_NOP);
                return;
            } catch (IOException e) {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("AD5251: interrupted while waiting for EEMEM write", ie);
                }
            }
        }
        throw new IOException("AD5251 busy: EEMEM write did not finish");
    }

    /**
     * Write both wipers in one consecutive-write transaction.
     *
     * @param code0 channel 0 wiper position 0–63
     * @param code1 channel 1 wiper position 0–63
     * @throws IOException on bus error
     */
    public void setBoth(int code0, int code1) throws IOException {
        connection.write(RDAC[0], new byte[]{(byte) clampCode(code0), (byte) clampCode(code1)});
    }

    /**
     * Set the wiper by divider ratio V(W−B)/V(A−B).
     *
     * @param channel 0 or 1
     * @param ratio   0.0–1.0; the code is {@code round(ratio × 64)} clamped to 63
     * @throws IOException on bus error
     */
    public void setRatio(int channel, double ratio) throws IOException {
        writeCode(channel, (int) Math.floor(ratio * 64.0 + 0.5));
    }

    /**
     * Read the divider ratio of a channel.
     *
     * @param channel 0 or 1
     * @return {@code code / 64}
     * @throws IOException on bus error
     */
    public double getRatio(int channel) throws IOException {
        return readCode(channel) / 64.0;
    }

    /**
     * Set the W–A resistance (rheostat mode, B open) to the nearest code.
     *
     * @param channel 0 or 1
     * @param ohms    target W–A resistance in Ω
     * @throws IOException on bus error
     */
    public void setResistanceWa(int channel, double ohms) throws IOException {
        int steps = (int) Math.floor((ohms - rW) / rAb * 64.0 + 0.5);
        writeCode(channel, 64 - steps);
    }

    /**
     * Read the W–A resistance for the current wiper code.
     *
     * @param channel 0 or 1
     * @return resistance in Ω, {@code (64 − D)/64 · RAB + RW}
     * @throws IOException on bus error
     */
    public double getResistanceWa(int channel) throws IOException {
        return (64 - readCode(channel)) / 64.0 * rAb + rW;
    }

    /**
     * Increment the wiper by one step (quick command).
     *
     * @param channel 0 or 1
     * @throws IOException on bus error
     */
    public void increment(int channel) throws IOException {
        quick(Q_INC_STEP, channel);
    }

    /**
     * Increment both wipers by one step (quick command).
     *
     * @throws IOException on bus error
     */
    public void increment() throws IOException {
        quickByte(Q_INC_STEP_ALL);
    }

    /**
     * Decrement the wiper by one step (quick command).
     *
     * @param channel 0 or 1
     * @throws IOException on bus error
     */
    public void decrement(int channel) throws IOException {
        quick(Q_DEC_STEP, channel);
    }

    /**
     * Decrement both wipers by one step (quick command).
     *
     * @throws IOException on bus error
     */
    public void decrement() throws IOException {
        quickByte(Q_DEC_STEP_ALL);
    }

    /**
     * Increase the level by 6 dB — the code doubles (quick command).
     *
     * @param channel 0 or 1
     * @throws IOException on bus error
     */
    public void doubleCode(int channel) throws IOException {
        quick(Q_INC_6DB, channel);
    }

    /**
     * Increase both wipers by 6 dB (quick command).
     *
     * @throws IOException on bus error
     */
    public void doubleCode() throws IOException {
        quickByte(Q_INC_6DB_ALL);
    }

    /**
     * Decrease the level by 6 dB — the code halves (quick command).
     *
     * @param channel 0 or 1
     * @throws IOException on bus error
     */
    public void halve(int channel) throws IOException {
        quick(Q_HALVE, channel);
    }

    /**
     * Decrease both wipers by 6 dB (quick command).
     *
     * @throws IOException on bus error
     */
    public void halve() throws IOException {
        quickByte(Q_DEC_6DB_ALL);
    }

    /**
     * Store the wiper in EEMEM (restored at power-on) and wait for the ≈ 26 ms
     * write cycle by acknowledge polling.
     *
     * @param channel 0 or 1
     * @throws IOException on bus error or if the chip does not acknowledge within 100 ms
     */
    public void store(int channel) throws IOException {
        quick(Q_STORE, channel);
        waitReady();
    }

    /**
     * Store both wipers in EEMEM, waiting after each.
     *
     * @throws IOException on bus error or if the chip does not acknowledge within 100 ms
     */
    public void store() throws IOException {
        store(0);
        store(1);
    }

    /**
     * Restore the wiper from EEMEM, then send a NOP to return to idle.
     *
     * @param channel 0 or 1
     * @throws IOException on bus error
     */
    public void restore(int channel) throws IOException {
        quick(Q_RESTORE, channel);
        nop();
    }

    /**
     * Restore both wipers from EEMEM, then send a NOP.
     *
     * @throws IOException on bus error
     */
    public void restore() throws IOException {
        quick(Q_RESTORE, 0);
        quick(Q_RESTORE, 1);
        nop();
    }

    /**
     * Quick-command reset ({@code 0xB8}): restore both wipers from EEMEM and
     * wait for the chip to acknowledge again.
     *
     * @throws IOException on bus error or if the chip does not acknowledge within 100 ms
     */
    public void reset() throws IOException {
        quickByte(Q_RESET);
        waitReady();
    }

    /**
     * Read a user EEMEM byte.
     *
     * @param index 4–15
     * @return the stored byte 0–63
     * @throws IllegalArgumentException if index is outside 4–15
     * @throws IOException              on bus error
     */
    public int readEemem(int index) throws IOException {
        checkIndex(index);
        return connection.read(EEMEM_FLAG | index, 1)[0] & 0xFF;
    }

    /**
     * Write a user EEMEM byte and wait for the ≈ 26 ms write cycle.
     *
     * @param index 4–15
     * @param value byte 0–63
     * @throws IllegalArgumentException if index is outside 4–15
     * @throws IOException              on bus error or if the chip does not acknowledge within 100 ms
     */
    public void writeEemem(int index, int value) throws IOException {
        checkIndex(index);
        connection.write(EEMEM_FLAG | index, new byte[]{(byte) value});
        waitReady();
    }

    private static void checkIndex(int index) {
        if (index < 4 || index > 15) {
            throw new IllegalArgumentException("index must be 4..15");
        }
    }

    /**
     * Read the factory-stored RAB tolerance of a channel.
     *
     * @param channel 0 or 1
     * @return tolerance in percent (sign-magnitude integer byte plus decimal byte / 256)
     * @throws IOException on bus error
     */
    public double readTolerance(int channel) throws IOException {
        checkChannel(channel);
        byte[] raw = connection.read(TOL_INT[channel], 2);
        int hi = raw[0] & 0xFF;
        double value = (hi & 0x7F) + (raw[1] & 0xFF) / 256.0;
        return (hi & 0x80) != 0 ? -value : value;
    }

    /**
     * Actual RAB of a channel from its stored tolerance.
     *
     * @param channel 0 or 1
     * @return {@code rAb × (1 + tolerance / 100)} in Ω
     * @throws IOException on bus error
     */
    public double rabActual(int channel) throws IOException {
        return rAb * (1.0 + readTolerance(channel) / 100.0);
    }

    /**
     * Send the NOP quick command ({@code 0x80}), returning the chip to idle.
     *
     * @throws IOException on bus error
     */
    public void nop() throws IOException {
        quickByte(Q_NOP);
    }

    /**
     * Change the resistance parameters used by conversions (no bus traffic).
     *
     * @param rAb    end-to-end resistance RAB in Ω
     * @param rWiper wiper resistance RW in Ω; negative keeps the current value
     */
    public void setRab(double rAb, double rWiper) {
        this.rAb = rAb;
        if (rWiper >= 0) {
            this.rW = rWiper;
        }
    }

    /**
     * Change the end-to-end resistance used by conversions (no bus traffic).
     *
     * @param rAb end-to-end resistance RAB in Ω
     */
    public void setRab(double rAb) {
        this.rAb = rAb;
    }
}
