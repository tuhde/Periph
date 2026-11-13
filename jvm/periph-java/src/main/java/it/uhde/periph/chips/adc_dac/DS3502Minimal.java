package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * DS3502 — 7-bit nonvolatile high-voltage I²C digital potentiometer (Maxim) —
 * minimal interface.
 *
 * <p>A single 128-position potentiometer (10 kΩ end to end, ±20 %) whose
 * terminals RH / RL / RW accept up to 15.5 V (V+ = 4.5–15.5 V) while the logic
 * runs from VCC = 2.5–5.5 V. The wiper position lives in the Wiper Register
 * (WR, SRAM); the Initial Value Register (IVR, EEPROM) holds the power-up
 * position (factory default 0x40). The I²C address is 0x28 (A1 = A0 = GND) to
 * 0x2B (A1 = A0 = VCC).
 *
 * <p>Two registers exist: {@code 0x00} is WR (and, depending on the MODE bit of
 * CR, also IVR; reads always return WR) and {@code 0x02} is the Control
 * Register whose bit 7 selects MODE (1 = writes to {@code 0x00} only change WR).
 * The constructor runs the initialization sequence (CR = 0x80 with one retry
 * after 3 ms, then reads WR), so routine wiper writes never wear the EEPROM.
 */
public class DS3502Minimal {

    /** 7-bit I²C address with A1 = A0 = GND. */
    public static final int DEFAULT_ADDRESS = 0x28;

    protected static final int REG_WR = 0x00;
    protected static final int REG_CR = 0x02;
    protected static final int CR_MODE_SRAM = 0x80;
    protected static final int CR_MODE_EEPROM = 0x00;
    protected static final int MAX_CODE = 127;

    protected final RegisterConnection connection;
    protected double vHigh;
    protected double vLow;
    protected int code;

    /**
     * Construct the driver with RH = 1.0 V and RL = 0 V (voltages are ratios)
     * and run the initialization sequence.
     *
     * @param connection I²C register connection bound to the device (0x28–0x2B)
     * @throws IOException on bus error
     */
    public DS3502Minimal(RegisterConnection connection) throws IOException {
        this(connection, 1.0, 0.0);
    }

    /**
     * Construct the driver and run the initialization sequence: write
     * CR ← 0x80 (MODE = 1), retrying once after 3 ms if the chip NACKs because
     * it is still in its power-up recall, then read WR. The wiper is left where
     * the IVR put it.
     *
     * @param connection I²C register connection bound to the device (0x28–0x2B)
     * @param vHigh      voltage applied to RH, in volts
     * @param vLow       voltage applied to RL, in volts
     * @throws IOException on bus error
     */
    public DS3502Minimal(RegisterConnection connection, double vHigh, double vLow) throws IOException {
        this.connection = connection;
        this.vHigh = vHigh;
        this.vLow = vLow;
        try {
            writeReg(REG_CR, CR_MODE_SRAM);
        } catch (IOException e) {
            sleepMs(3);
            writeReg(REG_CR, CR_MODE_SRAM);
        }
        readCode();
    }

    protected static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected static int clampCode(int code) {
        return Math.max(0, Math.min(MAX_CODE, code));
    }

    protected int readReg(int reg) throws IOException {
        return connection.read(reg, 1)[0] & 0xFF;
    }

    protected void writeReg(int reg, int value) throws IOException {
        connection.write(reg, new byte[]{(byte) value});
    }

    protected void writeCode(int value) throws IOException {
        int c = clampCode(value);
        writeReg(REG_WR, c);
        code = c;
    }

    protected int readCode() throws IOException {
        code = readReg(REG_WR) & MAX_CODE;
        return code;
    }

    /**
     * Set the wiper position (volatile, SRAM only).
     *
     * @param value wiper position 0–127 (clamped); 0 = at RL, 127 = at RH
     * @throws IOException on bus error
     */
    public void setWiper(int value) throws IOException {
        writeCode(value);
    }

    /**
     * Read back the wiper position (WR).
     *
     * @return wiper position 0–127
     * @throws IOException on bus error
     */
    public int getWiper() throws IOException {
        return readCode();
    }

    /**
     * Set the unloaded RW output voltage to the nearest reachable code.
     *
     * @param volts target voltage in volts (clamped between vLow and vHigh)
     * @throws IOException on bus error
     */
    public void setVoltage(double volts) throws IOException {
        double span = vHigh - vLow;
        if (span == 0.0) {
            writeCode(0);
            return;
        }
        writeCode((int) Math.round((volts - vLow) / span * MAX_CODE));
    }

    /**
     * Read the unloaded RW voltage for the current wiper code.
     *
     * @return voltage in volts, {@code vLow + (vHigh - vLow) * code / 127}
     * @throws IOException on bus error
     */
    public double getVoltage() throws IOException {
        return vLow + (vHigh - vLow) * readCode() / MAX_CODE;
    }
}
