package it.uhde.periph.chips.power

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection

/**
 * LTC4151 — full driver. Extends {@link Ltc4151Minimal} with all three channels,
 * snapshot conversions and CONTROL register access.
 *
 * <h2>Channel constants</h2>
 * {@link #SENSE} = 0, {@link #VIN} = 1, {@link #ADIN} = 2
 */
@CompileStatic
class Ltc4151Full extends Ltc4151Minimal {

    /** Channel: voltage across the sense resistor. */
    static final int SENSE = 0
    /** Channel: supply voltage at VIN. */
    static final int VIN = 1
    /** Channel: ADIN pin. */
    static final int ADIN = 2

    private static final int CTRL_SNAPSHOT  = 0x80
    private static final int CTRL_TEST      = 0x10
    private static final int CTRL_PAGE_RW   = 0x08
    private static final int CTRL_STUCK_BUS = 0x04
    private static final long SNAPSHOT_TIMEOUT_MS = 100L

    /** Result of {@link #readAll()}; all values in SI units (V, A, W). */
    static final class Readings {
        /** Supply voltage in V. */
        final double vin
        /** Sense voltage in V. */
        final double senseV
        /** Load current in A. */
        final double current
        /** Load power in W. */
        final double power
        /** ADIN pin voltage in V. */
        final double adin

        Readings(double vin, double senseV, double current, double power, double adin) {
            this.vin = vin
            this.senseV = senseV
            this.current = current
            this.power = power
            this.adin = adin
        }
    }

    /**
     * Construct the driver with the default 20 mΩ sense resistor. Makes no bus access.
     *
     * @param connection I²C connection bound to the LTC4151 device address
     */
    Ltc4151Full(RegisterConnection connection) {
        super(connection)
    }

    /**
     * Construct the driver. Makes no bus access.
     *
     * @param connection I²C connection bound to the LTC4151 device address
     * @param rSense     sense resistor in ohms; must be greater than 0
     */
    Ltc4151Full(RegisterConnection connection, double rSense) {
        super(connection, rSense)
    }

    private static void checkChannel(int channel) {
        if (channel < 0 || channel > 2) throw new IllegalArgumentException('channel must be 0, 1 or 2')
    }

    /**
     * Read the voltage across the sense resistor.
     *
     * @return SENSE+ − SENSE− in V (code × 20 µV)
     */
    double senseVoltage() {
        code(readBlock(REG_SENSE, 2), 0) * LSB_SENSE
    }

    /**
     * Read the ADIN pin voltage.
     *
     * @return ADIN in V (code × 0.5 mV)
     */
    double adinVoltage() {
        code(readBlock(REG_ADIN, 2), 0) * LSB_ADIN
    }

    /**
     * Read all three channels from one block read of registers 0x00–0x05.
     *
     * @return VIN, sense voltage, current, power and ADIN
     */
    Readings readAll() {
        byte[] d = readBlock(REG_SENSE, 6)
        double senseV = code(d, 0) * LSB_SENSE
        double vin = code(d, 2) * LSB_VIN
        double adin = code(d, 4) * LSB_ADIN
        double current = senseV / rSenseOhms
        new Readings(vin, senseV, current, vin * current, adin)
    }

    /**
     * Read the 12-bit code of a channel.
     *
     * @param channel {@link #SENSE}, {@link #VIN} or {@link #ADIN}
     * @return 12-bit conversion code, 0–4095
     */
    int readRaw(int channel) {
        checkChannel(channel)
        code(readBlock(channel * 2, 2), 0)
    }

    /**
     * Set the sense resistor value used for current and power.
     *
     * @param ohms sense resistor in ohms; must be greater than 0
     */
    void setSenseResistor(double ohms) {
        if (!(ohms > 0d)) throw new IllegalArgumentException('ohms must be > 0')
        this.rSenseOhms = ohms
    }

    /**
     * Get the sense resistor value.
     *
     * @return sense resistor in ohms
     */
    double getSenseResistor() {
        rSenseOhms
    }

