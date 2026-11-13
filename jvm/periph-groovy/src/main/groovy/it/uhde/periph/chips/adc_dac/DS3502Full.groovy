package it.uhde.periph.chips.adc_dac

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection

/**
 * DS3502 full interface — extends {@link DS3502Minimal} with ratio / resistance
 * helpers, increment, EEPROM storage of the power-up value, MODE control and
 * acknowledge polling.
 */
@CompileStatic
class DS3502Full extends DS3502Minimal {

    /** Nominal end-to-end resistance in Ω (±20 %). */
    protected static final double R_TOTAL = 10000.0d

    /**
     * Construct the driver and run the initialization sequence.
     *
     * @param connection I²C register connection bound to the device (0x28–0x2B)
     * @param vHigh      voltage applied to RH, in volts
     * @param vLow       voltage applied to RL, in volts
     */
    DS3502Full(RegisterConnection connection, double vHigh = 1.0d, double vLow = 0.0d) {
        super(connection, vHigh, vLow)
    }

    /**
     * Set the wiper by divider ratio V(RW−RL) / V(RH−RL).
     *
     * @param ratio 0.0–1.0; the code is {@code round(ratio * 127)}
     */
    void setRatio(double ratio) {
        writeCode((int) Math.round(ratio * MAX_CODE))
    }

    /**
     * Read the divider ratio.
     *
     * @return {@code code / 127}
     */
    double getRatio() {
        return (double) readCode() / MAX_CODE
    }

    /**
     * Nominal wiper resistance for the current code (RTOTAL = 10 kΩ ±20 %).
     *
     * @param terminal {@code 'L'} for wiper-to-RL, {@code 'H'} for wiper-to-RH
     * @return resistance in Ω (approximate; excludes the wiper resistance)
     */
    double getResistance(String terminal = 'L') {
        if (terminal != 'L' && terminal != 'H') {
            throw new IllegalArgumentException("terminal must be 'L' or 'H'")
        }
        int c = readCode()
        return terminal == 'L' ? (double) c / MAX_CODE * R_TOTAL : (double) (MAX_CODE - c) / MAX_CODE * R_TOTAL
    }

    /**
     * Move the wiper relative to its current position (SRAM only).
     *
     * @param steps signed step count (negative to decrement)
     * @return the new wiper code, clamped to 0–127
     */
    int increment(int steps = 1) {
        int c = clampCode(readCode() + steps)
        writeCode(c)
        return c
    }

    /**
     * Write the Initial Value Register (EEPROM power-up position). Sets MODE = 0,
     * writes WR / IVR, waits for the EEPROM write (acknowledge polling, ≤ 20 ms)
     * and restores MODE = 1, also on error. The wiper moves to {@code value}
     * (default: the current wiper position). Each call counts toward the EEPROM
     * endurance (50 000 writes at 70 °C, 200 000 at 25 °C).
     *
     * @param value position 0–127 to store, or a negative value for the current wiper position
     */
    void storeWiper(int value = -1) {
        int c = value < 0 ? readCode() : clampCode(value)
        writeReg(REG_CR, CR_MODE_EEPROM)
        try {
            writeReg(REG_WR, c)
            this.@code = c
            waitReady()
        } finally {
            writeReg(REG_CR, CR_MODE_SRAM)
        }
    }

    /**
     * Select what a write to the wiper register updates (advanced;
     * {@link #storeWiper(int)} handles it).
     *
     * @param eeprom true for MODE = 0 (writes also program the IVR), false for MODE = 1 (SRAM only)
     */
    void setMode(boolean eeprom) {
        writeReg(REG_CR, eeprom ? CR_MODE_EEPROM : CR_MODE_SRAM)
    }

    /**
     * Read the MODE bit of the Control Register.
     *
     * @return true if MODE = 0 (writes reach the EEPROM)
     */
    boolean getMode() {
        return (readReg(REG_CR) & CR_MODE_SRAM) == 0
    }

    /**
     * Acknowledge polling: repeat a read of CR until the chip ACKs (it NACKs
     * while an EEPROM write is in progress), waiting 1 ms between attempts.
     *
     * @param timeoutMs maximum time to wait, in milliseconds
     * @throws IOException if the chip still NACKs after {@code timeoutMs}
     */
    void waitReady(int timeoutMs = 30) {
        for (int i = 0; i <= timeoutMs; i++) {
            try {
                readReg(REG_CR)
                return
            } catch (IOException ignored) {
                sleepMs(1L)
            }
        }
        throw new IOException('DS3502 busy: EEPROM write did not finish')
    }

    /**
     * Change the RH / RL voltages used by the conversions (no bus traffic).
     *
     * @param vHigh voltage applied to RH, in volts
     * @param vLow  voltage applied to RL, in volts
     */
    void setEndpoints(double vHigh, double vLow = 0.0d) {
        this.@vHigh = vHigh
        this.@vLow = vLow
    }
}
