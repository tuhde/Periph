package it.uhde.periph.chips.gas;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * RRH62000 full interface — extends {@link Rrh62000Minimal} with the complete block, status
 * flags, particle bins, sleep, fan cleaning, reset, settings (moving average, fan speed, cleaning
 * interval and time), MOX resistance and identification.
 */
public class Rrh62000Full extends Rrh62000Minimal {

    /**
     * Creates the driver with the 1 s access interval; no bus access.
     *
     * @param connection register connection (1-byte register addresses) bound to {@code 0x69}
     */
    public Rrh62000Full(RegisterConnection connection) {
        super(connection);
    }

    /**
     * Creates the driver; no bus access.
     *
     * @param connection       register connection bound to {@code 0x69}
     * @param accessIntervalMs minimum spacing between two accesses (≥ 1000 ms for the module)
     */
    public Rrh62000Full(RegisterConnection connection, long accessIntervalMs) {
        super(connection, accessIntervalMs);
    }

    /**
     * Waits for a new sample and returns the complete decoded block.
     *
     * @return status, particles, temperature, humidity, TVOC, eCO₂ and IAQ
     * @throws IOException on a bus error, a timeout, a CRC mismatch or a start-up block
     */
    public synchronized Data readAll() throws IOException {
        return readBlock(true);
    }

    /**
     * Reads the current block (without waiting for new data) and returns its status word.
     *
     * @return the status flags
     * @throws IOException on a bus or data error
     */
    public synchronized Status readStatus() throws IOException {
        return readBlock(false).status();
    }

    /**
     * Reads the current block (without waiting for new data) and returns the particle values.
     *
     * @return the six PM values (µg/m³, KCl and smoke) and the five number concentrations (cm⁻³)
     * @throws IOException on a bus or data error
     */
    public synchronized Particles readParticles() throws IOException {
        return readBlock(false).particles();
    }

    /**
     * Checks the DATA flag ({@code 0x40}).
     *
     * @return true when new data has arrived
     * @throws IOException on a bus error
     */
    public synchronized boolean dataReady() throws IOException {
        return readByte(CMD_DATA) == 0x01;
    }

    /**
     * Puts the module to sleep ({@code 0x50} ← {@code 0x00}).
     *
     * @throws IOException on a bus error
     */
    public synchronized void sleep() throws IOException {
        writeCmd(CMD_SLEEP, 0x00);
    }

    /**
     * Wakes the module up ({@code 0x50} ← {@code 0x80}) and restarts the start-up timers.
     *
     * @throws IOException on a bus error
     */
    public synchronized void wake() throws IOException {
        writeCmd(CMD_SLEEP, 0x80);
        markStartup();
    }

    /**
     * Starts the fan dust-cleaning ({@code 0x51} ← {@code 0x01}).
     *
     * @throws IOException on a bus error
     */
    public synchronized void startCleaning() throws IOException {
        writeCmd(CMD_CLEAN, 0x01);
    }

    /**
     * Reads the cleaning status ({@code 0x74}).
     *
     * @return true when the cleaning is completed
     * @throws IOException on a bus error
     */
    public synchronized boolean cleaningDone() throws IOException {
        return readByte(CMD_CSTATUS) == 0x01;
    }

    /**
     * Resets the module ({@code 0x52} ← {@code 0x81}, same as power-on) and restarts the start-up
     * timers; a missing acknowledge during the reset is ignored.
     */
    public synchronized void softReset() {
        try {
            writeCmd(CMD_RESET, 0x81);
        } catch (IOException e) {
            // the module may not acknowledge while resetting
        }
        markStartup();
    }

    /**
     * Sets the number of moving averages ({@code 0x53}).
     *
     * @param count 1–60 (default 10)
     * @throws IllegalArgumentException if count is out of range
     * @throws IOException              on a bus error
     */
    public synchronized void setMovingAverage(int count) throws IOException {
        if (count < 1 || count > 60) throw new IllegalArgumentException("count must be 1..60");
        writeCmd(CMD_MAVE, count);
    }

    /**
     * Reads the number of moving averages.
     *
     * @return 1–60
     * @throws IOException on a bus error
     */
    public synchronized int getMovingAverage() throws IOException {
        return readByte(CMD_MAVE);
    }

