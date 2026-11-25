package it.uhde.periph.chips.rtc

import it.uhde.periph.connection.RegisterConnection

/**
 * PCF85063A — low-power I²C real-time clock and calendar (NXP) — minimal
 * interface.
 *
 * Reads and sets the calendar clock with no configuration beyond the
 * connection. Construction forces 24-hour mode with the clock running
 * (`STOP`=0); it writes no time register and does not reset the chip. Fixed
 * I²C address 0x51. There is no battery switch-over — the chip is fed from a
 * single `VDD` supply.
 *
 * This driver always operates the `Hours` register in 24-hour mode.
 * `weekday` follows the datasheet's suggested assignment, 0=Sunday…6=Saturday
 * — the `Weekdays` register has no hardware-enforced meaning.
 *
 * @param connection configured I²C connection bound to address 0x51
 */
open class PCF85063AMinimal(protected val connection: RegisterConnection) {

    companion object {
        /** Fixed I²C address — the PCF85063A has no address pins. */
        const val DEFAULT_ADDRESS = 0x51

        // Register map.
        const val REG_CONTROL_1 = 0x00
        const val REG_CONTROL_2 = 0x01
        const val REG_OFFSET = 0x02
        const val REG_RAM = 0x03
        const val REG_SECONDS = 0x04
        const val REG_SECOND_ALARM = 0x0B
        const val REG_TIMER_VALUE = 0x10
        const val REG_TIMER_MODE = 0x11

        // Control_1 (0x00) bits.
        const val C1_STOP = 0x20
        const val C1_SR = 0x10
        const val C1_CIE = 0x04
        const val C1_12_24 = 0x02
        const val C1_CAP_SEL = 0x01

        internal fun bcdToInt(b: Int): Int = ((b shr 4) and 0x0F) * 10 + (b and 0x0F)
        internal fun intToBcd(v: Int): Int = ((v / 10) shl 4) or (v % 10)
    }

    init {
        readReg(REG_CONTROL_1)          // presence check (no identity register)
        writeReg(REG_CONTROL_1, readControl1() and (C1_STOP or C1_12_24).inv())
    }

    protected fun readReg(reg: Int): Int = connection.read(reg, 1)[0].toInt() and 0xFF

    protected fun readBurst(reg: Int, n: Int): ByteArray = connection.read(reg, n)

    protected fun writeReg(reg: Int, value: Int) {
        connection.write(reg, byteArrayOf(value.toByte()))
    }

    protected fun writeBurst(reg: Int, vararg values: Int) {
        val out = ByteArray(values.size)
        for (i in values.indices) out[i] = values[i].toByte()
        connection.write(reg, out)
    }

    /** `Control_1` with `SR` masked, safe for read-modify-write. */
    protected fun readControl1(): Int = readReg(REG_CONTROL_1) and C1_SR.inv() and 0xFF

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
     * Read the current calendar clock (one 7-byte burst).
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
     * @param year absolute year (2000–2099; otherwise [IllegalArgumentException])
     * @param month 1–12
     * @param day day of month, 1–31
     * @param weekday day of week, 0 (Sunday) – 6 (Saturday)
     * @param hour 0–23
     * @param minute 0–59
     * @param second 0–59
     */
    fun setDatetime(year: Int, month: Int, day: Int, weekday: Int, hour: Int, minute: Int, second: Int) {
        require(year in 2000..2099) { "year must be 2000-2099" }
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
