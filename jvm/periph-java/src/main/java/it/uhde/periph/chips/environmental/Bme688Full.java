package it.uhde.periph.chips.environmental;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * BME688 — full driver. Extends {@link Bme688Minimal} with oversampling
 * control for all three TPH channels, IIR filter configuration, multi-profile
 * heater management, gas measurement control, combined read-all, status
 * polling, chip and variant ID read-back, the parallel mode and soft reset.
 *
 * <h2>Oversampling constants</h2>
 * {@link #OSRS_SKIP}, {@link #OSRS_X1}, {@link #OSRS_X2}, {@link #OSRS_X4},
 * {@link #OSRS_X8}, {@link #OSRS_X16}
 *
 * <h2>Mode constants</h2>
 * {@link #MODE_SLEEP}, {@link #MODE_FORCED}, {@link #MODE_PARALLEL}
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
public class Bme688Full extends Bme688Minimal {

    public static final int OSRS_SKIP = 0;
    public static final int OSRS_X1   = 1;
    public static final int OSRS_X2   = 2;
    public static final int OSRS_X4   = 3;
    public static final int OSRS_X8   = 4;
    public static final int OSRS_X16  = 5;

    public static final int MODE_SLEEP  = 0;
    public static final int MODE_FORCED = 1;
    public static final int MODE_PARALLEL = 2;

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
     * @param connection I²C connection bound to the BME688 address (0x76 or 0x77)
     * @throws IOException on I²C error, wrong chip ID, or invalid calibration
     */
    public Bme688Full(RegisterConnection connection) throws IOException {
        super(connection);
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
        connection.write(REG_CTRL_HUM, new byte[]{(byte) ctrlHum});
        connection.write(REG_CONFIG, new byte[]{(byte) config});
        connection.write(REG_CTRL_MEAS, new byte[]{(byte) ctrlMeas});
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
        connection.write(REG_CTRL_HUM, new byte[]{(byte) ctrlHum});
        connection.write(REG_CTRL_MEAS, new byte[]{(byte) ctrlMeas});
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
        connection.write(REG_CONFIG, new byte[]{(byte) config});
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
        connection.write(REG_CTRL_GAS_1, new byte[]{(byte) ctrlGas1});
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
        ctrlGas1 = (ctrlGas1 & ~RUN_GAS) | (enabled ? RUN_GAS : 0x00);
        connection.write(REG_CTRL_GAS_1, new byte[]{(byte) ctrlGas1});
    }

    /**
     * Turn the heater off or on via the heat_off override bit in ctrl_gas_0.
     *
     * @param off {@code true} to disable the heater
     * @throws IOException on I²C error
     */
    public void setHeaterOff(boolean off) throws IOException {
        ctrlGas0 = (ctrlGas0 & ~0x08) | (off ? 0x08 : 0x00);
        connection.write(REG_CTRL_GAS_0, new byte[]{(byte) ctrlGas0});
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
        int adcP = adcPress(raw, 0);
        int adcT = adcTemp(raw, 0);
        int adcH = adcHum(raw, 0);
        int gasAdc = adcGas(raw, 0);
        int gasRange = raw[16] & 0x0F;
        int gasValid = (raw[16] >> 5) & 1;
        int heatStab = (raw[16] >> 4) & 1;

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
     * <p>Reads the gas_valid_r bit from gas_r_lsb (0x2D) without triggering
     * a new measurement.
     *
     * @return {@code true} if the gas measurement was valid
     * @throws IOException on I²C error
     */
    public boolean gasValid() throws IOException {
        byte[] b = connection.read(0x2D, 1);
        return ((b[0] >> 5) & 1) == 1;
    }

    /**
     * Check whether the heater reached its target temperature in the most
     * recent measurement.
     *
     * <p>Reads the heat_stab_r bit from gas_r_lsb (0x2D) without triggering
     * a new measurement.
     *
     * @return {@code true} if the heater was stable
     * @throws IOException on I²C error
     */
    public boolean heaterStable() throws IOException {
        byte[] b = connection.read(0x2D, 1);
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
        byte[] b = connection.read(REG_MEAS_STATUS, 1);
        return b[0] & 0xFF;
    }

    /**
     * Read the chip ID register (0xD0).
     *
     * <p>Expected value is 0x61 for BME688.
     *
     * @return chip ID byte
     * @throws IOException on I²C error
     */
    public int chipId() throws IOException {
        byte[] b = connection.read(REG_ID, 1);
        return b[0] & 0xFF;
    }

    /**
     * Read the variant ID register (0xF0).
     *
     * <p>Expected value is 0x01 for BME688 (tells it apart from the BME680).
     *
     * @return variant ID byte
     * @throws IOException on I²C error
     */
    public int variantId() throws IOException {
        byte[] b = connection.read(REG_VARIANT_ID, 1);
        return b[0] & 0xFF;
    }

    /** One data field returned by {@link #readParallel()}. */
    public static final class Field {
        /** Heater step index (gas_meas_index). */
        public final int step;
        /** sub_meas_index counter. */
        public final int subIndex;
        /** Temperature in °C. */
        public final double temperature;
        /** Pressure in hPa. */
        public final double pressure;
        /** Relative humidity in %RH. */
        public final double humidity;
        /** Gas resistance in Ω, {@code Double.NaN} when the gas reading is not valid. */
        public final double gasResistance;
        /** gas_valid_r flag. */
        public final boolean gasValid;
        /** heat_stab_r flag. */
        public final boolean heaterStable;

        Field(int step, int subIndex, double temperature, double pressure, double humidity,
              double gasResistance, boolean gasValid, boolean heaterStable) {
            this.step = step;
            this.subIndex = subIndex;
            this.temperature = temperature;
            this.pressure = pressure;
            this.humidity = humidity;
            this.gasResistance = gasResistance;
            this.gasValid = gasValid;
            this.heaterStable = heaterStable;
        }
    }

    private static int calcSharedWait(double ms) {
        int units = (int) Math.floor(ms / 0.477 + 0.5);
        if (units <= 0x3F) return units;
        if (units <= 0x3F * 4) return (1 << 6) | (units / 4);
        if (units <= 0x3F * 16) return (2 << 6) | (units / 16);
        return (3 << 6) | Math.min(units / 64, 0x3F);
    }

    /**
     * Start parallel mode: the chip cycles TPHG through a heater profile on
     * its own.
     *
     * <p>Programs res_heat_x, gas_wait_x (repetitions), gas_wait_shared,
     * nb_conv and run_gas, then sets mode = parallel. At least one of the
     * T/P/H oversamplings must not be {@link #OSRS_SKIP}.
     *
     * @param tempsC       heater step temperatures in °C (1–10 entries)
     * @param repeats      repetitions per step (1–255 each, same length)
     * @param sharedWaitMs wait before every TPHG sequence in ms (0.477 ms resolution)
     * @throws IOException on I²C error
     */
    public void startParallel(int[] tempsC, int[] repeats, double sharedWaitMs) throws IOException {
        if (tempsC.length < 1 || tempsC.length > 10 || repeats.length != tempsC.length) {
            throw new IllegalArgumentException("1-10 heater steps with matching repeats required");
        }
        for (int i = 0; i < tempsC.length; i++) {
            if (repeats[i] < 1 || repeats[i] > 255) throw new IllegalArgumentException("repeats must be 1-255");
            connection.write(0x5A + i, new byte[]{(byte) calcHeaterResistance(tempsC[i], ambientTemp)});
            connection.write(0x64 + i, new byte[]{(byte) repeats[i]});
        }
        connection.write(0x6E, new byte[]{(byte) calcSharedWait(sharedWaitMs)});
        ctrlGas1 = RUN_GAS | tempsC.length;
        connection.write(REG_CTRL_GAS_1, new byte[]{(byte) ctrlGas1});
        connection.write(REG_CTRL_HUM, new byte[]{(byte) ctrlHum});
        connection.write(REG_CONFIG, new byte[]{(byte) config});
        connection.write(REG_CTRL_MEAS, new byte[]{(byte) ((ctrlMeas & 0xFC) | MODE_PARALLEL)});
    }

    /**
     * Burst-read the three data fields and return those holding new data.
     *
     * @return one {@link Field} per field with new_data set, in field order
     * @throws IOException on I²C error
     */
    public List<Field> readParallel() throws IOException {
        byte[] raw = connection.read(REG_MEAS_STATUS, 3 * FIELD_LEN);
        List<Field> out = new ArrayList<>();
        for (int n = 0; n < 3; n++) {
            int off = n * FIELD_LEN;
            if ((raw[off] & 0x80) == 0) continue;
            double t = compensateTemperature(adcTemp(raw, off));
            double p = compensatePressure(adcPress(raw, off));
            double h = compensateHumidity(adcHum(raw, off));
            boolean valid = ((raw[off + 16] >> 5) & 1) == 1;
            boolean stable = ((raw[off + 16] >> 4) & 1) == 1;
            double g = (valid && stable) ? compensateGas(adcGas(raw, off), raw[off + 16] & 0x0F) : Double.NaN;
            out.add(new Field(raw[off] & 0x0F, raw[off + 1] & 0xFF, t, p, h, g, valid, stable));
        }
        return out;
    }

    /**
     * Leave parallel mode (mode = sleep).
     *
     * @throws IOException on I²C error
     */
    public void stopParallel() throws IOException {
        connection.write(REG_CTRL_MEAS, new byte[]{(byte) (ctrlMeas & 0xFC)});
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
        connection.write(REG_RESET, new byte[]{(byte) RESET_CMD});
        try {
            Thread.sleep(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        readCalibration();
        setupHeater(0, heaterTempC, heaterDurationMs);
        connection.write(REG_CTRL_GAS_0, new byte[]{(byte) ctrlGas0});
        connection.write(REG_CTRL_GAS_1, new byte[]{(byte) ctrlGas1});
        connection.write(REG_CTRL_HUM, new byte[]{(byte) ctrlHum});
        connection.write(REG_CONFIG, new byte[]{(byte) config});
        connection.write(REG_CTRL_MEAS, new byte[]{(byte) ctrlMeas});
    }
}
