package it.uhde.periph.chips.pressure;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

/**
 * BMP180 — full driver. Extends {@link Bmp180Minimal} with oversampling control,
 * altitude calculation, sea-level pressure derivation, chip ID read-back, and
 * soft reset.
 *
 * <h2>OSS constants</h2>
 * {@link #OSS_ULP}, {@link #OSS_STANDARD}, {@link #OSS_HIGH_RES}, {@link #OSS_ULTRA_HIGH_RES}
 *
 * <h2>Altitude formula</h2>
 * {@code altitude_m = 44330.0 × (1.0 − (pressure_hPa / seaLevelHpa)^(1/5.255))}
 */
public class Bmp180Full extends Bmp180Minimal {

    /** Ultra-low-power mode: OSS = 0 (1 sample, ~4.5 ms, 3 µA RMS). */
    public static final int OSS_ULP             = 0;
    /** Standard mode: OSS = 1 (2 samples, ~7.5 ms, 5 µA RMS). */
    public static final int OSS_STANDARD        = 1;
    /** High-resolution mode: OSS = 2 (4 samples, ~13.5 ms, 7 µA RMS). */
    public static final int OSS_HIGH_RES        = 2;
    /** Ultra-high-resolution mode: OSS = 3 (8 samples, ~25.5 ms, 12 µA RMS). */
    public static final int OSS_ULTRA_HIGH_RES  = 3;

    /** Sea-level pressure in hPa used when none is supplied. */
    private static final double DEFAULT_SEA_LEVEL_HPA = 1013.25;

    /**
     * Construct the full driver, verify chip ID, and load calibration.
     *
     * @param transport I²C transport bound to address 0x77
     * @throws IOException on I²C error, wrong chip ID, or invalid calibration
     */
    public Bmp180Full(Transport transport) throws IOException {
        super(transport);
    }

    /**
     * Read the current oversampling setting.
     *
     * @return OSS value (0–3)
     */
    public int oversampling() {
        return oss;
    }

    /**
     * Set the oversampling setting.
     *
     * @param oss oversampling value (0–3); use the {@code OSS_*} constants
     * @throws IllegalArgumentException if oss is outside [0, 3]
     */
    public void setOversampling(int oss) {
        if (oss < 0 || oss > 3) {
            throw new IllegalArgumentException("OSS must be 0–3, got: " + oss);
        }
        this.oss = oss;
    }

    /**
     * Compute altitude using the default sea-level pressure (1013.25 hPa).
     *
     * @return altitude in m
     * @throws IOException on I²C error
     */
    public double altitude() throws IOException {
        return altitude(DEFAULT_SEA_LEVEL_HPA);
    }

    /**
     * Compute altitude for a given sea-level reference pressure.
     *
     * <p>Uses the barometric formula:
     * {@code altitude_m = 44330 × (1 − (pressure / seaLevelHpa)^(1/5.255))}
     *
     * @param seaLevelHpa reference sea-level pressure in hPa
     * @return altitude in m
     * @throws IOException on I²C error
     */
    public double altitude(double seaLevelHpa) throws IOException {
        double p = pressure();
        return 44330.0 * (1.0 - Math.pow(p / seaLevelHpa, 1.0 / 5.255));
    }

    /**
     * Back-calculate the sea-level pressure from the current reading and a
     * known altitude.
     *
     * @param altitudeM known altitude in m
     * @return sea-level pressure in hPa
     * @throws IOException on I²C error
     */
    public double seaLevelPressure(double altitudeM) throws IOException {
        double p = pressure();
        return p / Math.pow(1.0 - altitudeM / 44330.0, 5.255);
    }

    /**
     * Read the chip ID register (0xD0).
     *
     * <p>Expected value is 0x55 for BMP180.
     *
     * @return chip ID byte
     * @throws IOException on I²C error
     */
    public int chipId() throws IOException {
        byte[] b = transport.writeRead(new byte[]{(byte) REG_ID}, 1);
        return b[0] & 0xFF;
    }

    /**
     * Perform a soft reset and reload calibration.
     *
     * <p>Writes 0xB6 to register 0xE0, waits 15 ms for the chip to complete its
     * power-on sequence, then re-reads and validates the calibration EEPROM.
     *
     * @throws IOException on I²C error or invalid calibration after reset
     */
    public void reset() throws IOException {
        transport.write(new byte[]{(byte) REG_SOFT_RST, (byte) 0xB6});
        try {
            Thread.sleep(15);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        readCalibration();
    }
}
