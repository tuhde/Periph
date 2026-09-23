package it.uhde.periph.chips.rtc

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import java.io.IOException
import kotlin.math.abs

/**
 * PCF8523 full driver — extends [PCF8523Minimal] with the alarm, Timer A
 * (countdown or watchdog), Timer B, programmable `CLKOUT`, offset
 * calibration, battery backup control/status, oscillator-stop detection,
 * software reset, and the Level-3 interrupt API.
 *
 * `INT1` is shared with `CLKOUT`: interrupts only reach `INT1` once
 * `CLKOUT` is disabled ([disableClockOutput]); the driver never does that
 * implicitly. Timer B additionally drives the dedicated `INT2` pin.
 * [onInterrupt] uses `connection.intPin()` if wired, otherwise a 5 ms
 * polling thread.
 *
 * @param connection configured I²C connection bound to address 0x68
 */
open class PCF8523Full(connection: Connection) : PCF8523Minimal(connection) {

    companion object {
        /** Interrupt source: second tick (`SF`). */
        const val SOURCE_SECOND = 0x01
        /** Interrupt source: Timer A timed out (`CTAF` or `WTAF`). */
        const val SOURCE_TIMER_A = 0x02
        /** Interrupt source: Timer B timed out (`CTBF`). */
        const val SOURCE_TIMER_B = 0x04
        /** Interrupt source: all enabled alarm fields matched (`AF`). */
        const val SOURCE_ALARM = 0x08
        /** Interrupt source: battery switch-over occurred (`BSF`). */
        const val SOURCE_BATTERY_SWITCH = 0x10
        /** Interrupt source: battery low (`BLF`). */
        const val SOURCE_BATTERY_LOW = 0x20

        // CONTROL_2 (0x01) bits.
        private const val C2_WTAF = 0x80
        private const val C2_CTAF = 0x40
        private const val C2_CTBF = 0x20
        private const val C2_SF = 0x10
        private const val C2_AF = 0x08
        private const val C2_WTAIE = 0x04
        private const val C2_CTAIE = 0x02
        private const val C2_CTBIE = 0x01
        private const val C2_CLEARABLE = 0x78 // CTAF|CTBF|SF|AF: write 0 clears, 1 keeps
        private const val C2_ENABLES = 0x07

        // CONTROL_3 (0x02) bits.
        private const val C3_PM_MASK = 0xE0
        private const val C3_BSF = 0x08
        private const val C3_BLF = 0x04
        private const val C3_BSIE = 0x02
        private const val C3_BLIE = 0x01

        private const val SECONDS_OS = 0x80

        // TMR_CLKOUT_CTRL (0x0F) fields.
        private const val TMR_TAM = 0x80
        private const val TMR_TBM = 0x40
        private const val TMR_COF_MASK = 0x38
        private const val TMR_TAC_MASK = 0x06
        private const val TMR_TAC_COUNTDOWN = 0x02
        private const val TMR_TAC_WATCHDOG = 0x04
        private const val TMR_TBC = 0x01

        /** TBW[2:0] → low-pulse width in ms (datasheet Table 36; not uniformly spaced). */
        private val TBW_WIDTHS_MS = doubleArrayOf(46.875, 62.5, 78.125, 93.75, 125.0, 156.25, 187.5, 218.75)
    }

    /** Timer A operating mode. */
    enum class TimerAMode { COUNTDOWN, WATCHDOG }

    /** Timer source clock (`TAQ`/`TBQ` encoding). */
    enum class SourceClock(internal val bits: Int) {
        /** 4096 Hz — 244 µs … 62.256 ms. */
        HZ_4096(0x00),
        /** 64 Hz — 15.625 ms … 3.984 s. */
        HZ_64(0x01),
        /** 1 Hz — 1 s … 255 s. */
        HZ_1(0x02),
        /** 1/60 Hz — 1 min … 255 min. */
        HZ_1_60(0x03),
        /** 1/3600 Hz — 1 h … 255 h. */
        HZ_1_3600(0x07),
    }

    /** Offset correction interval. */
    enum class OffsetMode {
        /** Once every two hours, 4.34 ppm per LSB (default). */
        EVERY_TWO_HOURS,
        /** Once every minute, 4.069 ppm per LSB. */
        EVERY_MINUTE,
    }

    /** Battery switch-over mode. */
    enum class BatteryMode {
        /** Switch when VDD < VBAT and VDD < 2.5 V. */
        STANDARD,
        /** Switch whenever VDD < VBAT. */
        DIRECT,
        /** VDD only — tie VBAT to VDD. */
        DISABLED,
    }

