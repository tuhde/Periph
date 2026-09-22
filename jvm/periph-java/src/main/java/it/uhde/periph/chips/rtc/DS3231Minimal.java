package it.uhde.periph.chips.rtc;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * DS3231 — extremely accurate I²C-integrated RTC/TCXO/crystal (Analog Devices /
 * Maxim Integrated) — minimal interface.
 *
 * <p>Reads and sets the calendar clock, plus the free on-chip temperature
 * reading, with no configuration beyond the connection. Fixed I²C address
 * 0x68.
 *
 * <p>This driver always operates the chip's {@code HOURS} registers in
 * 24-hour mode; the native 12-hour/AM-PM encoding is never written or
 * exposed. The {@code DAY} (day-of-week) register is a free-running 1–7
 * counter with no hardware-enforced meaning — this driver defines
 * 1=Monday…7=Sunday (ISO 8601).
 */
public class DS3231Minimal {

    /** Fixed I²C address — the DS3231 has no address pins. */
    public static final int DEFAULT_ADDRESS = 0x68;

    // Register map.
    protected static final int REG_SECONDS         = 0x00;
    protected static final int REG_MINUTES          = 0x01;
    protected static final int REG_HOURS             = 0x02;
    protected static final int REG_DAY                = 0x03;
    protected static final int REG_DATE               = 0x04;
    protected static final int REG_MONTH_CENTURY  = 0x05;
    protected static final int REG_YEAR                = 0x06;
    protected static final int REG_ALARM1_SECONDS  = 0x07;
    protected static final int REG_ALARM1_MINUTES  = 0x08;
    protected static final int REG_ALARM1_HOURS     = 0x09;
    protected static final int REG_ALARM1_DAY_DATE = 0x0A;
    protected static final int REG_ALARM2_MINUTES  = 0x0B;
    protected static final int REG_ALARM2_HOURS     = 0x0C;
    protected static final int REG_ALARM2_DAY_DATE = 0x0D;
    protected static final int REG_CONTROL             = 0x0E;
    protected static final int REG_STATUS               = 0x0F;
    protected static final int REG_AGING_OFFSET     = 0x10;
    protected static final int REG_TEMP_MSB           = 0x11;
    protected static final int REG_TEMP_LSB            = 0x12;

    // CONTROL (0x0E) bits.
    protected static final int CONTROL_EOSC   = 0x80;
    protected static final int CONTROL_BBSQW = 0x40;
    protected static final int CONTROL_CONV   = 0x20;
    protected static final int CONTROL_RS2      = 0x10;
    protected static final int CONTROL_RS1      = 0x08;
    protected static final int CONTROL_INTCN  = 0x04;
    protected static final int CONTROL_A2IE    = 0x02;
    protected static final int CONTROL_A1IE    = 0x01;

    // CONTROL_STATUS (0x0F) bits.
    protected static final int STATUS_OSF       = 0x80;
    protected static final int STATUS_EN32KHZ = 0x08;
    protected static final int STATUS_BSY       = 0x04;
    protected static final int STATUS_A2F       = 0x02;
    protected static final int STATUS_A1F       = 0x01;

    protected final Connection connection;

    /**
     * Construct the driver. Confirms the device answers on the bus by reading
     * the {@code CONTROL} register (the DS3231 has no WHO_AM_I register); makes
     * no register writes.
     *
     * @param connection configured I²C connection bound to address 0x68
     * @throws IOException on bus error
     */
    public DS3231Minimal(Connection connection) throws IOException {
        this.connection = connection;
        readReg(REG_CONTROL);
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
     * @param weekday day of week, 1 (Monday) – 7 (Sunday), ISO 8601
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
        int weekday = raw[3] & 0x07;
        int day = bcdToInt(raw[4] & 0x3F);
        int month = bcdToInt(raw[5] & 0x1F);
        int year = 2000 + bcdToInt(raw[6] & 0xFF);
        return new DateTime(year, month, day, weekday, hour, minute, second);
    }

    /**
     * Set the calendar clock. Writes all seven clock/calendar registers,
     * forces 24-hour mode, and clears the Oscillator Stop Flag (the time is
     * now known-good).
     *
     * @param year    absolute year (2000–2099)
     * @param month   1–12
     * @param day     day of month, 1–31
     * @param weekday day of week, 1 (Monday) – 7 (Sunday), ISO 8601
     * @param hour    0–23
     * @param minute  0–59
     * @param second  0–59
     * @throws IOException on bus error
     */
    public void setDatetime(int year, int month, int day, int weekday, int hour, int minute, int second) throws IOException {
        writeBurst(REG_SECONDS,
                intToBcd(second),
                intToBcd(minute),
                intToBcd(hour),          // bit 6 = 0 -> 24-hour mode
                weekday,
                intToBcd(day),
                intToBcd(month),          // bit 7 (century) left 0
                intToBcd(year - 2000));
        int status = readReg(REG_STATUS);
        writeReg(REG_STATUS, status & ~STATUS_OSF);
    }

    /**
     * Read the on-chip digital temperature sensor's last completed
     * conversion. No wait is performed — the chip converts autonomously
     * every 64 s and on power-up, so the value may be up to 64 s stale.
     *
     * @return temperature in degrees Celsius
     * @throws IOException on bus error
     */
    public double readTemperature() throws IOException {
        byte[] raw = readBurst(REG_TEMP_MSB, 2);
        int msbSigned = raw[0]; // two's-complement byte
        int lsb = raw[1] & 0xFF;
        double frac = ((lsb >> 6) & 0x03) * 0.25;
        return msbSigned + frac;
    }
}
