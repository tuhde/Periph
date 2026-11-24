package it.uhde.periph.chips.environmental

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * BME688 — full driver. Extends {@link Bme688Minimal} with oversampling
 * control for all three TPH channels, IIR filter configuration, multi-profile
 * heater management, gas-conversion control, ambient-temperature override,
 * combined read-all, gas-validity and heater-stability checks, altitude,
 * sea-level pressure, dew-point calculation, status polling, chip ID
 * read-back, and soft reset.
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
@CompileStatic
class Bme688Full extends Bme688Minimal {

    static final int OSRS_SKIP = 0
    static final int OSRS_X1   = 1
    static final int OSRS_X2   = 2
    static final int OSRS_X4   = 3
    static final int OSRS_X8   = 4
    static final int OSRS_X16  = 5

    static final int MODE_SLEEP  = 0
    static final int MODE_FORCED = 1
    static final int MODE_PARALLEL = 2

    static final int FILTER_0   = 0
    static final int FILTER_1   = 1
    static final int FILTER_3   = 2
    static final int FILTER_7   = 3
    static final int FILTER_15  = 4
    static final int FILTER_31  = 5
    static final int FILTER_63  = 6
    static final int FILTER_127 = 7

    static final int STATUS_NEW_DATA      = 0x80
    static final int STATUS_GAS_MEASURING = 0x40
    static final int STATUS_MEASURING     = 0x20
    static final int STATUS_GAS_VALID     = 0x20
    static final int STATUS_HEATER_STABLE = 0x10

    private static final double DEFAULT_SEA_LEVEL_HPA = 1013.25

    /**
     * Construct the full driver at the default address (0x76), verify chip ID,
     * load calibration, and configure heater profile 0.
     *
     * @param connection I²C connection bound to address 0x76
     * @throws IOException on I²C error or wrong chip ID
     */
    Bme688Full(RegisterConnection connection) {
        super(connection)
    }

    /**
     * Construct the full driver at the given address, verify chip ID, load
     * calibration, and configure heater profile 0.
     *
     * @param connection I²C connection bound to the given address
     * @param addr      I²C device address (0x76 or 0x77)
     * @throws IOException on I²C error or wrong chip ID
     */
    Bme688Full(RegisterConnection connection, int addr) {
        super(connection, addr)
    }

    /**
     * Configure oversampling for all three TPH channels, operating mode, and
     * IIR filter in one call. Writes ctrl_hum, config, and ctrl_meas in the
     * correct order (ctrl_hum before ctrl_meas).
     *
     * @param osrsT  temperature oversampling (0–5, use OSRS_* constants)
     * @param osrsP  pressure oversampling (0–5, use OSRS_* constants)
     * @param osrsH  humidity oversampling (0–5, use OSRS_* constants)
     * @param mode   operating mode (use MODE_* constants)
     * @param filter IIR filter coefficient (0–7, use FILTER_* constants)
     * @throws IOException on I²C error
     */
    void configure(int osrsT, int osrsP, int osrsH, int mode, int filter) {
        ctrlHum  = osrsH & 0x07
        ctrlMeas = ((osrsT & 0x07) << 5) | ((osrsP & 0x07) << 2) | (mode & 0x03)
        config   = ((filter & 0x07) << 2)
        connection.write(REG_CTRL_HUM, [(byte) ctrlHum] as byte[])
        connection.write(REG_CONFIG, [(byte) config] as byte[])
        connection.write(REG_CTRL_MEAS, [(byte) ctrlMeas] as byte[])
    }

    /**
     * Update the temperature, pressure, and humidity oversampling settings.
     *
     * <p>Preserves the current mode bits in ctrl_meas. Writes ctrl_hum before
     * ctrl_meas so humidity oversampling changes take effect.
     *
     * @param osrsT temperature oversampling (0–5, use OSRS_* constants)
     * @param osrsP pressure oversampling (0–5, use OSRS_* constants)
     * @param osrsH humidity oversampling (0–5, use OSRS_* constants)
     * @throws IOException on I²C error
     */
    void setOversampling(int osrsT, int osrsP, int osrsH) {
        ctrlHum  = osrsH & 0x07
        ctrlMeas = ((osrsT & 0x07) << 5) | ((osrsP & 0x07) << 2) | (ctrlMeas & 0x03)
        connection.write(REG_CTRL_HUM, [(byte) ctrlHum] as byte[])
        connection.write(REG_CTRL_MEAS, [(byte) ctrlMeas] as byte[])
    }

