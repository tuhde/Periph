package it.uhde.periph.chips.environmental

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * AM2315 full driver — single values, raw words, fresh read, registers, user registers.
 *
 * <p>Extends {@link AM2315Minimal} with temperature-only and humidity-only reads, raw words, the
 * two-read real-time value, arbitrary register read/write (CRC verified), device information,
 * the user registers and an explicit wake-up.
 */
@CompileStatic
class AM2315Full extends AM2315Minimal {

    /**
     * Create the driver; does no bus access.
     *
     * @param connection I²C connection bound to 0x5C
     */
    AM2315Full(Connection connection) {
        super(connection)
    }

    /**
     * Read the temperature only (registers 0x02-0x03).
     *
     * @return temperature in °C
     * @throws IOException on bus error or CRC mismatch
     */
    double readTemperature() throws IOException {
        return toTemperature(word(readRegistersRaw(0x02, 2), 0))
    }

    /**
     * Read the humidity only (registers 0x00-0x01).
     *
     * @return relative humidity in %RH
     * @throws IOException on bus error or CRC mismatch
     */
    double readHumidity() throws IOException {
        return toHumidity(word(readRegistersRaw(0x00, 2), 0))
    }

    /**
     * Read the raw 16-bit words.
     *
     * @return {raw humidity, raw temperature}; the temperature word is sign + magnitude
     * @throws IOException on bus error or CRC mismatch
     */
    int[] readRaw() throws IOException {
        byte[] d = readRegistersRaw(0x00, 4)
        return [word(d, 0), word(d, 2)] as int[]
    }

    /**
     * Read twice (at least 2 s apart) and return the second, real-time result.
     *
     * @return {temperature in °C, relative humidity in %RH}
     * @throws IOException on bus error or CRC mismatch
     */
    double[] readFresh() throws IOException {
        read()
        return read()
    }

    /**
     * Read registers (function 0x03, CRC verified).
     *
     * @param start first register address (0x00-0x1F)
     * @param count number of registers, 1-10
     * @return the register contents
     * @throws IOException on bus error or CRC mismatch
     * @throws IllegalArgumentException if the range is invalid
     */
    byte[] readRegisters(int start, int count) throws IOException {
        if (count < 1 || count > 10 || start < 0 || start > 0x1F || start + count > 0x20) {
            throw new IllegalArgumentException('invalid register range')
        }
        return readRegistersRaw(start, count)
    }

    /**
     * Write the user registers 0x10-0x13 (function 0x10 with CRC).
     *
     * @param start first register address (0x10-0x13)
     * @param data  1-4 bytes; start + data.length must not pass 0x14
     * @throws IOException on bus error, CRC mismatch or a sensor error response
     * @throws IllegalArgumentException if the range is not inside the user registers
     */
    void writeRegisters(int start, byte[] data) throws IOException {
        int n = data.length
        if (n < 1 || n > 4 || start < 0x10 || start + n > 0x14) {
            throw new IllegalArgumentException('only the user registers 0x10-0x13 may be written')
        }
        byte[] frame = new byte[n + 5]
        frame[0] = (byte) FUNC_WRITE
        frame[1] = (byte) start
        frame[2] = (byte) n
        System.arraycopy(data, 0, frame, 3, n)
        int crc = crc16(frame, 0, n + 3)
        frame[n + 3] = (byte) crc
        frame[n + 4] = (byte) (crc >> 8)
        byte[] resp = transact(frame, 5, FUNC_WRITE)
        if ((resp[1] & 0xFF) != start || (resp[2] & 0xFF) != n) throw new IOException('AM2315 unexpected write response')
    }

    /**
     * Read the device information (registers 0x08-0x0E).
     *
     * @return {model, version, device id}
     * @throws IOException on bus error or CRC mismatch
     */
    long[] readDeviceInfo() throws IOException {
        byte[] d = readRegistersRaw(0x08, 7)
        long id = (((long) (d[3] & 0xFF)) << 24) | (((long) (d[4] & 0xFF)) << 16) | (((long) (d[5] & 0xFF)) << 8) | ((long) (d[6] & 0xFF))
        return [(long) word(d, 0), (long) (d[2] & 0xFF), id] as long[]
    }

    /**
     * Read the two 16-bit user registers (0x10-0x13).
     *
     * @return {user1, user2}
     * @throws IOException on bus error or CRC mismatch
     */
    int[] readUserRegisters() throws IOException {
        byte[] d = readRegistersRaw(0x10, 4)
        return [word(d, 0), word(d, 2)] as int[]
    }

    /**
     * Write the two 16-bit user registers (0x10-0x13).
     *
     * @param user1 value for registers 0x10-0x11
     * @param user2 value for registers 0x12-0x13
     * @throws IOException on bus error or a sensor error response
     */
    void writeUserRegisters(int user1, int user2) throws IOException {
        writeRegisters(0x10, [(byte) (user1 >> 8), (byte) user1, (byte) (user2 >> 8), (byte) user2] as byte[])
    }

    /** Wake the sensor: a dummy write (the NACK is ignored) and a 1 ms wait. */
    void wake() {
        wakeSensor()
    }
}