    /**
     * Alarm configuration; a `null` field is disabled (ignored in the match).
     *
     * @property minute 0–59, or `null`
     * @property hour 0–23, or `null`
     * @property day day of month 1–31, or `null`
     * @property weekday 0 (Sunday) – 6 (Saturday), or `null`
     */
    data class Alarm(val minute: Int? = null, val hour: Int? = null, val day: Int? = null, val weekday: Int? = null)

    /**
     * Offset calibration value.
     *
     * @property offset two's-complement correction, −64…+63 LSB
     * @property mode correction interval
     */
    data class Offset(val offset: Int, val mode: OffsetMode)

    @Volatile private var callback: ((Int) -> Unit)? = null
    private var intPin: InputPin? = null
    @Volatile private var polling = false
    private var pollThread: Thread? = null

    /** Fixed edge handler instance so offEdge() removes exactly what onEdge() registered. */
    private val edgeHandler = EdgeHandler { handleEdge() }

    private fun updateTmrClkout(clearMask: Int, setBits: Int) {
        val reg = readReg(REG_TMR_CLKOUT_CTRL)
        writeReg(REG_TMR_CLKOUT_CTRL, (reg and clearMask.inv()) or setBits)
    }

    /** Write CONTROL_3 with BSF=1 so the flag is left unchanged. */
    private fun writeControl3(value: Int) {
        writeReg(REG_CONTROL_3, (value and (C3_PM_MASK or C3_BSIE or C3_BLIE)) or C3_BSF)
    }

    // -- Alarm ---------------------------------------------------------------

    /**
     * Decode the alarm registers (0x0A–0x0D).
     *
     * @return the alarm; disabled fields are `null`
     */
    fun getAlarm(): Alarm {
        val raw = readBurst(REG_MINUTE_ALARM, 4).map { it.toInt() and 0xFF }
        fun field(b: Int, mask: Int, bcd: Boolean = true): Int? =
            if (b and 0x80 != 0) null else if (bcd) bcdToInt(b and mask) else b and mask
        return Alarm(field(raw[0], 0x7F), field(raw[1], 0x3F), field(raw[2], 0x3F), field(raw[3], 0x07, bcd = false))
    }

    /**
     * Write the alarm registers (0x0A–0x0D). `null` fields are disabled; the
     * alarm fires when every enabled field matches.
     *
     * @param alarm the alarm configuration
     */
    fun setAlarm(alarm: Alarm) {
        writeBurst(
            REG_MINUTE_ALARM,
            alarm.minute?.let { intToBcd(it) and 0x7F } ?: 0x80,
            alarm.hour?.let { intToBcd(it) and 0x3F } ?: 0x80,
            alarm.day?.let { intToBcd(it) and 0x3F } ?: 0x80,
            alarm.weekday?.let { it and 0x07 } ?: 0x80,
        )
    }

    // -- Timers --------------------------------------------------------------

    /**
     * Configure and start Timer A.
     *
     * @param mode countdown or watchdog
     * @param value countdown value, 0–255
     * @param sourceClock timer tick frequency
     * @param pulsed pulsed (`true`) or permanently-active interrupt
     */
    fun configureTimerA(mode: TimerAMode, value: Int, sourceClock: SourceClock, pulsed: Boolean = false) {
        val tac = if (mode == TimerAMode.WATCHDOG) TMR_TAC_WATCHDOG else TMR_TAC_COUNTDOWN
        writeReg(REG_TMR_A_FREQ_CTRL, sourceClock.bits)
        writeReg(REG_TMR_A_REG, value)
        updateTmrClkout(TMR_TAM or TMR_TAC_MASK, (if (pulsed) TMR_TAM else 0) or tac)
    }

    /** Stop Timer A (`TAC`=00). */
    fun disableTimerA() = updateTmrClkout(TMR_TAC_MASK, 0)

    /** @return Timer A's live countdown value, 0–255 (not the loaded one) */
    fun readTimerA(): Int = readReg(REG_TMR_A_REG)

    /**
     * Configure and start Timer B (also drives `INT2`).
     *
     * @param value countdown value, 0–255
     * @param sourceClock timer tick frequency
     * @param pulseWidthMs pulsed-mode low-pulse width in ms; the nearest of the eight hardware widths (46.875–218.75 ms) is used
     * @param pulsed pulsed (`true`) or permanently-active interrupt
     */
    fun configureTimerB(value: Int, sourceClock: SourceClock, pulseWidthMs: Double = 46.875, pulsed: Boolean = false) {
        val tbw = TBW_WIDTHS_MS.indices.minByOrNull { abs(TBW_WIDTHS_MS[it] - pulseWidthMs) } ?: 0
        writeReg(REG_TMR_B_FREQ_CTRL, (tbw shl 4) or sourceClock.bits)
        writeReg(REG_TMR_B_REG, value)
        updateTmrClkout(TMR_TBM or TMR_TBC, (if (pulsed) TMR_TBM else 0) or TMR_TBC)
    }

