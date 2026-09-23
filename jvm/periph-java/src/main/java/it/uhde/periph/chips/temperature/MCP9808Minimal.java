package it.uhde.periph.chips.temperature;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * MCP9808 ±0.5&nbsp;°C maximum accuracy digital temperature sensor (Microchip) — minimal interface.
 *
 * <p>Band-gap temperature sensor with a delta-sigma ADC, read over I²C. Registers are 16-bit,
 * big-endian, addressed through a non-incrementing Register Pointer. Eight selectable addresses
 * ({@code 0x18}–{@code 0x1F}) via the {@code A0}/{@code A1}/{@code A2} strap pins.
 *
 * <p>The constructor checks {@code MANUFACTURER_ID} ({@code 0x0054}) and the {@code DEVICE_ID}
 * byte ({@code 0x04}) and makes no register writes: the POR default (continuous conversion at
 * 0.0625&nbsp;°C resolution, Alert output disabled) already serves the primary use case.
 */
public class MCP9808Minimal {

    /** Default 7-bit I²C address ({@code A0} = {@code A1} = {@code A2} = GND). Valid range 0x18–0x1F. */
    public static final int DEFAULT_ADDRESS = 0x18;

    /** Expected {@code MANUFACTURER_ID} register value. */
    public static final int MANUFACTURER_ID = 0x0054;

    /** Expected {@code DEVICE_ID} (upper byte of {@code DEVICE_ID_REV}). */
    public static final int DEVICE_ID = 0x04;

    // Register pointers.
    protected static final int REG_CONFIG     = 0x01;
    protected static final int REG_TUPPER     = 0x02;
    protected static final int REG_TLOWER     = 0x03;
    protected static final int REG_TCRIT      = 0x04;
    protected static final int REG_TA         = 0x05;
    protected static final int REG_MFR_ID     = 0x06;
    protected static final int REG_DEVICE_ID  = 0x07;
    protected static final int REG_RESOLUTION = 0x08;

    protected final Connection connection;

    /**
     * Construct the driver and confirm the chip's identity (the revision byte is ignored).
     *
     * @param connection configured I²C connection pointing at the device (0x18–0x1F)
     * @throws IOException on bus error, or if {@code MANUFACTURER_ID}/{@code DEVICE_ID} do not match
     */
    public MCP9808Minimal(Connection connection) throws IOException {
        this.connection = connection;
        int mfr = readReg(REG_MFR_ID);
        if (mfr != MANUFACTURER_ID) {
            throw new IOException(String.format(
                    "MCP9808 not found: expected MANUFACTURER_ID 0x%04X, got 0x%04X", MANUFACTURER_ID, mfr));
        }
        int dev = readReg(REG_DEVICE_ID) >> 8;
        if (dev != DEVICE_ID) {
            throw new IOException(String.format(
                    "MCP9808 not found: expected DEVICE_ID 0x%02X, got 0x%02X", DEVICE_ID, dev));
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
     * Read the ambient temperature.
     *
     * <p>Masks off {@code TA}'s three boundary-status bits and decodes the 13-bit
     * two's-complement value (0.0625&nbsp;°C per LSB).
     *
     * @return ambient temperature in °C
     * @throws IOException on bus error
     */
    public double readTemperature() throws IOException {
        int raw = readReg(REG_TA) & 0x1FFF;
        if ((raw & 0x1000) != 0) raw -= 0x2000;
        return raw / 16.0;
    }
}
