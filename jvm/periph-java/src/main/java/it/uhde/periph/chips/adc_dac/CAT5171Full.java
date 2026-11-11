package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * CAT5171 — full interface: extends {@link CAT5171Minimal} with ratio and W–A
 * helpers, increment/decrement, midscale, shutdown and reset. The shutdown
 * state is write-only on the chip, so the driver tracks it.
 */
public class CAT5171Full extends CAT5171Minimal {

    /**
     * Construct the driver with the default 100 kΩ / 50 Ω resistance model.
     *
     * @param connection configured I²C connection bound to the device (0x2C or 0x2D)
     */
    public CAT5171Full(Connection connection) {
        super(connection);
    }

    /**
     * Construct the driver.
     *
     * @param connection configured I²C connection bound to the device (0x2C or 0x2D)
     * @param rAb        end-to-end resistance RAB in Ω
     * @param rWiper     wiper resistance RW in Ω
     */
    public CAT5171Full(Connection connection, double rAb, double rWiper) {
        super(connection, rAb, rWiper);
    }

    /**
     * Set the wiper by divider ratio V(W−B)/V(A−B).
     *
     * @param ratio 0.0–1.0; the code is {@code round(ratio × 256)} clamped to 255
     * @throws IOException on bus error
     */
    public void setRatio(double ratio) throws IOException {
        writeCode((int) Math.floor(ratio * 256.0 + 0.5));
    }

    /**
     * Read the divider ratio.
     *
     * @return {@code code / 256}
     * @throws IOException on bus error
     */
    public double getRatio() throws IOException {
        return readCode() / 256.0;
    }

    /**
     * Set the W–A resistance (rheostat mode, B open) to the nearest code.
     *
     * @param ohms target W–A resistance in Ω
     * @throws IOException on bus error
     */
    public void setResistanceWa(double ohms) throws IOException {
        int steps = (int) Math.floor((ohms - rW) / rAb * 256.0 + 0.5);
        writeCode(256 - steps);
    }

    /**
     * Read the W–A resistance for the current wiper code.
     *
     * @return resistance in Ω, {@code (256 − D)/256 · RAB + RW}
     * @throws IOException on bus error
     */
    public double getResistanceWa() throws IOException {
        return (256 - readCode()) / 256.0 * rAb + rW;
    }

    /**
     * Move the wiper relative to its current position.
     *
     * @param steps signed step count (negative to decrement)
     * @return the new wiper code, clamped to 0–255
     * @throws IOException on bus error
     */
    public int increment(int steps) throws IOException {
        int c = clampCode(readCode() + steps);
        writeCode(c);
        return c;
    }

    /**
     * Move the wiper to midscale (0x80) with the RS instruction bit; SD is
     * kept if the device is shut down.
     *
     * @throws IOException on bus error
     */
    public void midscale() throws IOException {
        writeInstr(INSTR_RS | (sd ? INSTR_SD : 0), MIDSCALE);
        code = MIDSCALE;
    }

    /**
     * Shut down: terminal A opens and the wiper shorts to B. The wiper
     * register is kept (the tracked code is rewritten with SD = 1).
     *
     * @throws IOException on bus error
     */
    public void shutdown() throws IOException {
        sd = true;
        writeInstr(INSTR_SD, code);
    }

    /**
     * Leave shutdown (SD = 0), rewriting the tracked code.
     *
     * @throws IOException on bus error
     */
    public void resume() throws IOException {
        sd = false;
        writeInstr(0, code);
    }

    /**
     * Driver-tracked shutdown state (the chip cannot report it).
     *
     * @return true if the device was shut down by this driver
     */
    public boolean isShutdown() {
        return sd;
    }

    /**
     * Midscale reset (RS = 1, SD = 0): wiper to 0x80, shutdown cleared.
     *
     * @throws IOException on bus error
     */
    public void reset() throws IOException {
        sd = false;
        writeInstr(INSTR_RS, MIDSCALE);
        code = MIDSCALE;
    }

    /**
     * Change the resistance parameters used by conversions (no bus traffic).
     *
     * @param rAb    end-to-end resistance RAB in Ω
     * @param rWiper wiper resistance RW in Ω
     */
    public void setRab(double rAb, double rWiper) {
        this.rAb = rAb;
        this.rW = rWiper;
    }

    /**
     * Change the end-to-end resistance used by conversions, keeping RW (no bus traffic).
     *
     * @param rAb end-to-end resistance RAB in Ω
     */
    public void setRab(double rAb) {
        this.rAb = rAb;
    }
}
