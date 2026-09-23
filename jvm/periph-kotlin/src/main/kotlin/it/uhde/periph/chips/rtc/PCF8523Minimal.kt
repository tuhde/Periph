package it.uhde.periph.chips.rtc

import it.uhde.periph.connection.Connection

/**
 * PCF8523 — low-power I²C real-time clock and calendar (NXP) — minimal
 * interface.
 *
 * Reads and sets the battery-backed calendar clock with no configuration
 * beyond the connection. Construction enables battery switch-over in
 * standard mode with battery-low detection (`PM[2:0]`=000) — a deliberate
 * override of the chip's single-supply power-on default. Fixed I²C address
 * 0x68.
 *
 * This driver always operates the `HOURS` registers in 24-hour mode.
 * `weekday` follows the datasheet's suggested assignment, 0=Sunday…6=Saturday
 * — the `WEEKDAYS` register has no hardware-enforced meaning.
 *
 * @param connection configured I²C connection bound to address 0x68
 */
open class PCF8523Minimal(protected val connection: Connection) {

    companion object {
        /** Fixed I²C address — the PCF8523 has no address pins. */
        const val DEFAULT_ADDRESS = 0x68

        // Register map.
        const val REG_CONTROL_1 = 0x00
        const val REG_CONTROL_2 = 0x01
        const val REG_CONTROL_3 = 0x02
        const val REG_SECONDS = 0x03
        const val REG_MINUTE_ALARM = 0x0A
        const val REG_OFFSET = 0x0E
        const val REG_TMR_CLKOUT_CTRL = 0x0F
        const val REG_TMR_A_FREQ_CTRL = 0x10
        const val REG_TMR_A_REG = 0x11
        const val REG_TMR_B_FREQ_CTRL = 0x12
        const val REG_TMR_B_REG = 0x13

        // CONTROL_1 (0x00) bits.
        const val C1_T = 0x40
        const val C1_STOP = 0x20
        const val C1_SR = 0x10
        const val C1_12_24 = 0x08
        const val C1_SIE = 0x04
        const val C1_AIE = 0x02

        internal fun bcdToInt(b: Int): Int = ((b shr 4) and 0x0F) * 10 + (b and 0x0F)
        internal fun intToBcd(v: Int): Int = ((v / 10) shl 4) or (v % 10)
    }

    init {
        readReg(REG_CONTROL_1)          // presence check (no identity register)
        writeReg(REG_CONTROL_3, 0x00)   // PM=000: standard switch-over, low detection on
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

    /** `CONTROL_1` with `T`/`SR` masked, safe for read-modify-write. */
    protected fun readControl1(): Int = readReg(REG_CONTROL_1) and (C1_T or C1_SR).inv() and 0xFF

    /**
     * Calendar clock reading.
     *
     * @property year absolute year (2000–2099)
     * @property month 1–12
     * @property day day of month, 1–31
     * @property weekday day of week, 0 (Sunday) – 6 (Saturday)
     * @property hour 0–23
     * @property minute 0–59
     * @property second 0–59
     */
    data class DateTime(
        val year: Int, val month: Int, val day: Int, val weekday: Int,
        val hour: Int, val minute: Int, val second: Int,
    )

    /**
     * Read the current calendar clock.
     *
     * @return the current date and time
     */
    fun getDatetime(): DateTime {
        val raw = readBurst(REG_SECONDS, 7)
        return DateTime(
            year = 2000 + bcdToInt(raw[6].toInt() and 0xFF),
            month = bcdToInt(raw[5].toInt() and 0x1F),
            day = bcdToInt(raw[3].toInt() and 0x3F),
            weekday = raw[4].toInt() and 0x07,
            hour = bcdToInt(raw[2].toInt() and 0x3F),
            minute = bcdToInt(raw[1].toInt() and 0x7F),
            second = bcdToInt(raw[0].toInt() and 0x7F),
        )
    }

    /**
     * Set the calendar clock using the `STOP`-bit precision start: freeze the
     * divider chain (`STOP`=1), write all seven time/date registers in one
     * transaction, then release `STOP`. Forces 24-hour mode and clears the
     * `OS` flag (the time is now known-good).
     *
     * @param year absolute year (2000–2099)
     * @param month 1–12
     * @param day day of month, 1–31
     * @param weekday day of week, 0 (Sunday) – 6 (Saturday)
     * @param hour 0–23
     * @param minute 0–59
     * @param second 0–59
     */
    fun setDatetime(year: Int, month: Int, day: Int, weekday: Int, hour: Int, minute: Int, second: Int) {
        val ctrl1 = readControl1() and C1_12_24.inv()
        writeReg(REG_CONTROL_1, ctrl1 or C1_STOP)
        writeBurst(
            REG_SECONDS,
            intToBcd(second) and 0x7F,   // OS = 0
            intToBcd(minute),
            intToBcd(hour) and 0x3F,
            intToBcd(day),
            weekday and 0x07,
            intToBcd(month),
            intToBcd(year - 2000),
        )
        writeReg(REG_CONTROL_1, ctrl1 and C1_STOP.inv())
    }
}
