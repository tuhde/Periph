package it.uhde.periph.chips.rtc

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin

/**
 * DS3231 full driver — extends [DS3231Minimal] with alarms, the
 * square-wave/32kHz outputs, oscillator control, forced temperature
 * conversion, aging-offset trim, and Level-2 selectable-source interrupts.
 *
 * [onInterrupt] subscribes to alarm matches delivered on `INT/SQW`; delivery
 * uses `connection.intPin()` (or an explicit override) if wired, otherwise
 * falls back to a 5 ms polling thread automatically. [pollInterrupt] reads
 * and clears the alarm flags. [enableInterrupt]/[disableInterrupt] select
 * which alarm(s) assert the pin.
 */
class DS3231Full(connection: Connection) : DS3231Minimal(connection) {

    companion object {
        /** Interrupt source: Alarm 1 matched. */
        const val SOURCE_ALARM1 = 0x01
        /** Interrupt source: Alarm 2 matched. */
        const val SOURCE_ALARM2 = 0x02

        // Alarm 1 match modes (mask/DY-DT combinations — Table 2 of the datasheet).
        const val ALARM1_MATCH_EVERY_SECOND = 0
        const val ALARM1_MATCH_SECONDS = 1
        const val ALARM1_MATCH_MINUTES_SECONDS = 2
        const val ALARM1_MATCH_HOURS_MINUTES_SECONDS = 3
        const val ALARM1_MATCH_DATE_HOURS_MINUTES_SECONDS = 4
        const val ALARM1_MATCH_DAY_HOURS_MINUTES_SECONDS = 5

        // Alarm 2 match modes.
        const val ALARM2_MATCH_EVERY_MINUTE = 0
        const val ALARM2_MATCH_MINUTES = 1
        const val ALARM2_MATCH_HOURS_MINUTES = 2
        const val ALARM2_MATCH_DATE_HOURS_MINUTES = 3
        const val ALARM2_MATCH_DAY_HOURS_MINUTES = 4

        // Square-wave output rates (CONTROL RS2:RS1).
        const val SQUARE_WAVE_1_HZ = 1
        const val SQUARE_WAVE_1024_HZ = 1024
        const val SQUARE_WAVE_4096_HZ = 4096
        const val SQUARE_WAVE_8192_HZ = 8192

        private const val MAX_TEMP_CONVERSION_WAIT_MS = 250L
    }

    /** Callback invoked with the alarm-flag bitmask on any alarm match. */
    @Volatile private var callback: ((Int) -> Unit)? = null

    /** INT-line pin currently armed, or `null` if polling. */
    private var intPin: InputPin? = null

    /** `true` while the polling-fallback thread is running. */
    @Volatile private var polling: Boolean = false

    /** Background daemon thread used only when no [InputPin] is available. */
    private var pollThread: Thread? = null

    /**
     * Fixed edge handler instance, reused across onEdge()/offEdge() calls — a
     * fresh lambda per call would not be guaranteed to evaluate to the same
     * object each time, so offEdge() could silently fail to remove it.
     */
    private val edgeHandler = EdgeHandler { handleEdge() }

    // -------------------------------------------------------------------------
    // Alarms
    // -------------------------------------------------------------------------

    /**
     * Alarm 1 configuration.
     *
     * @property second 0–59 (ignored unless [matchMode] is one of the "…seconds" modes)
     * @property minute 0–59
     * @property hour 0–23
     * @property dayOrDate day-of-week (1–7) or day-of-month (1–31), per [isDayOfWeek]
     * @property isDayOfWeek `true` = [dayOrDate] is a day-of-week match
     * @property matchMode one of the `ALARM1_MATCH_*` constants
     */
    data class Alarm1(val second: Int, val minute: Int, val hour: Int, val dayOrDate: Int, val isDayOfWeek: Boolean, val matchMode: Int)

    /** Alarm 2 configuration — same shape as [Alarm1] without seconds. */
    data class Alarm2(val minute: Int, val hour: Int, val dayOrDate: Int, val isDayOfWeek: Boolean, val matchMode: Int)

