package it.uhde.periph.chips.adc_dac

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * CAT5171 — 256-position I²C digital potentiometer (ON Semiconductor) —
 * minimal interface.
 *
 * <p>A single 3-terminal potentiometer (A, W, B) usable as a voltage divider
 * or 2-terminal rheostat. The chip has no register map: a write is
 * {@code [instruction, data]} where the instruction byte carries the
 * midscale-reset flag ({@code RS}, bit 6) and the shutdown flag
 * ({@code SD}, bit 5); a read is a bare one-byte read returning the wiper
 * register. The driver therefore uses the plain {@link Connection}
 * {@code write} / {@code read} calls. The I²C address is 0x2C (AD0 = GND) or
 * 0x2D (AD0 = VDD). The wiper is volatile (power-up midscale 0x80).
 *
 * <p>Resistances follow {@code R_WB = D/256 · RAB + RW}.
 */
@CompileStatic
class CAT5171Minimal {

    /** 7-bit I²C address with AD0 = GND. */
    public static final int DEFAULT_ADDRESS = 0x2C

    /** 7-bit I²C address with AD0 = VDD. */
    public static final int ADDRESS_AD0_HIGH = 0x2D

    /** Default end-to-end resistance RAB in Ω (100 kΩ part). */
    public static final double DEFAULT_RAB = 100000.0d

    /** Default wiper resistance RW in Ω (typical, 5 V). */
    public static final double DEFAULT_RW = 50.0d

    protected static final int INSTR_RS = 0x40
    protected static final int INSTR_SD = 0x20
    protected static final int MIDSCALE = 0x80

    protected final Connection connection
    protected double rAbOhms
    protected double rWOhms
    protected int wiperCode = MIDSCALE
    protected boolean shut = false

    /**
     * Construct the driver with the default 100 kΩ / 50 Ω resistance model.
     * Performs no bus traffic.
     *
     * @param connection configured I²C connection bound to the device (0x2C or 0x2D)
     */
    CAT5171Minimal(Connection connection) {
        this(connection, DEFAULT_RAB, DEFAULT_RW)
    }

    /**
     * Construct the driver. Performs no bus traffic (the chip has no identity
     * register and needs no initialisation).
     *
     * @param connection configured I²C connection bound to the device (0x2C or 0x2D)
     * @param rAb        end-to-end resistance RAB in Ω
     * @param rWiper     wiper resistance RW in Ω
     */
    CAT5171Minimal(Connection connection, double rAb, double rWiper) {
        this.connection = connection
        this.rAbOhms = rAb
        this.rWOhms = rWiper
    }

    protected static int clampCode(int code) {
        return Math.max(0, Math.min(255, code))
    }

    protected void writeInstr(int instruction, int value) {
        connection.write(new byte[]{(byte) instruction, (byte) value})
    }

    protected void writeCode(int value) {
        int c = clampCode(value)
        writeInstr(shut ? INSTR_SD : 0, c)
        wiperCode = c
    }

    protected int readCode() {
        int c = connection.read(1)[0] & 0xFF
        wiperCode = c
        return c
    }

    /**
     * Set the wiper position.
     *
     * @param value wiper position 0–255 (clamped)
     */
    void setWiper(int value) {
        writeCode(value)
    }

    /**
     * Read back the wiper position with a single read transaction.
     *
     * @return wiper position 0–255
     */
    int getWiper() {
        return readCode()
    }

    /**
     * Set the W–B resistance to the nearest reachable code.
     *
     * @param ohms target W–B resistance in Ω (clamped to the reachable range)
     */
    void setResistance(double ohms) {
        writeCode((int) Math.floor((ohms - rWOhms) / rAbOhms * 256.0d + 0.5d))
    }

    /**
     * Read the W–B resistance for the current wiper code.
     *
     * @return resistance in Ω, {@code D/256 · RAB + RW}
     */
    double getResistance() {
        return readCode() / 256.0d * rAbOhms + rWOhms
    }
}
