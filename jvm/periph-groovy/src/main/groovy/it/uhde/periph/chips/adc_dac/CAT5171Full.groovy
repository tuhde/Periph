package it.uhde.periph.chips.adc_dac

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * CAT5171 — full interface: extends {@link CAT5171Minimal} with ratio and W–A
 * helpers, increment/decrement, midscale, shutdown and reset. The shutdown
 * state is write-only on the chip, so the driver tracks it.
 */
@CompileStatic
class CAT5171Full extends CAT5171Minimal {

    /**
     * Construct the driver with the default 100 kΩ / 50 Ω resistance model.
     *
     * @param connection configured I²C connection bound to the device (0x2C or 0x2D)
     */
    CAT5171Full(Connection connection) {
        super(connection)
    }

    /**
     * Construct the driver.
     *
     * @param connection configured I²C connection bound to the device (0x2C or 0x2D)
     * @param rAb        end-to-end resistance RAB in Ω
     * @param rWiper     wiper resistance RW in Ω
     */
    CAT5171Full(Connection connection, double rAb, double rWiper) {
        super(connection, rAb, rWiper)
    }

    /**
     * Set the wiper by divider ratio V(W−B)/V(A−B).
     *
     * @param ratio 0.0–1.0; the code is {@code round(ratio × 256)} clamped to 255
     */
    void setRatio(double ratio) {
        writeCode((int) Math.floor(ratio * 256.0d + 0.5d))
    }

    /**
     * Read the divider ratio.
     *
     * @return {@code code / 256}
     */
    double getRatio() {
        return readCode() / 256.0d
    }

    /**
     * Set the W–A resistance (rheostat mode, B open) to the nearest code.
     *
     * @param ohms target W–A resistance in Ω
     */
    void setResistanceWa(double ohms) {
        int steps = (int) Math.floor((ohms - rWOhms) / rAbOhms * 256.0d + 0.5d)
        writeCode(256 - steps)
    }

    /**
     * Read the W–A resistance for the current wiper code.
     *
     * @return resistance in Ω, {@code (256 − D)/256 · RAB + RW}
     */
    double getResistanceWa() {
        return (256 - readCode()) / 256.0d * rAbOhms + rWOhms
    }

    /**
     * Move the wiper relative to its current position.
     *
     * @param steps signed step count (negative to decrement)
     * @return the new wiper code, clamped to 0–255
     */
    int increment(int steps) {
        int c = clampCode(readCode() + steps)
        writeCode(c)
        return c
    }

    /** Move the wiper to midscale (0x80) with the RS instruction bit; SD is kept if shut down. */
    void midscale() {
        writeInstr(INSTR_RS | (shut ? INSTR_SD : 0), MIDSCALE)
        wiperCode = MIDSCALE
    }

    /** Shut down: terminal A opens and the wiper shorts to B; the wiper register is kept. */
    void shutdown() {
        shut = true
        writeInstr(INSTR_SD, wiperCode)
    }

    /** Leave shutdown (SD = 0), rewriting the tracked code. */
    void resume() {
        shut = false
        writeInstr(0, wiperCode)
    }

    /**
     * Driver-tracked shutdown state (the chip cannot report it).
     *
     * @return true if the device was shut down by this driver
     */
    boolean isShutdown() {
        return shut
    }

    /** Midscale reset (RS = 1, SD = 0): wiper to 0x80, shutdown cleared. */
    void reset() {
        shut = false
        writeInstr(INSTR_RS, MIDSCALE)
        wiperCode = MIDSCALE
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
