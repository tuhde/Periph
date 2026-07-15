package it.uhde.periph.chips.environmental;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

/**
 * BME680 — full driver. Extends {@link Bme680Minimal} with oversampling
 * control for all three TPH channels, IIR filter configuration, multi-profile
 * heater management, gas measurement control, combined read-all, status
 * polling, chip ID read-back, and soft reset.
 *
 * <h2>Oversampling constants</h2>
 * {@link #OSRS_SKIP}, {@link #OSRS_X1}, {@link #OSRS_X2}, {@link #OSRS_X4},
 * {@link #OSRS_X8}, {@link #OSRS_X16}
 *
 * <h2>Mode constants</h2>
 * {@link #MODE_SLEEP}, {@link #MODE_FORCED}
 *
 * <h2>Filter constants</h2>
 * {@link #FILTER_0}, {@link #FILTER_1}, {@link #FILTER_3}, {@link #FILTER_7},
 * {@link #FILTER_15}, {@link #FILTER_31}, {@link #FILTER_63}, {@link #FILTER_127}
 *
 * <h2>Status flags</h2>
 * {@link #STATUS_NEW_DATA}, {@link #STATUS_GAS_MEASURING},
 * {@link #STATUS_MEASURING}, {@link #STATUS_GAS_VALID},
 * {@link #STATUS_HEATER_STABLE}
 */
public class Bme680Full extends Bme680Minimal {

    public static final int OSRS_SKIP = 0;
    public static final int OSRS_X1   = 1;
    public static final int OSRS_X2   = 2;
    public static final int OSRS_X4   = 3;
    public static final int OSRS_X8   = 4;
    public static final int OSRS_X16  = 5;

    public static final int MODE_SLEEP  = 0;
    public static final int MODE_FORCED = 1;

    public static final int FILTER_0   = 0;
    public static final int FILTER_1   = 1;
    public static final int FILTER_3   = 2;
    public static final int FILTER_7   = 3;
    public static final int FILTER_15  = 4;
    public static final int FILTER_31  = 5;
    public static final int FILTER_63  = 6;
    public static final int FILTER_127 = 7;

    /** Status bit: new TPH data available since last read. */
    public static final int STATUS_NEW_DATA      = 0x80;
    /** Status bit: gas conversion in progress. */
    public static final int STATUS_GAS_MEASURING = 0x40;
    /** Status bit: any conversion in progress. */
    public static final int STATUS_MEASURING     = 0x20;
    /** Gas status bit: real gas measurement (in gas_r_lsb). */
    public static final int STATUS_GAS_VALID     = 0x20;
    /** Gas status bit: heater reached target temperature (in gas_r_lsb). */
    public static final int STATUS_HEATER_STABLE = 0x10;

    /**
     * Construct the full driver, verify chip ID, load calibration, and
     * configure default settings.
     *
     * @param transport I²C transport bound to the BME680 address (0x76 or 0x77)
     * @throws IOException on I²C error, wrong chip ID, or invalid calibration
     */
    public Bme680Full(Transport transport) throws IOException {
        super(transport);
    }

    /**
     * Configure oversampling, operating mode, and IIR filter in one call.
     *
     * <p>Writes ctrl_hum, config, and ctrl_meas in the correct order
     * (ctrl_hum must precede ctrl_meas for humidity oversampling to latch).
     *
     * @param osrsT  temperature oversampling (0–5, use {@code OSRS_*})
     * @param osrsP  pressure oversampling (0–5, use {@code OSRS_*})
     * @param osrsH  humidity oversampling (0–5, use {@code OSRS_*})
     * @param mode   operating mode (use {@code MODE_*})
     * @param filter IIR filter coefficient (0–7, use {@code FILTER_*})
     * @throws IOException on I²C error
     */
    public void configure(int osrsT, int osrsP, int osrsH, int mode, int filter) throws IOException {
        ctrlHum  = osrsH & 0x07;
        config   = ((filter & 0x07) << 2);
        ctrlMeas = ((osrsT & 0x07) << 5) | ((osrsP & 0x07) << 2) | (mode & 0x03);
        transport.write(new byte[]{(byte) REG_CTRL_HUM, (byte) ctrlHum});
        transport.write(new byte[]{(byte) REG_CONFIG, (byte) config});
        transport.write(new byte[]{(byte) REG_CTRL_MEAS, (byte) ctrlMeas});
    }

