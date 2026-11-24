package it.uhde.periph.chips.environmental

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * BME688 — 4-in-1 environmental sensor: temperature, pressure, humidity, and
 * gas resistance (minimal driver).
 *
 * <p>Reads calibrated temperature (°C), pressure (hPa), humidity (%RH), and
 * gas resistance (Ω) via I²C using Bosch's integer compensation algorithms.
 * Calibration coefficients are loaded from the chip's NVM during construction.
 * The chip ID register is verified to be 0x61.
 *
 * <p>Configurable I²C address: 0x76 (SDO low, default) or 0x77 (SDO high).
 *
 * <p>Default settings: osrs_t=×1, osrs_p=×1, osrs_h=×1, filter off, gas
 * heater profile 0 at 320 °C for 150 ms. Measurements are triggered in
 * forced mode (one shot per call).
 */
@CompileStatic
class Bme688Minimal {

    static final int REG_RES_HEAT_VAL   = 0x00
    static final int REG_RES_HEAT_RANGE = 0x02
    static final int REG_STATUS         = 0x1D
    static final int REG_DATA           = 0x1D
    static final int REG_VARIANT_ID     = 0xF0
    static final int FIELD_LEN          = 17
    static final int RUN_GAS            = 0x20
    static final int REG_RES_HEAT_0     = 0x5A
    static final int REG_GAS_WAIT_0     = 0x64
    static final int REG_CTRL_GAS_0     = 0x70
    static final int REG_CTRL_GAS_1     = 0x71
    static final int REG_CTRL_HUM       = 0x72
    static final int REG_CTRL_MEAS      = 0x74
    static final int REG_CONFIG         = 0x75
    static final int REG_CALIB_BLOCK1   = 0x8A
    static final int REG_ID             = 0xD0
    static final int REG_RESET          = 0xE0
    static final int REG_CALIB_BLOCK2   = 0xE1
    static final int CHIP_ID            = 0x61
    static final int VARIANT_ID         = 0x01

    protected final RegisterConnection connection

    protected int parT1
    protected int parT2
    protected int parT3

    protected int parP1
    protected int parP2
    protected int parP3
    protected int parP4
    protected int parP5
    protected int parP6
    protected int parP7
    protected int parP8
    protected int parP9
    protected int parP10

    protected int parH1
    protected int parH2
    protected int parH3
    protected int parH4
    protected int parH5
    protected int parH6
    protected int parH7

    protected int parG1
    protected int parG2
    protected int parG3

    protected int resHeatVal
    protected int resHeatRange

    /** tFine shared between temperature, pressure, and humidity compensation. */
    protected int tFine

    /** Latest ambient temperature used for heater-resistance calculation. */
    protected double ambientTemp = 25.0d

    /** ctrl_hum register value. */
    protected int ctrlHum = 0x01
    /** ctrl_meas register value (mode bits are sleep by default). */
    protected int ctrlMeas = 0x24
    /** config register value. */
    protected int config = 0x00
    /** ctrl_gas_1 register value. */
    protected int ctrlGas1 = 0x20

    /** Heater target temperature in °C for the active profile. */
    protected int heaterTemp = 320
    /** Heater duration in ms for the active profile. */
    protected int heaterDuration = 150

    /** Gas-valid flag from the most recent measurement. */
    protected boolean lastGasValid = false
    /** Heater-stable flag from the most recent measurement. */
    protected boolean lastHeatStable = false

    /**
     * Construct the driver at the default address (0x76), verify the chip ID,
     * load calibration data, and configure heater profile 0.
     *
     * @param connection I²C connection bound to address 0x76
     * @throws IOException on I²C error or wrong chip ID
     */
    Bme688Minimal(RegisterConnection connection) {
        this(connection, 0x76)
    }

    /**
     * Construct the driver at the given address, verify the chip ID, load
     * calibration data, and configure heater profile 0.
     *
     * @param connection I²C connection bound to the given address
     * @param addr      I²C device address (0x76 or 0x77)
     * @throws IOException on I²C error or wrong chip ID
     */
    Bme688Minimal(RegisterConnection connection, int addr) {
        this.connection = connection

        byte[] id = connection.read(REG_ID, 1)
        int chipId = id[0] & 0xFF
        if (chipId != CHIP_ID) {
            throw new IOException(
                "BME688 not found: expected 0x61, got 0x" +
                Integer.toHexString(chipId))
        }

        readCalibration()
        writeSettings()

        int resHeat = calcHeaterResistance(heaterTemp, ambientTemp)
        connection.write(REG_RES_HEAT_0, [(byte) resHeat] as byte[])
        int gasWait = encodeGasWait(heaterDuration)
        connection.write(REG_GAS_WAIT_0, [(byte) gasWait] as byte[])
    }

