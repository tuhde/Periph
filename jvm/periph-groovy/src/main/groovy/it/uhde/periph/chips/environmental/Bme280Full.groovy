package it.uhde.periph.chips.environmental

import groovy.transform.CompileStatic
import it.uhde.periph.transport.Transport
import java.io.IOException

/**
 * BME280 — full driver. Extends {@link Bme280Minimal} with oversampling
 * control for all three TPH channels, IIR filter configuration, standby time
 * setting, altitude / sea-level pressure / dew-point computation, status
 * polling, chip ID read-back, and soft reset.
 *
 * <h2>Oversampling constants</h2>
 * {@link #OSRS_SKIP}, {@link #OSRS_X1}, {@link #OSRS_X2}, {@link #OSRS_X4},
 * {@link #OSRS_X8}, {@link #OSRS_X16}
 *
 * <h2>Mode constants</h2>
 * {@link #MODE_SLEEP}, {@link #MODE_FORCED}, {@link #MODE_NORMAL}
 *
 * <h2>Filter constants</h2>
 * {@link #FILTER_OFF}, {@link #FILTER_2}, {@link #FILTER_4},
 * {@link #FILTER_8}, {@link #FILTER_16}
 *
 * <h2>Standby time constants</h2>
 * {@link #T_SB_0_5_MS} … {@link #T_SB_1000_MS}, {@link #T_SB_10_MS},
 * {@link #T_SB_20_MS} — note that codes 6 and 7 mean <b>10 ms / 20 ms</b> on
 * the BME280, not 2000 ms / 4000 ms as on the BMP280.
 *
 * <h2>Status flags</h2>
 * {@link #STATUS_MEASURING}, {@link #STATUS_IM_UPDATE}
 */
@CompileStatic
class Bme280Full extends Bme280Minimal {

    static final int OSRS_SKIP = 0
    static final int OSRS_X1   = 1
    static final int OSRS_X2   = 2
    static final int OSRS_X4   = 3
    static final int OSRS_X8   = 4
    static final int OSRS_X16  = 5

    static final int MODE_SLEEP  = 0
    static final int MODE_FORCED = 1
    static final int MODE_NORMAL = 3

    static final int FILTER_OFF = 0
    static final int FILTER_2   = 1
    static final int FILTER_4   = 2
    static final int FILTER_8   = 3
    static final int FILTER_16  = 4

    static final int T_SB_0_5_MS   = 0
    static final int T_SB_62_5_MS  = 1
    static final int T_SB_125_MS   = 2
    static final int T_SB_250_MS   = 3
    static final int T_SB_500_MS   = 4
    static final int T_SB_1000_MS  = 5
    static final int T_SB_10_MS    = 6
    static final int T_SB_20_MS    = 7

    static final int STATUS_MEASURING  = 0x08
    static final int STATUS_IM_UPDATE  = 0x01

    private static final double DEFAULT_SEA_LEVEL_HPA = 1013.25
    private static final double MAGNUS_A = 17.27
    private static final double MAGNUS_B = 237.7

    /**
     * Construct the full driver at the default address (0x76), verify chip ID,
     * and load calibration.
     *
     * @param transport I²C transport bound to address 0x76
     * @throws IOException on I²C error, wrong chip ID, or invalid calibration
     */
    Bme280Full(Transport transport) throws IOException {
        super(transport)
    }

    /**
     * Construct the full driver at the given address, verify chip ID, and load
     * calibration.
     *
     * @param transport I²C transport bound to the given address
     * @param addr      I²C device address (0x76 or 0x77)
     * @throws IOException on I²C error, wrong chip ID, or invalid calibration
     */
    Bme280Full(Transport transport, int addr) throws IOException {
        super(transport, addr)
    }

    /**
     * Configure oversampling, operating mode, IIR filter, and standby time in
     * one call.
     *
     * <p>Writes ctrl_hum, config, and ctrl_meas in the correct order
     * (ctrl_hum must precede ctrl_meas for humidity oversampling to latch).
     *
     * @param osrsT  temperature oversampling (0–5, use {@code OSRS_*})
     * @param osrsP  pressure oversampling (0–5, use {@code OSRS_*})
     * @param osrsH  humidity oversampling (0–5, use {@code OSRS_*})
     * @param mode   operating mode (use {@code MODE_*})
     * @param filter IIR filter coefficient (0–4, use {@code FILTER_*})
     * @param tSb    standby time in normal mode (0–7, use {@code T_SB_*})
     * @throws IOException on I²C error
     */
    void configure(int osrsT, int osrsP, int osrsH, int mode, int filter, int tSb) throws IOException {
        ctrlHum  = osrsH & 0x07
        config   = ((tSb & 0x07) << 5) | ((filter & 0x07) << 2)
        ctrlMeas = ((osrsT & 0x07) << 5) | ((osrsP & 0x07) << 2) | (mode & 0x03)
        transport.write(new byte[]{(byte) REG_CTRL_HUM, (byte) ctrlHum})
        transport.write(new byte[]{(byte) REG_CONFIG, (byte) config})
        transport.write(new byte[]{(byte) REG_CTRL_MEAS, (byte) ctrlMeas})
    }