    /**
     * Update the temperature, pressure, and humidity oversampling settings.
     *
     * <p>Writes ctrl_hum then ctrl_meas to ensure humidity oversampling latches.
     * Preserves the current mode bits in ctrl_meas.
     *
     * @param osrsT temperature oversampling (0–5, use {@code OSRS_*})
     * @param osrsP pressure oversampling (0–5, use {@code OSRS_*})
     * @param osrsH humidity oversampling (0–5, use {@code OSRS_*})
     * @throws IOException on I²C error
     */
    public void setOversampling(int osrsT, int osrsP, int osrsH) throws IOException {
        ctrlHum  = osrsH & 0x07;
        ctrlMeas = ((osrsT & 0x07) << 5) | ((osrsP & 0x07) << 2) | (ctrlMeas & 0x03);
        transport.write(new byte[]{(byte) REG_CTRL_HUM, (byte) ctrlHum});
        transport.write(new byte[]{(byte) REG_CTRL_MEAS, (byte) ctrlMeas});
    }

    /**
     * Set the IIR filter coefficient.
     *
     * <p>Preserves reserved bits in the config register.
     *
     * @param coeff filter coefficient (0–7, use {@code FILTER_*})
     * @throws IOException on I²C error
     */
    public void setFilter(int coeff) throws IOException {
        config = (config & 0xE3) | ((coeff & 0x07) << 2);
        transport.write(new byte[]{(byte) REG_CONFIG, (byte) config});
    }

    /**
     * Configure heater profile 0 with target temperature and duration, and
     * select it as the active profile.
     *
     * @param tempC      target heater temperature in °C
     * @param durationMs heater on-time in ms (1–4032)
     * @throws IOException on I²C error
     */
    public void setHeater(int tempC, int durationMs) throws IOException {
        heaterTempC = tempC;
        heaterDurationMs = durationMs;
        setupHeater(0, tempC, durationMs);
        selectHeaterProfile(0);
    }

    /**
     * Configure a specific heater profile with target temperature and duration.
     *
     * <p>Does not select the profile; call {@link #selectHeaterProfile(int)}
     * to activate it.
     *
     * @param index      heater profile index (0–9)
     * @param tempC      target heater temperature in °C
     * @param durationMs heater on-time in ms (1–4032)
     * @throws IOException on I²C error
     */
    public void setHeaterProfile(int index, int tempC, int durationMs) throws IOException {
        setupHeater(index, tempC, durationMs);
    }

    /**
     * Select the active heater profile for the next forced-mode measurement.
     *
     * <p>Updates the nb_conv field in ctrl_gas_1.
     *
     * @param index heater profile index (0–9)
     * @throws IOException on I²C error
     */
    public void selectHeaterProfile(int index) throws IOException {
        ctrlGas1 = (ctrlGas1 & 0xF0) | (index & 0x0F);
        transport.write(new byte[]{(byte) REG_CTRL_GAS_1, (byte) ctrlGas1});
    }

    /**
     * Enable or disable the gas measurement in the next forced cycle.
     *
     * <p>Updates the run_gas bit in ctrl_gas_1.
     *
     * @param enabled {@code true} to enable gas conversion (run_gas=1)
     * @throws IOException on I²C error
     */
    public void setGasEnabled(boolean enabled) throws IOException {
        ctrlGas1 = (ctrlGas1 & ~0x10) | (enabled ? 0x10 : 0x00);
        transport.write(new byte[]{(byte) REG_CTRL_GAS_1, (byte) ctrlGas1});
    }

    /**
     * Turn the heater off or on via the heat_off override bit in ctrl_gas_0.
     *
     * @param off {@code true} to disable the heater
     * @throws IOException on I²C error
     */
    public void setHeaterOff(boolean off) throws IOException {
        ctrlGas0 = (ctrlGas0 & ~0x08) | (off ? 0x08 : 0x00);
        transport.write(new byte[]{(byte) REG_CTRL_GAS_0, (byte) ctrlGas0});
    }

    /**
     * Override the ambient temperature used for heater-resistance calculation
     * and re-apply the active heater profile with the new ambient value.
     *
     * @param tempC ambient temperature in °C
     * @throws IOException on I²C error
     */
    public void setAmbientTemperature(double tempC) throws IOException {
        ambientTemp = tempC;
        int nbConv = ctrlGas1 & 0x0F;
        setupHeater(nbConv, heaterTempC, heaterDurationMs);
    }

