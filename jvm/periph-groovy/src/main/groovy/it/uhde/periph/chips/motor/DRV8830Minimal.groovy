package it.uhde.periph.chips.motor

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * DRV8830 — low-voltage motor driver with I²C interface (Texas Instruments) —
 * minimal interface.
 *
 * <p>H-bridge driver for a single brushed DC motor, controlled entirely over
 * I²C. The host commands a target output <em>voltage</em>; the chip
 * PWM-regulates the bridge to hold that average voltage regardless of supply
 * sag. Nine selectable addresses (0x60–0x68) via the tri-state A0/A1 straps.
 *
 * <p>Conversion follows the datasheet's Table 1:
 * {@code voltage = VREF * vset / 16}, usable 0.48 V ({@code vset} 6) to
 * 5.06 V ({@code vset} 63).
 */
@CompileStatic
class DRV8830Minimal {

    /** Default I²C address (A0 = A1 = GND). Valid range 0x60–0x68. */
    public static final int DEFAULT_ADDRESS = 0x60

    /** Internal reference voltage in V, typical (datasheet: 1.235–1.335 V). */
    public static final double VREF = 1.285

    /** Lowest valid {@code VSET} code (0x00–0x05 are reserved). */
    public static final int VSET_MIN = 6

    /** Highest {@code VSET} code (≈5.06 V). */
    public static final int VSET_MAX = 63

    // Register map.
    protected static final int REG_CONTROL = 0x00
    protected static final int REG_FAULT   = 0x01

    // CONTROL (0x00) bits.
    protected static final int CTRL_IN1 = 0x01
    protected static final int CTRL_IN2 = 0x02

    // FAULT (0x01) bits.
    protected static final int FAULT_FAULT  = 0x01
    protected static final int FAULT_OCP    = 0x02
    protected static final int FAULT_UVLO   = 0x04
    protected static final int FAULT_OTS    = 0x08
    protected static final int FAULT_ILIMIT = 0x10
    protected static final int FAULT_CLEAR  = 0x80

    protected final Connection connection

    /**
     * Construct the driver. Confirms the device answers on the bus by reading
     * the {@code CONTROL} register (the DRV8830 has no identity register)
     * makes no register writes — the POR default already leaves the motor in
     * standby/coast.
     *
     * @param connection configured I²C connection bound to the device (0x60–0x68)
     */
    public DRV8830Minimal(Connection connection) {
        this.connection = connection
        readReg(REG_CONTROL)
    }

    protected int readReg(int reg) {
        return connection.writeRead(new byte[]{(byte) reg}, 1)[0] & 0xFF
    }

    protected void writeReg(int reg, int value) {
        connection.write(new byte[]{(byte) reg, (byte) value})
    }

    /** Map |voltage| to a {@code VSET} code; 0 means coast (below the floor). */
    protected static int voltageToVset(double voltage) {
        int vset = (int) (Math.abs(voltage) * 16.0 / VREF + 0.5)
        if (vset < VSET_MIN) return 0
        return Math.min(vset, VSET_MAX)
    }

    /** {@code VSET} code to volts; 0 for a reserved code. */
    protected static double vsetToVoltage(int vset) {
        if (vset < VSET_MIN) return 0.0
        return VREF * vset / 16.0
    }

    /**
     * Drive the motor at a regulated output voltage. Writes {@code VSET} and
     * {@code IN1}/{@code IN2} together in one {@code CONTROL} write; a
     * magnitude below the ~0.48 V floor coasts, above ~5.06 V it is clamped.
     *
     * @param voltage signed target voltage in V: positive = forward, negative = reverse, 0 = coast
     */
    public void drive(double voltage) {
        int vset = voltageToVset(voltage)
        if (vset == 0) {
            writeReg(REG_CONTROL, 0x00)
        } else if (voltage > 0) {
            writeReg(REG_CONTROL, (vset << 2) | CTRL_IN1)
        } else {
            writeReg(REG_CONTROL, (vset << 2) | CTRL_IN2)
        }
    }

    /**
     * Short-brake the motor ({@code IN1} = {@code IN2} = 1, both outputs high).
     *
     */
    public void brake() {
        writeReg(REG_CONTROL, CTRL_IN1 | CTRL_IN2)
    }

    /**
     * Put the bridge in standby/coast ({@code IN1} = {@code IN2} = 0) — same
     * as {@code drive(0.0)}.
     *
     */
    public void stop() {
        writeReg(REG_CONTROL, 0x00)
    }
}