    /**
     * Read and unpack all 28 calibration parameters from three NVM regions:
     * block 1 (23 bytes from 0x8A), block 2 (14 bytes from 0xE1), and three
     * single-byte registers at 0x00, 0x02, and 0x04.
     *
     * @throws IOException on I²C error
     */
    protected void readCalibration() {
        byte[] b1 = connection.read(REG_CALIB_BLOCK1, 23)
        byte[] b2 = connection.read(REG_CALIB_BLOCK2, 14)

        parT2 = (int)(short)(((b1[1] & 0xFF) << 8) | (b1[0] & 0xFF))
        parT3 = (int) b1[2]
        parP1 = ((b1[5] & 0xFF) << 8) | (b1[4] & 0xFF)
        parP2 = (int)(short)(((b1[7] & 0xFF) << 8) | (b1[6] & 0xFF))
        parP3 = (int) b1[8]
        parP4 = (int)(short)(((b1[11] & 0xFF) << 8) | (b1[10] & 0xFF))
        parP5 = (int)(short)(((b1[13] & 0xFF) << 8) | (b1[12] & 0xFF))
        parP7 = (int) b1[14]
        parP6 = (int) b1[15]
        parP8 = (int)(short)(((b1[19] & 0xFF) << 8) | (b1[18] & 0xFF))
        parP9 = (int)(short)(((b1[21] & 0xFF) << 8) | (b1[20] & 0xFF))
        parP10 = b1[22] & 0xFF

        parH2 = ((b2[0] & 0xFF) << 4) | ((b2[1] & 0xFF) >> 4)
        parH1 = ((b2[2] & 0xFF) << 4) | ((b2[1] & 0xFF) & 0x0F)
        parH3 = (int) b2[3]
        parH4 = (int) b2[4]
        parH5 = (int) b2[5]
        parH6 = b2[6] & 0xFF
        parH7 = (int) b2[7]
        parT1 = ((b2[9] & 0xFF) << 8) | (b2[8] & 0xFF)
        parG2 = (int)(short)(((b2[11] & 0xFF) << 8) | (b2[10] & 0xFF))
        parG1 = (int) b2[12]
        parG3 = (int) b2[13]

        byte[] rhv = connection.read(REG_RES_HEAT_VAL, 1)
        resHeatVal = (int) rhv[0]

        byte[] rhr = connection.read(REG_RES_HEAT_RANGE, 1)
        resHeatRange = (rhr[0] & 0xFF) >> 4 & 0x03

    }

    /**
     * Write the current ctrl_hum, config, ctrl_meas, and ctrl_gas_1 register
     * values to the chip. ctrl_hum is always written before ctrl_meas so that
     * humidity oversampling changes take effect.
     *
     * @throws IOException on I²C error
     */
    protected void writeSettings() {
        connection.write(REG_CTRL_HUM, [(byte) ctrlHum] as byte[])
        connection.write(REG_CONFIG, [(byte) config] as byte[])
        connection.write(REG_CTRL_MEAS, [(byte) ctrlMeas] as byte[])
        connection.write(REG_CTRL_GAS_1, [(byte) ctrlGas1] as byte[])
    }

    /**
     * Trigger a forced-mode TPHG measurement, poll meas_status until new_data
     * is set and measuring is clear, and burst-read the 17-byte field from
     * 0x1D. Updates {@link #lastGasValid} and {@link #lastHeatStable} from the
     * gas status bits in the response. The timeout is the heater time plus
     * 500 ms.
     *
     * @return 17-byte field (0x1D-0x2D): status, sub index, press, temp, hum, gas
     * @throws IOException on I²C error or measurement timeout
     */
    protected byte[] readRawData() {
        connection.write(REG_CTRL_HUM, [(byte) ctrlHum] as byte[])
        connection.write(REG_CTRL_MEAS, [(byte) ((ctrlMeas & 0xFC) | 0x01)] as byte[])
        long deadline = System.nanoTime() + (heaterDuration + 500L) * 1000000L
        while (true) {
            int st = connection.read(REG_STATUS, 1)[0] & 0xFF
            if ((st & 0x80) != 0 && (st & 0x20) == 0) break
            if (System.nanoTime() > deadline) throw new IOException('BME688 measurement timeout')
            Thread.sleep(5)
        }
        byte[] raw = connection.read(REG_DATA, FIELD_LEN)
        lastGasValid = ((raw[16] & 0xFF) >> 5 & 1) == 1
        lastHeatStable = ((raw[16] & 0xFF) >> 4 & 1) == 1
        return raw
    }

