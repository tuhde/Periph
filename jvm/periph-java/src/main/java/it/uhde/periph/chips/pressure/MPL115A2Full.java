package it.uhde.periph.chips.pressure;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * MPL115A2 — full driver. Extends {@link MPL115A2Minimal} with a combined reading, raw ADC counts,
 * coefficient access, the pure compensation function, hPa and altitude helpers.
 *
 * <h2>Altitude formula</h2>
 * {@code altitude_m = 44330.0 × (1.0 − (pressure_Pa / seaLevelPa)^(1/5.255))}
 */
public class MPL115A2Full extends MPL115A2Minimal {

    /** Sea-level pressure in Pa used when none is supplied. */
    private static final double DEFAULT_SEA_LEVEL_PA = 101325.0;

    /**
     * Construct the full driver and read the coefficient block.
     *
     * @param connection I²C connection bound to address 0x60
     * @throws IOException on I²C error
     */
    public MPL115A2Full(RegisterConnection connection) throws IOException {
        super(connection);
    }

    /**
     * Run one conversion and return both values.
     *
     * @return {@code {pressure_pa, temperature_c}}
     * @throws IOException on I²C error
     */
    public double[] read() throws IOException {
        int[] raw = convert();
        return new double[]{compute(raw[0], raw[1]), temperatureC(raw[1])};
    }

    /**
     * Run one conversion and return the raw 10-bit ADC counts.
     *
     * @return {@code {padc, tadc}}, each 0–1023
     * @throws IOException on I²C error
     */
    public int[] readRaw() throws IOException {
        return convert();
    }

    /**
     * Return the cached compensation coefficients.
     *
     * @return {@code {a0, b1, b2, c12}}
     */
    public double[] coefficients() {
        return new double[]{a0, b1, b2, c12};
    }

    /**
     * Re-read the coefficient block (e.g. after a power cycle).
     *
     * @throws IOException on I²C error
     */
    public void reloadCoefficients() throws IOException {
        loadCoefficients();
    }

    /**
     * Compute pressure from raw counts and the cached coefficients (no bus access).
     *
     * @param padc 10-bit pressure ADC count
     * @param tadc 10-bit temperature ADC count
     * @return pressure in Pa
     */
    public double compensate(int padc, int tadc) {
        return compute(padc, tadc);
    }

    /**
     * Run one conversion and return the pressure in hectopascals.
     *
     * @return pressure in hPa
     * @throws IOException on I²C error
     */
    public double pressureHpa() throws IOException {
        return readPressure() / 100.0;
    }

    /**
     * Compute altitude using the default sea-level pressure (101325 Pa).
     *
     * @return altitude in m
     * @throws IOException on I²C error
     */
    public double altitude() throws IOException {
        return altitude(DEFAULT_SEA_LEVEL_PA);
    }

    /**
     * Compute altitude for a given sea-level reference pressure from a fresh reading
     * (barometric formula, informative).
     *
     * @param seaLevelPa reference sea-level pressure in Pa
     * @return altitude in m
     * @throws IOException on I²C error
     */
    public double altitude(double seaLevelPa) throws IOException {
        double p = readPressure();
        return 44330.0 * (1.0 - Math.pow(p / seaLevelPa, 1.0 / 5.255));
    }
}