    /**
     * Set the IIR filter coefficient applied to temperature and pressure.
     *
     * <p>Preserves the reserved and spi_3w_en bits in the config register.
     *
     * @param coeff filter coefficient (0–7, use FILTER_* constants)
     * @throws IOException on I²C error
     */
    void setFilter(int coeff) {
        config = (config & 0xE3) | ((coeff & 0x07) << 2)
        connection.write(REG_CONFIG, [(byte) config] as byte[])
    }

    /**
     * Configure heater profile 0 with the given target temperature and
     * duration, and activate it.
     *
     * @param tempC      target heater temperature in °C
     * @param durationMs heater on-time in ms (1–4032)
     * @throws IOException on I²C error
     */
    void setHeater(int tempC, int durationMs) {
        setHeaterProfile(0, tempC, durationMs)
        selectHeaterProfile(0)
    }

    /**
     * Configure one of the 10 heater profiles with a target temperature and
     * duration.
     *
     * @param index      profile index (0–9)
     * @param tempC      target heater temperature in °C
     * @param durationMs heater on-time in ms (1–4032)
     * @throws IOException on I²C error
     */
    void setHeaterProfile(int index, int tempC, int durationMs) {
        heaterTemp = tempC
        heaterDuration = durationMs
        int resHeat = calcHeaterResistance(tempC, ambientTemp)
        int gasWait = encodeGasWait(durationMs)
        connection.write((REG_RES_HEAT_0 + index), [(byte) resHeat] as byte[])
        connection.write((REG_GAS_WAIT_0 + index), [(byte) gasWait] as byte[])
    }

    /**
     * Select which heater profile to use in the next forced-mode cycle by
     * updating the nb_conv field in ctrl_gas_1.
     *
     * @param index profile index (0–9)
     * @throws IOException on I²C error
     */
    void selectHeaterProfile(int index) {
        ctrlGas1 = (ctrlGas1 & 0xF0) | (index & 0x0F)
        connection.write(REG_CTRL_GAS_1, [(byte) ctrlGas1] as byte[])
    }

    /**
     * Enable or disable the gas conversion in the next forced-mode cycle.
     *
     * @param enabled {@code true} to enable gas measurement, {@code false} to skip
     * @throws IOException on I²C error
     */
    void setGasEnabled(boolean enabled) {
        ctrlGas1 = enabled ? (ctrlGas1 | RUN_GAS) : (ctrlGas1 & ~RUN_GAS)
        connection.write(REG_CTRL_GAS_1, [(byte) ctrlGas1] as byte[])
    }

    /**
     * Turn the heater on or off via the heat_off bit in ctrl_gas_0.
     *
     * @param off {@code true} to disable the heater, {@code false} to enable
     * @throws IOException on I²C error
     */
    void setHeaterOff(boolean off) {
        int ctrlGas0 = off ? 0x08 : 0x00
        connection.write(REG_CTRL_GAS_0, [(byte) ctrlGas0] as byte[])
    }

    /**
     * Override the ambient temperature used for heater-resistance calculation
     * and re-apply the active heater profile with the new value.
     *
     * @param tempC ambient temperature in °C
     * @throws IOException on I²C error
     */
    void setAmbientTemperature(double tempC) {
        ambientTemp = tempC
        int profile = ctrlGas1 & 0x0F
        int resHeat = calcHeaterResistance(heaterTemp, ambientTemp)
        connection.write((REG_RES_HEAT_0 + profile), [(byte) resHeat] as byte[])
    }

