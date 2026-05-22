package it.uhde.periph.chips.pressure;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

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
public class Bmp280Full extends Bmp280Minimal {

    // Oversampling constants
    /** Skip measurement (output set to 0x80000). */
    public static final int OSRS_SKIP = 0;
    /** Oversampling ×1. */
    public static final int OSRS_X1   = 1;
    /** Oversampling ×2. */
    public static final int OSRS_X2   = 2;
    /** Oversampling ×4. */
    public static final int OSRS_X4   = 3;
    /** Oversampling ×8. */
    public static final int OSRS_X8   = 4;
    /** Oversampling ×16. */
    public static final int OSRS_X16  = 5;

    // Mode constants
    /** Sleep mode: no measurements performed. */
    public static final int MODE_SLEEP  = 0;
    /** Forced mode: single measurement then sleep. */
    public static final int MODE_FORCED = 1;
    /** Normal mode: continuous measurements with standby interval. */
    public static final int MODE_NORMAL = 3;

    // IIR filter coefficient constants
    /** IIR filter off. */
    public static final int FILTER_OFF = 0;
    /** IIR filter coefficient 2. */
    public static final int FILTER_2   = 1;
    /** IIR filter coefficient 4. */
    public static final int FILTER_4   = 2;
    /** IIR filter coefficient 8. */
    public static final int FILTER_8   = 3;
    /** IIR filter coefficient 16. */
    public static final int FILTER_16  = 4;

    // Standby time constants (normal mode)
    /** Standby time 0.5 ms. */
    public static final int T_SB_0_5_MS  = 0;
    /** Standby time 62.5 ms. */
    public static final int T_SB_62_5_MS = 1;
    /** Standby time 125 ms. */
    public static final int T_SB_125_MS  = 2;
    /** Standby time 250 ms. */
    public static final int T_SB_250_MS  = 3;
    /** Standby time 500 ms. */
    public static final int T_SB_500_MS  = 4;
    /** Standby time 1000 ms. */
    public static final int T_SB_1000_MS = 5;
    /** Standby time 2000 ms. */
    public static final int T_SB_2000_MS = 6;
    /** Standby time 4000 ms. */
    public static final int T_SB_4000_MS = 7;

    // Status bit masks
    /** Status bit: device is currently performing a measurement. */
    public static final int STATUS_MEASURING  = 0x08;
    /** Status bit: NVM data is being copied to image registers. */
    public static final int STATUS_IM_UPDATE  = 0x01;

    private static final double DEFAULT_SEA_LEVEL_HPA = 1013.25;

    /**
     * Construct the full driver at the default address (0x76), verify chip ID,
     * and load calibration.
     *
     * @param transport I²C transport bound to address 0x76
     * @throws IOException on I²C error, wrong chip ID, or invalid calibration
     */
    public Bmp280Full(Transport transport) throws IOException {
        super(transport);
    }

    /**
     * Construct the full driver at the given address, verify chip ID, and load
     * calibration.
     *
     * @param transport I²C transport bound to the given address
     * @param addr      I²C device address (0x76 or 0x77)
     * @throws IOException on I²C error, wrong chip ID, or invalid calibration
     */
    public Bmp280Full(Transport transport, int addr) throws IOException {
        super(transport, addr);
    }

    /**
     * Configure oversampling, operating mode, IIR filter, and standby time in
     * one call.
     *
     * @param osrsT  temperature oversampling (0–5, use {@code OSRS_*})
     * @param osrsP  pressure oversampling (0–5, use {@code OSRS_*})
     * @param mode   operating mode (use {@code MODE_*})
     * @param filter IIR filter coefficient (0–4, use {@code FILTER_*})
     * @param tSb    standby time in normal mode (0–7, use {@code T_SB_*})
     * @throws IOException on I²C error
     */
    public void configure(int osrsT, int osrsP, int mode, int filter, int tSb) throws IOException {
        ctrlMeas = ((osrsT & 0x07) << 5) | ((osrsP & 0x07) << 2) | (mode & 0x03);
        config   = ((tSb   & 0x07) << 5) | ((filter & 0x07) << 2);
        transport.write(new byte[]{(byte) REG_CONFIG, (byte) config});
        transport.write(new byte[]{(byte) REG_CTRL_MEAS, (byte) ctrlMeas});
    }

    /**
     * Update the temperature and pressure oversampling settings.
     *
     * <p>Preserves the current mode bits in ctrl_meas.
     *
     * @param osrsT temperature oversampling (0–5, use {@code OSRS_*})
     * @param osrsP pressure oversampling (0–5, use {@code OSRS_*})
     * @throws IOException on I²C error
     */
    public void setOversampling(int osrsT, int osrsP) throws IOException {
        ctrlMeas = ((osrsT & 0x07) << 5) | ((osrsP & 0x07) << 2) | (ctrlMeas & 0x03);
        transport.write(new byte[]{(byte) REG_CTRL_MEAS, (byte) ctrlMeas});
    }

    /**
     * Set the operating mode.
     *
     * <p>Preserves the oversampling bits in ctrl_meas.
     *
     * @param mode operating mode (use {@code MODE_*})
     * @throws IOException on I²C error
     */
    public void setMode(int mode) throws IOException {
        ctrlMeas = (ctrlMeas & 0xFC) | (mode & 0x03);
        transport.write(new byte[]{(byte) REG_CTRL_MEAS, (byte) ctrlMeas});
    }

    /**
     * Set the IIR filter coefficient.
     *
     * <p>Preserves the standby time bits in the config register.
     *
     * @param coeff filter coefficient (0–4, use {@code FILTER_*})
     * @throws IOException on I²C error
     */
    public void setFilter(int coeff) throws IOException {
        config = (config & 0xE3) | ((coeff & 0x07) << 2);
        transport.write(new byte[]{(byte) REG_CONFIG, (byte) config});
    }

    /**
     * Set the standby time used in normal mode.
     *
     * <p>Preserves the filter bits in the config register.
     *
     * @param tSb standby time (0–7, use {@code T_SB_*})
     * @throws IOException on I²C error
     */
    public void setStandby(int tSb) throws IOException {
        config = (config & 0x1F) | ((tSb & 0x07) << 5);
        transport.write(new byte[]{(byte) REG_CONFIG, (byte) config});
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
    public int status() throws IOException {
        byte[] b = transport.writeRead(new byte[]{(byte) REG_STATUS}, 1);
        return b[0] & 0xFF;
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
     * <p>Expected value is 0x58 for BMP280.
     *
     * @return chip ID byte
     * @throws IOException on I²C error
     */
    public int chipId() throws IOException {
        byte[] b = transport.writeRead(new byte[]{(byte) REG_ID}, 1);
        return b[0] & 0xFF;
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
    public void reset() throws IOException {
        transport.write(new byte[]{(byte) REG_SOFT_RST, (byte) 0xB6});
        try {
            Thread.sleep(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        readCalibration();
        transport.write(new byte[]{(byte) REG_CONFIG,    (byte) config});
        transport.write(new byte[]{(byte) REG_CTRL_MEAS, (byte) ctrlMeas});
    }
}
