package it.uhde.periph.chips.environmental;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * BME688 — 4-in-1 environmental sensor: temperature, pressure, humidity, and
 * gas resistance (minimal driver).
 *
 * <p>Reads calibrated temperature (°C), pressure (hPa), humidity (%RH), and gas
 * sensor resistance (Ω) via I²C. Calibration coefficients are loaded from
 * three non-contiguous register blocks during construction. The chip ID
 * register is verified to be {@code 0x61}.
 *
 * <p>Default settings: osrs_t=×1, osrs_p=×1, osrs_h=×1, IIR filter off,
 * forced mode. Gas heater profile 0 is configured for 320 °C target
 * temperature with 150 ms duration.
 *
 * <p>The minimal driver uses forced mode only (the full driver adds the
 * parallel mode). Each read triggers a single-shot TPHG measurement cycle and
 * polls {@code new_data} instead of sleeping for a fixed time.
 */
public class Bme688Minimal {

    protected static final int REG_RES_HEAT_VAL    = 0x00;
    protected static final int REG_RES_HEAT_RANGE  = 0x02;
    protected static final int REG_MEAS_STATUS     = 0x1D;
    protected static final int REG_VARIANT_ID      = 0xF0;
    protected static final int FIELD_LEN           = 17;
    protected static final int RUN_GAS             = 0x20;
    protected static final int REG_CTRL_GAS_0      = 0x70;
    protected static final int REG_CTRL_GAS_1      = 0x71;
    protected static final int REG_CTRL_HUM        = 0x72;
    protected static final int REG_CTRL_MEAS       = 0x74;
    protected static final int REG_CONFIG          = 0x75;
    protected static final int REG_CAL_BLOCK1      = 0x8A;
    protected static final int REG_ID              = 0xD0;
    protected static final int REG_RESET           = 0xE0;
    protected static final int REG_CAL_BLOCK2      = 0xE1;

    protected static final int CHIP_ID      = 0x61;
    protected static final int VARIANT_ID   = 0x01;
    protected static final int RESET_CMD    = 0xB6;

    protected final RegisterConnection connection;

    protected int parT1;
    protected int parT2;
    protected int parT3;
    protected int parP1;
    protected int parP2;
    protected int parP3;
    protected int parP4;
    protected int parP5;
    protected int parP6;
    protected int parP7;
    protected int parP8;
    protected int parP9;
    protected int parP10;
    protected int parH1;
    protected int parH2;
    protected int parH3;
    protected int parH4;
    protected int parH5;
    protected int parH6;
    protected int parH7;
    protected int parG1;
    protected int parG2;
    protected int parG3;
    protected int resHeatVal;
    protected int resHeatRange;

    /** tFine shared between temperature, pressure, and humidity compensation. */
    protected int tFine;

    /** Ambient temperature (°C) used for heater-resistance calculation. */
    protected double ambientTemp = 25.0;

    protected int ctrlHum  = 0x01;
    protected int ctrlMeas = 0x24;
    protected int config   = 0x00;
    protected int ctrlGas0 = 0x00;
    protected int ctrlGas1 = RUN_GAS;

    protected int heaterTempC      = 320;
    protected int heaterDurationMs = 150;

    /**
     * Construct the driver, verify the chip ID, load calibration data, and
     * configure default settings including heater profile 0.
     *
     * @param connection I²C connection bound to the BME688 address (0x76 or 0x77)
     * @throws IOException on I²C error, wrong chip ID, or invalid calibration
     */
    public Bme688Minimal(RegisterConnection connection) throws IOException {
        this.connection = connection;

        byte[] id = connection.read(REG_ID, 1);
        int chipId = id[0] & 0xFF;
        if (chipId != CHIP_ID) {
            throw new IOException(
                    "BME688 not found: expected 0x61, got 0x"
                    + Integer.toHexString(chipId));
        }

        readCalibration();

        connection.write(REG_CTRL_HUM, new byte[]{(byte) ctrlHum});
        connection.write(REG_CTRL_MEAS, new byte[]{(byte) ctrlMeas});
        connection.write(REG_CONFIG, new byte[]{(byte) config});

        setupHeater(0, heaterTempC, heaterDurationMs);
        connection.write(REG_CTRL_GAS_1, new byte[]{(byte) ctrlGas1});
    }

