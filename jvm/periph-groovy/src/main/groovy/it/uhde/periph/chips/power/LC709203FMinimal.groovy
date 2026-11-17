package it.uhde.periph.chips.power

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection

/**
 * LC709203F — 1-cell lithium-ion / polymer battery fuel gauge (onsemi), minimal driver.
 *
 * <p>Reports cell voltage, state of charge and temperature. The constructor runs the datasheet
 * starting flow (operational mode, APA from the design capacity, battery profile, I²C temperature
 * mode with 25 °C). Every transfer carries a CRC-8 (polynomial 0x07, init 0x00, over the address
 * bytes as well) that the driver appends on writes and verifies on reads (one retry).
 *
 * <p>I²C address: 0x0B (fixed). The chip stretches the clock.
 */
@CompileStatic
class LC709203FMinimal {

    protected static final int REG_BEFORE_RSOC   = 0x04
    protected static final int REG_THERMISTOR_B  = 0x06
    protected static final int REG_INITIAL_RSOC  = 0x07
    protected static final int REG_TEMPERATURE   = 0x08
    protected static final int REG_VOLTAGE       = 0x09
    protected static final int REG_DIRECTION     = 0x0A
    protected static final int REG_APA           = 0x0B
    protected static final int REG_APT           = 0x0C
    protected static final int REG_RSOC          = 0x0D
    protected static final int REG_ITE           = 0x0F
    protected static final int REG_IC_VERSION    = 0x11
    protected static final int REG_PROFILE       = 0x12
    protected static final int REG_ALARM_RSOC    = 0x13
    protected static final int REG_ALARM_VOLTAGE = 0x14
    protected static final int REG_POWER_MODE    = 0x15
    protected static final int REG_STATUS        = 0x16
    protected static final int REG_PROFILE_ID    = 0x1A

    private static final int[][] APA_TABLE = [
        [100, 0x08], [200, 0x0B], [500, 0x10], [1000, 0x19], [2000, 0x2D], [3000, 0x36]
    ] as int[][]

    protected final RegisterConnection connection

    /**
     * Construct the driver and run the starting flow.
     *
     * @param connection I²C connection bound to address 0x0B
     * @param capacityMah design capacity in mAh (clamped to 100–3000 for the APA interpolation)
     * @param profile battery profile written to register 0x12 (0 or 1)
     */
    LC709203FMinimal(RegisterConnection connection, int capacityMah = 1000, int profile = 1) {
        this.connection = connection
        wakeAndOperational()
        writeWord(REG_APA, apaFor(capacityMah))
        writeWord(REG_PROFILE, profile & 1)
        pause(100L) // TINIT: re-initialization like a battery insertion
        wakeAndOperational()
        writeWord(REG_STATUS, 0x0000)
        setTemperature(25.0d)
    }

    /**
     * CRC-8 (ATM): polynomial 0x07, init 0x00, MSB first, no reflection, no final XOR.
     *
     * @param data bytes to checksum
     * @return CRC byte (0–255)
     */
    static int crc8(int... data) {
        int crc = 0
        for (int b : data) {
            crc ^= b & 0xFF
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x07) & 0xFF : (crc << 1) & 0xFF
            }
        }
        crc
    }

    /**
     * APA value interpolated from the datasheet table (Type-01/03) for a design capacity.
     *
     * @param capacityMah design capacity in mAh
     * @return APA register value (clamped to 100–3000 mAh)
     */
    static int apaFor(int capacityMah) {
        int last = APA_TABLE.length - 1
        if (capacityMah <= APA_TABLE[0][0]) return APA_TABLE[0][1]
        if (capacityMah >= APA_TABLE[last][0]) return APA_TABLE[last][1]
        for (int i = 1; i <= last; i++) {
            if (capacityMah <= APA_TABLE[i][0]) {
                double c0 = APA_TABLE[i - 1][0]
                double a0 = APA_TABLE[i - 1][1]
                double c1 = APA_TABLE[i][0]
                double a1 = APA_TABLE[i][1]
                return (int) Math.round(a0 + (a1 - a0) * (capacityMah - c0) / (c1 - c0))
            }
        }
        APA_TABLE[last][1]
    }

    /**
     * Read the cell voltage.
     *
     * @return cell voltage in V (1 mV LSB)
     */
    double voltage() {
        readWord(REG_VOLTAGE) / 1000.0d
    }

    /**
     * Read the state of charge from the ITE register.
     *
     * @return state of charge in % (0.0–100.0)
     */
    double percent() {
        readWord(REG_ITE) / 10.0d
    }

    /**
     * Read the cell temperature register.
     *
     * @return cell temperature in °C
     */
    double temperature() {
        readWord(REG_TEMPERATURE) / 10.0d - 273.15d
    }

    /**
     * Supply the cell temperature (I²C temperature mode).
     *
     * @param celsius cell temperature in °C, clamped to −20…+60
     */
    void setTemperature(double celsius) {
        double c = Math.max(-20.0d, Math.min(60.0d, celsius))
        writeWord(REG_TEMPERATURE, (int) Math.round((c + 273.15d) * 10.0d))
    }

    // ---- low-level helpers ----

    /** Write operational mode twice, ignoring bus errors (the first transfer may be lost while waking). */
    protected void wakeAndOperational() {
        for (int i = 0; i < 2; i++) {
            try {
                writeWord(REG_POWER_MODE, 0x0001, false)
            } catch (IOException ignored) {
                // wake-up pulse
            }
        }
        pause(1L)
    }

    /**
     * Read a 16-bit word, verify the CRC-8 and retry once.
     *
     * @param cmd command code
     * @return word (0–65535)
     */
    protected int readWord(int cmd) {
        IOException last = null
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                byte[] b = connection.read(cmd, 3)
                int lo = b[0] & 0xFF
                int hi = b[1] & 0xFF
                if (crc8(0x16, cmd, 0x17, lo, hi) == (b[2] & 0xFF)) return lo | (hi << 8)
                last = new IOException(String.format('LC709203F CRC mismatch on command 0x%02X', cmd))
            } catch (IOException e) {
                last = e
            }
        }
        throw last
    }

    /**
     * Write a 16-bit word with CRC-8.
     *
     * @param cmd command code
     * @param value 16-bit value
     * @param retry retry once on a bus error
     */
    protected void writeWord(int cmd, int value, boolean retry = true) {
        int lo = value & 0xFF
        int hi = (value >> 8) & 0xFF
        byte[] data = [(byte) lo, (byte) hi, (byte) crc8(0x16, cmd, lo, hi)] as byte[]
        try {
            connection.write(cmd, data)
        } catch (IOException e) {
            if (!retry) throw e
            connection.write(cmd, data)
        }
    }

    /**
     * Sleep for a number of milliseconds.
     *
     * @param ms duration in milliseconds
     */
    protected static void pause(long ms) {
        try {
            Thread.sleep(ms)
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt()
        }
    }
}
