package it.uhde.periph.chips.humidity

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * HS30xx — relative humidity and temperature sensors (Renesas HS3001, HS3002, HS3003), minimal driver.
 *
 * <p>The three parts share one protocol and differ only in accuracy class and housing, so
 * {@link HS3001Minimal}, {@link HS3002Minimal} and {@link HS3003Minimal} are thin subclasses.
 * Each {@link #read()} sends a measurement request (an address-only write), waits 40 ms,
 * fetches four bytes (data fetch) and converts them; stale data are fetched once more after
 * 10 ms. I²C address 0x44 (fixed).
 *
 * <h2>Configuration defaults</h2>
 * <ul>
 *   <li>Nothing is written at construction: bytes written within 10 ms of power-up could be
 *       taken as NVM programming commands</li>
 *   <li>Factory resolution (14-bit humidity and temperature), fixed 40 ms wait, no NVM access</li>
 * </ul>
 */
@CompileStatic
class HS30xxMinimal {

    protected static final double FULL_SCALE = 16383.0d

    protected final Connection connection
    protected long waitMs = 40L

    /**
     * Create the driver; does no bus access.
     *
     * @param connection I²C connection bound to 0x44
     */
    HS30xxMinimal(Connection connection) {
        this.connection = connection
    }

    protected static void sleepMs(long ms) {
        try {
            Thread.sleep(ms)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
        }
    }

    /** Send the measurement request (address-only write), wait and read {@code len} bytes without a stale check. */
    protected byte[] fetchRaw(int len) throws IOException {
        connection.write(new byte[0])
        sleepMs(waitMs)
        return connection.read(len)
    }

    /** {@link #fetchRaw} plus one retry after 10 ms if the status bits say stale. */
    protected byte[] fetch(int len) throws IOException {
        byte[] data = fetchRaw(len)
        if ((data[0] & 0xC0) != 0) {
            sleepMs(10L)
            data = connection.read(len)
            if ((data[0] & 0xC0) != 0) throw new IOException('HS30xx returned stale data')
        }
        return data
    }

    protected static int rawHumidity(byte[] d) {
        return ((d[0] & 0x3F) << 8) | (d[1] & 0xFF)
    }

    protected static int rawTemperature(byte[] d) {
        return ((d[2] & 0xFF) << 6) | ((d[3] & 0xFF) >> 2)
    }

    protected static double toHumidity(int raw) {
        return raw / FULL_SCALE * 100.0d
    }

    protected static double toTemperature(int raw) {
        return raw / FULL_SCALE * 165.0d - 40.0d
    }

    /**
     * Measure temperature and humidity (the output is always scaled to 14 bits).
     *
     * @return {temperature in °C (−40…125), relative humidity in %RH (0…100)}
     * @throws IOException on bus error or if the data are still stale after one retry
     */
    double[] read() throws IOException {
        byte[] d = fetch(4)
        return [toTemperature(rawTemperature(d)), toHumidity(rawHumidity(d))] as double[]
    }
}
