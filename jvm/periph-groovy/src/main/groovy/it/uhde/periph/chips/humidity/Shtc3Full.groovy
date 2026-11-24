package it.uhde.periph.chips.humidity

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

import java.io.IOException

/**
 * SHTC3 — full driver: single values, raw codes, low-power mode, clock stretching, sleep control,
 * soft reset, product ID and dew point. Extends {@link Shtc3Minimal}.
 */
@CompileStatic
class Shtc3Full extends Shtc3Minimal {

    /**
     * Create the driver; does no bus access.
     *
     * @param connection I²C connection bound to 0x70
     */
    Shtc3Full(Connection connection) {
        super(connection)
    }

    /**
     * Run one measurement cycle and return the temperature.
     *
     * @return temperature in °C
     * @throws IOException on bus error or CRC mismatch
     */
    double readTemperature() throws IOException {
        return toTemperature(measure()[0])
    }

    /**
     * Run one measurement cycle and return the humidity.
     *
     * @return relative humidity in %RH, clamped to 0–100
     * @throws IOException on bus error or CRC mismatch
     */
    double readHumidity() throws IOException {
        return toHumidity(measure()[1])
    }

    /**
     * Run one measurement cycle and return the raw codes.
     *
     * @return {raw temperature, raw humidity}, both 16-bit unsigned
     * @throws IOException on bus error or CRC mismatch
     */
    int[] readRaw() throws IOException {
        return measure()
    }

    /**
     * Use the low-power (0.7 ms, waits 1 ms) or the normal (10.8 ms, waits 13 ms) measurement commands.
     *
     * @param on true for low-power mode
     */
    void setLowPower(boolean on) {
        this.@lowPower = on
    }

    /**
     * Use the clock-stretching command set; the fixed wait is skipped. Needs a controller that
     * tolerates a slave holding SCL low.
     *
     * @param on true for clock stretching
     */
    void setClockStretching(boolean on) {
        this.@clockStretching = on
    }

    /**
     * Choose whether a read ends with the sleep command (default on).
     *
     * @param on true to sleep after every cycle
     */
    void setSleepBetween(boolean on) {
        this.@sleepBetween = on
    }

    /**
     * Send the wake-up command (0x3517) and wait 1 ms.
     *
     * @throws IOException on bus error
     */
    void wake() throws IOException {
        command(CMD_WAKE)
        sleepMs(1)
        asleep = false
    }

    /**
     * Send the sleep command (0xB098).
     *
     * @throws IOException on bus error
     */
    void sleep() throws IOException {
        goToSleep()
    }

    /**
     * Soft reset (0x805D), 1 ms wait; leaves the sensor idle.
     *
     * @throws IOException on bus error
     */
    void softReset() throws IOException {
        wakeIfAsleep()
        command(CMD_RESET)
        sleepMs(1)
    }

    /**
     * Read the 16-bit ID word (command 0xEFC8, CRC verified). A sleeping sensor is woken for the
     * read and put back to sleep.
     *
     * @return the ID word; {@code (id & 0x083F) == 0x0807} for the SHTC3
     * @throws IOException on bus error or CRC mismatch
     */
    int readId() throws IOException {
        boolean wasAsleep = asleep
        wakeIfAsleep()
        try {
            return readIdWord()
        } finally {
            if (wasAsleep) goToSleep()
        }
    }

    /**
     * Run one measurement and compute the dew point (Magnus formula over water).
     *
     * @return dew point in °C
     * @throws IOException on bus error or CRC mismatch
     */
    double dewPoint() throws IOException {
        double[] m = read()
        double t = m[0]
        double rh = m[1] <= 0.0d ? 0.01d : m[1]
        double g = Math.log(rh / 100.0d) + 17.62d * t / (243.12d + t)
        return 243.12d * g / (17.62d - g)
    }
}
