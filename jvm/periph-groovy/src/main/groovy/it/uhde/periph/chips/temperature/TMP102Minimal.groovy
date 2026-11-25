package it.uhde.periph.chips.temperature

import groovy.transform.CompileStatic

import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection

import java.io.IOException

/**
 * TMP102 12-bit digital temperature sensor with ALERT output (Texas Instruments) — minimal interface.
 *
 * <p>Two-wire temperature sensor (0.0625&nbsp;°C per LSB, ±0.5&nbsp;°C typical) read over I²C.
 * Registers are 16-bit, big-endian, addressed through a persistent Register Pointer. Four
 * selectable addresses ({@code 0x48}–{@code 0x4B}) via the {@code ADD0} strap.
 *
 * <p>The constructor makes no bus access: the power-on default (continuous conversion at 4&nbsp;Hz,
 * comparator mode, 12-bit) already serves the primary use case.
 */
@CompileStatic
class TMP102Minimal {

    /** Default 7-bit I²C address ({@code ADD0} = GND). Valid range 0x48–0x4B. */
    public static final int DEFAULT_ADDRESS = 0x48
    // Register pointers.
    protected static final int REG_TEMPERATURE = 0x00
    protected static final int REG_CONFIG      = 0x01
    protected static final int REG_T_LOW       = 0x02
    protected static final int REG_T_HIGH      = 0x03
    protected static final double LSB_C = 0.0625
    protected final RegisterConnection connection
    /**
     * Construct the driver (no bus access).
     *
     * @param connection configured I²C connection pointing at the device (0x48–0x4B)
     */
    TMP102Minimal(RegisterConnection connection) {
        this.connection = connection
    }

    /**
     * Read a 16-bit big-endian register.
     *
     * @param reg register pointer
     * @return unsigned register value
     * @throws IOException on bus error
     */
    protected int readReg(int reg) throws IOException {
        byte[] b = connection.read(reg, 2)
        return ((b[0] & 0xFF) << 8) | (b[1] & 0xFF)
    }

    /**
     * Write a 16-bit big-endian register.
     *
     * @param reg   register pointer
     * @param value 16-bit value
     * @throws IOException on bus error
     */
    protected void writeReg(int reg, int value) throws IOException {
        connection.write(reg, [(byte) (value >> 8), (byte) value] as byte[])
    }

    /**
     * Decode a TEMPERATURE register value; bit 0 = 1 marks the 13-bit extended format.
     *
     * @param raw16 unsigned 16-bit register value
     * @return signed count (0.0625&nbsp;°C per LSB)
     */
    protected static int decodeRaw(int raw16) {
        if ((raw16 & 0x0001) != 0) return Register.toSigned(raw16 >> 3, 13)
        return Register.toSigned(raw16 >> 4, 12)
    }

    /**
     * Read the temperature.
     *
     * <p>Decodes the 12-bit or, in extended mode, 13-bit two's-complement value; the format is
     * taken from bit 0 of the result.
     *
     * @return temperature in °C
     * @throws IOException on bus error
     */
    double readTemperature() throws IOException {
        return decodeRaw(readReg(REG_TEMPERATURE)) * LSB_C
    }
}