    /** Read Alarm 1's current configuration, decoded from registers 0x07–0x0A. */
    fun getAlarm1(): Alarm1 {
        val raw = readBurst(REG_ALARM1_SECONDS, 4)
        val a1m1 = if (raw[0].toInt() and 0x80 != 0) 1 else 0
        val a1m2 = if (raw[1].toInt() and 0x80 != 0) 1 else 0
        val a1m3 = if (raw[2].toInt() and 0x80 != 0) 1 else 0
        val a1m4 = if (raw[3].toInt() and 0x80 != 0) 1 else 0
        val isDayOfWeek = raw[3].toInt() and 0x40 != 0
        val dayOrDate = bcdToInt(raw[3].toInt() and 0x3F)
        val matchMode = decodeAlarm1MatchMode(a1m4, a1m3, a1m2, a1m1, isDayOfWeek)
        return Alarm1(
            bcdToInt(raw[0].toInt() and 0x7F),
            bcdToInt(raw[1].toInt() and 0x7F),
            bcdToInt(raw[2].toInt() and 0x3F),
            dayOrDate, isDayOfWeek, matchMode
        )
    }

    /**
     * Configure and store Alarm 1. Does not enable it — call [enableInterrupt]
     * with [SOURCE_ALARM1] to arm it.
     */
    fun setAlarm1(second: Int, minute: Int, hour: Int, dayOrDate: Int, isDayOfWeek: Boolean, matchMode: Int) {
        val masks = when (matchMode) {
            ALARM1_MATCH_EVERY_SECOND -> intArrayOf(1, 1, 1, 1)
            ALARM1_MATCH_SECONDS -> intArrayOf(0, 1, 1, 1)
            ALARM1_MATCH_MINUTES_SECONDS -> intArrayOf(0, 0, 1, 1)
            ALARM1_MATCH_HOURS_MINUTES_SECONDS -> intArrayOf(0, 0, 0, 1)
            ALARM1_MATCH_DATE_HOURS_MINUTES_SECONDS -> intArrayOf(0, 0, 0, 0)
            ALARM1_MATCH_DAY_HOURS_MINUTES_SECONDS -> intArrayOf(0, 0, 0, 0)
            else -> return
        }
        val (m1, m2, m3, m4) = masks
        val dayDateByte = (m4 shl 7) or ((if (isDayOfWeek) 1 else 0) shl 6) or (intToBcd(dayOrDate) and 0x3F)
        writeBurst(
            REG_ALARM1_SECONDS,
            (m1 shl 7) or (intToBcd(second) and 0x7F),
            (m2 shl 7) or (intToBcd(minute) and 0x7F),
            (m3 shl 7) or (intToBcd(hour) and 0x3F),
            dayDateByte
        )
    }

    private fun decodeAlarm1MatchMode(a1m4: Int, a1m3: Int, a1m2: Int, a1m1: Int, isDayOfWeek: Boolean): Int {
        if (a1m4 == 1 && a1m3 == 1 && a1m2 == 1 && a1m1 == 1) return ALARM1_MATCH_EVERY_SECOND
        if (a1m4 == 1 && a1m3 == 1 && a1m2 == 1) return ALARM1_MATCH_SECONDS
        if (a1m4 == 1 && a1m3 == 1) return ALARM1_MATCH_MINUTES_SECONDS
        if (a1m4 == 1) return ALARM1_MATCH_HOURS_MINUTES_SECONDS
        return if (isDayOfWeek) ALARM1_MATCH_DAY_HOURS_MINUTES_SECONDS else ALARM1_MATCH_DATE_HOURS_MINUTES_SECONDS
    }

    /** Read Alarm 2's current configuration, decoded from registers 0x0B–0x0D. */
    fun getAlarm2(): Alarm2 {
        val raw = readBurst(REG_ALARM2_MINUTES, 3)
        val a2m2 = if (raw[0].toInt() and 0x80 != 0) 1 else 0
        val a2m3 = if (raw[1].toInt() and 0x80 != 0) 1 else 0
        val a2m4 = if (raw[2].toInt() and 0x80 != 0) 1 else 0
        val isDayOfWeek = raw[2].toInt() and 0x40 != 0
        val dayOrDate = bcdToInt(raw[2].toInt() and 0x3F)
        val matchMode = decodeAlarm2MatchMode(a2m4, a2m3, a2m2, isDayOfWeek)
        return Alarm2(bcdToInt(raw[0].toInt() and 0x7F), bcdToInt(raw[1].toInt() and 0x3F), dayOrDate, isDayOfWeek, matchMode)
    }

