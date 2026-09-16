package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * BMP085 — full driver. Extends {@link Bmp085Minimal} with oversampling control,
 * altitude calculation, sea-level pressure derivation, chip ID read-back, and
 * soft reset.
 *
 * <h2>OSS constants</h2>
 * {@link #OSS_ULP}, {@link #OSS_STANDARD}, {@link #OSS_HIGH_RES}, {@link #OSS_ULTRA_HIGH_RES}
 *
 * <h2>Altitude formula</h2>
 * {@code altitude_m = 44330.0 × (1.0 − (pressure_Pa / seaLevelPa)^(1/5.255))}
 */
class Bmp085Full @JvmOverloads constructor(
    connection: Connection
) : Bmp085Minimal(connection) {

    /** Ultra-low-power mode: OSS = 0 (1 sample, ~4.5 ms, 3 µA RMS). */
    companion object {
        const val OSS_ULP             = 0
        /** Standard mode: OSS = 1 (2 samples, ~7.5 ms, 5 µA RMS). */
        const val OSS_STANDARD        = 1
        /** High-resolution mode: OSS = 2 (4 samples, ~13.5 ms, 7 µA RMS). */
        const val OSS_HIGH_RES        = 2
        /** Ultra-high-resolution mode: OSS = 3 (8 samples, ~25.5 ms, 12 µA RMS). */
        const val OSS_ULTRA_HIGH_RES  = 3

        /** Sea-level pressure in Pa used when none is supplied. */
        private const val DEFAULT_SEA_LEVEL_PA = 101325.0
    }

    /**
     * Read the current oversampling setting.
     *
     * @return OSS value (0–3)
     */
    fun oversampling(): Int = oss

    /**
     * Set the oversampling setting.
     *
     * @param oss oversampling value (0–3); use the {@code OSS_*} constants
     * @throws IllegalArgumentException if oss is outside [0, 3]
     */
    fun setOversampling(oss: Int) {
        if (oss < 0 || oss > 3) {
            throw IllegalArgumentException("OSS must be 0–3, got: $oss")
        }
        this.oss = oss
    }

    /**
     * Compute altitude using the default sea-level pressure (101325.0 Pa).
     *
     * @return altitude in m
     * @throws IOException on I²C error
     */
    fun altitude(): Double = altitude(DEFAULT_SEA_LEVEL_PA)

    /**
     * Compute altitude for a given sea-level reference pressure.
     *
     * <p>Uses the barometric formula:
     * {@code altitude_m = 44330 × (1 − (pressure / seaLevelPa)^(1/5.255))}
     *
     * @param seaLevelPa reference sea-level pressure in Pa
     * @return altitude in m
     * @throws IOException on I²C error
     */
    fun altitude(seaLevelPa: Double): Double {
        val p = pressure()
        return 44330.0 * (1.0 - Math.pow(p / seaLevelPa, 1.0 / 5.255))
    }

    /**
     * Back-calculate the sea-level pressure from the current reading and a
     * known altitude.
     *
     * @param altitudeM known altitude in m
     * @return sea-level pressure in Pa
     * @throws IOException on I²C error
     */
    fun seaLevelPressure(altitudeM: Double): Double {
        val p = pressure()
        return p / Math.pow(1.0 - altitudeM / 44330.0, 5.255)
    }

    /**
     * Read the chip ID register (0xD0).
     *
     * <p>Expected value is 0x55 for BMP085.
     *
     * @return chip ID byte
     * @throws IOException on I²C error
     */
    fun chipId(): Int {
        val b = connection.writeRead(byteArrayOf(REG_ID.toByte()), 1)
        return b[0].toInt() and 0xFF
    }

    /**
     * Perform a soft reset and reload calibration.
     *
     * <p>Writes 0xB6 to register 0xE0, waits 15 ms for the chip to complete its
     * power-on sequence, then re-reads and validates the calibration EEPROM.
     *
     * @throws IOException on I²C error or invalid calibration after reset
     */
    fun reset() {
        connection.write(byteArrayOf(REG_SOFT_RST.toByte(), 0xB6.toByte()))
        Thread.sleep(15)
        readCalibration()
    }
}