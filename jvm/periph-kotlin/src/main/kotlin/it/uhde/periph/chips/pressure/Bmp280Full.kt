package it.uhde.periph.chips.pressure

import it.uhde.periph.transport.Transport
import java.io.IOException

/**
 * BMP280 — full driver. Extends [Bmp280Minimal] with oversampling control,
 * operating mode selection, IIR filter and standby-time configuration,
 * altitude calculation, sea-level pressure derivation, status polling, chip ID
 * read-back, and soft reset.
 *
 * ## Oversampling constants
 * [OSRS_SKIP], [OSRS_X1], [OSRS_X2], [OSRS_X4], [OSRS_X8], [OSRS_X16]
 *
 * ## Mode constants
 * [MODE_SLEEP], [MODE_FORCED], [MODE_NORMAL]
 *
 * ## Filter constants
 * [FILTER_OFF], [FILTER_2], [FILTER_4], [FILTER_8], [FILTER_16]
 *
 * ## Standby time constants
 * [T_SB_0_5_MS] … [T_SB_4000_MS]
 *
 * ## Altitude formula
 * `altitude_m = 44330.0 × (1.0 − (pressure_hPa / seaLevelHpa)^(1/5.255))`
 */
class Bmp280Full @JvmOverloads constructor(
    transport: Transport,
    addr: Int = 0x76
) : Bmp280Minimal(transport, addr) {

    companion object {
        // Oversampling constants
        /** Skip measurement (output set to 0x80000). */
        const val OSRS_SKIP = 0
        /** Oversampling ×1. */
        const val OSRS_X1   = 1
        /** Oversampling ×2. */
        const val OSRS_X2   = 2
        /** Oversampling ×4. */
        const val OSRS_X4   = 3
        /** Oversampling ×8. */
        const val OSRS_X8   = 4
        /** Oversampling ×16. */
        const val OSRS_X16  = 5

        // Mode constants
        /** Sleep mode: no measurements performed. */
        const val MODE_SLEEP  = 0
        /** Forced mode: single measurement then sleep. */
        const val MODE_FORCED = 1
        /** Normal mode: continuous measurements with standby interval. */
        const val MODE_NORMAL = 3

        // IIR filter coefficient constants
        /** IIR filter off. */
        const val FILTER_OFF = 0
        /** IIR filter coefficient 2. */
        const val FILTER_2   = 1
        /** IIR filter coefficient 4. */
        const val FILTER_4   = 2
        /** IIR filter coefficient 8. */
        const val FILTER_8   = 3
        /** IIR filter coefficient 16. */
        const val FILTER_16  = 4

        // Standby time constants (normal mode)
        /** Standby time 0.5 ms. */
        const val T_SB_0_5_MS  = 0
        /** Standby time 62.5 ms. */
        const val T_SB_62_5_MS = 1
        /** Standby time 125 ms. */
        const val T_SB_125_MS  = 2
        /** Standby time 250 ms. */
        const val T_SB_250_MS  = 3
        /** Standby time 500 ms. */
        const val T_SB_500_MS  = 4
        /** Standby time 1000 ms. */
        const val T_SB_1000_MS = 5
        /** Standby time 2000 ms. */
        const val T_SB_2000_MS = 6
        /** Standby time 4000 ms. */
        const val T_SB_4000_MS = 7

        // Status bit masks
        /** Status bit: device is currently performing a measurement. */
        const val STATUS_MEASURING = 0x08
        /** Status bit: NVM data is being copied to image registers. */
        const val STATUS_IM_UPDATE = 0x01

        private const val DEFAULT_SEA_LEVEL_HPA = 1013.25
    }

    /**
     * Configure oversampling, operating mode, IIR filter, and standby time in
     * one call.
     *
     * @param osrsT  temperature oversampling (0–5, use OSRS_* constants)
     * @param osrsP  pressure oversampling (0–5, use OSRS_* constants)
     * @param mode   operating mode (use MODE_* constants)
     * @param filter IIR filter coefficient (0–4, use FILTER_* constants)
     * @param tSb    standby time in normal mode (0–7, use T_SB_* constants)
     * @throws IOException on I²C error
     */
    fun configure(osrsT: Int, osrsP: Int, mode: Int, filter: Int, tSb: Int) {
        ctrlMeas = ((osrsT and 0x07) shl 5) or ((osrsP and 0x07) shl 2) or (mode and 0x03)
        config   = ((tSb   and 0x07) shl 5) or ((filter and 0x07) shl 2)
        transport.write(byteArrayOf(REG_CONFIG.toByte(),    config.toByte()))
        transport.write(byteArrayOf(REG_CTRL_MEAS.toByte(), ctrlMeas.toByte()))
    }

    /**
     * Update the temperature and pressure oversampling settings.
     *
     * Preserves the current mode bits in ctrl_meas.
     *
     * @param osrsT temperature oversampling (0–5, use OSRS_* constants)
     * @param osrsP pressure oversampling (0–5, use OSRS_* constants)
     * @throws IOException on I²C error
     */
    fun setOversampling(osrsT: Int, osrsP: Int) {
        ctrlMeas = ((osrsT and 0x07) shl 5) or ((osrsP and 0x07) shl 2) or (ctrlMeas and 0x03)
        transport.write(byteArrayOf(REG_CTRL_MEAS.toByte(), ctrlMeas.toByte()))
    }

    /**
     * Set the operating mode.
     *
     * Preserves the oversampling bits in ctrl_meas.
     *
     * @param mode operating mode (use MODE_* constants)
     * @throws IOException on I²C error
     */
    fun setMode(mode: Int) {
        ctrlMeas = (ctrlMeas and 0xFC) or (mode and 0x03)
        transport.write(byteArrayOf(REG_CTRL_MEAS.toByte(), ctrlMeas.toByte()))
    }

    /**
     * Set the IIR filter coefficient.
     *
     * Preserves the standby time bits in the config register.
     *
     * @param coeff filter coefficient (0–4, use FILTER_* constants)
     * @throws IOException on I²C error
     */
    fun setFilter(coeff: Int) {
        config = (config and 0xE3) or ((coeff and 0x07) shl 2)
        transport.write(byteArrayOf(REG_CONFIG.toByte(), config.toByte()))
    }

    /**
     * Set the standby time used in normal mode.
     *
     * Preserves the filter bits in the config register.
     *
     * @param tSb standby time (0–7, use T_SB_* constants)
     * @throws IOException on I²C error
     */
    fun setStandby(tSb: Int) {
        config = (config and 0x1F) or ((tSb and 0x07) shl 5)
        transport.write(byteArrayOf(REG_CONFIG.toByte(), config.toByte()))
    }

    /**
     * Read the status register (0xF3).
     *
     * Bit 3 ([STATUS_MEASURING]) is set while a measurement is in progress.
     * Bit 0 ([STATUS_IM_UPDATE]) is set while NVM data is being transferred.
     *
     * @return raw status byte
     * @throws IOException on I²C error
     */
    fun status(): Int {
        val b = transport.writeRead(byteArrayOf(REG_STATUS.toByte()), 1)
        return b[0].toInt() and 0xFF
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
     * Expected value is 0x58 for BMP280.
     *
     * @return chip ID byte
     * @throws IOException on I²C error
     */
    fun chipId(): Int {
        val b = transport.writeRead(byteArrayOf(REG_ID.toByte()), 1)
        return b[0].toInt() and 0xFF
    }

    /**
     * Perform a soft reset, reload calibration, and re-apply the current
     * ctrl_meas and config register values.
     *
     * Writes 0xB6 to register 0xE0, waits 2 ms for the chip to complete
     * its power-on sequence, then re-reads calibration NVM and restores the
     * previously configured ctrl_meas and config registers.
     *
     * @throws IOException on I²C error
     */
    fun reset() {
        transport.write(byteArrayOf(REG_SOFT_RST.toByte(), 0xB6.toByte()))
        Thread.sleep(2)
        readCalibration()
        transport.write(byteArrayOf(REG_CONFIG.toByte(),    config.toByte()))
        transport.write(byteArrayOf(REG_CTRL_MEAS.toByte(), ctrlMeas.toByte()))
    }
}
