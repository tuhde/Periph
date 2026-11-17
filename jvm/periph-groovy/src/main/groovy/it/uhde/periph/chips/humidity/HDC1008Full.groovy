package it.uhde.periph.chips.humidity

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import java.io.IOException
import java.util.function.Consumer

/**
 * HDC1008 full driver — single values, resolution, heater, battery flag, serial ID and the DRDYn
 * data-ready interrupt. Extends {@link HDC1008Minimal}.
 *
 * <p>{@link #onInterrupt(Consumer)} uses {@code connection.intPin()} if wired (DRDYn falling
 * edge), otherwise a 5&nbsp;ms polling thread. The caller triggers the measurement; the callback
 * receives {temperature °C, humidity %RH} (humidity is NaN after a single-mode temperature trigger).
 */
@CompileStatic
class HDC1008Full extends HDC1008Minimal {

    private volatile Consumer<double[]> callback
    private InputPin intPin
    private volatile boolean polling = false
    private Thread pollThread
    private final EdgeHandler edgeHandler = { -> handleEdge() } as EdgeHandler

    /**
     * Create the driver; does no bus access.
     *
     * @param connection plain I²C connection bound to 0x40–0x43
     */
    HDC1008Full(Connection connection) {
        super(connection)
    }

    /**
     * Measure and return the raw 16-bit words.
     *
     * @return {raw temperature, raw humidity}, bits 1:0 zero
     * @throws IOException on bus error or timeout
     */
    int[] readRaw() {
        return measurePair()
    }

    /**
     * Measure temperature only (single mode).
     *
     * @return degrees Celsius
     * @throws IOException on bus error or timeout
     */
    double readTemperature() {
        setMode(false)
        byte[] d = triggerRead(REG_TEMPERATURE, 2)
        return toTemperature(((d[0] & 0xFF) << 8) | (d[1] & 0xFF))
    }

    /**
     * Measure humidity only (single mode).
     *
     * @return %RH
     * @throws IOException on bus error or timeout
     */
    double readHumidity() {
        setMode(false)
        byte[] d = triggerRead(REG_HUMIDITY, 2)
        return toHumidity(((d[0] & 0xFF) << 8) | (d[1] & 0xFF))
    }

    /**
     * Select the conversion resolutions.
     *
     * @param temperatureBits 14 or 11
     * @param humidityBits    14, 11 or 8
     * @throws IllegalArgumentException for other values
     * @throws IOException on bus error
     */
    void setResolution(int temperatureBits, int humidityBits) {
        if (temperatureBits != 14 && temperatureBits != 11) throw new IllegalArgumentException('temperatureBits must be 14 or 11')
        if (humidityBits != 14 && humidityBits != 11 && humidityBits != 8) throw new IllegalArgumentException('humidityBits must be 14, 11 or 8')
        int c = cfg & ~(CFG_TRES | CFG_HRES)
        if (temperatureBits == 11) c |= CFG_TRES
        if (humidityBits == 11) c |= 0x0100
        else if (humidityBits == 8) c |= 0x0200
        cfg = c
        writeConfig()
    }

    /**
     * Enable or disable the heater.
     *
     * @param on true to enable
     * @throws IOException on bus error
     */
    void setHeater(boolean on) {
        cfg = on ? (cfg | CFG_HEAT) : (cfg & ~CFG_HEAT)
        writeConfig()
    }

    /**
     * Read the configuration register.
     *
     * @return the 16-bit configuration word
     * @throws IOException on bus error
     */
    int readConfig() {
        connection.write([(byte) REG_CONFIG] as byte[])
        byte[] d = connection.read(2)
        return ((d[0] & 0xFF) << 8) | (d[1] & 0xFF)
    }

    /**
     * Whether the heater bit is set in the configuration register.
     *
     * @return true if the heater is enabled
     * @throws IOException on bus error
     */
    boolean isHeaterOn() {
        return (readConfig() & CFG_HEAT) != 0
    }

    /**
     * Whether VDD is below 2.8 V (BTST).
     *
     * @return true if the battery flag is set
     * @throws IOException on bus error
     */
    boolean batteryLow() {
        return (readConfig() & CFG_BTST) != 0
    }

    /**
     * Set RST, wait 15 ms, then rewrite the stored configuration.
     *
     * @throws IOException on bus error
     */
    void softwareReset() {
        connection.write([(byte) REG_CONFIG, (byte) (CFG_RST >> 8), (byte) 0x00] as byte[])
        sleepMs(15)
        writeConfig()
    }

    /**
     * Read the 40-bit serial number from registers 0xFB–0xFD.
     *
     * @return the serial ID
     * @throws IOException on bus error
     */
    long serialId() {
        long[] w = new long[3]
        for (int i = 0; i < 3; i++) {
            connection.write([(byte) (REG_SERIAL1 + i)] as byte[])
            byte[] d = connection.read(2)
            w[i] = (long) (((d[0] & 0xFF) << 8) | (d[1] & 0xFF))
        }
        return (w[0] << 24) | (w[1] << 8) | (w[2] >> 7)
    }

    /**
     * Typical conversion time for the current resolutions (temperature + humidity).
     *
     * @return seconds
     */
    double conversionTime() {
        double t = (cfg & CFG_TRES) != 0 ? 0.00365d : 0.00635d
        int h = cfg & CFG_HRES
        return t + (h == 0x0100 ? 0.00385d : (h == 0x0200 ? 0.0025d : 0.0065d))
    }

    /**
     * Whether a triggered measurement is ready: an ACKed read header means ready (the bytes are
     * read and cached for the callback), a NACK means not ready.
     *
     * @return true when data are ready
     */
    synchronized boolean pollInterrupt() {
        if (pending != null) return true
        try {
            pending = connection.read((cfg & CFG_MODE) != 0 ? 4 : 2)
        } catch (IOException nack) {
            return false
        }
        return true
    }

    /**
     * Subscribe to the data-ready event using {@code connection.intPin()} or a 5 ms polling thread.
     *
     * @param callback receives {temperature °C, humidity %RH}
     */
    void onInterrupt(Consumer<double[]> callback) {
        onInterrupt(callback, connection.intPin())
    }

    /**
     * Subscribe to the data-ready event, overriding which {@link InputPin} delivers edges.
     *
     * @param callback receives {temperature °C, humidity %RH}
     * @param pin      DRDYn pin to arm, or {@code null} to force the polling fallback
     */
    void onInterrupt(Consumer<double[]> callback, InputPin pin) {
        offInterrupt()
        this.callback = callback
        if (pin != null) {
            this.intPin = pin
            pin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            polling = true
            pollThread = new Thread({ ->
                while (polling) {
                    handleEdge()
                    try {
                        Thread.sleep(5)
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            } as Runnable, 'hdc1008-poll')
            pollThread.daemon = true
            pollThread.start()
        }
    }

    /** Unsubscribe and stop delivery. */
    void offInterrupt() {
        callback = null
        if (intPin != null) {
            intPin.offEdge(edgeHandler)
            intPin = null
        }
        polling = false
        if (pollThread != null) {
            pollThread.interrupt()
            pollThread = null
        }
    }

    private void handleEdge() {
        Consumer<double[]> cb = callback
        if (cb == null || !pollInterrupt()) return
        byte[] d
        synchronized (this) {
            d = pending
            pending = null
        }
        if (d == null) return
        double t = toTemperature(((d[0] & 0xFF) << 8) | (d[1] & 0xFF))
        double h = d.length >= 4 ? toHumidity(((d[2] & 0xFF) << 8) | (d[3] & 0xFF)) : Double.NaN
        cb.accept([t, h] as double[])
    }
}
