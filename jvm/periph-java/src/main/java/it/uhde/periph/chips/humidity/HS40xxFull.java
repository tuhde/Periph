package it.uhde.periph.chips.humidity;

import it.uhde.periph.connection.Connection;
import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;

import java.io.IOException;
import java.util.function.IntConsumer;

/**
 * HS40xx full driver — single values, hold mode, resolution, periodic mode, alerts, ID, dew point.
 *
 * <p>Extends {@link HS40xxMinimal}. Registers are volatile: they return to their defaults whenever
 * the supply is cut or after {@link #softReset()}.
 *
 * <p>{@link #onInterrupt(IntConsumer)} uses {@code connection.intPin()} if wired (edge per the
 * configured polarity), otherwise a polling thread at the periodic interval; the callback receives
 * the {@code SOURCE_*} status bitmask.
 */
public class HS40xxFull extends HS40xxMinimal {

    /** Alert source: humidity below the low trigger threshold. */
    public static final int SOURCE_RH_LOW = 0x01;
    /** Alert source: humidity above the high trigger threshold. */
    public static final int SOURCE_RH_HIGH = 0x02;
    /** Alert source: temperature below the low trigger threshold. */
    public static final int SOURCE_TEMP_LOW = 0x04;
    /** Alert source: temperature above the high trigger threshold. */
    public static final int SOURCE_TEMP_HIGH = 0x08;

    private static final int CMD_READ_REG = 0xA7;
    private static final int CMD_WRITE_REG = 0xA6;
    private static final int CMD_STOP_PERIODIC = 0x30;
    private static final int CMD_READ_ID = 0xD7;
    private static final int CMD_SOFT_RESET = 0xFE;

    private static final int REG_RESOLUTION = 0x00;
    private static final int REG_PERIODIC = 0x02;
    private static final int REG_ALERT_EN = 0x03;

    private static final int[] BITS = {8, 10, 12, 14};

    private boolean alertsEnabled = false;
    private boolean activeHigh = true;
    private double periodS = 1.0;
    private volatile IntConsumer callback;
    private InputPin intPin;
    private volatile boolean polling = false;
    private Thread pollThread;
    private final EdgeHandler edgeHandler = this::handleEdge;

    /**
     * Create the driver; does no bus access.
     *
     * @param connection I²C connection bound to 0x54
     */
    public HS40xxFull(Connection connection) {
        super(connection);
    }

    /**
     * Measure temperature only (no-hold {@code 0xF3}, 3 bytes, CRC verified).
     *
     * @return temperature in °C
     * @throws IOException on bus error or CRC mismatch
     */
    public double readTemperature() throws IOException {
        return toTemperature(rawTemperature(measure(CMD_T_NOHOLD, 3, false), 0));
    }

    /**
     * Measure humidity (uses the {@code 0xF5} humidity + temperature command).
     *
     * @return relative humidity in %RH
     * @throws IOException on bus error or CRC mismatch
     */
    public double readHumidity() throws IOException {
        return read()[1];
    }

    /**
     * Measure with the hold command {@code 0xE5} (the sensor stretches SCL until done; needs a
     * clock-stretching-capable controller and SCL ≥ 200 kHz).
     *
     * @return {temperature in °C, relative humidity in %RH}
     * @throws IOException on bus error or CRC mismatch
     */
    public double[] readHold() throws IOException {
        byte[] d = measure(CMD_HT_HOLD, 5, true);
        return new double[]{toTemperature(rawTemperature(d, 2)), toHumidity(rawHumidity(d))};
    }

    /**
     * Measure and return the raw 14-bit values (CRC verified).
     *
     * @return {raw humidity, raw temperature}
     * @throws IOException on bus error or CRC mismatch
     */
    public int[] readRaw() throws IOException {
        byte[] d = measure(CMD_HT_NOHOLD, 5, false);
        return new int[]{rawHumidity(d), rawTemperature(d, 2)};
    }

    /**
     * Read one configuration register ({@code 0xA7} + address, then a one-byte read).
     *
     * @param reg register address (0x00…0x14)
     * @return register value (0…255)
     * @throws IOException on bus error
     */
    public int readRegister(int reg) throws IOException {
        connection.write(new byte[]{(byte) CMD_READ_REG, (byte) reg});
        return readFrame(1)[0] & 0xFF;
    }

    /**
     * Write one configuration register ({@code 0xA6} + address + data).
     *
     * @param reg   register address (0x00…0x14)
     * @param value byte to write
     * @throws IOException on bus error
     */
    public void writeRegister(int reg, int value) throws IOException {
        connection.write(new byte[]{(byte) CMD_WRITE_REG, (byte) reg, (byte) value});
    }

    private void modify(int reg, int mask, int bits) throws IOException {
        int v = readRegister(reg);
        writeRegister(reg, (v & ~mask & 0xFF) | (bits & mask));
    }

    private static int bitsCode(int bits) {
        for (int i = 0; i < BITS.length; i++) if (BITS[i] == bits) return i;
        throw new IllegalArgumentException("resolution must be 8, 10, 12 or 14 bits");
    }