    /**
     * Read and unpack all 28 calibration parameters from three register regions.
     *
     * <p>Block 1: 23 bytes from 0x8A (par_T2, par_T3, par_P1–par_P10).
     * Block 2: 14 bytes from 0xE1 (par_H1–par_H7, par_T1, par_G1–par_G3).
     * Single bytes: res_heat_val (0x00), res_heat_range (0x02).
     *
     * @throws IOException on I²C error
     */
    protected void readCalibration() throws IOException {
        byte[] b1 = connection.read(REG_CAL_BLOCK1, 23);
        byte[] b2 = connection.read(REG_CAL_BLOCK2, 14);

        parT2 = (short) (((b1[1] & 0xFF) << 8) | (b1[0] & 0xFF));
        parT3 = b1[2];
        parP1 = ((b1[5] & 0xFF) << 8) | (b1[4] & 0xFF);
        parP2 = (short) (((b1[7] & 0xFF) << 8) | (b1[6] & 0xFF));
        parP3 = b1[8];
        parP4 = (short) (((b1[11] & 0xFF) << 8) | (b1[10] & 0xFF));
        parP5 = (short) (((b1[13] & 0xFF) << 8) | (b1[12] & 0xFF));
        parP7 = b1[14];
        parP6 = b1[15];
        parP8 = (short) (((b1[19] & 0xFF) << 8) | (b1[18] & 0xFF));
        parP9 = (short) (((b1[21] & 0xFF) << 8) | (b1[20] & 0xFF));
        parP10 = b1[22] & 0xFF;

        parH2 = ((b2[0] & 0xFF) << 4) | ((b2[1] & 0xFF) >> 4);
        parH1 = ((b2[2] & 0xFF) << 4) | (b2[1] & 0x0F);
        parH3 = b2[3];
        parH4 = b2[4];
        parH5 = b2[5];
        parH6 = b2[6] & 0xFF;
        parH7 = b2[7];
        parT1 = ((b2[9] & 0xFF) << 8) | (b2[8] & 0xFF);
        parG2 = (short) (((b2[11] & 0xFF) << 8) | (b2[10] & 0xFF));
        parG1 = b2[12];
        parG3 = b2[13];

        byte[] rhv = connection.read(REG_RES_HEAT_VAL, 1);
        resHeatVal = rhv[0];

        byte[] rhr = connection.read(REG_RES_HEAT_RANGE, 1);
        resHeatRange = (rhr[0] >> 4) & 0x03;
    }

