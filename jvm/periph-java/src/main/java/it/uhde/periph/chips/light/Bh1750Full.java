package it.uhde.periph.chips.light;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * BH1750FVI — full driver: raw codes, one-time measurements in each mode, continuous measurement,
 * power-down/power-on, reset, the measurement-time register (MTreg) and sensitivity adjustment.
 * Extends {@link Bh1750Minimal}. {@link #read()} uses the mode of the last
 * {@link #measureOnce(int)} or {@link #startContinuous(int)} call (H-resolution by default).
 */
public class Bh1750Full extends Bh1750Minimal {

    /**
     * Create the driver; does no bus access.
     *
     * @param connection I²C connection bound to 0x23 or 0x5C
     */
    public Bh1750Full(Connection connection) {
        super(connection);
    }

    private static void checkMode(int mode) {
        if (mode < MODE_H || mode > MODE_L) {
            throw new IllegalArgumentException("BH1750 mode must be MODE_H, MODE_H2 or MODE_L");
        }
    }

    /**
     * Run one one-time measurement in the current mode.
     *
     * @return the raw 16-bit count
     * @throws IOException on bus error
     */
    public int readRaw() throws IOException {
        return measureRaw(mode);
    }

    /**
     * Run one one-time measurement in {@link #MODE_H}.
     *
     * @return illuminance in lux
     * @throws IOException on bus error
     */
    public double measureOnce() throws IOException {
        return measureOnce(MODE_H);
    }

    /**
     * Run one one-time measurement.
     *
     * @param mode {@link #MODE_H} (1 lx), {@link #MODE_H2} (0.5 lx) or {@link #MODE_L} (4 lx)
     * @return illuminance in lux
     * @throws IOException on bus error
     * @throws IllegalArgumentException if mode is invalid
     */
    public double measureOnce(int mode) throws IOException {
        checkMode(mode);
        this.mode = mode;
        return toLux(measureRaw(mode), mode);
    }

    /**
     * Start continuous measurements in {@link #MODE_H}.
     *
     * @throws IOException on bus error
     */
    public void startContinuous() throws IOException {
        startContinuous(MODE_H);
    }

    /**
     * Start continuous measurements; the first result is ready after {@link #measurementTime(int)}.
     *
     * @param mode {@link #MODE_H} (1 lx), {@link #MODE_H2} (0.5 lx) or {@link #MODE_L} (4 lx)
     * @throws IOException on bus error
     * @throws IllegalArgumentException if mode is invalid
     */
    public void startContinuous(int mode) throws IOException {
        checkMode(mode);
        this.mode = mode;
        command(OP_CONTINUOUS[mode]);
    }

    /**
     * Read the latest continuous-mode result without waiting.
     *
     * @return illuminance in lux
     * @throws IOException on bus error
     */
    public double readContinuous() throws IOException {
        return toLux(readWord(), mode);
    }

    /**
     * Enter power-down (opcode 0x00).
     *
     * @throws IOException on bus error
     */
    public void powerDown() throws IOException {
        command(OP_POWER_DOWN);
    }

    /**
     * Leave power-down and wait for a measurement command (opcode 0x01).
     *
     * @throws IOException on bus error
     */
    public void powerOn() throws IOException {
        command(OP_POWER_ON);
    }

    /**
     * Clear the illuminance data register (opcode 0x07; powers on first, as reset is ignored in
     * power-down).
     *
     * @throws IOException on bus error
     */
    public void reset() throws IOException {
        command(OP_POWER_ON);
        command(OP_RESET);
    }

    /**
     * Write the measurement-time register (two opcodes, high bits then low bits).
     *
     * @param mtreg register value, 31–254 (default 69)
     * @throws IOException on bus error
     * @throws IllegalArgumentException if mtreg is outside 31–254 (nothing is written)
     */
    public void setMeasurementTime(int mtreg) throws IOException {
        if (mtreg < 31 || mtreg > 254) {
            throw new IllegalArgumentException("BH1750 MTreg must be 31-254");
        }
        command(OP_MT_HIGH | (mtreg >> 5));
        command(OP_MT_LOW | (mtreg & 0x1F));
        this.mtreg = mtreg;
    }

    /**
     * The last written MTreg (the chip has no read-back; 69 after init).
     *
     * @return the MTreg value
     */
    public int getMeasurementTimeRegister() {
        return mtreg;
    }

    /**
     * Set the sensitivity relative to the default (MTreg = round(69 × factor)).
     *
     * @param factor 0.45–3.68
     * @throws IOException on bus error
     * @throws IllegalArgumentException if the resulting MTreg is outside 31–254
     */
    public void setSensitivity(double factor) throws IOException {
        setMeasurementTime((int) Math.round(DEFAULT_MTREG * factor));
    }

    /**
     * Maximum measurement time of {@link #MODE_H} at the current MTreg.
     *
     * @return seconds
     */
    public double measurementTime() {
        return measurementTime(MODE_H);
    }

    /**
     * Maximum measurement time of a mode at the current MTreg.
     *
     * @param mode {@link #MODE_H}, {@link #MODE_H2} or {@link #MODE_L}
     * @return seconds
     */
    public double measurementTime(int mode) {
        checkMode(mode);
        return waitMsExact(mode) / 1000.0;
    }

    private double waitMsExact(int mode) {
        return T_MAX_MS[mode] * this.mtreg / DEFAULT_MTREG;
    }

    /**
     * Illuminance represented by one raw count of {@link #MODE_H} at the current MTreg.
     *
     * @return lux per count
     */
    public double luxPerCount() {
        return luxPerCount(MODE_H);
    }

    /**
     * Illuminance represented by one raw count at the current MTreg.
     *
     * @param mode {@link #MODE_H}, {@link #MODE_H2} or {@link #MODE_L}
     * @return lux per count
     */
    public double luxPerCount(int mode) {
        checkMode(mode);
        return toLux(1, mode);
    }
}