    /**
     * Set the measurement resolutions (register 0x00, read-modify-write).
     *
     * @param humidityBits    8, 10, 12 or 14, or {@code null} to leave unchanged
     * @param temperatureBits 8, 10, 12 or 14, or {@code null} to leave unchanged
     * @throws IllegalArgumentException for other values (before any bus access)
     * @throws IOException              on bus error
     */
    public void setResolution(Integer humidityBits, Integer temperatureBits) throws IOException {
        int mask = 0;
        int value = 0;
        if (humidityBits != null) {
            value |= bitsCode(humidityBits) << 2;
            mask |= 0x0C;
        }
        if (temperatureBits != null) {
            value |= bitsCode(temperatureBits);
            mask |= 0x03;
        }
        if (mask != 0) modify(REG_RESOLUTION, mask, value);
    }

    /**
     * Read the measurement resolutions.
     *
     * @return {humidity bits, temperature bits}, each 8, 10, 12 or 14
     * @throws IOException on bus error
     */
    public int[] getResolution() throws IOException {
        int v = readRegister(REG_RESOLUTION);
        return new int[]{BITS[(v >> 2) & 3], BITS[v & 3]};
    }

    /**
     * Start periodic measurement mode (register 0x02 bit 7 plus the period).
     *
     * @param periodS 0.5, 1 or 2.5 seconds
     * @throws IllegalArgumentException for other periods
     * @throws IOException              on bus error
     */
    public void startPeriodic(double periodS) throws IOException {
        int code;
        if (periodS == 0.5) code = 0;
        else if (periodS == 1.0) code = 1;
        else if (periodS == 2.5) code = 2;
        else throw new IllegalArgumentException("periodS must be 0.5, 1 or 2.5");
        alertsEnabled = (readRegister(REG_ALERT_EN) & 0x0F) != 0;
        this.periodS = periodS;
        modify(REG_PERIODIC, 0xB0, 0x80 | (code << 4));
    }

    /**
     * Leave periodic mode (command {@code 0x30}); the sensor then sleeps.
     *
     * @throws IOException on bus error
     */
    public void stopPeriodic() throws IOException {
        connection.write(new byte[]{(byte) CMD_STOP_PERIODIC});
    }

    /**
     * Fetch the latest periodic result (a plain read, CRC verified). The frame has a leading
     * status byte when any alert source is enabled.
     *
     * @return {temperature in °C, relative humidity in %RH, status} — status is the
     *         {@code SOURCE_*} bitmask of the active alerts (0 when alerts are off)
     * @throws IOException on bus error or CRC mismatch
     */
    public double[] readPeriodic() throws IOException {
        boolean alerts = alertsEnabled;
        byte[] b = readFrame(alerts ? 6 : 5);
        int status = 0;
        if (alerts) {
            status = b[0] & 0x0F;
            b = java.util.Arrays.copyOfRange(b, 1, 6);
        }
        if (!crcOk(b)) throw new IOException("HS40xx CRC mismatch");
        return new double[]{toTemperature(rawTemperature(b, 2)), toHumidity(rawHumidity(b)), status};
    }

    private static int rhWord(double rh) {
        return (int) Math.max(0, Math.min(16383, Math.round(rh / 100.0 * FULL_SCALE)));
    }

    private static int tempWord(double t) {
        return (int) Math.max(0, Math.min(16383, Math.round((t + 40.0) / 165.0 * FULL_SCALE)));
    }

    private void writeThreshold(int lowReg, int word) throws IOException {
        writeRegister(lowReg, word & 0xFF);
        writeRegister(lowReg + 1, word >> 8);
    }

    /**
     * Program alert trigger / reset thresholds; {@code null} leaves a threshold unchanged.
     * Humidity values are in %RH, temperatures in °C.
     *
     * @param rhHigh        humidity high trigger
     * @param rhHighReset   humidity high reset
     * @param rhLow         humidity low trigger
     * @param rhLowReset    humidity low reset
     * @param tempHigh      temperature high trigger
     * @param tempHighReset temperature high reset
     * @param tempLow       temperature low trigger
     * @param tempLowReset  temperature low reset
     * @throws IOException on bus error
     */
    public void setAlertThresholds(Double rhHigh, Double rhHighReset, Double rhLow, Double rhLowReset,
                                   Double tempHigh, Double tempHighReset, Double tempLow, Double tempLowReset)
            throws IOException {
        if (rhHigh != null) writeThreshold(0x0F, rhWord(rhHigh));
        if (rhHighReset != null) writeThreshold(0x0D, rhWord(rhHighReset));
        if (rhLow != null) writeThreshold(0x11, rhWord(rhLow));
        if (rhLowReset != null) writeThreshold(0x13, rhWord(rhLowReset));
        if (tempHigh != null) writeThreshold(0x07, tempWord(tempHigh));
        if (tempHighReset != null) writeThreshold(0x05, tempWord(tempHighReset));
        if (tempLow != null) writeThreshold(0x09, tempWord(tempLow));
        if (tempLowReset != null) writeThreshold(0x0B, tempWord(tempLowReset));
    }