    /** Raw pressure ADC of the field starting at {@code off}. */
    protected static int adcPress(byte[] raw, int off) {
        return ((raw[off + 2] & 0xFF) << 12) | ((raw[off + 3] & 0xFF) << 4) | ((raw[off + 4] & 0xFF) >> 4)
    }

    /** Raw temperature ADC of the field starting at {@code off}. */
    protected static int adcTemp(byte[] raw, int off) {
        return ((raw[off + 5] & 0xFF) << 12) | ((raw[off + 6] & 0xFF) << 4) | ((raw[off + 7] & 0xFF) >> 4)
    }

    /** Raw humidity ADC of the field starting at {@code off}. */
    protected static int adcHum(byte[] raw, int off) {
        return ((raw[off + 8] & 0xFF) << 8) | (raw[off + 9] & 0xFF)
    }

    /** Raw gas ADC of the field starting at {@code off}. */
    protected static int adcGas(byte[] raw, int off) {
        return ((raw[off + 15] & 0xFF) << 2) | ((raw[off + 16] & 0xFF) >> 6)
    }

    /**
     * Compute temperature compensation and update tFine.
     *
     * @param adcT raw 20-bit temperature ADC value
     * @return temperature in °C
     */
    protected double compensateTemperature(int adcT) {
        long var1 = ((long)(adcT >> 3)) - ((long)(parT1 << 1))
        long var2 = (var1 * (long) parT2) >> 11
        long var3 = (((var1 >> 1) * (var1 >> 1)) >> 12) * ((long) parT3 << 4) >> 14
        tFine = (int)(var2 + var3)
        double t = ((tFine * 5L + 128L) >> 8) / 100.0d
        ambientTemp = t
        return t
    }

    /**
     * Compute pressure compensation using the current tFine value.
     *
     * @param adcP raw 20-bit pressure ADC value
     * @return pressure in hPa
     */
    protected double compensatePressure(int adcP) {
        long var1 = ((long) tFine >> 1) - 64000L
        long var2 = ((((var1 >> 2) * (var1 >> 2)) >> 11) * (long) parP6) >> 2
        var2 = var2 + ((var1 * (long) parP5) << 1)
        var2 = (var2 >> 2) + ((long) parP4 << 16)
        var1 = (((((var1 >> 2) * (var1 >> 2)) >> 13) * ((long) parP3 << 5)) >> 3) + (((long) parP2 * var1) >> 1)
        var1 = var1 >> 18
        var1 = ((32768L + var1) * (long) parP1) >> 15
        if (var1 == 0L) return 0.0d
        long pressComp = 1048576L - (long) adcP
        pressComp = ((pressComp - (var2 >> 12)) * 3125L)
        if (pressComp >= (1L << 30)) {
            pressComp = pressComp.intdiv(var1) << 1
        } else {
            pressComp = (pressComp << 1).intdiv(var1)
        }
        var1 = ((long) parP9 * (((pressComp >> 3) * (pressComp >> 3)) >> 13)) >> 12
        var2 = ((pressComp >> 2) * (long) parP8) >> 13
        long var3 = ((pressComp >> 8) * (pressComp >> 8) * (pressComp >> 8) * (long) parP10) >> 17
        pressComp = pressComp + ((var1 + var2 + var3 + ((long) parP7 << 7)) >> 4)
        return pressComp / 100.0d
    }

    /**
     * Compute humidity compensation using the current tFine value.
     *
     * @param humAdc raw 16-bit humidity ADC value
     * @return humidity in %RH
     */
    protected double compensateHumidity(int humAdc) {
        long tempScaled = (long) tFine
        long var1 = (long) humAdc - (((long) parH1 << 4) + (((tempScaled * (long) parH3).intdiv(100L)) >> 1))
        long var2 = ((long) parH2 * (((tempScaled * (long) parH4).intdiv(100L)) +
                     (((tempScaled * ((tempScaled * (long) parH5).intdiv(100L))) >> 6).intdiv(100L)) +
                     (1L << 14))) >> 10
        long var3 = var1 * var2
        long var4 = (((long) parH6 << 7) + ((tempScaled * (long) parH7).intdiv(100L))) >> 4
        long var5 = ((var3 >> 14) * (var3 >> 14)) >> 10
        long var6 = (var4 * var5) >> 1
        long humComp = (((var3 + var6) >> 10) * 1000L) >> 12
        if (humComp < 0L) humComp = 0L
        if (humComp > 100000L) humComp = 100000L
        return humComp / 1000.0d
    }

