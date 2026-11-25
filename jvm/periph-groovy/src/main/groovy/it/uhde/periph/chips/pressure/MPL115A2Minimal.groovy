package it.uhde.periph.chips.pressure

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection

/**
 * MPL115A2 — miniature I²C digital barometer, 50–115 kPa (minimal driver).
 *
 * <p>Reads the compensated absolute pressure in pascals and an approximate temperature in degrees
 * Celsius. The four per-device compensation coefficients (a0, b1, b2, c12) are read once at
 * construction and cached; each reading writes CONVERT, waits 5 ms and reads the ADC registers.
 *
 * <p>Fixed I²C address: 0x60. The chip has no configuration registers.
 */
@CompileStatic
class MPL115A2Minimal {

    static final int REG_PADC    = 0x00
    static final int REG_COEF    = 0x04
    static final int REG_CONVERT = 0x12

    protected final RegisterConnection connection

    protected double a0, b1, b2, c12

    /**
     * Construct the driver and read the coefficient block.
     *
     * @param connection I²C connection bound to address 0x60
     */
    MPL115A2Minimal(RegisterConnection connection) {
        this.connection = connection
        loadCoefficients()
    }

    /** Read the eight coefficient bytes and cache a0, b1, b2, c12. */
    protected void loadCoefficients() {
        byte[] d = connection.read(REG_COEF, 8)
        a0  = (short) (((d[0] & 0xFF) << 8) | (d[1] & 0xFF)) / 8.0d
        b1  = (short) (((d[2] & 0xFF) << 8) | (d[3] & 0xFF)) / 8192.0d
        b2  = (short) (((d[4] & 0xFF) << 8) | (d[5] & 0xFF)) / 16384.0d
        c12 = (short) (((d[6] & 0xFF) << 8) | (d[7] & 0xFF)) / 16777216.0d
    }

    /** Run one conversion and return the raw ADC counts {padc, tadc}, each 0–1023. */
    protected int[] convert() {
        connection.write(REG_CONVERT, [(byte) 0x00] as byte[])
        Thread.sleep(5)
        byte[] d = connection.read(REG_PADC, 4)
        int padc = (((d[0] & 0xFF) << 8) | (d[1] & 0xFF)) >> 6
        int tadc = (((d[2] & 0xFF) << 8) | (d[3] & 0xFF)) >> 6
        return [padc, tadc] as int[]
    }

    /**
     * Compute the pressure from raw counts and the cached coefficients (no bus access).
     *
     * @param padc 10-bit pressure ADC count
     * @param tadc 10-bit temperature ADC count
     * @return pressure in Pa
     */
    protected double compute(int padc, int tadc) {
        double pcomp = a0 + (b1 + c12 * (double) tadc) * (double) padc + b2 * (double) tadc
        return (pcomp * (115.0d - 50.0d) / 1023.0d + 50.0d) * 1000.0d
    }

    /** Approximate temperature from the temperature count: {@code 25 + (Tadc − 498) / −5.35}. */
    protected static double temperatureC(int tadc) {
        return 25.0d + ((double) (tadc - 498)) / -5.35d
    }

    /**
     * Run one conversion and return the compensated pressure.
     *
     * @return pressure in Pa
     */
    double readPressure() {
        int[] raw = convert()
        return compute(raw[0], raw[1])
    }

    /**
     * Run one conversion and return an approximate temperature (the datasheet gives no formula;
     * accurate to a few degrees — use for trends only).
     *
     * @return temperature in °C (approximate)
     */
    double readTemperature() {
        return temperatureC(convert()[1])
    }
}
