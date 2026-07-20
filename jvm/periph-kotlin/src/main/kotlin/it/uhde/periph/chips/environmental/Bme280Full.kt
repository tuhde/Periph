package it.uhde.periph.chips.environmental

import it.uhde.periph.transport.Transport
import java.io.IOException
import kotlin.math.ln
import kotlin.math.pow

/**
 * BME280 — full driver. Extends [Bme280Minimal] with oversampling control for
 * all three TPH channels, IIR filter configuration, standby time setting,
 * altitude / sea-level pressure / dew-point computation, status polling,
 * chip ID read-back, and soft reset.
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
 * [T_SB_0_5_MS] … [T_SB_1000_MS], [T_SB_10_MS], [T_SB_20_MS] — note that
 * codes 6 and 7 mean **10 ms / 20 ms** on the BME280, not 2000 ms / 4000 ms
 * as on the BMP280.
 *
 * ## Status flags
 * [STATUS_MEASURING], [STATUS_IM_UPDATE]
 */
class Bme280Full @JvmOverloads constructor(
    transport: Transport,
    addr: Int = 0x76
) : Bme280Minimal(transport, addr) {

    companion object {
        const val OSRS_SKIP = 0
        const val OSRS_X1   = 1
        const val OSRS_X2   = 2
        const val OSRS_X4   = 3
        const val OSRS_X8   = 4
        const val OSRS_X16  = 5

        const val MODE_SLEEP  = 0
        const val MODE_FORCED = 1
        const val MODE_NORMAL = 3

        const val FILTER_OFF = 0
        const val FILTER_2   = 1
        const val FILTER_4   = 2
        const val FILTER_8   = 3
        const val FILTER_16  = 4

        const val T_SB_0_5_MS   = 0
        const val T_SB_62_5_MS  = 1
        const val T_SB_125_MS   = 2
        const val T_SB_250_MS   = 3
        const val T_SB_500_MS   = 4
        const val T_SB_1000_MS  = 5
        const val T_SB_10_MS    = 6
        const val T_SB_20_MS    = 7

        const val STATUS_MEASURING  = 0x08
        const val STATUS_IM_UPDATE  = 0x01

        private const val DEFAULT_SEA_LEVEL_HPA = 1013.25
        private const val MAGNUS_A = 17.27
        private const val MAGNUS_B = 237.7
    }

    /**
     * Configure oversampling, operating mode, IIR filter, and standby time in
     * one call.
     *
     * Writes ctrl_hum, config, and ctrl_meas in the correct order (ctrl_hum
     * must precede ctrl_meas for humidity oversampling to latch).
     *
     * @param osrsT  temperature oversampling (0–5, use [OSRS_SKIP]..[OSRS_X16])
     * @param osrsP  pressure oversampling (0–5)
     * @param osrsH  humidity oversampling (0–5)
     * @param mode   operating mode (use [MODE_SLEEP]..[MODE_NORMAL])
     * @param filter IIR filter coefficient (0–4)
     * @param tSb    standby time in normal mode (0–7)
     * @throws IOException on I²C error
     */
    fun configure(osrsT: Int, osrsP: Int, osrsH: Int, mode: Int, filter: Int, tSb: Int) {
        ctrlHum  = osrsH and 0x07
        config   = ((tSb and 0x07) shl 5) or ((filter and 0x07) shl 2)
        ctrlMeas = ((osrsT and 0x07) shl 5) or ((osrsP and 0x07) shl 2) or (mode and 0x03)
        transport.write(byteArrayOf(REG_CTRL_HUM.toByte(), ctrlHum.toByte()))
        transport.write(byteArrayOf(REG_CONFIG.toByte(), config.toByte()))
        transport.write(byteArrayOf(REG_CTRL_MEAS.toByte(), ctrlMeas.toByte()))
    }

    /**
     * Update the temperature, pressure, and humidity oversampling settings.
     *
     * @param osrsT temperature oversampling (0–5)
     * @param osrsP pressure oversampling (0–5)
     * @param osrsH humidity oversampling (0–5)
     * @throws IOException on I²C error
     */
    fun setOversampling(osrsT: Int, osrsP: Int, osrsH: Int) {
        ctrlHum  = osrsH and 0x07
        ctrlMeas = ((osrsT and 0x07) shl 5) or ((osrsP and 0x07) shl 2) or (ctrlMeas and 0x03)
        transport.write(byteArrayOf(REG_CTRL_HUM.toByte(), ctrlHum.toByte()))
        transport.write(byteArrayOf(REG_CTRL_MEAS.toByte(), ctrlMeas.toByte()))
    }

    /**
     * Set the operating mode. Preserves the oversampling bits in ctrl_meas.
     *
     * @param mode operating mode (use [MODE_SLEEP]..[MODE_NORMAL])
     * @throws IOException on I²C error
     */
    fun setMode(mode: Int) {
        ctrlMeas = (ctrlMeas and 0xFC) or (mode and 0x03)
        transport.write(byteArrayOf(REG_CTRL_MEAS.toByte(), ctrlMeas.toByte()))
    }

    /**
     * Set the IIR filter coefficient. Preserves the standby time bits.
     *
     * @param coeff filter coefficient (0–4)
     * @throws IOException on I²C error
     */
    fun setFilter(coeff: Int) {
        config = (config and 0xE3) or ((coeff and 0x07) shl 2)
        transport.write(byteArrayOf(REG_CONFIG.toByte(), config.toByte()))
    }

    /**
     * Set the standby time used in normal mode. Preserves the filter bits.
     *
     * @param tSb standby time (0–7). On the BME280 codes 6 and 7 mean 10 ms
     *            and 20 ms, not 2000 ms and 4000 ms.
     * @throws IOException on I²C error
     */
    fun setStandby(tSb: Int) {
        config = (config and 0x1F) or ((tSb and 0x07) shl 5)
        transport.write(byteArrayOf(REG_CONFIG.toByte(), config.toByte()))
    }

    /**
     * Read the status register (0xF3).
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
     * @param seaLevelHpa reference sea-level pressure in hPa
     * @return altitude in m
     * @throws IOException on I²C error
     */
    fun altitude(seaLevelHpa: Double): Double {
        val p = pressure()
        return 44330.0 * (1.0 - (p / seaLevelHpa).pow(1.0 / 5.255))
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
        return p / (1.0 - altitudeM / 44330.0).pow(5.255)
    }

    /**
     * Compute the dew point from the current temperature and humidity using
     * the Magnus-Tetens approximation.
     *
     * @return dew point in °C
     * @throws IOException on I²C error
     */
    fun dewPoint(): Double {
        val t = temperature()
        val h = humidity()
        if (h <= 0.0) return Double.NEGATIVE_INFINITY
        val alpha = (MAGNUS_A * t) / (MAGNUS_B + t) + ln(h / 100.0)
        return (MAGNUS_B * alpha) / (MAGNUS_A - alpha)
    }

    /**
     * Read the chip ID register (0xD0).
     *
     * Expected value is 0x60 for BME280.
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
     * configuration.
     *
     * @throws IOException on I²C error
     */
    fun reset() {
        transport.write(byteArrayOf(REG_SOFT_RST.toByte(), RESET_CMD.toByte()))
        try { Thread.sleep(2) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        readCalibration()
        transport.write(byteArrayOf(REG_CTRL_HUM.toByte(), ctrlHum.toByte()))
        transport.write(byteArrayOf(REG_CONFIG.toByte(), config.toByte()))
        transport.write(byteArrayOf(REG_CTRL_MEAS.toByte(), ctrlMeas.toByte()))
    }
}
