package it.uhde.periph.chips.rtc

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * DS3231 — extremely accurate I²C-integrated RTC/TCXO/crystal (Analog Devices /
 * Maxim Integrated) — minimal interface.
 *
 * Reads and sets the calendar clock, plus the free on-chip temperature
 * reading, with no configuration beyond the connection. Fixed I²C address
 * 0x68.
 *
 * This driver always operates the chip's `HOURS` registers in 24-hour mode;
 * the native 12-hour/AM-PM encoding is never written or exposed. The `DAY`
 * (day-of-week) register is a free-running 1–7 counter with no
 * hardware-enforced meaning — this driver defines 1=Monday…7=Sunday (ISO 8601).
 */
open class DS3231Minimal(protected val connection: Connection) {

    companion object {
        /** Fixed I²C address — the DS3231 has no address pins. */
        const val DEFAULT_ADDRESS = 0x68

        // Register map.
        const val REG_SECONDS = 0x00
        const val REG_MINUTES = 0x01
        const val REG_HOURS = 0x02
        const val REG_DAY = 0x03
        const val REG_DATE = 0x04
        const val REG_MONTH_CENTURY = 0x05
        const val REG_YEAR = 0x06
        const val REG_ALARM1_SECONDS = 0x07
        const val REG_ALARM1_MINUTES = 0x08
        const val REG_ALARM1_HOURS = 0x09
        const val REG_ALARM1_DAY_DATE = 0x0A
        const val REG_ALARM2_MINUTES = 0x0B
        const val REG_ALARM2_HOURS = 0x0C
        const val REG_ALARM2_DAY_DATE = 0x0D
        const val REG_CONTROL = 0x0E
        const val REG_STATUS = 0x0F
        const val REG_AGING_OFFSET = 0x10
        const val REG_TEMP_MSB = 0x11
        const val REG_TEMP_LSB = 0x12

        // CONTROL (0x0E) bits.
        const val CONTROL_EOSC = 0x80
        const val CONTROL_BBSQW = 0x40
        const val CONTROL_CONV = 0x20
        const val CONTROL_RS2 = 0x10
        const val CONTROL_RS1 = 0x08
        const val CONTROL_INTCN = 0x04
        const val CONTROL_A2IE = 0x02
        const val CONTROL_A1IE = 0x01

        // CONTROL_STATUS (0x0F) bits.
        const val STATUS_OSF = 0x80
        const val STATUS_EN32KHZ = 0x08
        const val STATUS_BSY = 0x04
        const val STATUS_A2F = 0x02
        const val STATUS_A1F = 0x01

        internal fun bcdToInt(b: Int): Int = ((b shr 4) and 0x0F) * 10 + (b and 0x0F)
        internal fun intToBcd(v: Int): Int = ((v / 10) shl 4) or (v % 10)
    }

    init {
        readReg(REG_CONTROL)
    }

    protected fun readReg(reg: Int): Int = connection.writeRead(byteArrayOf(reg.toByte()), 1)[0].toInt() and 0xFF

    protected fun readBurst(reg: Int, n: Int): ByteArray = connection.writeRead(byteArrayOf(reg.toByte()), n)

    protected fun writeReg(reg: Int, value: Int) {
        connection.write(byteArrayOf(reg.toByte(), value.toByte()))
    }

    protected fun writeBurst(reg: Int, vararg values: Int) {
        val out = ByteArray(values.size + 1)
        out[0] = reg.toByte()
        for (i in values.indices) out[i + 1] = values[i].toByte()
        connection.write(out)
    }

    /**
     * Calendar clock reading.
     *
     * @property year absolute year (2000–2099)
     * @property month 1–12
     * @property day day of month, 1–31
     * @property weekday day of week, 1 (Monday) – 7 (Sunday), ISO 8601
     * @property hour 0–23
     * @property minute 0–59
     * @property second 0–59
     */
    data class DateTime(
        val year: Int, val month: Int, val day: Int, val weekday: Int,
        val hour: Int, val minute: Int, val second: Int
    )

    /**
     * Read the current calendar clock.
     *
     * @return the current date and time
     */
    fun getDatetime(): DateTime {
        val raw = readBurst(REG_SECONDS, 7)
        val second = bcdToInt(raw[0].toInt() and 0x7F)
        val minute = bcdToInt(raw[1].toInt() and 0x7F)
        val hour = bcdToInt(raw[2].toInt() and 0x3F)
        val weekday = raw[3].toInt() and 0x07
        val day = bcdToInt(raw[4].toInt() and 0x3F)
        val month = bcdToInt(raw[5].toInt() and 0x1F)
        val year = 2000 + bcdToInt(raw[6].toInt() and 0xFF)
        return DateTime(year, month, day, weekday, hour, minute, second)
    }

    /**
     * Set the calendar clock. Writes all seven clock/calendar registers,
     * forces 24-hour mode, and clears the Oscillator Stop Flag (the time is
     * now known-good).
     *
     * @param year absolute year (2000–2099)
     * @param month 1–12
     * @param day day of month, 1–31
     * @param weekday day of week, 1 (Monday) – 7 (Sunday), ISO 8601
     * @param hour 0–23
     * @param minute 0–59
     * @param second 0–59
     */
    fun setDatetime(year: Int, month: Int, day: Int, weekday: Int, hour: Int, minute: Int, second: Int) {
        writeBurst(
            REG_SECONDS,
            intToBcd(second),
            intToBcd(minute),
            intToBcd(hour), // bit 6 = 0 -> 24-hour mode
            weekday,
            intToBcd(day),
            intToBcd(month), // bit 7 (century) left 0
            intToBcd(year - 2000)
        )
        val status = readReg(REG_STATUS)
        writeReg(REG_STATUS, status and STATUS_OSF.inv())
    }

    /**
     * Read the on-chip digital temperature sensor's last completed
     * conversion. No wait is performed — the chip converts autonomously
     * every 64 s and on power-up, so the value may be up to 64 s stale.
     *
     * @return temperature in degrees Celsius
     */
    fun readTemperature(): Double {
        val raw = readBurst(REG_TEMP_MSB, 2)
        val msbSigned = raw[0].toInt() // two's-complement byte
        val lsb = raw[1].toInt() and 0xFF
        val frac = ((lsb shr 6) and 0x03) * 0.25
        return msbSigned + frac
    }
}
