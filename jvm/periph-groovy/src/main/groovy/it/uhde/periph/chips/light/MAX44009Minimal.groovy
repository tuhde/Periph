package it.uhde.periph.chips.light

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection

/**
 * MAX44009 — ultra-low-power ambient light sensor with ADC (Maxim / Analog
 * Devices) — minimal interface.
 *
 * <p>An on-chip photodiode with automatic ranging reports the illuminance as a
 * 12-bit floating-point value (4-bit exponent, 8-bit mantissa):
 * {@code lux = 2^E · M · 0.045}, from 0.045 lx to 188 006.4 lx. The I²C
 * address is 0x4A (A0 = GND) or 0x4B (A0 = VCC).
 *
 * <p>Construction runs the initialization sequence (interrupt off, CONFIG 0x03,
 * thresholds fully open, timer 0xFF, INT_STATUS read once) and waits 900 ms so
 * the first {@link #lux()} returns a real measurement. A
 * {@link RegisterConnection} read ends with a STOP, so the lux registers are
 * read as high, low, high and repeated once if the high byte changed in
 * between.
 */
@CompileStatic
class MAX44009Minimal {

    /** 7-bit I²C address with A0 = GND. */
    public static final int DEFAULT_ADDRESS = 0x4A

    /** 7-bit I²C address with A0 = VCC. */
    public static final int ADDRESS_A0_HIGH = 0x4B

    /** Wait after the initialization writes, in ms (tests may set it to 0). */
    public static volatile int initWaitMs = 900

    protected static final int REG_INT_STATUS = 0x00
    protected static final int REG_INT_ENABLE = 0x01
    protected static final int REG_CONFIG = 0x02
    protected static final int REG_LUX_HIGH = 0x03
    protected static final int REG_LUX_LOW = 0x04
    protected static final int REG_UPPER = 0x05
    protected static final int REG_LOWER = 0x06
    protected static final int REG_TIMER = 0x07
    protected static final int CFG_CONT = 0x80
    protected static final int CFG_MANUAL = 0x40
    protected static final int CFG_CDR = 0x08
    protected static final int CFG_TIM_MASK = 0x07
    protected static final double LUX_PER_LSB = 0.045d
    protected static final double MAX_LUX = 188006.4d

    protected final RegisterConnection connection
    protected int lastExponent

    /**
     * Construct the driver and run the initialization sequence.
     *
     * @param connection I²C register connection bound to the device (0x4A or 0x4B)
     */
    MAX44009Minimal(RegisterConnection connection) {
        this.connection = connection
        writeReg(REG_INT_ENABLE, 0x00)
        writeReg(REG_CONFIG, 0x03)
        writeReg(REG_UPPER, 0xFF)
        writeReg(REG_LOWER, 0x00)
        writeReg(REG_TIMER, 0xFF)
        readReg(REG_INT_STATUS)
        if (initWaitMs > 0) {
            try {
                Thread.sleep((long) initWaitMs)
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt()
            }
        }
    }

    protected int readReg(int reg) {
        return connection.read(reg, 1)[0] & 0xFF
    }

    protected void writeReg(int reg, int value) {
        connection.write(reg, [(byte) value] as byte[])
    }

    /** Read LUX_HIGH, LUX_LOW and LUX_HIGH again; repeat once if the high byte changed. Returns {exponent, mantissa}. */
    protected int[] readLuxRegs() {
        int high = readReg(REG_LUX_HIGH)
        int low = readReg(REG_LUX_LOW)
        int high2 = readReg(REG_LUX_HIGH)
        if (high2 != high) {
            high = high2
            low = readReg(REG_LUX_LOW)
        }
        int exponent = high >> 4
        int mantissa = ((high & 0x0F) << 4) | (low & 0x0F)
        this.@lastExponent = exponent
        return [exponent, mantissa] as int[]
    }

    protected static double luxFrom(int exponent, int mantissa) {
        if (exponent >= 15) return MAX_LUX
        return (double) (1 << exponent) * mantissa * LUX_PER_LSB
    }

    /**
     * Read the ambient illuminance.
     *
     * @return illuminance in lux ({@code 2^E · M · 0.045}); the maximum 188006.4 lx on overrange (E = 15)
     */
    double lux() {
        int[] r = readLuxRegs()
        return luxFrom(r[0], r[1])
    }
}
