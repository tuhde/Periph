package it.uhde.periph.chips.temperature;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * TMP117 ±0.1&nbsp;°C high-accuracy, low-power digital temperature sensor (Texas Instruments) —
 * minimal interface.
 *
 * <p>NIST-traceable 16-bit temperature sensor (0.0078125&nbsp;°C per LSB) read over an
 * I²C/SMBus-compatible bus. Registers are 16-bit, big-endian, addressed through a
 * non-incrementing Register Pointer. Four selectable addresses ({@code 0x48}–{@code 0x4B}) via
 * the 4-level {@code ADD0} strap.
 *
 * <p>The constructor checks {@code DEVICE_ID} bits 11:0 ({@code 0x117}) and makes no register
 * writes: the POR/EEPROM default (continuous conversion, 8-conversion averaging, 1&nbsp;s cycle,
 * Alert mode) already serves the primary use case.
 */
public class TMP117Minimal {

    /** Default 7-bit I²C address ({@code ADD0} = GND). Valid range 0x48–0x4B. */
    public static final int DEFAULT_ADDRESS = 0x48;

    /** Expected {@code DEVICE_ID} bits 11:0 (bits 15:12 are the silicon revision). */
    public static final int DEVICE_ID = 0x117;

    // Register pointers.
    protected static final int REG_TEMP_RESULT = 0x00;
    protected static final int REG_CONFIG      = 0x01;
    protected static final int REG_THIGH       = 0x02;
    protected static final int REG_TLOW        = 0x03;
    protected static final int REG_EEPROM_UL   = 0x04;
    protected static final int REG_EEPROM1     = 0x05;
    protected static final int REG_EEPROM2     = 0x06;
    protected static final int REG_TEMP_OFFSET = 0x07;
    protected static final int REG_EEPROM3     = 0x08;
    protected static final int REG_DEVICE_ID   = 0x0F;

    /** Temperature LSB in °C, shared by TEMP_RESULT, the limits and TEMP_OFFSET. */
    protected static final double LSB_C = 0.0078125;

    protected final Connection connection;

    /**
     * Construct the driver and confirm the chip's identity (the revision nibble is ignored).
     *
     * @param connection configured I²C connection pointing at the device (0x48–0x4B)
     * @throws IOException on bus error, or if {@code DEVICE_ID} does not match
     */
    public TMP117Minimal(Connection connection) throws IOException {
        this.connection = connection;
        int did = readReg(REG_DEVICE_ID) & 0x0FFF;
        if (did != DEVICE_ID) {
            throw new IOException(String.format(
                    "TMP117 not found: expected DEVICE_ID 0x%03X, got 0x%03X", DEVICE_ID, did));
        }
    }

    /**
     * Read a 16-bit big-endian register.
     *
     * @param reg register pointer
     * @return unsigned register value
     * @throws IOException on bus error
     */
    protected int readReg(int reg) throws IOException {
        byte[] b = connection.writeRead(new byte[]{(byte) reg}, 2);
        return ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
    }

    /**
     * Write a 16-bit big-endian register.
     *
     * @param reg   register pointer
     * @param value 16-bit value
     * @throws IOException on bus error
     */
    protected void writeReg(int reg, int value) throws IOException {
        connection.write(new byte[]{(byte) reg, (byte) (value >> 8), (byte) value});
    }

    /**
     * Decode a 16-bit two's-complement temperature register (0.0078125&nbsp;°C per LSB).
     *
     * @param raw unsigned 16-bit register value
     * @return temperature in °C
     */
    protected static double decodeTemperature(int raw) {
        return (short) raw * LSB_C;
    }

    /**
     * Read the temperature.
     *
     * <p>Decodes {@code TEMP_RESULT}'s 16-bit two's-complement value (0.0078125&nbsp;°C per LSB).
     * Returns −256.0 until the first conversion after power-up completes.
     *
     * @return temperature in °C
     * @throws IOException on bus error
     */
    public double readTemperature() throws IOException {
        return decodeTemperature(readReg(REG_TEMP_RESULT));
    }
}
