package it.uhde.periph.chips.environmental

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * AM2315 — humidity and temperature sensor probe (Aosong), minimal driver.
 *
 * <p>The sensor speaks a Modbus-style protocol: every read is a wake-up (a write the sensor does
 * not acknowledge), a command write, a 2 ms wait and a read whose response carries a CRC-16. The
 * sensor measures after each communication and returns that result on the next one, so
 * {@link #read()} returns the <em>previous</em> measurement; transactions are kept at least 2 s
 * apart by sleeping the remainder. I²C address 0x5C (fixed, standard mode, at most 100 kHz).
 *
 * <h2>Configuration defaults</h2>
 * <ul>
 *   <li>Nothing is written at construction; the first read waits until 2 s after construction (power-up settling)</li>
 *   <li>At least 2 s between transactions</li>
 *   <li>One retry after a new wake-up on a CRC mismatch or bus error</li>
 * </ul>
 */
@CompileStatic
class AM2315Minimal {

    protected static final int FUNC_READ  = 0x03
    protected static final int FUNC_WRITE = 0x10

    /** The sensor answered with an error response (function code with bit 7 set). */
    static class ErrorResponse extends IOException {
        private final int errorCode

        /** @param code the response code, 0x80 (function not supported) to 0x84 (write disabled) */
        ErrorResponse(int code) {
            super(String.format('AM2315 error response 0x%02X', code))
            this.errorCode = code
        }

        /** @return the error response code 0x80-0x84 */
        int code() { return errorCode }
    }

    protected final Connection connection
    private long minIntervalMs = 2000L
    private long lastNanos = System.nanoTime()

    /**
     * Create the driver; does no bus access.
     *
     * @param connection I²C connection bound to 0x5C
     */
    AM2315Minimal(Connection connection) {
        this.connection = connection
    }

    /**
     * Override the minimum interval between transactions (default 2000 ms); intended for tests.
     *
     * @param ms interval in milliseconds, 0 disables the wait
     */
    void setMinIntervalMs(long ms) {
        this.@minIntervalMs = ms
    }

    protected static void sleepMs(long ms) {
        try {
            Thread.sleep(ms)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * CRC-16 (Modbus) used by the AM2315: init 0xFFFF, polynomial 0xA001 (reflected).
     *
     * @param data bytes to check
     * @param off  first byte
     * @param len  number of bytes
     * @return the CRC
     */
    static int crc16(byte[] data, int off, int len) {
        int crc = 0xFFFF
        for (int i = off; i < off + len; i++) {
            crc ^= data[i] & 0xFF
            for (int b = 0; b < 8; b++) {
                crc = (crc & 1) != 0 ? (crc >> 1) ^ 0xA001 : crc >> 1
            }
        }
        return crc
    }

    private void waitInterval() {
        if (minIntervalMs <= 0L) return
        long remaining = minIntervalMs - (System.nanoTime() - lastNanos).intdiv(1_000_000L)
        if (remaining > 0L) sleepMs(remaining)
    }

    /** Wake the sensor: a dummy write (the NACK is ignored) and a 1 ms wait. */
    protected void wakeSensor() {
        try {
            connection.write([(byte) 0x00] as byte[])      // the sleeping sensor NACKs this
        } catch (IOException expected) {
            // ignored
        }
        sleepMs(1L)
    }

    private byte[] exchange(byte[] frame, int responseLen) throws IOException {
        waitInterval()
        wakeSensor()
        try {
            connection.write(frame)
            sleepMs(2L)
            return connection.read(responseLen)
        } finally {
            lastNanos = System.nanoTime()
        }
    }

    private static void check(byte[] data, int function, int responseLen) throws IOException {
        if (data.length >= 1 && (data[0] & 0x80) != 0) throw new ErrorResponse(data[0] & 0xFF)
        if (data.length < responseLen) throw new IOException('AM2315 short response')
        int crc = (data[responseLen - 2] & 0xFF) | ((data[responseLen - 1] & 0xFF) << 8)
        if ((data[0] & 0xFF) != function || crc16(data, 0, responseLen - 2) != crc) {
            throw new IOException('AM2315 CRC mismatch')
        }
    }

    /** One command/response exchange with a retry on a bus or CRC error; frame is the full command frame. */
    protected byte[] transact(byte[] frame, int responseLen, int function) throws IOException {
        IOException failure = null
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                byte[] data = exchange(frame, responseLen)
                check(data, function, responseLen)
                return data
            } catch (ErrorResponse e) {
                throw e
            } catch (IOException e) {
                failure = e
            }
        }
        throw failure
    }

    /** Read count (1-10) registers from start; the response is CRC verified. */
    protected byte[] readRegistersRaw(int start, int count) throws IOException {
        byte[] data = transact([(byte) FUNC_READ, (byte) start, (byte) count] as byte[], count + 4, FUNC_READ)
        if ((data[1] & 0xFF) != count) throw new IOException('AM2315 unexpected byte count')
        byte[] out = new byte[count]
        System.arraycopy(data, 2, out, 0, count)
        return out
    }

    protected static double toHumidity(int raw) {
        return raw / 10.0d
    }

    protected static double toTemperature(int raw) {
        double t = (raw & 0x7FFF) / 10.0d
        return (raw & 0x8000) != 0 ? -t : t
    }

    protected static int word(byte[] d, int i) {
        return ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF)
    }

    /**
     * Read temperature and humidity (registers 0x00-0x03); the result is the previous measurement.
     *
     * @return {temperature in °C, relative humidity in %RH}
     * @throws IOException on bus error, two CRC mismatches or a sensor error response ({@link ErrorResponse})
     */
    double[] read() throws IOException {
        byte[] d = readRegistersRaw(0x00, 4)
        return [toTemperature(word(d, 2)), toHumidity(word(d, 0))] as double[]
    }
}