    /** Stop Timer B (`TBC`=0). */
    fun disableTimerB() = updateTmrClkout(TMR_TBC, 0)

    /** @return Timer B's live countdown value, 0–255 (not the loaded one) */
    fun readTimerB(): Int = readReg(REG_TMR_B_REG)

    // -- CLKOUT --------------------------------------------------------------

    /**
     * Drive `CLKOUT` on the shared `INT1`/`CLKOUT` pin.
     *
     * @param frequencyHz one of 32768, 16384, 8192, 4096, 1024, 32, 1 Hz (other values disable CLKOUT)
     */
    fun setClockOutput(frequencyHz: Int) {
        val cof = when (frequencyHz) {
            32768 -> 0
            16384 -> 1
            8192 -> 2
            4096 -> 3
            1024 -> 4
            32 -> 5
            1 -> 6
            else -> 7
        }
        updateTmrClkout(TMR_COF_MASK, cof shl 3)
    }

    /** Disable `CLKOUT` (`COF`=111), freeing `INT1` for interrupts. */
    fun disableClockOutput() = updateTmrClkout(TMR_COF_MASK, TMR_COF_MASK)

    // -- Offset --------------------------------------------------------------

    /**
     * Read the offset calibration register.
     *
     * @return the correction (−64…+63 LSB) and its interval
     */
    fun getOffset(): Offset {
        val raw = readReg(REG_OFFSET)
        var offset = raw and 0x7F
        if (offset and 0x40 != 0) offset -= 128
        return Offset(offset, if (raw and 0x80 != 0) OffsetMode.EVERY_MINUTE else OffsetMode.EVERY_TWO_HOURS)
    }

    /**
     * Write the offset calibration register.
     *
     * @param offset two's-complement correction, −64…+63 LSB
     * @param mode correction interval (4.34 or 4.069 ppm per LSB)
     */
    fun setOffset(offset: Int, mode: OffsetMode = OffsetMode.EVERY_TWO_HOURS) {
        writeReg(REG_OFFSET, (if (mode == OffsetMode.EVERY_MINUTE) 0x80 else 0) or (offset and 0x7F))
    }

    // -- Battery backup ------------------------------------------------------

    /**
     * Select the battery switch-over mode (`PM[2:0]`).
     *
     * @param mode standard, direct or disabled switch-over
     * @param lowDetection enable battery-low detection
     */
    fun configureBatteryBackup(mode: BatteryMode, lowDetection: Boolean = true) {
        val pm = when (mode) {
            BatteryMode.STANDARD -> if (lowDetection) 0x00 else 0x04
            BatteryMode.DIRECT -> if (lowDetection) 0x01 else 0x05
            BatteryMode.DISABLED -> if (lowDetection) 0x02 else 0x07
        }
        val ctrl3 = readReg(REG_CONTROL_3)
        writeControl3((ctrl3 and C3_PM_MASK.inv()) or (pm shl 5))
    }

    /** @return `BSF` — a switch-over to VBAT occurred since it was last cleared */
    fun isBatterySwitchedOver(): Boolean = readReg(REG_CONTROL_3) and C3_BSF != 0

    /** Clear `BSF` only, leaving `PM` and the enable bits unchanged. */
    fun clearBatterySwitchover() {
        val ctrl3 = readReg(REG_CONTROL_3)
        writeReg(REG_CONTROL_3, ctrl3 and (C3_PM_MASK or C3_BSIE or C3_BLIE))
    }

    /** @return `BLF` (read-only) — VBAT is below the detection threshold */
    fun isBatteryLow(): Boolean = readReg(REG_CONTROL_3) and C3_BLF != 0

    /** @return the `OS` flag (bit 7 of `SECONDS`) — the time may be invalid; cleared by [setDatetime] */
    fun oscillatorStopped(): Boolean = readReg(REG_SECONDS) and SECONDS_OS != 0

    /**
     * Send the software-reset sequence (0x58 to `CONTROL_1`). Resets all
     * control/configuration registers to power-on defaults — including
     * `PM`=111 (battery backup disabled) — but keeps the time/date/alarm/timer values.
     */
    fun softwareReset() = writeReg(REG_CONTROL_1, 0x58)

    // -- Interrupt API (Level 3 — multiple INT lines) ------------------------