    /**
     * Trigger a single TPHG cycle and return all four compensated values.
     *
     * <p>More efficient than calling {@link #temperature()}, {@link #pressure()},
     * {@link #humidity()}, and {@link #gasResistance()} separately, which each
     * trigger their own measurement cycle.
     *
     * @return double array: [0]=temperature °C, [1]=pressure hPa,
     *         [2]=humidity %RH, [3]=gas resistance Ω (NaN if invalid)
     * @throws IOException on I²C error
     */
    double[] readAll() {
        byte[] raw = readRawData()
        int pressAdc = adcPress(raw, 0)
        int tempAdc  = adcTemp(raw, 0)
        int humAdc   = adcHum(raw, 0)
        int gasAdc   = adcGas(raw, 0)
        int gasRange = raw[16] & 0x0F

        double t = compensateTemperature(tempAdc)
        double p = compensatePressure(pressAdc)
        double h = compensateHumidity(humAdc)
        double g = (lastGasValid && lastHeatStable) ? compensateGasResistance(gasAdc, gasRange) : Double.NaN
        [t, p, h, g] as double[]
    }

    /**
     * Check whether the most recent gas measurement was valid.
     *
     * <p>The gas_valid_r flag is set when the gas measurement completed
     * successfully (not a dummy slot).
     *
     * @return {@code true} if the last gas reading was valid
     */
    boolean gasValid() {
        return lastGasValid
    }

    /**
     * Check whether the heater reached its target temperature in the most
     * recent measurement.
     *
     * <p>The heat_stab_r flag is set when the heater stabilized within the
     * configured gas_wait duration.
     *
     * @return {@code true} if the heater was stable in the last measurement
     */
    boolean heaterStable() {
        return lastHeatStable
    }

    /**
     * Read the meas_status_0 register (0x1D).
     *
     * <p>Bit 7 ({@link #STATUS_NEW_DATA}) is set when new TPH data is
     * available. Bit 6 ({@link #STATUS_GAS_MEASURING}) is set during gas
     * conversion. Bit 5 ({@link #STATUS_MEASURING}) is set during any
     * conversion.
     *
     * @return raw status byte
     * @throws IOException on I²C error
     */
    int status() {
        byte[] b = connection.read(REG_STATUS, 1)
        return b[0] & 0xFF
    }

    /**
     * Read the chip ID register (0xD0).
     *
     * <p>Expected value is 0x61 for BME688.
     *
     * @return chip ID byte
     * @throws IOException on I²C error
     */
    int chipId() {
        byte[] b = connection.read(REG_ID, 1)
        return b[0] & 0xFF
    }

    /**
     * Read the variant ID register (0xF0).
     *
     * <p>Expected value is 0x01 for BME688 (tells it apart from the BME680).
     *
     * @return variant ID byte
     * @throws IOException on I²C error
     */
    int variantId() {
        byte[] b = connection.read(REG_VARIANT_ID, 1)
        return b[0] & 0xFF
    }

    /** One data field returned by {@link #readParallel()}. */
    static class Field {
        /** Heater step index (gas_meas_index). */
        int step
        /** sub_meas_index counter. */
        int subIndex
        /** Temperature in °C. */
        double temperature
        /** Pressure in hPa. */
        double pressure
        /** Relative humidity in %RH. */
        double humidity
        /** Gas resistance in Ω, NaN when the gas reading is not valid. */
        double gasResistance
        /** gas_valid_r flag. */
        boolean gasValid
        /** heat_stab_r flag. */
        boolean heaterStable
    }

    private static int calcSharedWait(double ms) {
        int units = (int) Math.floor(ms / 0.477d + 0.5d)
        if (units <= 0x3F) return units
        if (units <= 0x3F * 4) return (1 << 6) | units.intdiv(4)
        if (units <= 0x3F * 16) return (2 << 6) | units.intdiv(16)
        return (3 << 6) | Math.min(units.intdiv(64), 0x3F)
    }