    /**
     * Sets the fan speed ({@code 0x63}).
     *
     * @param percent 60–100 % (default 86)
     * @throws IllegalArgumentException if percent is out of range
     * @throws IOException              on a bus error
     */
    public synchronized void setFanSpeed(int percent) throws IOException {
        if (percent < 60 || percent > 100) throw new IllegalArgumentException("percent must be 60..100");
        writeCmd(CMD_SPEEDFAN, percent);
    }

    /**
     * Reads the fan speed.
     *
     * @return percent (60–100)
     * @throws IOException on a bus error
     */
    public synchronized int getFanSpeed() throws IOException {
        return readByte(CMD_SPEEDFAN);
    }

    /**
     * Sets the automatic cleaning interval ({@code 0x5A}/{@code 0x5B}, 16-bit count of 30 s units).
     *
     * @param seconds 0–1 814 400 s, a multiple of 30 (default 604 800 = 7 days)
     * @throws IllegalArgumentException if seconds is out of range or not a multiple of 30
     * @throws IOException              on a bus error
     */
    public synchronized void setCleaningInterval(int seconds) throws IOException {
        if (seconds < 0 || seconds > 1814400 || seconds % 30 != 0) {
            throw new IllegalArgumentException("seconds must be 0..1814400 and a multiple of 30");
        }
        int units = seconds / 30;
        writeCmd(CMD_TINTC_H, units >> 8);
        writeCmd(CMD_TINTC_L, units & 0xFF);
    }

    /**
     * Reads the automatic cleaning interval.
     *
     * @return seconds
     * @throws IOException on a bus error
     */
    public synchronized int getCleaningInterval() throws IOException {
        int hi = readByte(CMD_TINTC_H);
        int lo = readByte(CMD_TINTC_L);
        return (hi << 8 | lo) * 30;
    }

    /**
     * Sets the fan auto-cleaning time ({@code 0x5C}).
     *
     * @param seconds 0–60 s (default 10)
     * @throws IllegalArgumentException if seconds is out of range
     * @throws IOException              on a bus error
     */
    public synchronized void setCleaningTime(int seconds) throws IOException {
        if (seconds < 0 || seconds > 60) throw new IllegalArgumentException("seconds must be 0..60");
        writeCmd(CMD_TCLEAN, seconds);
    }

    /**
     * Reads the fan auto-cleaning time.
     *
     * @return seconds (0–60)
     * @throws IOException on a bus error
     */
    public synchronized int getCleaningTime() throws IOException {
        return readByte(CMD_TCLEAN);
    }

    /**
     * Reads the MOX sensor resistance ({@code 0x71}, 4 bytes big-endian).
     *
     * @return ohms
     * @throws IOException on a bus error
     */
    public synchronized long readMoxResistance() throws IOException {
        byte[] d = readCmd(CMD_RMOX, 4);
        return (long) (d[0] & 0xFF) << 24 | (d[1] & 0xFF) << 16 | (d[2] & 0xFF) << 8 | (d[3] & 0xFF);
    }

    /**
     * Reads the unique ID ({@code 0x72}).
     *
     * @return 6 bytes
     * @throws IOException on a bus error
     */
    public synchronized byte[] uniqueId() throws IOException {
        return readCmd(CMD_UID, 6);
    }

    /**
     * Reads the TVOC algorithm version ({@code 0x73}).
     *
     * @return {@code {major, minor, patch}}
     * @throws IOException on a bus error
     */
    public synchronized int[] algorithmVersion() throws IOException {
        byte[] d = readCmd(CMD_ARGVER, 3);
        return new int[] {d[0] & 0xFF, d[1] & 0xFF, d[2] & 0xFF};
    }

    /**
     * Reads the firmware version ({@code 0x75}).
     *
     * @return {@code {major, minor}}
     * @throws IOException on a bus error
     */
    public synchronized int[] firmwareVersion() throws IOException {
        byte[] d = readCmd(CMD_FWVER, 2);
        return new int[] {d[0] & 0xFF, d[1] & 0xFF};
    }
}