    /**
     * Subscribe to interrupts. Call [disableClockOutput] first if `INT1` still
     * carries `CLKOUT`.
     *
     * @param callback called with the status mask (test with the `SOURCE_*` constants)
     * @param intPin INT-line pin to arm, or `null` to use the 5 ms polling fallback
     */
    fun onInterrupt(callback: (Int) -> Unit, intPin: InputPin? = connection.intPin()) {
        this.callback = callback
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
        }, "pcf8523-poll").apply {
            isDaemon = true
            start()
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
            if (status != 0) callback?.invoke(status)
        } catch (ignored: IOException) {
            // bus error; wait for the next edge/tick rather than propagating
        }
    }

    /**
     * Read `CONTROL_2`/`CONTROL_3`, clear the set `CTAF`/`CTBF`/`SF`/`AF`/`BSF`
     * flags (`WTAF`/`BLF` are read-only; enable bits untouched).
     *
     * @return the pre-clear status mask — test with the `SOURCE_*` constants
     */
    fun pollInterrupt(): Int {
        val raw = readBurst(REG_CONTROL_2, 2)
        val ctrl2 = raw[0].toInt() and 0xFF
        val ctrl3 = raw[1].toInt() and 0xFF
        var status = 0
        if (ctrl2 and C2_SF != 0) status = status or SOURCE_SECOND
        if (ctrl2 and (C2_CTAF or C2_WTAF) != 0) status = status or SOURCE_TIMER_A
        if (ctrl2 and C2_CTBF != 0) status = status or SOURCE_TIMER_B
        if (ctrl2 and C2_AF != 0) status = status or SOURCE_ALARM
        if (ctrl3 and C3_BSF != 0) status = status or SOURCE_BATTERY_SWITCH
        if (ctrl3 and C3_BLF != 0) status = status or SOURCE_BATTERY_LOW
        // Write 0 only to the flags seen set, 1 to the rest, so a flag that
        // sets between the read and this write is not lost.
        if (ctrl2 and C2_CLEARABLE != 0) {
            writeReg(REG_CONTROL_2, (C2_CLEARABLE and ctrl2.inv()) or (ctrl2 and C2_ENABLES))
        }
        if (ctrl3 and C3_BSF != 0) {
            writeReg(REG_CONTROL_3, ctrl3 and (C3_PM_MASK or C3_BSIE or C3_BLIE))
        }
        return status
    }

    /**
     * Enable one or more interrupt sources. [SOURCE_TIMER_A] sets `WTAIE` or
     * `CTAIE` depending on Timer A's configured mode — call [configureTimerA] first.
     *
     * @param source bitwise OR of `SOURCE_*` constants
     */
    fun enableInterrupt(source: Int) = setInterruptEnables(source, true)

    /**
     * Disable one or more interrupt sources.
     *
     * @param source bitwise OR of `SOURCE_*` constants
     */
    fun disableInterrupt(source: Int) = setInterruptEnables(source, false)

    private fun setInterruptEnables(source: Int, enable: Boolean) {
        fun apply(reg: Int, bits: Int) = if (enable) reg or bits else reg and bits.inv()
        if (source and (SOURCE_SECOND or SOURCE_ALARM) != 0) {
            val bits = (if (source and SOURCE_SECOND != 0) C1_SIE else 0) or (if (source and SOURCE_ALARM != 0) C1_AIE else 0)
            writeReg(REG_CONTROL_1, apply(readControl1(), bits))
        }
        if (source and (SOURCE_TIMER_A or SOURCE_TIMER_B) != 0) {
            var bits = 0
            if (source and SOURCE_TIMER_A != 0) {
                bits = bits or if (enable) {
                    if (readReg(REG_TMR_CLKOUT_CTRL) and TMR_TAC_MASK == TMR_TAC_WATCHDOG) C2_WTAIE else C2_CTAIE
                } else {
                    C2_WTAIE or C2_CTAIE
                }
            }
            if (source and SOURCE_TIMER_B != 0) bits = bits or C2_CTBIE
            val enables = readReg(REG_CONTROL_2) and C2_ENABLES
            // Re-supply the enable bits; write 1 to every clearable flag so
            // none is cleared by accident (AND semantics).
            writeReg(REG_CONTROL_2, C2_CLEARABLE or (apply(enables, bits) and C2_ENABLES))
        }
        if (source and (SOURCE_BATTERY_SWITCH or SOURCE_BATTERY_LOW) != 0) {
            val bits = (if (source and SOURCE_BATTERY_SWITCH != 0) C3_BSIE else 0) or
                (if (source and SOURCE_BATTERY_LOW != 0) C3_BLIE else 0)
            writeControl3(apply(readReg(REG_CONTROL_3), bits))
        }
    }
}