    /**
     * Run one snapshot conversion of a channel, then return to continuous scan.
     *
     * <p>Clears the snapshot bit, writes the channel with the snapshot bit set, polls the
     * channel's BUSY bit (timeout 100 ms), reads the result and restores continuous scan.
     *
     * @param channel {@link #SENSE}, {@link #VIN} or {@link #ADIN}
     * @return converted value in V (sense voltage, VIN or ADIN)
     * @throws IOException if the conversion does not finish within 100 ms
     */
    double snapshot(int channel) {
        checkChannel(channel)
        int base = readControlRaw() & (CTRL_PAGE_RW | CTRL_STUCK_BUS)
        writeControlRaw(base)
        writeControlRaw(base | CTRL_SNAPSHOT | (channel << 5))
        long start = System.nanoTime()
        while (isBusy(channel)) {
            if ((System.nanoTime() - start).intdiv(1_000_000L) > SNAPSHOT_TIMEOUT_MS) {
                writeControlRaw(base)
                throw new IOException('LTC4151 snapshot timeout')
            }
            Thread.sleep(2L)
        }
        int raw = readRaw(channel)
        writeControlRaw(base)
        raw * (channel == SENSE ? LSB_SENSE : (channel == VIN ? LSB_VIN : LSB_ADIN))
    }

    /** Switch to continuous scan mode (snapshot bit cleared). */
    void setContinuous() {
        writeControlRaw(readControlRaw() & ~(CTRL_SNAPSHOT | CTRL_TEST))
    }

    /**
     * Read the snapshot bit of CONTROL.
     *
     * @return true if the chip is in snapshot mode
     */
    boolean isSnapshotMode() {
        (readControlRaw() & CTRL_SNAPSHOT) != 0
    }

    /**
     * Read the BUSY bit of a channel (snapshot mode).
     *
     * @param channel {@link #SENSE}, {@link #VIN} or {@link #ADIN}
     * @return true while that channel is being converted
     */
    boolean isBusy(int channel) {
        checkChannel(channel)
        (connection.read(channel * 2 + 1, 1)[0] & 0x08) != 0
    }

    /**
     * Enable or disable the stuck-bus reset timer (CONTROL.G2, read-modify-write).
     *
     * @param enabled true to enable (power-on default)
     */
    void setStuckBusTimer(boolean enabled) {
        int v = readControlRaw() & ~CTRL_TEST
        writeControlRaw(enabled ? (v | CTRL_STUCK_BUS) : (v & ~CTRL_STUCK_BUS))
    }

    /**
     * Enable or disable I²C page read/write (CONTROL.G3, read-modify-write). When
     * disabled the driver reads each register separately.
     *
     * @param enabled true to enable (power-on default)
     */
    void setPageRw(boolean enabled) {
        int v = readControlRaw() & ~CTRL_TEST
        writeControlRaw(enabled ? (v | CTRL_PAGE_RW) : (v & ~CTRL_PAGE_RW))
        this.pageEnabled = enabled
    }

    /**
     * Read the CONTROL register.
     *
     * @return raw CONTROL value (power-on default 0x0C)
     */
    int readControl() {
        readControlRaw()
    }

    /**
     * Write the CONTROL register. Test mode (bit 4) is always masked to 0.
     *
     * @param value raw CONTROL value
     */
    void writeControl(int value) {
        int v = value & ~CTRL_TEST & 0xFF
        writeControlRaw(v)
        this.pageEnabled = (v & CTRL_PAGE_RW) != 0
    }

    /**
     * Convert an ADIN/VIN code pair to temperature (datasheet NTC example circuit,
     * valid for roughly 20–60 °C only).
     *
     * @param adinCode 12-bit ADIN code
     * @param vinCode  12-bit VIN code
     * @return temperature in °C, 58.82 × (adinCode / vinCode − 0.1066)
     */
    static double temperatureFromAdin(int adinCode, int vinCode) {
        58.82d * ((double) adinCode / (double) vinCode - 0.1066d)
    }
}
