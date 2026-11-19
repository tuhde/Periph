package it.uhde.periph.chips.humidity

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * HS30xx full driver — raw values, wait override, NVM resolution and ID, dew point.
 *
 * <p>Extends {@link HS30xxMinimal}. The NVM methods (resolution, ID) need the sensor's
 * programming mode, which can only be entered within 10 ms after power-up. They therefore take a
 * {@code powerCycle} callback that cuts and restores the sensor supply (for example via a
 * MOSFET or a GPIO powering the sensor); with {@code null} they throw
 * {@link IllegalStateException} before any bus access.
 */
@CompileStatic
class HS30xxFull extends HS30xxMinimal {

    private static final int REG_RH_READ = 0x06
    private static final int REG_RH_WRITE = 0x46
    private static final int REG_T_READ = 0x11
    private static final int REG_T_WRITE = 0x51
    private static final int REG_ID_HIGH = 0x1E
    private static final int REG_ID_LOW = 0x1F
    private static final int NVM_OK = 0x81
    private static final int RES_MASK = 0x0C00
    private static final int[] BITS = [8, 10, 12, 14] as int[]

    /**
     * Create the driver; does no bus access.
     *
     * @param connection I²C connection bound to 0x44
     */
    HS30xxFull(Connection connection) {
        super(connection)
    }

    /**
     * Measure temperature only (four-byte fetch, humidity discarded).
     *
     * @return temperature in °C
     * @throws IOException on bus error or stale data
     */
    double readTemperature() throws IOException {
        return toTemperature(rawTemperature(fetch(4)))
    }

    /**
     * Measure humidity only (two-byte fetch).
     *
     * @return relative humidity in %RH
     * @throws IOException on bus error or stale data
     */
    double readHumidity() throws IOException {
        return toHumidity(rawHumidity(fetch(2)))
    }

    /**
     * Measure and return the raw 14-bit values and the status field, without a stale check.
     *
     * @return {raw humidity, raw temperature, status} with status 0 = valid, 1 = stale
     * @throws IOException on bus error
     */
    int[] readRaw() throws IOException {
        byte[] d = fetchRaw(4)
        return [rawHumidity(d), rawTemperature(d), (d[0] & 0xC0) >> 6] as int[]
    }

    /**
     * Override the wait between measurement request and data fetch (default 40 ms).
     * Lower resolutions convert faster: 0.1 ms + RH_time/2 + T_time/2 (datasheet).
     *
     * @param ms wait in milliseconds, at least 1
     */
    void setMeasurementWait(long ms) {
        if (ms < 1L) throw new IllegalArgumentException('ms must be >= 1')
        this.waitMs = ms
    }

    /**
     * Send the enter-programming-mode command (0xA0 0x00 0x00) and wait; only valid within 10 ms after power-up.
     *
     * @throws IOException on bus error
     */
    void enterProgrammingMode() throws IOException {
        connection.write([0xA0, 0x00, 0x00] as byte[])
        sleepMs(1L)
    }

    /**
     * Send the leave-programming-mode command (0x80 0x00 0x00).
     *
     * @throws IOException on bus error
     */
    void exitProgrammingMode() throws IOException {
        connection.write([0x80, 0x00, 0x00] as byte[])
    }

    /**
     * Read one 16-bit NVM register (programming mode only).
     *
     * @param reg NVM read address (0x06, 0x11, 0x1E or 0x1F)
     * @return register value (0…65535)
     * @throws IOException on bus error or if the response status byte is not 0x81
     */
    int readNvm(int reg) throws IOException {
        connection.write([reg, 0x00, 0x00] as byte[])
        sleepMs(1L)
        byte[] d = connection.read(3)
        if ((d[0] & 0xFF) != NVM_OK) {
            throw new IOException(String.format('HS30xx NVM read failed (status 0x%02X)', d[0] & 0xFF))
        }
        return ((d[1] & 0xFF) << 8) | (d[2] & 0xFF)
    }

