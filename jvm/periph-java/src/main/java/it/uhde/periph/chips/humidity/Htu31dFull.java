package it.uhde.periph.chips.humidity;

import it.uhde.periph.connection.Connection;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTU31D — full driver: single values, raw codes, separate oversampling for humidity and
 * temperature, non-blocking conversion, heater, diagnostic byte, serial number, soft reset and
 * dew point. Extends {@link Htu31dMinimal}.
 */
public class Htu31dFull extends Htu31dMinimal {

    /** Diagnostic bit: NVM CRC failed. */
    public static final int DIAG_NVM_ERR = 0x80;
    /** Diagnostic bit: humidity truncated. */
    public static final int DIAG_RH_OVERRUN = 0x40;
    /** Diagnostic bit: humidity calculation above 120 %RH. */
    public static final int DIAG_RH_HIGH_ERR = 0x20;
    /** Diagnostic bit: humidity calculation below −10 %RH. */
    public static final int DIAG_RH_LOW_ERR = 0x10;
    /** Diagnostic bit: temperature truncated. */
    public static final int DIAG_T_OVERRUN = 0x08;
    /** Diagnostic bit: temperature calculation above 150 °C. */
    public static final int DIAG_T_HIGH_ERR = 0x04;
    /** Diagnostic bit: temperature calculation below −50 °C. */
    public static final int DIAG_T_LOW_ERR = 0x02;
    /** Diagnostic bit: the heater is on. */
    public static final int DIAG_HEATER_ON = 0x01;

    /**
     * Create the driver; does no bus access.
     *
     * @param connection I²C connection bound to 0x40 or 0x41
     */
    public Htu31dFull(Connection connection) {
        super(connection);
    }

    /**
     * Run one humidity measurement (read command 0x10).
     *
     * @return relative humidity in %RH, clamped to 0–100
     * @throws IOException on bus error or CRC mismatch
     */
    public double readHumidity() throws IOException {
        return toHumidity(measure(CMD_READ_RH, 1)[0]);
    }

    /**
     * Run one temperature measurement (the read stops after the temperature word).
     *
     * @return temperature in °C
     * @throws IOException on bus error or CRC mismatch
     */
    public double readTemperature() throws IOException {
        return toTemperature(measure(CMD_READ_TRH, 1)[0]);
    }

    /**
     * Measure and return the raw 16-bit codes.
     *
     * @return {t_raw, rh_raw}
     * @throws IOException on bus error or CRC mismatch
     */
    public int[] readRaw() throws IOException {
        return measure(CMD_READ_TRH, 2);
    }

    /**
     * Select the oversampling of the following measurements.
     *
     * @param osrRh humidity oversampling 0–3
     * @param osrT  temperature oversampling 0–3
     * @throws IllegalArgumentException if either value is not 0–3
     */
    public void setResolution(int osrRh, int osrT) {
        if (osrRh < 0 || osrRh > 3 || osrT < 0 || osrT > 3) {
            throw new IllegalArgumentException("osr must be 0-3");
        }
        this.osrRh = osrRh;
        this.osrT = osrT;
    }

    /**
     * Return the oversampling setting.
     *
     * @return {osrRh, osrT}
     */
    public int[] getResolution() {
        return new int[]{osrRh, osrT};
    }

    /**
     * Typical conversion time of one measurement at the current oversampling.
     *
     * @return seconds
     */
    public double conversionTime() {
        return T_RH[osrRh] + T_T[osrT];
    }

    /**
     * Write the conversion command only (non-blocking use); wait {@link #conversionTime()} plus
     * margin before {@link #readResult()}.
     *
     * @throws IOException on bus error
     */
    public void startConversion() throws IOException {
        start();
    }

    /**
     * Read the result of the last conversion (command 0x00).
     *
     * @return {temperature °C, relative humidity %RH}
     * @throws IOException on bus error or CRC mismatch
     */
    public double[] readResult() throws IOException {
        int[] w = readWords(CMD_READ_TRH, 2);
        return new double[]{toTemperature(w[0]), toHumidity(w[1])};
    }

    /**
     * Switch the on-chip heater on (0x04) or off (0x02).
     *
     * @param on true for on
     * @throws IOException on bus error
     */
    public void setHeater(boolean on) throws IOException {
        command(on ? CMD_HEATER_ON : CMD_HEATER_OFF);
    }

    /**
     * Read the diagnostic byte (command 0x08).
     *
     * @return the byte; test it against the {@code DIAG_*} masks
     * @throws IOException on bus error
     */
    public int diagnostic() throws IOException {
        command(CMD_READ_DIAG);
        return connection.read(1)[0] & 0xFF;
    }

    /**
     * Read the diagnostic byte and name its bits.
     *
     * @return nvm_err, rh_overrun, rh_high_err, rh_low_err, t_overrun, t_high_err, t_low_err, heater_on
     * @throws IOException on bus error
     */
    public Map<String, Boolean> diagnosticFlags() throws IOException {
        int v = diagnostic();
        Map<String, Boolean> m = new LinkedHashMap<>();
        m.put("nvm_err", (v & DIAG_NVM_ERR) != 0);
        m.put("rh_overrun", (v & DIAG_RH_OVERRUN) != 0);
        m.put("rh_high_err", (v & DIAG_RH_HIGH_ERR) != 0);
        m.put("rh_low_err", (v & DIAG_RH_LOW_ERR) != 0);
        m.put("t_overrun", (v & DIAG_T_OVERRUN) != 0);
        m.put("t_high_err", (v & DIAG_T_HIGH_ERR) != 0);
        m.put("t_low_err", (v & DIAG_T_LOW_ERR) != 0);
        m.put("heater_on", (v & DIAG_HEATER_ON) != 0);
        return m;
    }

    /**
     * Whether the diagnostic byte reports the heater on.
     *
     * @return true if the heater is on
     * @throws IOException on bus error
     */
    public boolean isHeaterOn() throws IOException {
        return (diagnostic() & DIAG_HEATER_ON) != 0;
    }

    /**
     * Read the 24-bit serial number (command 0x0A, CRC verified).
     *
     * @return the serial number
     * @throws IOException on bus error or CRC mismatch
     */
    public int serialNumber() throws IOException {
        command(CMD_READ_SERIAL);
        byte[] b = connection.read(4);
        if (crc8(b, 0, 3) != (b[3] & 0xFF)) {
            throw new IOException("HTU31D CRC mismatch");
        }
        return ((b[0] & 0xFF) << 16) | ((b[1] & 0xFF) << 8) | (b[2] & 0xFF);
    }

    /**
     * Soft reset (0x1E) and wait 15 ms; heater off, oversampling back to OSR 3 / 3.
     *
     * @throws IOException on bus error
     */
    public void softReset() throws IOException {
        command(CMD_RESET);
        sleepMs(15);
        osrRh = 3;
        osrT = 3;
    }

    /**
     * Run one measurement and compute the dew point (Magnus formula over water).
     *
     * @return dew point in °C
     * @throws IOException on bus error or CRC mismatch
     */
    public double dewPoint() throws IOException {
        double[] m = read();
        double t = m[0];
        double rh = m[1] <= 0.0 ? 0.01 : m[1];
        double g = Math.log(rh / 100.0) + 17.62 * t / (243.12 + t);
        return 243.12 * g / (17.62 - g);
    }
}
