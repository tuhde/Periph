package it.uhde.periph.chips.pressure;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * MPL115A2 — miniature I²C digital barometer, 50–115 kPa (minimal driver).
 *
 * <p>Reads the compensated absolute pressure in pascals and an approximate temperature in degrees
 * Celsius. The four per-device compensation coefficients (a0, b1, b2, c12) are read once at
 * construction and cached; each reading writes CONVERT, waits 5 ms and reads the ADC registers.
 *
 * <p>Fixed I²C address: 0x60. The chip has no configuration registers.
 */
public class MPL115A2Minimal {

    protected static final int REG_PADC    = 0x00;
    protected static final int REG_COEF    = 0x04;
    protected static final int REG_CONVERT = 0x12;

    protected final RegisterConnection connection;

    protected double a0, b1, b2, c12;

    /**
     * Construct the driver and read the coefficient block.
     *
     * @param connection I²C connection bound to address 0x60
     * @throws IOException on I²C error
     */
    public MPL115A2Minimal(RegisterConnection connection) throws IOException {
        this.connection = connection;
        loadCoefficients();
    }

    /**
     * Read the eight coefficient bytes and cache a0, b1, b2, c12.
     *
     * @throws IOException on I²C error
     */
    protected void loadCoefficients() throws IOException {
        byte[] d = connection.read(REG_COEF, 8);
        a0  = (short) (((d[0] & 0xFF) << 8) | (d[1] & 0xFF)) / 8.0;
        b1  = (short) (((d[2] & 0xFF) << 8) | (d[3] & 0xFF)) / 8192.0;
        b2  = (short) (((d[4] & 0xFF) << 8) | (d[5] & 0xFF)) / 16384.0;
        c12 = (short) (((d[6] & 0xFF) << 8) | (d[7] & 0xFF)) / 16777216.0;
    }

    /**
     * Run one conversion and return the raw ADC counts.
     *
     * @return {@code {padc, tadc}}, each 0–1023
     * @throws IOException on I²C error
     */
    protected int[] convert() throws IOException {
        connection.write(REG_CONVERT, new byte[]{0x00});
        try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        byte[] d = connection.read(REG_PADC, 4);
        int padc = (((d[0] & 0xFF) << 8) | (d[1] & 0xFF)) >> 6;
        int tadc = (((d[2] & 0xFF) << 8) | (d[3] & 0xFF)) >> 6;
        return new int[]{padc, tadc};
    }

    /**
     * Compute the pressure from raw counts and the cached coefficients (no bus access).
     *
     * @param padc 10-bit pressure ADC count
     * @param tadc 10-bit temperature ADC count
     * @return pressure in Pa
     */
    protected double compute(int padc, int tadc) {
        double pcomp = a0 + (b1 + c12 * tadc) * padc + b2 * tadc;
        return (pcomp * (115.0 - 50.0) / 1023.0 + 50.0) * 1000.0;
    }

    /**
     * Approximate temperature from the temperature count: {@code 25 + (Tadc − 498) / −5.35}.
     *
     * @param tadc 10-bit temperature ADC count
     * @return temperature in °C (approximate)
     */
    protected static double temperatureC(int tadc) {
        return 25.0 + (tadc - 498) / -5.35;
    }

    /**
     * Run one conversion and return the compensated pressure.
     *
     * @return pressure in Pa
     * @throws IOException on I²C error
     */
    public double readPressure() throws IOException {
        int[] raw = convert();
        return compute(raw[0], raw[1]);
    }

    /**
     * Run one conversion and return an approximate temperature (the datasheet gives no formula;
     * accurate to a few degrees — use for trends only).
     *
     * @return temperature in °C (approximate)
     * @throws IOException on I²C error
     */
    public double readTemperature() throws IOException {
        return temperatureC(convert()[1]);
    }
}