    /**
     * Configure and store Alarm 2. Does not enable it — call [enableInterrupt]
     * with [SOURCE_ALARM2] to arm it.
     */
    fun setAlarm2(minute: Int, hour: Int, dayOrDate: Int, isDayOfWeek: Boolean, matchMode: Int) {
        val (m2, m3, m4) = when (matchMode) {
            ALARM2_MATCH_EVERY_MINUTE -> Triple(1, 1, 1)
            ALARM2_MATCH_MINUTES -> Triple(0, 1, 1)
            ALARM2_MATCH_HOURS_MINUTES -> Triple(0, 0, 1)
            ALARM2_MATCH_DATE_HOURS_MINUTES -> Triple(0, 0, 0)
            ALARM2_MATCH_DAY_HOURS_MINUTES -> Triple(0, 0, 0)
            else -> return
        }
        val dayDateByte = (m4 shl 7) or ((if (isDayOfWeek) 1 else 0) shl 6) or (intToBcd(dayOrDate) and 0x3F)
        writeBurst(
            REG_ALARM2_MINUTES,
            (m2 shl 7) or (intToBcd(minute) and 0x7F),
            (m3 shl 7) or (intToBcd(hour) and 0x3F),
            dayDateByte
        )
    }

    private fun decodeAlarm2MatchMode(a2m4: Int, a2m3: Int, a2m2: Int, isDayOfWeek: Boolean): Int {
        if (a2m4 == 1 && a2m3 == 1 && a2m2 == 1) return ALARM2_MATCH_EVERY_MINUTE
        if (a2m4 == 1 && a2m3 == 1) return ALARM2_MATCH_MINUTES
        if (a2m4 == 1) return ALARM2_MATCH_HOURS_MINUTES
        return if (isDayOfWeek) ALARM2_MATCH_DAY_HOURS_MINUTES else ALARM2_MATCH_DATE_HOURS_MINUTES
    }

    // -------------------------------------------------------------------------
    // Square wave / 32kHz outputs
    // -------------------------------------------------------------------------

    /**
     * Enable the square-wave output on `INT/SQW`. Mutually exclusive with
     * alarm interrupts — they share the `INTCN` bit, so whichever of this
     * method or [enableInterrupt] is called last wins.
     */
    fun enableSquareWave(rateHz: Int, batteryBacked: Boolean) {
        val rs = when (rateHz) {
            SQUARE_WAVE_1_HZ -> 0x00
            SQUARE_WAVE_1024_HZ -> CONTROL_RS1
            SQUARE_WAVE_4096_HZ -> CONTROL_RS2
            SQUARE_WAVE_8192_HZ -> CONTROL_RS2 or CONTROL_RS1
            else -> return
        }
        var control = readReg(REG_CONTROL)
        control = control and (CONTROL_INTCN or CONTROL_RS2 or CONTROL_RS1 or CONTROL_BBSQW).inv()
        control = control or rs
        if (batteryBacked) control = control or CONTROL_BBSQW
        writeReg(REG_CONTROL, control)
    }

    /** Disable the square wave, returning `INT/SQW` to interrupt mode. */
    fun disableSquareWave() {
        val control = readReg(REG_CONTROL)
        writeReg(REG_CONTROL, control or CONTROL_INTCN)
    }

    /** `true` if the free-running 32kHz output pin is enabled. */
    fun is32kHzEnabled(): Boolean = readReg(REG_STATUS) and STATUS_EN32KHZ != 0

    fun enable32kHzOutput() {
        val status = readReg(REG_STATUS)
        writeReg(REG_STATUS, status or STATUS_EN32KHZ)
    }

    fun disable32kHzOutput() {
        val status = readReg(REG_STATUS)
        writeReg(REG_STATUS, status and STATUS_EN32KHZ.inv())
    }

    // -------------------------------------------------------------------------
    // Oscillator / temperature / aging
    // -------------------------------------------------------------------------

    /** `true` if the Oscillator Stop Flag is set — timekeeping data may be invalid since the last check. */
    fun oscillatorStopped(): Boolean = readReg(REG_STATUS) and STATUS_OSF != 0

    /** Write 0 to the Oscillator Stop Flag only, preserving the 32kHz-output enable bit. */
    fun clearOscillatorStopped() {
        val status = readReg(REG_STATUS)
        writeReg(REG_STATUS, status and STATUS_OSF.inv())
    }

    /** Clear `EOSC` — the oscillator keeps running on `VBAT` (the power-on default). */
    fun enableBatteryOscillator() {
        val control = readReg(REG_CONTROL)
        writeReg(REG_CONTROL, control and CONTROL_EOSC.inv())
    }

    /** Set `EOSC` — the oscillator stops when switched to `VBAT`, saving battery current. */
    fun disableBatteryOscillator() {
        val control = readReg(REG_CONTROL)
        writeReg(REG_CONTROL, control or CONTROL_EOSC)
    }

