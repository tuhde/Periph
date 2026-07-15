package it.uhde.periph.chips.environmental

import groovy.transform.CompileStatic
import it.uhde.periph.transport.Transport
import java.io.IOException

/**
 * BME680 — 4-in-1 environmental sensor: temperature, pressure, humidity, and
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
class Bme680Minimal {

    static final int REG_RES_HEAT_VAL   = 0x00
    static final int REG_RES_HEAT_RANGE = 0x02
    static final int REG_RANGE_SWITCH   = 0x04
    static final int REG_STATUS         = 0x1D
    static final int REG_DATA           = 0x1F
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

    private static final long[] CONST_ARRAY1 = [
        2147483647L, 2147483647L, 2147483647L, 2147483647L, 2147483647L,
        2126008810L, 2147483647L, 2130303777L, 2147483647L, 2147483647L,
        2143188679L, 2136746228L, 2147483647L, 2126008810L, 2147483647L,
        2147483647L
    ] as long[]

    private static final long[] CONST_ARRAY2 = [
        4096000000L, 2048000000L, 1024000000L, 512000000L, 255744255L,
        127110228L, 64000000L, 32258064L, 16016016L, 8000000L,
        4000000L, 2000000L, 1000000L, 500000L, 250000L, 125000L
    ] as long[]

    protected final Transport transport

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
    protected int rangeSwitchError

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
    protected int ctrlGas1 = 0x10

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
     * @param transport I²C transport bound to address 0x76
     * @throws IOException on I²C error or wrong chip ID
     */
    Bme680Minimal(Transport transport) {
        this(transport, 0x76)
    }

    /**
     * Construct the driver at the given address, verify the chip ID, load
     * calibration data, and configure heater profile 0.
     *
     * @param transport I²C transport bound to the given address
     * @param addr      I²C device address (0x76 or 0x77)
     * @throws IOException on I²C error or wrong chip ID
     */
    Bme680Minimal(Transport transport, int addr) {
        this.transport = transport

        byte[] id = transport.writeRead([(byte) REG_ID] as byte[], 1)
        int chipId = id[0] & 0xFF
        if (chipId != CHIP_ID) {
            throw new IOException(
                "BME680 not found: expected 0x61, got 0x" +
                Integer.toHexString(chipId))
        }

        readCalibration()
        writeSettings()

        int resHeat = calcHeaterResistance(heaterTemp, (int) ambientTemp)
        transport.write([(byte) REG_RES_HEAT_0, (byte) resHeat] as byte[])
        int gasWait = encodeGasWait(heaterDuration)
        transport.write([(byte) REG_GAS_WAIT_0, (byte) gasWait] as byte[])
    }

    /**
     * Read and unpack all 28 calibration parameters from three NVM regions:
     * block 1 (23 bytes from 0x8A), block 2 (14 bytes from 0xE1), and three
     * single-byte registers at 0x00, 0x02, and 0x04.
     *
     * @throws IOException on I²C error
     */
    protected void readCalibration() {
        byte[] b1 = transport.writeRead([(byte) REG_CALIB_BLOCK1] as byte[], 23)
        byte[] b2 = transport.writeRead([(byte) REG_CALIB_BLOCK2] as byte[], 14)

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

        byte[] rhv = transport.writeRead([(byte) REG_RES_HEAT_VAL] as byte[], 1)
        resHeatVal = (int) rhv[0]

        byte[] rhr = transport.writeRead([(byte) REG_RES_HEAT_RANGE] as byte[], 1)
        resHeatRange = (rhr[0] & 0xFF) >> 4 & 0x03

        byte[] rse = transport.writeRead([(byte) REG_RANGE_SWITCH] as byte[], 1)
        int rseRaw = (rse[0] & 0xFF) >> 4
        rangeSwitchError = rseRaw > 7 ? rseRaw - 16 : rseRaw
    }

    /**
     * Write the current ctrl_hum, config, ctrl_meas, and ctrl_gas_1 register
     * values to the chip. ctrl_hum is always written before ctrl_meas so that
     * humidity oversampling changes take effect.
     *
     * @throws IOException on I²C error
     */
    protected void writeSettings() {
        transport.write([(byte) REG_CTRL_HUM, (byte) ctrlHum] as byte[])
        transport.write([(byte) REG_CONFIG, (byte) config] as byte[])
        transport.write([(byte) REG_CTRL_MEAS, (byte) ctrlMeas] as byte[])
        transport.write([(byte) REG_CTRL_GAS_1, (byte) ctrlGas1] as byte[])
    }

    /**
     * Trigger a forced-mode TPHG measurement and burst-read 13 bytes of data
     * from 0x1F. Updates {@link #lastGasValid} and {@link #lastHeatStable}
     * from the gas status bits in the response.
     *
     * @return 13-byte raw data array: pressure (3), temperature (3),
     *         humidity (2), status (3), gas (2)
     * @throws IOException on I²C error
     */
    protected byte[] readRawData() {
        transport.write([(byte) REG_CTRL_HUM, (byte) ctrlHum] as byte[])
        transport.write([(byte) REG_CTRL_MEAS, (byte)((ctrlMeas & 0xFC) | 0x01)] as byte[])
        Thread.sleep((long)(heaterDuration + 50))
        byte[] raw = transport.writeRead([(byte) REG_DATA] as byte[], 13)
        lastGasValid = ((raw[12] & 0xFF) >> 5 & 1) == 1
        lastHeatStable = ((raw[12] & 0xFF) >> 4 & 1) == 1
        return raw
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
     * Compute gas resistance using 64-bit integer math with the two 16-entry
     * lookup tables.
     *
     * @param gasAdc   raw 10-bit gas ADC value
     * @param gasRange gas-range code (0–15) selecting the lookup table index
     * @return gas resistance in Ω
     */
    protected double compensateGasResistance(int gasAdc, int gasRange) {
        long var1 = ((1340L + 5L * (long) rangeSwitchError) * CONST_ARRAY1[gasRange]) >> 16
        long var2 = (((long) gasAdc << 15) - (1L << 24)) + var1
        long gasRes = ((CONST_ARRAY2[gasRange] * var1) >> 9) + (var2 >> 1)
        return (double) gasRes.intdiv(var2)
    }

    /**
     * Compute the heater target resistance register value for a given target
     * temperature and ambient temperature.
     *
     * @param targetTemp desired heater temperature in °C
     * @param ambTemp    ambient temperature in °C
     * @return byte value to write to a res_heat_x register
     */
    protected int calcHeaterResistance(int targetTemp, int ambTemp) {
        long var1 = (((long) ambTemp * (long) parG3).intdiv(10L)) << 8
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
        int adcT = ((raw[3] & 0xFF) << 12) | ((raw[4] & 0xFF) << 4) | ((raw[5] & 0xFF) >> 4)
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
        int adcP = ((raw[0] & 0xFF) << 12) | ((raw[1] & 0xFF) << 4) | ((raw[2] & 0xFF) >> 4)
        int adcT = ((raw[3] & 0xFF) << 12) | ((raw[4] & 0xFF) << 4) | ((raw[5] & 0xFF) >> 4)
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
        int adcT = ((raw[3] & 0xFF) << 12) | ((raw[4] & 0xFF) << 4) | ((raw[5] & 0xFF) >> 4)
        int humAdc = ((raw[6] & 0xFF) << 8) | (raw[7] & 0xFF)
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
        int adcT = ((raw[3] & 0xFF) << 12) | ((raw[4] & 0xFF) << 4) | ((raw[5] & 0xFF) >> 4)
        compensateTemperature(adcT)
        if (!lastGasValid || !lastHeatStable) return Double.NaN
        int gasAdc = ((raw[11] & 0xFF) << 2) | ((raw[12] & 0xFF) >> 6)
        int gasRange = raw[12] & 0x0F
        return compensateGasResistance(gasAdc, gasRange)
    }
}
