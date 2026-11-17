package it.uhde.periph.chips.power

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection

/**
 * LTC4151 — 7–80 V high-side current and voltage monitor (minimal driver).
 *
 * <p>A 12-bit ΔΣ ADC scans the sense voltage (20 µV/LSB), VIN (25 mV/LSB) and ADIN
 * (0.5 mV/LSB) continuously after power-up, so no configuration is needed beyond the
 * sense resistor and the constructor makes no bus access.
 *
 * <p>I²C address: 0x67–0x6F (ADR1/ADR0 three-state pins; 0x6A with both open).
 *
 * <h2>Configuration (chip power-on state, not rewritten)</h2>
 * <ul>
 *   <li>CONTROL = 0x0C: continuous scan, page read/write and stuck-bus timer enabled</li>
 *   <li>Sense resistor: 20 mΩ unless given (full-scale current 4.096 A)</li>
 * </ul>
 */
@CompileStatic
class Ltc4151Minimal {

    // --- Register addresses ---
    protected static final int REG_SENSE   = 0x00
    protected static final int REG_VIN     = 0x02
    protected static final int REG_ADIN    = 0x04
    protected static final int REG_CONTROL = 0x06

    protected static final double LSB_SENSE = 20e-6d
    protected static final double LSB_VIN   = 0.025d
    protected static final double LSB_ADIN  = 0.0005d

    protected final RegisterConnection connection
    protected double rSenseOhms
    protected boolean pageEnabled = true

    /**
     * Construct the driver with the default 20 mΩ sense resistor. Makes no bus access.
     *
     * @param connection I²C connection bound to the LTC4151 device address
     */
    Ltc4151Minimal(RegisterConnection connection) {
        this(connection, 0.02d)
    }

    /**
     * Construct the driver. Makes no bus access.
     *
     * @param connection I²C connection bound to the LTC4151 device address
     * @param rSense     sense resistor in ohms; must be greater than 0
     */
    Ltc4151Minimal(RegisterConnection connection, double rSense) {
        if (!(rSense > 0d)) throw new IllegalArgumentException('rSense must be > 0')
        this.connection = connection
        this.rSenseOhms = rSense
    }

    /**
     * Read the supply voltage at VIN.
     *
     * @return VIN in V (code × 25 mV)
     */
    double voltage() {
        code(readBlock(REG_VIN, 2), 0) * LSB_VIN
    }

    /**
     * Read the load current.
     *
     * @return current in A (sense voltage / rSense)
     */
    double current() {
        code(readBlock(REG_SENSE, 2), 0) * LSB_SENSE / rSenseOhms
    }

    /**
     * Read the load power from one consistent block read of SENSE and VIN.
     *
     * @return power in W (VIN × current)
     */
    double power() {
        byte[] d = readBlock(REG_SENSE, 4)
        code(d, 2) * LSB_VIN * (code(d, 0) * LSB_SENSE / rSenseOhms)
    }

    // ---- low-level helpers ----

    /**
     * Extract a 12-bit code (8 MSBs then 4 LSBs in bits 7:4) from a buffer.
     *
     * @param d      buffer
     * @param offset index of the high byte
     * @return 12-bit code, 0–4095
     */
    protected static int code(byte[] d, int offset) {
        ((d[offset] & 0xFF) << 4) | ((d[offset + 1] & 0xFF) >> 4)
    }

    /**
     * Read consecutive registers; single-register reads when page read/write is disabled.
     *
     * @param reg    first register
     * @param length number of bytes
     * @return the bytes read
     */
    protected byte[] readBlock(int reg, int length) {
        if (pageEnabled || length == 1) return connection.read(reg, length)
        byte[] out = new byte[length]
        for (int i = 0; i < length; i++) out[i] = connection.read(reg + i, 1)[0]
        out
    }

    /**
     * Read the raw CONTROL register.
     *
     * @return raw CONTROL value
     */
    protected int readControlRaw() {
        connection.read(REG_CONTROL, 1)[0] & 0xFF
    }

    /**
     * Write the raw CONTROL register.
     *
     * @param value raw value
     */
    protected void writeControlRaw(int value) {
        connection.write(REG_CONTROL, [(byte) value] as byte[])
    }
}
