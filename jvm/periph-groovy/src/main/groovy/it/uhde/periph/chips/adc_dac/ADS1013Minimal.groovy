package it.uhde.periph.chips.adc_dac

import groovy.transform.CompileStatic

import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection

import java.io.IOException

/**
 * ADS1013 12-bit I²C ADC with a fixed ±2.048&nbsp;V range (Texas Instruments) — minimal
 * interface.
 *
 * <p>Smallest member of the ADS101x family: one input ({@code AIN0} − {@code AIN1}), no PGA, no
 * multiplexer, no comparator and no {@code ALERT/RDY} pin. Registers are 16-bit, big-endian,
 * addressed through a persistent Address Pointer. Four selectable addresses ({@code 0x48}–
 * {@code 0x4B}) via the {@code ADDR} strap. The chip has no ID register, so the constructor
 * performs no I²C traffic.
 *
 * <p>Defaults: 1600&nbsp;SPS, single-shot. The whole Config word is written for every conversion.
 */
@CompileStatic
class ADS1013Minimal {

    /** Default 7-bit I²C address ({@code ADDR} = GND). Valid range 0x48–0x4B. */
    public static final int DEFAULT_ADDRESS = 0x48

    // Register pointers.
    protected static final int REG_CONVERSION = 0x00
    protected static final int REG_CONFIG     = 0x01

    protected static final int CFG_OS          = 0x8000
    protected static final int CFG_MODE_SINGLE = 0x0100
    // Reserved bits written at their reset values: 000010 in 14:9, 00011 in 4:0.
    protected static final int CFG_RESERVED    = 0x0403

    protected static final int[] SPS = [128, 250, 490, 920, 1600, 2400, 3300] as int[]
    protected static final double LSB = 0.001

    protected final RegisterConnection connection

    protected int dr = 4
    protected boolean continuous = false

    /**
     * Construct the driver. Performs no I²C traffic.
     *
     * @param connection configured I²C connection pointing at the device (0x48–0x4B)
     */
    ADS1013Minimal(RegisterConnection connection) {
        this.connection = connection
    }

    /**
     * Read a 16-bit big-endian register.
     *
     * @param reg register pointer
     * @return unsigned register value
     * @throws IOException on bus error
     */
    protected int readReg(int reg) {
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
    protected void writeReg(int reg, int value) {
        connection.write(reg, [(byte) (value >> 8), (byte) value] as byte[])
    }

    /**
     * Build the whole 16-bit Config word from the cached settings.
     *
     * @param os         value of the {@code OS} bit (start a conversion)
     * @param continuous {@code true} for continuous mode ({@code MODE} = 0)
     * @return Config register value
     */
    protected int configWord(boolean os, boolean continuous) {
        int word = CFG_RESERVED | (dr << 5)
        if (os) word |= CFG_OS
        if (!continuous) word |= CFG_MODE_SINGLE
        return word
    }

    /**
     * Read the CONVERSION register as a signed 12-bit code.
     *
     * @return code, −2048…2047
     * @throws IOException on bus error
     */
    protected int readConversion() {
        return Register.toSigned(readReg(REG_CONVERSION), 16) >> 4
    }

    /**
     * Run one single-shot conversion of {@code AIN0} − {@code AIN1}.
     *
     * <p>Writes the whole Config word with {@code OS} = 1, polls {@code OS} until the chip is idle
     * again, then reads {@code CONVERSION}.
     *
     * @return 12-bit two's-complement code, −2048…2047
     * @throws IOException on bus error
     */
    int readRaw() {
        writeReg(REG_CONFIG, configWord(true, false))
        continuous = false
        for (int i = 0; i < 100; i++) {
            if ((readReg(REG_CONFIG) & CFG_OS) != 0) break
            try {
                Thread.sleep(1)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return readConversion()
    }

    /**
     * Run one single-shot conversion and convert it to volts.
     *
     * @return voltage {@code AIN0} − {@code AIN1} in volts ({@code raw × 0.001})
     * @throws IOException on bus error
     */
    double readVoltage() {
        return readRaw() * LSB
    }
}
