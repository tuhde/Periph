package it.uhde.periph.chips.pressure;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * MS5607 — full driver: single values, raw codes, oversampling, non-blocking conversion, PROM
 * access and altitude. Extends {@link Ms5607Minimal}.
 */
public class Ms5607Full extends Ms5607Minimal {

    /** Conversion kind: D1, pressure. */
    public static final int PRESSURE = 0;
    /** Conversion kind: D2, temperature. */
    public static final int TEMPERATURE = 1;

    /**
     * Create the driver; does no bus access.
     *
     * @param connection I²C connection bound to 0x77 (or 0x76 with CSB high)
     */
    public Ms5607Full(Connection connection) {
        super(connection);
    }

    private static int osrIndexOf(int osr) {
        for (int i = 0; i < OSR_VALUES.length; i++) if (OSR_VALUES[i] == osr) return i;
        throw new IllegalArgumentException("OSR must be 256, 512, 1024, 2048 or 4096");
    }

    /**
     * Convert D2 and D1 at the current oversampling ratio.
     *
     * @return {d1 (pressure), d2 (temperature)}, both 24-bit
     * @throws IOException on bus error or an ADC result of 0
     */
    public int[] readRaw() throws IOException {
        int d2 = convert(1);
        int d1 = convert(0);
        return new int[]{d1, d2};
    }

    /**
     * Measure and return the compensated pressure.
     *
     * @return pressure in Pa
     * @throws IOException on bus error
     */
    public double readPressure() throws IOException {
        return read()[1];
    }

    /**
     * Convert D2 only and return the temperature (with the low-temperature correction).
     *
     * @return temperature in °C
     * @throws IOException on bus error
     */
    public double readTemperature() throws IOException {
        return compute(0, convert(1), true)[0];
    }

    /**
     * Set the oversampling ratio used for both conversions.
     *
     * @param osr 256, 512, 1024, 2048 or 4096
     * @throws IllegalArgumentException for any other value
     */
    public void setOversampling(int osr) {
        osrIndex = osrIndexOf(osr);
    }

    /**
     * Current oversampling ratio.
     *
     * @return 256 … 4096
     */
    public int getOversampling() {
        return OSR_VALUES[osrIndex];
    }

    /**
     * Maximum conversion time for the current oversampling ratio.
     *
     * @return seconds
     */
    public double conversionTime() {
        return T_CONV_US[osrIndex] / 1.0e6;
    }

    /**
     * Maximum conversion time for an oversampling ratio.
     *
     * @param osr 256, 512, 1024, 2048 or 4096
     * @return seconds (0.60 / 1.17 / 2.28 / 4.54 / 9.04 ms)
     * @throws IllegalArgumentException for an invalid ratio
     */
    public double conversionTime(int osr) {
        return T_CONV_US[osrIndexOf(osr)] / 1.0e6;
    }

    /**
     * Write a conversion command with the current ratio without waiting (non-blocking use).
     *
     * @param kind {@link #PRESSURE} or {@link #TEMPERATURE}
     * @throws IOException on bus error
     */
    public void startConversion(int kind) throws IOException {
        start(kind, osrIndex);
    }

    /**
     * Write a conversion command without waiting (non-blocking use); wait at least
     * {@link #conversionTime(int)} before {@link #readAdc()}.
     *
     * @param kind {@link #PRESSURE} or {@link #TEMPERATURE}
     * @param osr  oversampling ratio (256 … 4096)
     * @throws IOException on bus error
     * @throws IllegalArgumentException for an invalid ratio
     */
    public void startConversion(int kind, int osr) throws IOException {
        start(kind, osrIndexOf(osr));
    }

    /**
     * ADC read command plus 3 bytes: the result of the last conversion. Only valid after the
     * conversion time; a read during the conversion or a repeated read returns 0.
     *
     * @return 24-bit result
     * @throws IOException on bus error or a result of 0
     */
    public int readAdc() throws IOException {
        return readAdcValue();
    }

    /**
     * Pure computation with the stored coefficients (no bus access), second-order correction on.
     *
     * @param d1 24-bit raw pressure
     * @param d2 24-bit raw temperature
     * @return {temperature in °C, pressure in Pa}
     */
    public double[] compensate(int d1, int d2) {
        return compute(d1, d2, true);
    }

    /**
     * Pure computation with the stored coefficients (no bus access).
     *
     * @param d1          24-bit raw pressure
     * @param d2          24-bit raw temperature
     * @param secondOrder false for the first-order result only
     * @return {temperature in °C, pressure in Pa}
     */
    public double[] compensate(int d1, int d2, boolean secondOrder) {
        return compute(d1, d2, secondOrder);
    }

    /**
     * Reset (0x1E), wait 3 ms and reload and verify the PROM.
     *
     * @throws IOException on bus error or a PROM CRC mismatch
     */
    public void reset() throws IOException {
        init();
    }

    /**
     * Read the eight PROM words from the chip.
     *
     * @return the PROM words
     * @throws IOException on bus error
     */
    public int[] readProm() throws IOException {
        return readPromWords();
    }

    /**
     * The calibration coefficients read at init.
     *
     * @return {C1, …, C6}
     */
    public int[] coefficients() {
        return new int[]{prom[1], prom[2], prom[3], prom[4], prom[5], prom[6]};
    }

    /**
     * Verify the CRC-4 of the PROM words read at init (no bus access).
     *
     * @return true if the stored CRC nibble matches
     */
    public boolean promCrcOk() {
        return crc4(prom) == (prom[7] & 0x0F);
    }

    /**
     * Serial code (PROM word 7, bits 15:4).
     *
     * @return 12-bit serial code
     */
    public int serialCode() {
        return (prom[7] >> 4) & 0x0FFF;
    }

    /**
     * Altitude from one pressure reading relative to standard sea-level pressure (101325 Pa).
     *
     * @return altitude in metres
     * @throws IOException on bus error
     */
    public double altitude() throws IOException {
        return altitude(101325.0);
    }

    /**
     * Altitude from one pressure reading (international barometric formula).
     *
     * @param seaLevelPa reference sea-level pressure in pascals
     * @return altitude in metres
     * @throws IOException on bus error
     */
    public double altitude(double seaLevelPa) throws IOException {
        return 44330.0 * (1.0 - Math.pow(readPressure() / seaLevelPa, 1.0 / 5.255));
    }
}
