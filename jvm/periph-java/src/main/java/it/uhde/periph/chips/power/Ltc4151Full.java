package it.uhde.periph.chips.power;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * LTC4151 — full driver. Extends {@link Ltc4151Minimal} with all three channels,
 * snapshot conversions and CONTROL register access.
 *
 * <h2>Channel constants</h2>
 * {@link #SENSE} = 0, {@link #VIN} = 1, {@link #ADIN} = 2
 */
public class Ltc4151Full extends Ltc4151Minimal {

    /** Channel: voltage across the sense resistor. */
    public static final int SENSE = 0;
    /** Channel: supply voltage at VIN. */
    public static final int VIN = 1;
    /** Channel: ADIN pin. */
    public static final int ADIN = 2;

    private static final int CTRL_SNAPSHOT  = 0x80;
    private static final int CTRL_TEST      = 0x10;
    private static final int CTRL_PAGE_RW   = 0x08;
    private static final int CTRL_STUCK_BUS = 0x04;
    private static final long SNAPSHOT_TIMEOUT_MS = 100;

    /** Result of {@link #readAll()}; all values in SI units. */
    public static final class Readings {
        /** Supply voltage in V. */
        public final double vin;
        /** Sense voltage in V. */
        public final double senseV;
        /** Load current in A. */
        public final double current;
        /** Load power in W. */
        public final double power;
        /** ADIN pin voltage in V. */
        public final double adin;

        Readings(double vin, double senseV, double current, double power, double adin) {
            this.vin = vin;
            this.senseV = senseV;
            this.current = current;
            this.power = power;
            this.adin = adin;
        }
    }

    /**
     * Construct the driver with the default 20 mΩ sense resistor. Makes no bus access.
     *
     * @param connection I²C connection bound to the LTC4151 device address
     */
    public Ltc4151Full(RegisterConnection connection) {
        super(connection);
    }

    /**
     * Construct the driver. Makes no bus access.
     *
     * @param connection I²C connection bound to the LTC4151 device address
     * @param rSense     sense resistor in ohms; must be greater than 0
     */
    public Ltc4151Full(RegisterConnection connection, double rSense) {
        super(connection, rSense);
    }

    private static void checkChannel(int channel) {
        if (channel < 0 || channel > 2) throw new IllegalArgumentException("channel must be 0, 1 or 2");
    }

    /**
     * Read the voltage across the sense resistor.
     *
     * @return SENSE+ − SENSE− in V (code × 20 µV)
     * @throws IOException on I²C error
     */
    public double senseVoltage() throws IOException {
        return code(readBlock(REG_SENSE, 2), 0) * LSB_SENSE;
    }

    /**
     * Read the ADIN pin voltage.
     *
     * @return ADIN in V (code × 0.5 mV)
     * @throws IOException on I²C error
     */
    public double adinVoltage() throws IOException {
        return code(readBlock(REG_ADIN, 2), 0) * LSB_ADIN;
    }

    /**
     * Read all three channels from one block read of registers 0x00–0x05.
     *
     * @return VIN, sense voltage, current, power and ADIN
     * @throws IOException on I²C error
     */
    public Readings readAll() throws IOException {
        byte[] d = readBlock(REG_SENSE, 6);
        double senseV = code(d, 0) * LSB_SENSE;
        double vin = code(d, 2) * LSB_VIN;
        double adin = code(d, 4) * LSB_ADIN;
        double current = senseV / rSense;
        return new Readings(vin, senseV, current, vin * current, adin);
    }

    /**
     * Read the 12-bit code of a channel.
     *
     * @param channel {@link #SENSE}, {@link #VIN} or {@link #ADIN}
     * @return 12-bit conversion code, 0–4095
     * @throws IOException on I²C error
     */
    public int readRaw(int channel) throws IOException {
        checkChannel(channel);
        return code(readBlock(channel * 2, 2), 0);
    }

    /**
     * Set the sense resistor value used for current and power.
     *
     * @param ohms sense resistor in ohms; must be greater than 0
     * @throws IllegalArgumentException if ohms is not greater than 0
     */
    public void setSenseResistor(double ohms) {
        if (!(ohms > 0)) throw new IllegalArgumentException("ohms must be > 0");
        rSense = ohms;
    }

    /**
     * Get the sense resistor value.
     *
     * @return sense resistor in ohms
     */
    public double getSenseResistor() {
        return rSense;
    }

    /**
     * Run one snapshot conversion of a channel, then return to continuous scan.
     *
     * <p>Clears the snapshot bit, writes the channel with the snapshot bit set, polls the
     * channel's BUSY bit (timeout 100 ms), reads the result and restores continuous scan.
     *
     * @param channel {@link #SENSE}, {@link #VIN} or {@link #ADIN}
     * @return converted value in V (sense voltage, VIN or ADIN)
     * @throws IOException on I²C error or if the conversion does not finish within 100 ms
     */
    public double snapshot(int channel) throws IOException {
        checkChannel(channel);
        int base = readControlRaw() & (CTRL_PAGE_RW | CTRL_STUCK_BUS);
        writeControlRaw(base);
        writeControlRaw(base | CTRL_SNAPSHOT | (channel << 5));
        long start = System.nanoTime();
        while (isBusy(channel)) {
            if ((System.nanoTime() - start) / 1_000_000 > SNAPSHOT_TIMEOUT_MS) {
                writeControlRaw(base);
                throw new IOException("LTC4151 snapshot timeout");
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
        int raw = readRaw(channel);
        writeControlRaw(base);
        return raw * (channel == SENSE ? LSB_SENSE : channel == VIN ? LSB_VIN : LSB_ADIN);
    }

    /**
     * Switch to continuous scan mode (snapshot bit cleared).
     *
     * @throws IOException on I²C error
     */
    public void setContinuous() throws IOException {
        writeControlRaw(readControlRaw() & ~(CTRL_SNAPSHOT | CTRL_TEST));
    }

    /**
     * Read the snapshot bit of CONTROL.
     *
     * @return true if the chip is in snapshot mode
     * @throws IOException on I²C error
     */
    public boolean isSnapshotMode() throws IOException {
        return (readControlRaw() & CTRL_SNAPSHOT) != 0;
    }

    /**
     * Read the BUSY bit of a channel (snapshot mode).
     *
     * @param channel {@link #SENSE}, {@link #VIN} or {@link #ADIN}
     * @return true while that channel is being converted
     * @throws IOException on I²C error
     */
    public boolean isBusy(int channel) throws IOException {
        checkChannel(channel);
        return (connection.read(channel * 2 + 1, 1)[0] & 0x08) != 0;
    }

    /**
     * Enable or disable the stuck-bus reset timer (CONTROL.G2, read-modify-write).
     *
     * @param enabled true to enable (power-on default)
     * @throws IOException on I²C error
     */
    public void setStuckBusTimer(boolean enabled) throws IOException {
        int v = readControlRaw() & ~CTRL_TEST;
        writeControlRaw(enabled ? (v | CTRL_STUCK_BUS) : (v & ~CTRL_STUCK_BUS));
    }

    /**
     * Enable or disable I²C page read/write (CONTROL.G3, read-modify-write). When
     * disabled the driver reads each register separately.
     *
     * @param enabled true to enable (power-on default)
     * @throws IOException on I²C error
     */
    public void setPageRw(boolean enabled) throws IOException {
        int v = readControlRaw() & ~CTRL_TEST;
        writeControlRaw(enabled ? (v | CTRL_PAGE_RW) : (v & ~CTRL_PAGE_RW));
        pageRw = enabled;
    }

    /**
     * Read the CONTROL register.
     *
     * @return raw CONTROL value (power-on default 0x0C)
     * @throws IOException on I²C error
     */
    public int readControl() throws IOException {
        return readControlRaw();
    }

    /**
     * Write the CONTROL register. Test mode (bit 4) is always masked to 0.
     *
     * @param value raw CONTROL value
     * @throws IOException on I²C error
     */
    public void writeControl(int value) throws IOException {
        int v = value & ~CTRL_TEST & 0xFF;
        writeControlRaw(v);
        pageRw = (v & CTRL_PAGE_RW) != 0;
    }

    /**
     * Convert an ADIN/VIN code pair to temperature (datasheet NTC example circuit,
     * valid for roughly 20–60 °C only).
     *
     * @param adinCode 12-bit ADIN code
     * @param vinCode  12-bit VIN code
     * @return temperature in °C, 58.82 × (adinCode / vinCode − 0.1066)
     */
    public static double temperatureFromAdin(int adinCode, int vinCode) {
        return 58.82 * ((double) adinCode / vinCode - 0.1066);
    }
}