    /**
     * Trigger a single forced-mode TPHG measurement and return all four values.
     *
     * <p>More efficient than calling {@link #temperature()}, {@link #pressure()},
     * {@link #humidity()}, and {@link #gasResistance()} separately, which each
     * trigger their own measurement cycle.
     *
     * @return array of {@code [temperature °C, pressure hPa, humidity %RH, gas Ω]};
     *         gas element is {@code Double.NaN} if the measurement was invalid
     * @throws IOException on I²C error
     */
    public double[] readAll() throws IOException {
        byte[] raw = triggerAndRead();
        int adcP = ((raw[0] & 0xFF) << 12) | ((raw[1] & 0xFF) << 4) | ((raw[2] & 0xFF) >> 4);
        int adcT = ((raw[3] & 0xFF) << 12) | ((raw[4] & 0xFF) << 4) | ((raw[5] & 0xFF) >> 4);
        int adcH = ((raw[6] & 0xFF) << 8) | (raw[7] & 0xFF);
        int gasAdc = ((raw[11] & 0xFF) << 2) | ((raw[12] & 0xFF) >> 6);
        int gasRange = raw[12] & 0x0F;
        int gasValid = (raw[12] >> 5) & 1;
        int heatStab = (raw[12] >> 4) & 1;

        double t = compensateTemperature(adcT);
        ambientTemp = t;
        double p = compensatePressure(adcP);
        double h = compensateHumidity(adcH);
        double g = (gasValid == 0 || heatStab == 0) ? Double.NaN : compensateGas(gasAdc, gasRange);

        return new double[]{t, p, h, g};
    }

    /**
     * Check whether the most recent gas measurement was valid.
     *
     * <p>Reads the gas_valid_r bit from gas_r_lsb (0x2B) without triggering
     * a new measurement.
     *
     * @return {@code true} if the gas measurement was valid
     * @throws IOException on I²C error
     */
    public boolean gasValid() throws IOException {
        byte[] b = transport.writeRead(new byte[]{0x2B}, 1);
        return ((b[0] >> 5) & 1) == 1;
    }

    /**
     * Check whether the heater reached its target temperature in the most
     * recent measurement.
     *
     * <p>Reads the heat_stab_r bit from gas_r_lsb (0x2B) without triggering
     * a new measurement.
     *
     * @return {@code true} if the heater was stable
     * @throws IOException on I²C error
     */
    public boolean heaterStable() throws IOException {
        byte[] b = transport.writeRead(new byte[]{0x2B}, 1);
        return ((b[0] >> 4) & 1) == 1;
    }

    /**
     * Read the measurement status register (0x1D).
     *
     * <p>Use {@link #STATUS_NEW_DATA}, {@link #STATUS_GAS_MEASURING}, and
     * {@link #STATUS_MEASURING} to interpret the result.
     *
     * @return raw status byte
     * @throws IOException on I²C error
     */
    public int status() throws IOException {
        byte[] b = transport.writeRead(new byte[]{(byte) REG_MEAS_STATUS}, 1);
        return b[0] & 0xFF;
    }

    /**
     * Read the chip ID register (0xD0).
     *
     * <p>Expected value is 0x61 for BME680.
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
     * configuration including the heater profile.
     *
     * <p>Writes 0xB6 to register 0xE0, waits 2 ms for the chip to complete
     * its power-on sequence, then re-reads calibration NVM and restores all
     * register shadows.
     *
     * @throws IOException on I²C error
     */
    public void reset() throws IOException {
        transport.write(new byte[]{(byte) REG_RESET, (byte) RESET_CMD});
        try {
            Thread.sleep(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        readCalibration();
        setupHeater(0, heaterTempC, heaterDurationMs);
        transport.write(new byte[]{(byte) REG_CTRL_GAS_0, (byte) ctrlGas0});
        transport.write(new byte[]{(byte) REG_CTRL_GAS_1, (byte) ctrlGas1});
        transport.write(new byte[]{(byte) REG_CTRL_HUM, (byte) ctrlHum});
        transport.write(new byte[]{(byte) REG_CONFIG, (byte) config});
        transport.write(new byte[]{(byte) REG_CTRL_MEAS, (byte) ctrlMeas});
    }
}
