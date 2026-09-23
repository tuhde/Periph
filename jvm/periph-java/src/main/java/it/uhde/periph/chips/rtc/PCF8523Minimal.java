package it.uhde.periph.chips.rtc;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * PCF8523 — low-power I²C real-time clock and calendar (NXP) — minimal
 * interface.
 *
 * <p>Reads and sets the battery-backed calendar clock with no configuration
 * beyond the connection. The constructor enables battery switch-over in
 * standard mode with battery-low detection ({@code PM[2:0]}=000) — a
 * deliberate override of the chip's single-supply power-on default. Fixed
 * I²C address 0x68.
 *
 * <p>This driver always operates the {@code HOURS} registers in 24-hour
 * mode. {@code weekday} follows the datasheet's suggested assignment,
 * 0=Sunday…6=Saturday — the {@code WEEKDAYS} register has no
 * hardware-enforced meaning.
 */
public class PCF8523Minimal {

    /** Fixed I²C address — the PCF8523 has no address pins. */
    public static final int DEFAULT_ADDRESS = 0x68;

    // Register map.
    protected static final int REG_CONTROL_1       = 0x00;
    protected static final int REG_CONTROL_2       = 0x01;
    protected static final int REG_CONTROL_3       = 0x02;
    protected static final int REG_SECONDS         = 0x03;
    protected static final int REG_MINUTE_ALARM    = 0x0A;
    protected static final int REG_OFFSET          = 0x0E;
    protected static final int REG_TMR_CLKOUT_CTRL = 0x0F;
    protected static final int REG_TMR_A_FREQ_CTRL = 0x10;
    protected static final int REG_TMR_A_REG       = 0x11;
    protected static final int REG_TMR_B_FREQ_CTRL = 0x12;
    protected static final int REG_TMR_B_REG       = 0x13;

    // CONTROL_1 (0x00) bits.
    protected static final int C1_T     = 0x40;
    protected static final int C1_STOP  = 0x20;
    protected static final int C1_SR    = 0x10;
    protected static final int C1_12_24 = 0x08;
    protected static final int C1_SIE   = 0x04;
    protected static final int C1_AIE   = 0x02;

    protected final Connection connection;

    /**
     * Construct the driver. Confirms the device answers by reading
     * {@code CONTROL_1} (the PCF8523 has no identity register), then writes
     * {@code CONTROL_3}=0x00 (battery switch-over standard mode, battery-low
     * detection enabled).
     *
     * @param connection configured I²C connection bound to address 0x68
     * @throws IOException on bus error
     */
    public PCF8523Minimal(Connection connection) throws IOException {
        this.connection = connection;
        readReg(REG_CONTROL_1);
        writeReg(REG_CONTROL_3, 0x00);
    }

    // -------------------------------------------------------------------------
    // Register helpers
    // -------------------------------------------------------------------------

    protected int readReg(int reg) throws IOException {
        return connection.writeRead(new byte[]{(byte) reg}, 1)[0] & 0xFF;
    }

    protected byte[] readBurst(int reg, int n) throws IOException {
        return connection.writeRead(new byte[]{(byte) reg}, n);
    }

    protected void writeReg(int reg, int value) throws IOException {
        connection.write(new byte[]{(byte) reg, (byte) value});
    }

    protected void writeBurst(int reg, int... values) throws IOException {
        byte[] out = new byte[values.length + 1];
        out[0] = (byte) reg;
        for (int i = 0; i < values.length; i++) out[i + 1] = (byte) values[i];
        connection.write(out);
    }

    /** {@code CONTROL_1} with {@code T}/{@code SR} masked, safe for read-modify-write. */
    protected int readControl1() throws IOException {
        return readReg(REG_CONTROL_1) & ~(C1_T | C1_SR) & 0xFF;
    }

    protected static int bcdToInt(int b) {
        return ((b >> 4) & 0x0F) * 10 + (b & 0x0F);
    }

    protected static int intToBcd(int v) {
        return ((v / 10) << 4) | (v % 10);
    }

    // -------------------------------------------------------------------------
    // Date/time
    // -------------------------------------------------------------------------

    /**
     * Calendar clock reading.
     *
     * @param year    absolute year (2000–2099)
     * @param month   1–12
     * @param day     day of month, 1–31
     * @param weekday day of week, 0 (Sunday) – 6 (Saturday)
     * @param hour    0–23
     * @param minute  0–59
     * @param second  0–59
     */
    public record DateTime(int year, int month, int day, int weekday, int hour, int minute, int second) {}

    /**
     * Read the current calendar clock.
     *
     * @return the current date and time
     * @throws IOException on bus error
     */
    public DateTime getDatetime() throws IOException {
        byte[] raw = readBurst(REG_SECONDS, 7);
        int second = bcdToInt(raw[0] & 0x7F);
        int minute = bcdToInt(raw[1] & 0x7F);
        int hour = bcdToInt(raw[2] & 0x3F);
        int day = bcdToInt(raw[3] & 0x3F);
        int weekday = raw[4] & 0x07;
        int month = bcdToInt(raw[5] & 0x1F);
        int year = 2000 + bcdToInt(raw[6] & 0xFF);
        return new DateTime(year, month, day, weekday, hour, minute, second);
    }

    /**
     * Set the calendar clock using the {@code STOP}-bit precision start:
     * freeze the divider chain ({@code STOP}=1), write all seven time/date
     * registers in one transaction, then release {@code STOP}. Forces 24-hour
     * mode and clears the {@code OS} flag (the time is now known-good).
     *
     * @param year    absolute year (2000–2099)
     * @param month   1–12
     * @param day     day of month, 1–31
     * @param weekday day of week, 0 (Sunday) – 6 (Saturday)
     * @param hour    0–23
     * @param minute  0–59
     * @param second  0–59
     * @throws IOException on bus error
     */
    public void setDatetime(int year, int month, int day, int weekday, int hour, int minute, int second) throws IOException {
        int ctrl1 = readControl1() & ~C1_12_24;
        writeReg(REG_CONTROL_1, ctrl1 | C1_STOP);
        writeBurst(REG_SECONDS,
                intToBcd(second) & 0x7F,   // OS = 0
                intToBcd(minute),
                intToBcd(hour) & 0x3F,
                intToBcd(day),
                weekday & 0x07,
                intToBcd(month),
                intToBcd(year - 2000));
        writeReg(REG_CONTROL_1, ctrl1 & ~C1_STOP);
    }
}