    /**
     * Compute gas resistance (BME688 formula: no lookup tables and no
     * calibration term).
     *
     * @param gasAdc   raw 10-bit gas ADC value
     * @param gasRange gas-range code (0-15)
     * @return gas resistance in Ω, or NaN if the divisor is not positive
     */
    protected double compensateGasResistance(int gasAdc, int gasRange) {
        long var1 = 262144L >> gasRange
        long var2 = 4096L + 3L * ((long) gasAdc - 512L)
        if (var2 <= 0L) return Double.NaN
        return (double) (((10000L * var1).intdiv(var2)) * 100L)
    }

    /**
     * Compute the heater target resistance register value for a given target
     * temperature and ambient temperature.
     *
     * @param targetTemp desired heater temperature in °C
     * @param ambTemp    ambient temperature in °C
     * @return byte value to write to a res_heat_x register
     */
    protected int calcHeaterResistance(int targetTemp, double ambTemp) {
        // Multiply as double before flooring to whole tenths, matching the
        // spec's `((amb_temp_c * par_G3) // 10) << 8` - truncating ambTemp
        // to long *before* the multiply (the previous implementation)
        // silently discards its fractional part.
        long var1 = ((long) Math.floor((ambTemp * parG3) / 10.0d)) << 8
        long var2 = (long)(parG1 + 784) * ((((long)(parG2 + 154009) * (long) targetTemp * 5L).intdiv(100L)) + 3276800L).intdiv(10L)
        long var3 = var1 + (var2 >> 1)
        long var4 = var3.intdiv((long)(resHeatRange + 4))
        long var5 = (131L * (long) resHeatVal) + 65536L
        long resHeatX100 = (var4.intdiv(var5) - 250L) * 34L
        return (int)((resHeatX100 + 50L).intdiv(100L))
    }

    /**
     * Encode a heater on-time duration into the gas_wait register format.
     *
     * <p>Uses a 6-bit timer with a 2-bit multiplier (×1, ×4, ×16, or ×64)
     * to represent durations from 1 to 4032 ms.
     *
     * @param targetMs desired heater on-time in ms (1–4032)
     * @return byte value to write to a gas_wait_x register
     */
    protected static int encodeGasWait(int targetMs) {
        if (targetMs <= 0x3F) return targetMs
        if (targetMs <= 0x3F * 4) return (1 << 6) | targetMs.intdiv(4)
        if (targetMs <= 0x3F * 16) return (2 << 6) | targetMs.intdiv(16)
        return (3 << 6) | Math.min(targetMs.intdiv(64), 0x3F)
    }

    /**
     * Read the temperature.
     *
     * <p>Triggers a forced-mode TPHG measurement, runs Bosch integer
     * compensation, and returns the result in degrees Celsius. Also updates
     * the ambient temperature used for heater-resistance calculation.
     *
     * @return temperature in °C
     * @throws IOException on I²C error
     */
    double temperature() {
        byte[] raw = readRawData()
        int adcT = adcTemp(raw, 0)
        return compensateTemperature(adcT)
    }

    /**
     * Read the pressure.
     *
     * <p>Triggers a forced-mode TPHG measurement, compensates temperature
     * first (to populate tFine), then compensates pressure. Returns the
     * result in hPa.
     *
     * @return pressure in hPa
     * @throws IOException on I²C error
     */
    double pressure() {
        byte[] raw = readRawData()
        int adcP = adcPress(raw, 0)
        int adcT = adcTemp(raw, 0)
        compensateTemperature(adcT)
        return compensatePressure(adcP)
    }

    /**
     * Read the humidity.
     *
     * <p>Triggers a forced-mode TPHG measurement, compensates temperature
     * first (to populate tFine), then compensates humidity. Returns the
     * result in %RH.
     *
     * @return humidity in %RH
     * @throws IOException on I²C error
     */
    double humidity() {
        byte[] raw = readRawData()
        int adcT = adcTemp(raw, 0)
        int humAdc = adcHum(raw, 0)
        compensateTemperature(adcT)
        return compensateHumidity(humAdc)
    }

    /**
     * Read the gas resistance.
     *
     * <p>Triggers a forced-mode TPHG measurement, compensates temperature
     * first (to populate tFine), then computes gas resistance using 64-bit
     * integer math. Returns {@code Double.NaN} if the gas measurement was
     * invalid or the heater did not stabilize.
     *
     * @return gas resistance in Ω, or {@code Double.NaN} if invalid
     * @throws IOException on I²C error
     */
    double gasResistance() {
        byte[] raw = readRawData()
        int adcT = adcTemp(raw, 0)
        compensateTemperature(adcT)
        if (!lastGasValid || !lastHeatStable) return Double.NaN
        int gasAdc = adcGas(raw, 0)
        int gasRange = raw[16] & 0x0F
        return compensateGasResistance(gasAdc, gasRange)
    }
}