    /**
     * Trigger a forced-mode TPHG measurement, poll {@code meas_status} until
     * {@code new_data} is set and {@code measuring} is clear, and burst-read
     * the 17-byte field from 0x1D.
     *
     * <p>Writes ctrl_hum then ctrl_meas with forced mode. The timeout is the
     * heater time plus 500 ms.
     *
     * @return 17-byte field (0x1D–0x2D): status, sub index, press, temp, hum, gas
     * @throws IOException on I²C error or measurement timeout
     */
    protected byte[] triggerAndRead() throws IOException {
        connection.write(REG_CTRL_HUM, new byte[]{(byte) ctrlHum});
        connection.write(REG_CTRL_MEAS, new byte[]{(byte) ((ctrlMeas & 0xFC) | 0x01)});
        long deadline = System.nanoTime() + (heaterDurationMs + 500L) * 1_000_000L;
        while (true) {
            int st = connection.read(REG_MEAS_STATUS, 1)[0] & 0xFF;
            if ((st & 0x80) != 0 && (st & 0x20) == 0) break;
            if (System.nanoTime() > deadline) throw new IOException("BME688 measurement timeout");
            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return connection.read(REG_MEAS_STATUS, FIELD_LEN);
    }

    /** Raw press ADC of the field starting at {@code off}. */
    protected static int adcPress(byte[] raw, int off) {
        return ((raw[off + 2] & 0xFF) << 12) | ((raw[off + 3] & 0xFF) << 4) | ((raw[off + 4] & 0xFF) >> 4);
    }

    /** Raw temperature ADC of the field starting at {@code off}. */
    protected static int adcTemp(byte[] raw, int off) {
        return ((raw[off + 5] & 0xFF) << 12) | ((raw[off + 6] & 0xFF) << 4) | ((raw[off + 7] & 0xFF) >> 4);
    }

    /** Raw humidity ADC of the field starting at {@code off}. */
    protected static int adcHum(byte[] raw, int off) {
        return ((raw[off + 8] & 0xFF) << 8) | (raw[off + 9] & 0xFF);
    }

    /** Raw gas ADC of the field starting at {@code off}. */
    protected static int adcGas(byte[] raw, int off) {
        return ((raw[off + 15] & 0xFF) << 2) | ((raw[off + 16] & 0xFF) >> 6);
    }

    /**
     * Compute temperature compensation and update tFine.
     *
     * @param adcT raw 20-bit temperature ADC value
     * @return temperature in °C
     */
    protected double compensateTemperature(int adcT) {
        long var1 = ((long) adcT >> 3) - ((long) parT1 << 1);
        long var2 = (var1 * (long) parT2) >> 11;
        long var3 = ((((var1 >> 1) * (var1 >> 1)) >> 12) * ((long) parT3 << 4)) >> 14;
        tFine = (int) (var2 + var3);
        return ((tFine * 5 + 128) >> 8) / 100.0;
    }

    /**
     * Compute pressure compensation using the current tFine value.
     *
     * @param adcP raw 20-bit pressure ADC value
     * @return pressure in hPa
     */
    protected double compensatePressure(int adcP) {
        long var1 = ((long) tFine >> 1) - 64000L;
        long var2 = ((((var1 >> 2) * (var1 >> 2)) >> 11) * (long) parP6) >> 2;
        var2 = var2 + ((var1 * (long) parP5) << 1);
        var2 = (var2 >> 2) + ((long) parP4 << 16);
        var1 = (((((var1 >> 2) * (var1 >> 2)) >> 13) * ((long) parP3 << 5)) >> 3)
             + (((long) parP2 * var1) >> 1);
        var1 = var1 >> 18;
        var1 = ((32768L + var1) * (long) parP1) >> 15;
        if (var1 == 0) return 0.0;
        long pressComp = 1048576L - adcP;
        pressComp = (pressComp - (var2 >> 12)) * 3125L;
        if (pressComp >= (1L << 30)) {
            pressComp = (pressComp / var1) << 1;
        } else {
            pressComp = (pressComp << 1) / var1;
        }
        var1 = ((long) parP9 * (((pressComp >> 3) * (pressComp >> 3)) >> 13)) >> 12;
        var2 = ((pressComp >> 2) * (long) parP8) >> 13;
        long var3 = ((pressComp >> 8) * (pressComp >> 8) * (pressComp >> 8) * (long) parP10) >> 17;
        pressComp = pressComp + ((var1 + var2 + var3 + ((long) parP7 << 7)) >> 4);
        return pressComp / 100.0;
    }

    /**
     * Compute humidity compensation using the current tFine value.
     *
     * @param adcH raw 16-bit humidity ADC value
     * @return humidity in %RH
     */
    protected double compensateHumidity(int adcH) {
        long tempScaled = tFine;
        long var1 = adcH - (((long) parH1 << 4) + (((tempScaled * parH3) / 100) >> 1));
        long var2 = (long) parH2 * (((tempScaled * parH4) / 100)
                    + (((tempScaled * ((tempScaled * parH5) / 100)) >> 6) / 100)
                    + (1L << 14));
        var2 = var2 >> 10;
        long var3 = var1 * var2;
        long var4 = (((long) parH6 << 7) + ((tempScaled * parH7) / 100)) >> 4;
        long var5 = ((var3 >> 14) * (var3 >> 14)) >> 10;
        long var6 = (var4 * var5) >> 1;
        long humComp = (((var3 + var6) >> 10) * 1000L) >> 12;
        if (humComp < 0) humComp = 0;
        if (humComp > 100000) humComp = 100000;
        return humComp / 1000.0;
    }

    /**
     * Compute gas sensor resistance (BME688 formula: no lookup tables and no
     * calibration term).
     *
     * @param gasAdc   raw 10-bit gas ADC value
     * @param gasRange gas-range code (0–15)
     * @return gas resistance in Ω, or {@code Double.NaN} if computation fails
     */
    protected double compensateGas(int gasAdc, int gasRange) {
        long var1 = 262144L >> gasRange;
        long var2 = 4096L + 3L * (gasAdc - 512);
        if (var2 <= 0) return Double.NaN;
        return (double) ((10000L * var1 / var2) * 100L);
    }

    /**
     * Calculate the heater target resistance register value for a given
     * temperature and ambient.
     *
     * @param targetTempC desired heater temperature in °C
     * @param ambTempC    ambient temperature in °C
     * @return 8-bit register value for res_heat_x
     */
    protected int calcHeaterResistance(int targetTempC, double ambTempC) {
        // Multiply as double before flooring to whole tenths, matching the
        // spec's `((amb_temp_c * par_G3) // 10) << 8` - truncating ambTempC
        // to long *before* the multiply (the previous implementation)
        // silently discards its fractional part.
        long var1 = ((long) Math.floor((ambTempC * parG3) / 10.0)) << 8;
        long var2 = ((long) (parG1 + 784))
                  * ((((((long) (parG2 + 154009)) * targetTempC * 5) / 100) + 3276800L) / 10);
        long var3 = var1 + (var2 >> 1);
        long var4 = var3 / (resHeatRange + 4);
        long var5 = (131L * resHeatVal) + 65536L;
        long resHeatX100 = ((var4 / var5) - 250) * 34;
        return (int) ((resHeatX100 + 50) / 100);
    }

    /**
     * Encode a heater duration into the gas_wait_x register format.
     *
     * <p>Uses a 6-bit timer with a 2-bit multiplier (×1, ×4, ×16, ×64)
     * to represent durations from 1 to 4032 ms.
     *
     * @param targetMs desired heater on-time in ms (1–4032)
     * @return 8-bit register value for gas_wait_x
     */
    protected int calcGasWait(int targetMs) {
        if (targetMs <= 0x3F) {
            return targetMs;
        } else if (targetMs <= 0x3F * 4) {
            return (1 << 6) | (targetMs / 4);
        } else if (targetMs <= 0x3F * 16) {
            return (2 << 6) | (targetMs / 16);
        } else {
            return (3 << 6) | Math.min(targetMs / 64, 0x3F);
        }
    }

    /**
     * Configure a heater profile with the given target temperature and duration.
     *
     * @param profileIndex heater profile index (0–9)
     * @param targetTempC  desired heater temperature in °C
     * @param durationMs   heater on-time in ms (1–4032)
     * @throws IOException on I²C error
     */
    protected void setupHeater(int profileIndex, int targetTempC, int durationMs) throws IOException {
        int resHeat = calcHeaterResistance(targetTempC, ambientTemp);
        connection.write((0x5A + profileIndex), new byte[]{(byte) resHeat});
        int gasWait = calcGasWait(durationMs);
        connection.write((0x64 + profileIndex), new byte[]{(byte) gasWait});
    }

    /**
     * Read the temperature.
     *
     * <p>Triggers a forced-mode TPHG measurement, compensates temperature,
     * and updates the internal ambient temperature estimate used for heater
     * calculations.
     *
     * @return temperature in °C
     * @throws IOException on I²C error
     */
    public double temperature() throws IOException {
        byte[] raw = triggerAndRead();
        int adcT = adcTemp(raw, 0);
        double t = compensateTemperature(adcT);
        ambientTemp = t;
        return t;
    }

    /**
     * Read the pressure.
     *
     * <p>Triggers a forced-mode TPHG measurement, compensates temperature
     * first (to populate tFine), then compensates pressure.
     *
     * @return pressure in hPa
     * @throws IOException on I²C error
     */
    public double pressure() throws IOException {
        byte[] raw = triggerAndRead();
        int adcP = adcPress(raw, 0);
        int adcT = adcTemp(raw, 0);
        compensateTemperature(adcT);
        return compensatePressure(adcP);
    }

    /**
     * Read the humidity.
     *
     * <p>Triggers a forced-mode TPHG measurement, compensates temperature
     * first (to populate tFine), then compensates humidity.
     *
     * @return humidity in %RH
     * @throws IOException on I²C error
     */
    public double humidity() throws IOException {
        byte[] raw = triggerAndRead();
        int adcH = adcHum(raw, 0);
        int adcT = adcTemp(raw, 0);
        compensateTemperature(adcT);
        return compensateHumidity(adcH);
    }

    /**
     * Read the gas sensor resistance.
     *
     * <p>Triggers a forced-mode TPHG measurement and compensates the gas
     * sensor reading. Returns {@code Double.NaN} if the gas measurement
     * was invalid or the heater did not stabilize.
     *
     * @return gas resistance in Ω, or {@code Double.NaN} if invalid
     * @throws IOException on I²C error
     */
    public double gasResistance() throws IOException {
        byte[] raw = triggerAndRead();
        int gasAdc = adcGas(raw, 0);
        int gasRange = raw[16] & 0x0F;
        int gasValid = (raw[16] >> 5) & 1;
        int heatStab = (raw[16] >> 4) & 1;
        if (gasValid == 0 || heatStab == 0) return Double.NaN;
        return compensateGas(gasAdc, gasRange);
    }
}