    /**
     * Force an immediate temperature conversion and wait for it to complete
     * (max 200 ms per the datasheet; polled with a 5 ms margin).
     *
     * @throws java.io.IOException if the conversion does not complete within the expected time
     */
    fun forceTemperatureConversion() {
        val control = readReg(REG_CONTROL)
        writeReg(REG_CONTROL, control or CONTROL_CONV)
        val deadline = System.currentTimeMillis() + MAX_TEMP_CONVERSION_WAIT_MS
        while (readReg(REG_STATUS) and STATUS_BSY != 0) {
            if (System.currentTimeMillis() >= deadline) {
                throw java.io.IOException("DS3231 temperature conversion timed out")
            }
            try {
                Thread.sleep(5)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.io.IOException(e)
            }
        }
    }

    /** The raw signed 8-bit oscillator trim code from `AGING_OFFSET`. */
    fun getAgingOffset(): Int = readReg(REG_AGING_OFFSET).toByte().toInt()

    /**
     * @param offset raw signed 8-bit oscillator trim code, −128 to 127
     * @throws IllegalArgumentException if [offset] is out of range
     */
    fun setAgingOffset(offset: Int) {
        require(offset in -128..127) { "aging offset out of range: $offset" }
        writeReg(REG_AGING_OFFSET, offset and 0xFF)
    }

    // -------------------------------------------------------------------------
    // Interrupt API (Level 2 — selectable sources)
    // -------------------------------------------------------------------------

    /**
     * Subscribe to alarm matches using `connection.intPin()` (or a 5 ms
     * polling thread if none is wired), unless [intPin] overrides which pin
     * delivers edges. Also sets `INTCN`=1 so `INT/SQW` carries alarm
     * interrupts instead of the square wave.
     *
     * @param callback called with the alarm-flag bitmask ([SOURCE_ALARM1]/[SOURCE_ALARM2]) on any match
     * @param intPin INT-line pin to arm, or `null` to force the 5 ms polling fallback
     */
    fun onInterrupt(callback: (Int) -> Unit, intPin: InputPin? = connection.intPin()) {
        this.callback = callback
        val control = readReg(REG_CONTROL)
        writeReg(REG_CONTROL, control or CONTROL_INTCN)
        if (intPin != null) {
            this.intPin = intPin
            intPin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            startPolling()
        }
    }

    /** Unsubscribe and stop delivery. */
    fun offInterrupt() {
        callback = null
        intPin?.offEdge(edgeHandler)
        intPin = null
        stopPolling()
    }

    private fun startPolling() {
        polling = true
        pollThread = Thread({
            while (polling) {
                handleEdge()
                try {
                    Thread.sleep(5)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }, "ds3231-poll").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun stopPolling() {
        polling = false
        pollThread?.interrupt()
        pollThread = null
    }

    private fun handleEdge() {
        try {
            val status = pollInterrupt()
            if (status == 0) return
            callback?.invoke(status)
        } catch (e: java.io.IOException) {
            // bus error; wait for the next edge/tick rather than propagating
        }
    }

    /**
     * Read `CONTROL_STATUS` and clear `A1F`/`A2F` (leaving
     * `OSF`/`EN32kHz`/`BSY` untouched).
     *
     * @return the pre-clear byte; mask with [SOURCE_ALARM1]/[SOURCE_ALARM2] to test each source
     */
    fun pollInterrupt(): Int {
        val status = readReg(REG_STATUS)
        writeReg(REG_STATUS, status and (STATUS_A1F or STATUS_A2F).inv())
        return status and (SOURCE_ALARM1 or SOURCE_ALARM2)
    }

    /**
     * Enable one alarm's interrupt output and route `INT/SQW` to interrupt
     * mode (`INTCN`=1).
     *
     * @param source [SOURCE_ALARM1] or [SOURCE_ALARM2]
     */
    fun enableInterrupt(source: Int) {
        var control = readReg(REG_CONTROL)
        control = control or CONTROL_INTCN
        if (source == SOURCE_ALARM1) control = control or CONTROL_A1IE
        else if (source == SOURCE_ALARM2) control = control or CONTROL_A2IE
        writeReg(REG_CONTROL, control)
    }

    /**
     * Disable one alarm's interrupt output.
     *
     * @param source [SOURCE_ALARM1] or [SOURCE_ALARM2]
     */
    fun disableInterrupt(source: Int) {
        var control = readReg(REG_CONTROL)
        if (source == SOURCE_ALARM1) control = control and CONTROL_A1IE.inv()
        else if (source == SOURCE_ALARM2) control = control and CONTROL_A2IE.inv()
        writeReg(REG_CONTROL, control)
    }
}
