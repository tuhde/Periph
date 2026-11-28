package it.uhde.periph.chips.gas

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * RRH62000 — full driver: extends [Rrh62000Minimal] with the complete block, status flags,
 * particle bins, sleep, fan cleaning, reset, settings (moving average, fan speed, cleaning interval
 * and time), MOX resistance and identification.
 *
 * @param connection register connection (1-byte register addresses) bound to address 0x69
 * @param accessIntervalMs minimum spacing between two accesses (≥ 1000 ms for the module)
 */
open class Rrh62000Full @JvmOverloads constructor(
    connection: RegisterConnection,
    accessIntervalMs: Long = 1000,
) : Rrh62000Minimal(connection, accessIntervalMs) {

    /**
     * Waits for a new sample and returns the complete decoded block.
     *
     * @return status, particles, temperature, humidity, TVOC, eCO₂ and IAQ
     */
    @Synchronized
    fun readAll(): Rrh62000Data = readBlock(true)

    /**
     * Reads the current block (without waiting for new data) and returns its status word.
     *
     * @return the status flags
     */
    @Synchronized
    fun readStatus(): Rrh62000Status = readBlock(false).status

    /**
     * Reads the current block (without waiting for new data) and returns the particle values.
     *
     * @return the six PM values (µg/m³, KCl and smoke) and the five number concentrations (cm⁻³)
     */
    @Synchronized
    fun readParticles(): Rrh62000Particles = readBlock(false).particles

    /**
     * Checks the DATA flag (0x40).
     *
     * @return true when new data has arrived
     */
    @Synchronized
    fun dataReady(): Boolean = readByte(CMD_DATA) == 0x01

    /** Puts the module to sleep (0x50 ← 0x00). */
    @Synchronized
    fun sleep() = writeCmd(CMD_SLEEP, 0x00)

    /** Wakes the module up (0x50 ← 0x80) and restarts the start-up timers. */
    @Synchronized
    fun wake() {
        writeCmd(CMD_SLEEP, 0x80)
        markStartup()
    }

    /** Starts the fan dust-cleaning (0x51 ← 0x01). */
    @Synchronized
    fun startCleaning() = writeCmd(CMD_CLEAN, 0x01)

    /**
     * Reads the cleaning status (0x74).
     *
     * @return true when the cleaning is completed
     */
    @Synchronized
    fun cleaningDone(): Boolean = readByte(CMD_CSTATUS) == 0x01

    /**
     * Resets the module (0x52 ← 0x81, same as power-on) and restarts the start-up timers; a missing
     * acknowledge during the reset is ignored.
     */
    @Synchronized
    fun softReset() {
        try {
            writeCmd(CMD_RESET, 0x81)
        } catch (e: IOException) {
            // the module may not acknowledge while resetting
        }
        markStartup()
    }

    /**
     * Sets the number of moving averages (0x53).
     *
     * @param count 1–60 (default 10)
     * @throws IllegalArgumentException if count is out of range
     */
    @Synchronized
    fun setMovingAverage(count: Int) {
        require(count in 1..60) { "count must be 1..60" }
        writeCmd(CMD_MAVE, count)
    }

    /**
     * Reads the number of moving averages.
     *
     * @return 1–60
     */
    @Synchronized
    fun getMovingAverage(): Int = readByte(CMD_MAVE)

    /**
     * Sets the fan speed (0x63).
     *
     * @param percent 60–100 % (default 86)
     * @throws IllegalArgumentException if percent is out of range
     */
    @Synchronized
    fun setFanSpeed(percent: Int) {
        require(percent in 60..100) { "percent must be 60..100" }
        writeCmd(CMD_SPEEDFAN, percent)
    }

    /**
     * Reads the fan speed.
     *
     * @return percent (60–100)
     */
    @Synchronized
    fun getFanSpeed(): Int = readByte(CMD_SPEEDFAN)

    /**
     * Sets the automatic cleaning interval (0x5A/0x5B, 16-bit count of 30 s units).
     *
     * @param seconds 0–1 814 400 s, a multiple of 30 (default 604 800 = 7 days)
     * @throws IllegalArgumentException if seconds is out of range or not a multiple of 30
     */
    @Synchronized
    fun setCleaningInterval(seconds: Int) {
        require(seconds in 0..1814400 && seconds % 30 == 0) { "seconds must be 0..1814400 and a multiple of 30" }
        val units = seconds / 30
        writeCmd(CMD_TINTC_H, units shr 8)
        writeCmd(CMD_TINTC_L, units and 0xFF)
    }

    /**
     * Reads the automatic cleaning interval.
     *
     * @return seconds
     */
    @Synchronized
    fun getCleaningInterval(): Int {
        val hi = readByte(CMD_TINTC_H)
        val lo = readByte(CMD_TINTC_L)
        return ((hi shl 8) or lo) * 30
    }

    /**
     * Sets the fan auto-cleaning time (0x5C).
     *
     * @param seconds 0–60 s (default 10)
     * @throws IllegalArgumentException if seconds is out of range
     */
    @Synchronized
    fun setCleaningTime(seconds: Int) {
        require(seconds in 0..60) { "seconds must be 0..60" }
        writeCmd(CMD_TCLEAN, seconds)
    }

    /**
     * Reads the fan auto-cleaning time.
     *
     * @return seconds (0–60)
     */
    @Synchronized
    fun getCleaningTime(): Int = readByte(CMD_TCLEAN)

    /**
     * Reads the MOX sensor resistance (0x71, 4 bytes big-endian).
     *
     * @return ohms
     */
    @Synchronized
    fun readMoxResistance(): Long {
        val d = readCmd(CMD_RMOX, 4)
        return d.fold(0L) { acc, x -> (acc shl 8) or (x.toLong() and 0xFF) }
    }

    /**
     * Reads the unique ID (0x72).
     *
     * @return 6 bytes
     */
    @Synchronized
    fun uniqueId(): ByteArray = readCmd(CMD_UID, 6)

    /**
     * Reads the TVOC algorithm version (0x73).
     *
     * @return `[major, minor, patch]`
     */
    @Synchronized
    fun algorithmVersion(): IntArray = readCmd(CMD_ARGVER, 3).map { it.toInt() and 0xFF }.toIntArray()

    /**
     * Reads the firmware version (0x75).
     *
     * @return `[major, minor]`
     */
    @Synchronized
    fun firmwareVersion(): IntArray = readCmd(CMD_FWVER, 2).map { it.toInt() and 0xFF }.toIntArray()
}