    /**
     * Update the temperature, pressure, and humidity oversampling settings.
     *
     * <p>Writes ctrl_hum then ctrl_meas to ensure humidity oversampling
     * latches. Preserves the current mode bits in ctrl_meas.
     *
     * @param osrsT temperature oversampling (0–5, use {@code OSRS_*})
     * @param osrsP pressure oversampling (0–5, use {@code OSRS_*})
     * @param osrsH humidity oversampling (0–5, use {@code OSRS_*})
     * @throws IOException on I²C error
     */
    void setOversampling(int osrsT, int osrsP, int osrsH) throws IOException {
        ctrlHum  = osrsH & 0x07
        ctrlMeas = ((osrsT & 0x07) << 5) | ((osrsP & 0x07) << 2) | (ctrlMeas & 0x03)
        transport.write(new byte[]{(byte) REG_CTRL_HUM, (byte) ctrlHum})
        transport.write(new byte[]{(byte) REG_CTRL_MEAS, (byte) ctrlMeas})
    }

    /**
     * Set the operating mode. Preserves the oversampling bits in ctrl_meas.
     *
     * @param mode operating mode (use {@code MODE_*})
     * @throws IOException on I²C error
     */
    void setMode(int mode) throws IOException {
        ctrlMeas = (ctrlMeas & 0xFC) | (mode & 0x03)
        transport.write(new byte[]{(byte) REG_CTRL_MEAS, (byte) ctrlMeas})
    }

    /**
     * Set the IIR filter coefficient. Preserves the standby time bits.
     *
     * @param coeff filter coefficient (0–4, use {@code FILTER_*})
     * @throws IOException on I²C error
     */
    void setFilter(int coeff) throws IOException {
        config = (config & 0xE3) | ((coeff & 0x07) << 2)
        transport.write(new byte[]{(byte) REG_CONFIG, (byte) config})
    }

    /**
     * Set the standby time used in normal mode. Preserves the filter bits.
     *
     * @param tSb standby time (0–7, use {@code T_SB_*}). On the BME280 codes 6
     *            and 7 mean 10 ms and 20 ms, not 2000 ms and 4000 ms.
     * @throws IOException on I²C error
     */
    void setStandby(int tSb) throws IOException {
        config = (config & 0x1F) | ((tSb & 0x07) << 5)
        transport.write(new byte[]{(byte) REG_CONFIG, (byte) config})
    }

    /**
     * Read the status register (0xF3).
     *
     * @return raw status byte
     * @throws IOException on I²C error
     */
    int status() throws IOException {
        byte[] b = transport.writeRead(new byte[]{(byte) REG_STATUS}, 1)
        return b[0] & 0xFF
    }

    /**
     * Compute altitude using the default sea-level pressure (1013.25 hPa).
     *
     * @return altitude in m
     * @throws IOException on I²C error
     */
    double altitude() throws IOException {
        return altitude(DEFAULT_SEA_LEVEL_HPA)
    }

    /**
     * Compute altitude for a given sea-level reference pressure.
     *
     * @param seaLevelHpa reference sea-level pressure in hPa
     * @return altitude in m
     * @throws IOException on I²C error
     */
    double altitude(double seaLevelHpa) throws IOException {
        double p = pressure()
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
    double seaLevelPressure(double altitudeM) throws IOException {
        double p = pressure()
        return p / Math.pow(1.0 - altitudeM / 44330.0, 5.255)
    }

    /**
     * Compute the dew point from the current temperature and humidity using
     * the Magnus-Tetens approximation.
     *
     * @return dew point in °C
     * @throws IOException on I²C error
     */
    double dewPoint() throws IOException {
        double t = temperature()
        double h = humidity()
        if (h <= 0.0) return Double.NEGATIVE_INFINITY
        double alpha = (MAGNUS_A * t) / (MAGNUS_B + t) + Math.log(h / 100.0)
        return (MAGNUS_B * alpha) / (MAGNUS_A - alpha)
    }

    /**
     * Read the chip ID register (0xD0).
     *
     * <p>Expected value is 0x60 for BME280.
     *
     * @return chip ID byte
     * @throws IOException on I²C error
     */
    int chipId() throws IOException {
        byte[] b = transport.writeRead(new byte[]{(byte) REG_ID}, 1)
        return b[0] & 0xFF
    }

    /**
     * Perform a soft reset, reload calibration, and re-apply the current
     * configuration.
     *
     * <p>Writes 0xB6 to register 0xE0, waits 2 ms, then re-reads calibration
     * NVM and restores ctrl_hum / config / ctrl_meas.
     *
     * @throws IOException on I²C error
     */
    void reset() throws IOException {
        transport.write(new byte[]{(byte) REG_RESET, (byte) RESET_CMD})
        try { Thread.sleep(2) } catch (InterruptedException e) { Thread.currentThread().interrupt() }
        readCalibration()
        transport.write(new byte[]{(byte) REG_CTRL_HUM, (byte) ctrlHum})
        transport.write(new byte[]{(byte) REG_CONFIG, (byte) config})
        transport.write(new byte[]{(byte) REG_CTRL_MEAS, (byte) ctrlMeas})
    }
}