    /**
     * Set the ALERT pin polarity (register 0x02 bit 0).
     *
     * @param activeHigh {@code true} for active high when triggered (default)
     * @throws IOException on bus error
     */
    public void setAlertPolarity(boolean activeHigh) throws IOException {
        this.activeHigh = activeHigh;
        modify(REG_PERIODIC, 0x01, activeHigh ? 1 : 0);
    }

    /**
     * Enable alert source(s) (register 0x03).
     *
     * @param source bitmask of {@code SOURCE_*}
     * @throws IOException on bus error
     */
    public void enableInterrupt(int source) throws IOException {
        int v = readRegister(REG_ALERT_EN) | (source & 0x0F);
        writeRegister(REG_ALERT_EN, v);
        alertsEnabled = (v & 0x0F) != 0;
    }

    /**
     * Disable alert source(s) (register 0x03).
     *
     * @param source bitmask of {@code SOURCE_*}
     * @throws IOException on bus error
     */
    public void disableInterrupt(int source) throws IOException {
        int v = readRegister(REG_ALERT_EN) & ~(source & 0x0F) & 0xFF;
        writeRegister(REG_ALERT_EN, v);
        alertsEnabled = (v & 0x0F) != 0;
    }

    /**
     * Fetch the latest periodic result and return the alert status; needs periodic mode with an
     * alert source enabled.
     *
     * @return bitmask of {@code SOURCE_*} for the alerts currently active
     * @throws IllegalStateException if no alert source is enabled
     * @throws IOException           on bus error or CRC mismatch
     */
    public int pollInterrupt() throws IOException {
        if (!alertsEnabled) throw new IllegalStateException("HS40xx alerts are not enabled");
        return (int) readPeriodic()[2];
    }

    /**
     * Subscribe to the ALERT pin ({@code connection.intPin()}), falling back to polling at the
     * periodic interval when no pin is wired.
     *
     * @param callback receives the {@code SOURCE_*} status bitmask
     */
    public void onInterrupt(IntConsumer callback) {
        onInterrupt(callback, connection.intPin());
    }

    /**
     * Subscribe to the ALERT pin, overriding which {@link InputPin} delivers edges.
     *
     * @param callback receives the {@code SOURCE_*} status bitmask
     * @param pin      ALERT pin to arm, or {@code null} to force the polling fallback
     */
    public void onInterrupt(IntConsumer callback, InputPin pin) {
        offInterrupt();
        this.callback = callback;
        if (pin != null) {
            this.intPin = pin;
            pin.onEdge(edgeHandler, activeHigh ? EdgeTrigger.RISING : EdgeTrigger.FALLING);
        } else {
            polling = true;
            long periodMs = (long) (periodS * 1000);
            pollThread = new Thread(() -> {
                while (polling) {
                    handleEdge();
                    try {
                        Thread.sleep(periodMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "hs40xx-poll");
            pollThread.setDaemon(true);
            pollThread.start();
        }
    }

    /** Unsubscribe and stop delivery. */
    public void offInterrupt() {
        callback = null;
        if (intPin != null) {
            intPin.offEdge(edgeHandler);
            intPin = null;
        }
        polling = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
    }

    private void handleEdge() {
        IntConsumer cb = callback;
        if (cb == null) return;
        try {
            cb.accept(pollInterrupt());
        } catch (IOException | IllegalStateException e) {
            // a failed poll delivers nothing
        }
    }

    /**
     * Read the 32-bit sensor ID (command {@code 0xD7}, four bytes MSB first).
     *
     * @return unique device ID (unsigned)
     * @throws IOException on bus error
     */
    public long readId() throws IOException {
        connection.write(new byte[]{(byte) CMD_READ_ID});
        byte[] d = readFrame(4);
        return ((long) (d[0] & 0xFF) << 24) | ((d[1] & 0xFF) << 16) | ((d[2] & 0xFF) << 8) | (d[3] & 0xFF);
    }

    /**
     * Stop periodic mode, send the soft reset ({@code 0xFE}) and wait 500 ms.
     *
     * @throws IOException on bus error
     */
    public void softReset() throws IOException {
        stopPeriodic();
        connection.write(new byte[]{(byte) CMD_SOFT_RESET});
        sleepMs(500);
        alertsEnabled = false;
        activeHigh = true;
    }

    /**
     * Run one measurement and return the dew point (Magnus formula).
     *
     * @return dew point in °C; −273.15 if humidity is 0 %RH
     * @throws IOException on bus error or CRC mismatch
     */
    public double dewPoint() throws IOException {
        double[] m = read();
        if (m[1] <= 0.0) return -273.15;
        double gamma = Math.log(m[1] / 100.0) + 17.62 * m[0] / (243.12 + m[0]);
        return 243.12 * gamma / (17.62 - gamma);
    }
}
