package it.uhde.periph.chips.pressure

import groovy.transform.CompileStatic
import it.uhde.periph.transport.Transport
import java.io.IOException

/**
 * BMP280 — full driver. Extends {@link Bmp280Minimal} with oversampling
 * control, operating mode selection, IIR filter and standby-time configuration,
 * altitude calculation, sea-level pressure derivation, status polling, chip ID
 * read-back, and soft reset.
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
 * {@link #T_SB_0_5_MS} … {@link #T_SB_4000_MS}
 *
 * <h2>Altitude formula</h2>
 * {@code altitude_m = 44330.0 × (1.0 − (pressure_hPa / seaLevelHpa)^(1/5.255))}
 */
@CompileStatic
class Bmp280Full extends Bmp280Minimal {

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

    static final int T_SB_0_5_MS  = 0
    static final int T_SB_62_5_MS = 1
    static final int T_SB_125_MS  = 2
    static final int T_SB_250_MS  = 3
    static final int T_SB_500_MS  = 4
    static final int T_SB_1000_MS = 5
    static final int T_SB_2000_MS = 6
    static final int T_SB_4000_MS = 7

    static final int STATUS_MEASURING = 0x08
    static final int STATUS_IM_UPDATE = 0x01

    private static final double DEFAULT_SEA_LEVEL_HPA = 1013.25

    /**
     * Construct the full driver at the default address (0x76), verify chip ID,
     * and load calibration.
     *
     * @param transport I²C transport bound to address 0x76
     * @throws IOException on I²C error or wrong chip ID
     */
    Bmp280Full(Transport transport) {
        super(transport)
    }

    /**
     * Construct the full driver at the given address, verify chip ID, and load
     * calibration.
     *
     * @param transport I²C transport bound to the given address
     * @param addr      I²C device address (0x76 or 0x77)
     * @throws IOException on I²C error or wrong chip ID
     */
    Bmp280Full(Transport transport, int addr) {
        super(transport, addr)
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
    void configure(int osrsT, int osrsP, int mode, int filter, int tSb) {
        ctrlMeas = ((osrsT & 0x07) << 5) | ((osrsP & 0x07) << 2) | (mode & 0x03)
        config   = ((tSb   & 0x07) << 5) | ((filter & 0x07) << 2)
        transport.write([(byte) REG_CONFIG,    (byte) config]   as byte[])
        transport.write([(byte) REG_CTRL_MEAS, (byte) ctrlMeas] as byte[])
    }

    /**
     * Update the temperature and pressure oversampling settings.
     *
     * <p>Preserves the current mode bits in ctrl_meas.
     *
     * @param osrsT temperature oversampling (0–5, use OSRS_* constants)
     * @param osrsP pressure oversampling (0–5, use OSRS_* constants)
     * @throws IOException on I²C error
     */
    void setOversampling(int osrsT, int osrsP) {
        ctrlMeas = ((osrsT & 0x07) << 5) | ((osrsP & 0x07) << 2) | (ctrlMeas & 0x03)
        transport.write([(byte) REG_CTRL_MEAS, (byte) ctrlMeas] as byte[])
    }

    /**
     * Set the operating mode.
     *
     * <p>Preserves the oversampling bits in ctrl_meas.
     *
     * @param mode operating mode (use MODE_* constants)
     * @throws IOException on I²C error
     */
    void setMode(int mode) {
        ctrlMeas = (ctrlMeas & 0xFC) | (mode & 0x03)
        transport.write([(byte) REG_CTRL_MEAS, (byte) ctrlMeas] as byte[])
    }

    /**
     * Set the IIR filter coefficient.
     *
     * <p>Preserves the standby time bits in the config register.
     *
     * @param coeff filter coefficient (0–4, use FILTER_* constants)
     * @throws IOException on I²C error
     */
    void setFilter(int coeff) {
        config = (config & 0xE3) | ((coeff & 0x07) << 2)
        transport.write([(byte) REG_CONFIG, (byte) config] as byte[])
    }

    /**
     * Set the standby time used in normal mode.
     *
     * <p>Preserves the filter bits in the config register.
     *
     * @param tSb standby time (0–7, use T_SB_* constants)
     * @throws IOException on I²C error
     */
    void setStandby(int tSb) {
        config = (config & 0x1F) | ((tSb & 0x07) << 5)
        transport.write([(byte) REG_CONFIG, (byte) config] as byte[])
    }

    /**
     * Read the status register (0xF3).
     *
     * <p>Bit 3 ({@link #STATUS_MEASURING}) is set while a measurement is in
     * progress. Bit 0 ({@link #STATUS_IM_UPDATE}) is set while NVM data is
     * being transferred.
     *
     * @return raw status byte
     * @throws IOException on I²C error
     */
    int status() {
        byte[] b = transport.writeRead([(byte) REG_STATUS] as byte[], 1)
        return b[0] & 0xFF
    }

    /**
     * Compute altitude using the default sea-level pressure (1013.25 hPa).
     *
     * @return altitude in m
     * @throws IOException on I²C error
     */
    double altitude() {
        return altitude(DEFAULT_SEA_LEVEL_HPA)
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
    double altitude(double seaLevelHpa) {
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
    double seaLevelPressure(double altitudeM) {
        double p = pressure()
        return p / Math.pow(1.0 - altitudeM / 44330.0, 5.255)
    }

    /**
     * Read the chip ID register (0xD0).
     *
     * <p>Expected value is 0x58 for BMP280.
     *
     * @return chip ID byte
     * @throws IOException on I²C error
     */
    int chipId() {
        byte[] b = transport.writeRead([(byte) REG_ID] as byte[], 1)
        return b[0] & 0xFF
    }

    /**
     * Perform a soft reset, reload calibration, and re-apply the current
     * ctrl_meas and config register values.
     *
     * <p>Writes 0xB6 to register 0xE0, waits 2 ms for the chip to complete
     * its power-on sequence, then re-reads calibration NVM and restores the
     * previously configured ctrl_meas and config registers.
     *
     * @throws IOException on I²C error
     */
    void reset() {
        transport.write([(byte) REG_SOFT_RST, (byte) 0xB6] as byte[])
        Thread.sleep(2)
        readCalibration()
        transport.write([(byte) REG_CONFIG,    (byte) config]   as byte[])
        transport.write([(byte) REG_CTRL_MEAS, (byte) ctrlMeas] as byte[])
    }
}