    private void writeNvm(int reg, int value) throws IOException {
        connection.write([reg, (value >> 8) & 0xFF, value & 0xFF] as byte[])
        sleepMs(14L)
    }

    private void program(Runnable powerCycle) throws IOException {
        if (powerCycle == null) throw new IllegalStateException('HS30xx NVM access needs a powerCycle callback')
        powerCycle.run()
        enterProgrammingMode()
    }

    private static int bitsCode(int value) {
        for (int i = 0; i < BITS.length; i++) {
            if (BITS[i] == value) return i
        }
        throw new IllegalArgumentException('resolution must be 8, 10, 12 or 14 bits')
    }

    /**
     * Read the NVM resolution settings (power-cycles the sensor).
     *
     * @param powerCycle cuts and restores the sensor supply; the programming-mode command follows immediately
     * @return {humidity bits, temperature bits}, each 8, 10, 12 or 14
     * @throws IOException on bus error or a failed NVM read
     * @throws IllegalStateException if {@code powerCycle} is null
     */
    int[] getResolution(Runnable powerCycle) throws IOException {
        program(powerCycle)
        try {
            int rh = readNvm(REG_RH_READ)
            int t = readNvm(REG_T_READ)
            return [BITS[(rh >> 10) & 3], BITS[(t >> 10) & 3]] as int[]
        } finally {
            exitProgrammingMode()
        }
    }

    /**
     * Write the NVM resolution settings (power-cycles the sensor; bits 11:10 only).
     * Each register is read, bits 11:10 replaced and the whole word written back, then the driver
     * waits 14 ms for the NVM commit.
     *
     * @param humidityBits    8, 10, 12 or 14, or null to leave unchanged
     * @param temperatureBits 8, 10, 12 or 14, or null to leave unchanged
     * @param powerCycle      cuts and restores the sensor supply
     * @throws IOException on bus error or a failed NVM read
     * @throws IllegalArgumentException for any other resolution (before any bus access)
     * @throws IllegalStateException if {@code powerCycle} is null
     */
    void setResolution(Integer humidityBits, Integer temperatureBits, Runnable powerCycle) throws IOException {
        int rhCode = humidityBits == null ? -1 : bitsCode(humidityBits.intValue())
        int tCode = temperatureBits == null ? -1 : bitsCode(temperatureBits.intValue())
        program(powerCycle)
        try {
            if (rhCode >= 0) writeNvm(REG_RH_WRITE, (readNvm(REG_RH_READ) & ~RES_MASK & 0xFFFF) | (rhCode << 10))
            if (tCode >= 0) writeNvm(REG_T_WRITE, (readNvm(REG_T_READ) & ~RES_MASK & 0xFFFF) | (tCode << 10))
        } finally {
            exitProgrammingMode()
        }
    }

    /**
     * Read the 32-bit sensor ID from NVM (power-cycles the sensor).
     *
     * @param powerCycle cuts and restores the sensor supply
     * @return the 32-bit ID (unsigned)
     * @throws IOException on bus error or a failed NVM read
     * @throws IllegalStateException if {@code powerCycle} is null
     */
    long readId(Runnable powerCycle) throws IOException {
        program(powerCycle)
        try {
            long high = readNvm(REG_ID_HIGH)
            long low = readNvm(REG_ID_LOW)
            return (high << 16) | low
        } finally {
            exitProgrammingMode()
        }
    }

    /**
     * Measure once and compute the dew point (Magnus formula).
     *
     * @return dew point in °C; −273.15 if the humidity is 0 %RH
     * @throws IOException on bus error or stale data
     */
    double dewPoint() throws IOException {
        double[] m = read()
        double t = m[0]
        double rh = m[1]
        if (rh <= 0.0d) return -273.15d
        double gamma = Math.log(rh / 100.0d) + 17.62d * t / (243.12d + t)
        return 243.12d * gamma / (17.62d - gamma)
    }
}
