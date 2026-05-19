package it.uhde.periph.chips.pressure

import it.uhde.periph.transport.Transport
import java.io.IOException

/**
 * BMP180 — full driver. Extends [Bmp180Minimal] with oversampling control,
 * altitude calculation, sea-level pressure derivation, chip ID read-back, and
 * soft reset.
 *
 * ## OSS constants
 * [OSS_ULP], [OSS_STANDARD], [OSS_HIGH_RES], [OSS_ULTRA_HIGH_RES]
 *
 * ## Altitude formula
 * `altitude_m = 44330.0 × (1.0 − (pressure_hPa / seaLevelHpa)^(1/5.255))`
 */
class Bmp180Full(transport: Transport) : Bmp180Minimal(transport) {

    companion object {
        /** Ultra-low-power mode: OSS = 0 (1 sample, ~4.5 ms, 3 µA RMS). */
        const val OSS_ULP             = 0
        /** Standard mode: OSS = 1 (2 samples, ~7.5 ms, 5 µA RMS). */
        const val OSS_STANDARD        = 1
        /** High-resolution mode: OSS = 2 (4 samples, ~13.5 ms, 7 µA RMS). */
        const val OSS_HIGH_RES        = 2
        /** Ultra-high-resolution mode: OSS = 3 (8 samples, ~25.5 ms, 12 µA RMS). */
        const val OSS_ULTRA_HIGH_RES  = 3

        private const val DEFAULT_SEA_LEVEL_HPA = 1013.25
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
     * @param oss oversampling value (0–3); use the OSS_* constants
     * @throws IllegalArgumentException if oss is outside [0, 3]
     */
    fun setOversampling(oss: Int) {
        require(oss in 0..3) { "OSS must be 0–3, got: $oss" }
        this.oss = oss
    }

    /**
     * Compute altitude using the default sea-level pressure (1013.25 hPa).
     *
     * @return altitude in m
     * @throws IOException on I²C error
     */
    fun altitude(): Double = altitude(DEFAULT_SEA_LEVEL_HPA)

    /**
     * Compute altitude for a given sea-level reference pressure.
     *
     * Uses the barometric formula:
     * `altitude_m = 44330 × (1 − (pressure / seaLevelHpa)^(1/5.255))`
     *
     * @param seaLevelHpa reference sea-level pressure in hPa
     * @return altitude in m
     * @throws IOException on I²C error
     */
    fun altitude(seaLevelHpa: Double): Double {
        val p = pressure()
        return 44330.0 * (1.0 - Math.pow(p / seaLevelHpa, 1.0 / 5.255))
    }

    /**
     * Back-calculate the sea-level pressure from the current reading and a
     * known altitude.
     *
     * @param altitudeM known altitude in m
     * @return sea-level pressure in hPa
     * @throws IOException on I²C error
     */
    fun seaLevelPressure(altitudeM: Double): Double {
        val p = pressure()
        return p / Math.pow(1.0 - altitudeM / 44330.0, 5.255)
    }

    /**
     * Read the chip ID register (0xD0).
     *
     * Expected value is 0x55 for BMP180.
     *
     * @return chip ID byte
     * @throws IOException on I²C error
     */
    fun chipId(): Int {
        val b = transport.writeRead(byteArrayOf(REG_ID.toByte()), 1)
        return b[0].toInt() and 0xFF
    }

    /**
     * Perform a soft reset and reload calibration.
     *
     * Writes 0xB6 to register 0xE0, waits 15 ms for the chip to complete its
     * power-on sequence, then re-reads and validates the calibration EEPROM.
     *
     * @throws IOException on I²C error or invalid calibration after reset
     */
    fun reset() {
        transport.write(byteArrayOf(REG_SOFT_RST.toByte(), 0xB6.toByte()))
        Thread.sleep(15)
        readCalibration()
    }
}
