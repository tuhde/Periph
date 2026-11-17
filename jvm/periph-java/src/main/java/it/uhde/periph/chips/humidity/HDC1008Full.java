package it.uhde.periph.chips.humidity;

import it.uhde.periph.connection.Connection;
import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * HDC1008 full driver — single values, resolution, heater, battery flag, serial ID and the DRDYn
 * data-ready interrupt. Extends {@link HDC1008Minimal}.
 *
 * <p>{@link #onInterrupt(Consumer)} uses {@code connection.intPin()} if wired (DRDYn falling
 * edge), otherwise a 5&nbsp;ms polling thread. The caller triggers the measurement; the callback
 * receives {temperature °C, humidity %RH} (humidity is NaN after a single-mode temperature trigger).
 */
public class HDC1008Full extends HDC1008Minimal {

    private Consumer<double[]> callback;
    private InputPin intPin;
    private volatile boolean polling = false;
    private Thread pollThread;
    private final EdgeHandler edgeHandler = this::handleEdge;

    /**
     * Create the driver; does no bus access.
     *
     * @param connection plain I²C connection bound to 0x40–0x43
     */
    public HDC1008Full(Connection connection) {
        super(connection);
    }

    /**
     * Measure and return the raw 16-bit words.
     *
     * @return {raw temperature, raw humidity}, bits 1:0 zero
     * @throws IOException on bus error or timeout
     */
    public int[] readRaw() throws IOException {
        return measurePair();
    }

    /**
     * Measure temperature only (single mode).
     *
     * @return degrees Celsius
     * @throws IOException on bus error or timeout
     */
    public double readTemperature() throws IOException {
        setMode(false);
        byte[] d = triggerRead(REG_TEMPERATURE, 2);
        return toTemperature(((d[0] & 0xFF) << 8) | (d[1] & 0xFF));
    }

    /**
     * Measure humidity only (single mode).
     *
     * @return %RH
     * @throws IOException on bus error or timeout
     */
    public double readHumidity() throws IOException {
        setMode(false);
        byte[] d = triggerRead(REG_HUMIDITY, 2);
        return toHumidity(((d[0] & 0xFF) << 8) | (d[1] & 0xFF));
    }

    /**
     * Select the conversion resolutions.
     *
     * @param temperatureBits 14 or 11
     * @param humidityBits    14, 11 or 8
     * @throws IllegalArgumentException for other values
     * @throws IOException on bus error
     */
    public void setResolution(int temperatureBits, int humidityBits) throws IOException {
        if (temperatureBits != 14 && temperatureBits != 11) throw new IllegalArgumentException("temperatureBits must be 14 or 11");
        if (humidityBits != 14 && humidityBits != 11 && humidityBits != 8) throw new IllegalArgumentException("humidityBits must be 14, 11 or 8");
        int cfg = config & ~(CFG_TRES | CFG_HRES);
        if (temperatureBits == 11) cfg |= CFG_TRES;
        if (humidityBits == 11) cfg |= 0x0100;
        else if (humidityBits == 8) cfg |= 0x0200;
        config = cfg;
        writeConfig();
    }

    /**
     * Enable or disable the heater.
     *
     * @param on true to enable
     * @throws IOException on bus error
     */
    public void setHeater(boolean on) throws IOException {
        config = on ? (config | CFG_HEAT) : (config & ~CFG_HEAT);
        writeConfig();
    }

    /**
     * Read the configuration register.
     *
     * @return the 16-bit configuration word
     * @throws IOException on bus error
     */
    public int readConfig() throws IOException {
        connection.write(new byte[]{(byte) REG_CONFIG});
        byte[] d = connection.read(2);
        return ((d[0] & 0xFF) << 8) | (d[1] & 0xFF);
    }

    /**
     * Whether the heater bit is set in the configuration register.
     *
     * @return true if the heater is enabled
     * @throws IOException on bus error
     */
    public boolean isHeaterOn() throws IOException {
        return (readConfig() & CFG_HEAT) != 0;
    }

    /**
     * Whether VDD is below 2.8 V (BTST).
     *
     * @return true if the battery flag is set
     * @throws IOException on bus error
     */
    public boolean batteryLow() throws IOException {
        return (readConfig() & CFG_BTST) != 0;
    }

    /**
     * Set RST, wait 15 ms, then rewrite the stored configuration.
     *
     * @throws IOException on bus error
     */
    public void softwareReset() throws IOException {
        connection.write(new byte[]{(byte) REG_CONFIG, (byte) (CFG_RST >> 8), 0x00});
        sleepMs(15);
        writeConfig();
    }

    /**
     * Read the 40-bit serial number from registers 0xFB–0xFD.
     *
     * @return the serial ID
     * @throws IOException on bus error
     */
    public long serialId() throws IOException {
        long[] w = new long[3];
        for (int i = 0; i < 3; i++) {
            connection.write(new byte[]{(byte) (REG_SERIAL1 + i)});
            byte[] d = connection.read(2);
            w[i] = ((d[0] & 0xFF) << 8) | (d[1] & 0xFF);
        }
        return (w[0] << 24) | (w[1] << 8) | (w[2] >> 7);
    }

    /**
     * Typical conversion time for the current resolutions (temperature + humidity).
     *
     * @return seconds
     */
    public double conversionTime() {
        double t = (config & CFG_TRES) != 0 ? 0.00365 : 0.00635;
        switch (config & CFG_HRES) {
            case 0x0100: return t + 0.00385;
            case 0x0200: return t + 0.0025;
            default: return t + 0.0065;
        }
    }

    /**
     * Whether a triggered measurement is ready: an ACKed read header means ready (the bytes are
     * read and cached for the callback), a NACK means not ready.
     *
     * @return true when data are ready
     */
    public synchronized boolean pollInterrupt() {
        if (pending != null) return true;
        try {
            pending = connection.read((config & CFG_MODE) != 0 ? 4 : 2);
        } catch (IOException nack) {
            return false;
        }
        return true;
    }

    /**
     * Subscribe to the data-ready event using {@code connection.intPin()} or a 5 ms polling thread.
     *
     * @param callback receives {temperature °C, humidity %RH}
     */
    public void onInterrupt(Consumer<double[]> callback) {
        onInterrupt(callback, connection.intPin());
    }

    /**
     * Subscribe to the data-ready event, overriding which {@link InputPin} delivers edges.
     *
     * @param callback receives {temperature °C, humidity %RH}
     * @param pin      DRDYn pin to arm, or {@code null} to force the polling fallback
     */
    public void onInterrupt(Consumer<double[]> callback, InputPin pin) {
        offInterrupt();
        this.callback = callback;
        if (pin != null) {
            this.intPin = pin;
            pin.onEdge(edgeHandler, EdgeTrigger.FALLING);
        } else {
            polling = true;
            pollThread = new Thread(() -> {
                while (polling) {
                    handleEdge();
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "hdc1008-poll");
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
        Consumer<double[]> cb = callback;
        if (cb == null || !pollInterrupt()) return;
        byte[] d;
        synchronized (this) {
            d = pending;
            pending = null;
        }
        if (d == null) return;
        double t = toTemperature(((d[0] & 0xFF) << 8) | (d[1] & 0xFF));
        double h = d.length >= 4 ? toHumidity(((d[2] & 0xFF) << 8) | (d[3] & 0xFF)) : Double.NaN;
        cb.accept(new double[]{t, h});
    }
}