    /**
     * Start parallel mode: the chip cycles TPHG through a heater profile on
     * its own. Programs res_heat_x, gas_wait_x (repetitions), gas_wait_shared,
     * nb_conv and run_gas, then sets mode = parallel. At least one of the
     * T/P/H oversamplings must not be {@link #OSRS_SKIP}.
     *
     * @param tempsC       heater step temperatures in °C (1-10 entries)
     * @param repeats      repetitions per step (1-255 each, same length)
     * @param sharedWaitMs wait before every TPHG sequence in ms (0.477 ms resolution)
     * @throws IOException on I²C error
     */
    void startParallel(int[] tempsC, int[] repeats, double sharedWaitMs) {
        if (tempsC.length < 1 || tempsC.length > 10 || repeats.length != tempsC.length) {
            throw new IllegalArgumentException('1-10 heater steps with matching repeats required')
        }
        for (int i = 0; i < tempsC.length; i++) {
            if (repeats[i] < 1 || repeats[i] > 255) throw new IllegalArgumentException('repeats must be 1-255')
            connection.write(REG_RES_HEAT_0 + i, [(byte) calcHeaterResistance(tempsC[i], ambientTemp)] as byte[])
            connection.write(REG_GAS_WAIT_0 + i, [(byte) repeats[i]] as byte[])
        }
        connection.write(0x6E, [(byte) calcSharedWait(sharedWaitMs)] as byte[])
        ctrlGas1 = RUN_GAS | tempsC.length
        connection.write(REG_CTRL_GAS_1, [(byte) ctrlGas1] as byte[])
        connection.write(REG_CTRL_HUM, [(byte) ctrlHum] as byte[])
        connection.write(REG_CONFIG, [(byte) config] as byte[])
        connection.write(REG_CTRL_MEAS, [(byte) ((ctrlMeas & 0xFC) | MODE_PARALLEL)] as byte[])
    }

    /**
     * Burst-read the three data fields and return those holding new data.
     *
     * @return one {@link Field} per field with new_data set, in field order
     * @throws IOException on I²C error
     */
    List<Field> readParallel() {
        byte[] raw = connection.read(REG_DATA, 3 * FIELD_LEN)
        List<Field> out = []
        for (int n = 0; n < 3; n++) {
            int off = n * FIELD_LEN
            if ((raw[off] & 0x80) == 0) continue
            Field f = new Field()
            f.step = raw[off] & 0x0F
            f.subIndex = raw[off + 1] & 0xFF
            f.temperature = compensateTemperature(adcTemp(raw, off))
            f.pressure = compensatePressure(adcPress(raw, off))
            f.humidity = compensateHumidity(adcHum(raw, off))
            f.gasValid = ((raw[off + 16] & 0xFF) >> 5 & 1) == 1
            f.heaterStable = ((raw[off + 16] & 0xFF) >> 4 & 1) == 1
            f.gasResistance = (f.gasValid && f.heaterStable)
                ? compensateGasResistance(adcGas(raw, off), raw[off + 16] & 0x0F) : Double.NaN
            out << f
        }
        return out
    }

    /**
     * Leave parallel mode (mode = sleep).
     *
     * @throws IOException on I²C error
     */
    void stopParallel() {
        connection.write(REG_CTRL_MEAS, [(byte) (ctrlMeas & 0xFC)] as byte[])
    }

    /**
     * Perform a soft reset, reload calibration, and re-apply the current
     * settings and heater profile.
     *
     * <p>Writes 0xB6 to register 0xE0, waits 2 ms for the chip to complete
     * its power-on sequence, then re-reads calibration NVM and restores the
     * previously configured registers and heater profile.
     *
     * @throws IOException on I²C error
     */
    void reset() {
        connection.write(REG_RESET, [(byte) 0xB6] as byte[])
        Thread.sleep(2)
        readCalibration()
        writeSettings()
        int profile = ctrlGas1 & 0x0F
        int resHeat = calcHeaterResistance(heaterTemp, ambientTemp)
        connection.write((REG_RES_HEAT_0 + profile), [(byte) resHeat] as byte[])
        int gasWait = encodeGasWait(heaterDuration)
        connection.write((REG_GAS_WAIT_0 + profile), [(byte) gasWait] as byte[])
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
        return 44330.0d * (1.0d - Math.pow(p / seaLevelHpa, 1.0d / 5.255d))
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
        return p / Math.pow(1.0d - altitudeM / 44330.0d, 5.255d)
    }

    /**
     * Compute the dew point from a single TPHG measurement using the
     * Magnus-Tetens approximation.
     *
     * @return dew-point temperature in °C
     * @throws IOException on I²C error
     */
    double dewPoint() {
        double[] all = readAll()
        double t  = all[0]
        double rh = all[2]
        double lnRh = Math.log(rh / 100.0d)
        double m = 17.625d * t / (243.04d + t)
        return 243.04d * (lnRh + m) / (17.625d - lnRh - m)
    }
}
