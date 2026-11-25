package it.uhde.periph.chips.rtc

import it.uhde.periph.connection.RegisterConnection
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import java.io.IOException

/**
 * PCF85063A full driver — extends [PCF85063AMinimal] with the five-field
 * alarm, the countdown timer, minute / half-minute interrupts, programmable
 * `CLKOUT`, offset calibration, oscillator-load selection, oscillator-stop
 * detection, clock stop/start, the free RAM byte, software reset and the
 * interrupt API.
 *
 * The `INT` pin is open-drain and active-low; `CLKOUT` is a separate pin.
 * [onInterrupt] uses `connection.intPin()` if wired, otherwise a 5 ms
 * polling thread.
 *
 * @param connection configured I²C connection bound to address 0x51
 */
open class PCF85063AFull(connection: RegisterConnection) : PCF85063AMinimal(connection) {

    companion object {
        /** Interrupt source: all enabled alarm fields matched (`AF`). */
        const val SOURCE_ALARM = 0x01
        /** Interrupt source: countdown timer expired (`TF`). */
        const val SOURCE_TIMER = 0x02
        /** Interrupt source: minute interrupt (enable only; reported as [SOURCE_TIMER]). */
        const val SOURCE_MINUTE = 0x04
        /** Interrupt source: half-minute interrupt (enable only; reported as [SOURCE_TIMER]). */
        const val SOURCE_HALF_MINUTE = 0x08
        /** Interrupt source: offset-correction pulse (enable only, no flag). */
        const val SOURCE_CORRECTION = 0x10

        // Control_2 (0x01) bits.
        private const val C2_AIE = 0x80
        private const val C2_AF = 0x40
        private const val C2_MI = 0x20
        private const val C2_HMI = 0x10
        private const val C2_TF = 0x08
        private const val C2_COF = 0x07
        private const val C2_FLAGS = 0x48 // AF|TF: write 0 clears, 1 keeps

        // Timer_mode (0x11) bits.
        private const val TM_TCF_MASK = 0x18
        private const val TM_TE = 0x04
        private const val TM_TIE = 0x02
        private const val TM_TI_TP = 0x01

        private const val SECONDS_OS = 0x80
    }

    /** Timer source clock (`TCF` encoding). */
    enum class SourceClock(internal val bits: Int) {
        /** 4.096 kHz — 244 µs … 62.256 ms. */
        HZ_4096(0x00),
        /** 64 Hz — 15.625 ms … 3.984 s. */
        HZ_64(0x01),
        /** 1 Hz — 1 s … 255 s. */
        HZ_1(0x02),
        /** 1/60 Hz — 1 min … 4 h 15 min. */
        HZ_1_60(0x03),
    }

    /** Offset correction mode. */
    enum class OffsetMode {
        /** Correction every two hours, 4.34 ppm per LSB (default). */
        NORMAL,
        /** Correction every four minutes, 4.069 ppm per LSB, higher power. */
        COURSE,
    }

    /** Minute / half-minute interrupt selection. */
    enum class MinuteInterrupt { OFF, MINUTE, HALF_MINUTE }

    /**
     * Alarm configuration; a `null` field is disabled (ignored in the match).
     *
     * @property second 0–59, or `null`
     * @property minute 0–59, or `null`
     * @property hour 0–23, or `null`
     * @property day day of month 1–31, or `null`
     * @property weekday 0 (Sunday) – 6 (Saturday), or `null`
     */
    data class Alarm(
        val second: Int? = null, val minute: Int? = null, val hour: Int? = null,
        val day: Int? = null, val weekday: Int? = null,
    )

    /**
     * Offset calibration value.
     *
     * @property offset two's-complement correction, −64…+63 LSB
     * @property mode correction mode
     */
    data class Offset(val offset: Int, val mode: OffsetMode)

    @Volatile private var callback: ((Int) -> Unit)? = null
    private var intPin: InputPin? = null
    @Volatile private var polling = false
    private var pollThread: Thread? = null

    /** Fixed edge handler instance so offEdge() removes exactly what onEdge() registered. */
    private val edgeHandler = EdgeHandler { handleEdge() }

    private fun updateControl1(clearMask: Int, setBits: Int) {
        writeReg(REG_CONTROL_1, (readControl1() and clearMask.inv()) or setBits)
    }

    /**
     * Read-modify-write `Control_2`. `AF` and `TF` are written 1 (unchanged)
     * unless explicitly cleared through [clearMask] (AND semantics).
     */
    private fun updateControl2(clearMask: Int, setBits: Int) {
        val reg = readReg(REG_CONTROL_2)
        writeReg(REG_CONTROL_2, ((reg and clearMask.inv()) or setBits or (C2_FLAGS and clearMask.inv())) and 0xFF)
    }

    private fun updateTimerMode(clearMask: Int, setBits: Int) {
        val reg = readReg(REG_TIMER_MODE)
        writeReg(REG_TIMER_MODE, ((reg and clearMask.inv()) or setBits) and 0x1F)
    }

    // -- Oscillator / clock control -------------------------------------------

    /**
     * @return the `OS` flag (bit 7 of `Seconds`) — the time may be invalid; cleared by [setDatetime] or [clearOscillatorStop]
     */
    fun oscillatorStopped(): Boolean = (readReg(REG_SECONDS) and SECONDS_OS) != 0

    /**
     * Clear `OS`, keeping the current seconds value. If [oscillatorStopped]
     * is still true afterwards the oscillator is not running.
     */
    fun clearOscillatorStop() {
        writeReg(REG_SECONDS, readReg(REG_SECONDS) and 0x7F)
    }

    /** Freeze the clock (`STOP`=1); the divider chain is held in reset. */
    fun stopClock() {
        updateControl1(0, C1_STOP)
    }

    /** Release the clock (`STOP`=0). */
    fun startClock() {
        updateControl1(C1_STOP, 0)
    }

    // -- Alarm ---------------------------------------------------------------

    /**
     * Decode the alarm registers (0x0B–0x0F).
     *
     * @return the alarm; disabled fields are `null`
     */
    fun getAlarm(): Alarm {
        val raw = readBurst(REG_SECOND_ALARM, 5).map { it.toInt() and 0xFF }
        fun field(b: Int, mask: Int, bcd: Boolean = true): Int? =
            if (b and 0x80 != 0) null else if (bcd) bcdToInt(b and mask) else b and mask
        return Alarm(field(raw[0], 0x7F), field(raw[1], 0x7F), field(raw[2], 0x3F), field(raw[3], 0x3F), field(raw[4], 0x07, bcd = false))
    }

    /**
     * Write the alarm registers (0x0B–0x0F) and clear `AF`. `null` fields are
     * disabled; the alarm fires when every enabled field matches.
     *
     * @param alarm the alarm configuration
     */
    fun setAlarm(alarm: Alarm) {
        writeBurst(
            REG_SECOND_ALARM,
            alarm.second?.let { intToBcd(it) and 0x7F } ?: 0x80,
            alarm.minute?.let { intToBcd(it) and 0x7F } ?: 0x80,
            alarm.hour?.let { intToBcd(it) and 0x3F } ?: 0x80,
            alarm.day?.let { intToBcd(it) and 0x3F } ?: 0x80,
            alarm.weekday?.let { it and 0x07 } ?: 0x80,
        )
        updateControl2(C2_AF, 0)
    }

    // -- Countdown timer -----------------------------------------------------

    /**
     * Configure and start the countdown timer. The timer is disabled first so
     * `Timer_value` can be changed safely. Does not set `TIE` — use
     * [enableInterrupt] with [SOURCE_TIMER].
     *
     * @param value countdown value, 1–255
     * @param sourceClock timer tick frequency
     * @param pulsed pulsed (`true`) or level (`false`, INT follows TF) interrupt
     */
    fun configureTimer(value: Int, sourceClock: SourceClock, pulsed: Boolean = false) {
        updateTimerMode(TM_TE, 0)
        writeReg(REG_TIMER_VALUE, value)
        updateTimerMode(TM_TCF_MASK or TM_TI_TP, (sourceClock.bits shl 3) or (if (pulsed) TM_TI_TP else 0) or TM_TE)
    }

    /** Stop the timer (`TE`=0, `TIE`=0) and select the 1/60 Hz clock for power saving. */
    fun disableTimer() {
        updateTimerMode(TM_TE or TM_TIE or TM_TCF_MASK, TM_TCF_MASK)
    }

    /**
     * Read the live countdown value; reads until two consecutive reads agree.
     *
     * @return the current counter value, 0–255 (not the loaded one)
     */
    fun readTimer(): Int {
        var prev = readReg(REG_TIMER_VALUE)
        while (true) {
            val cur = readReg(REG_TIMER_VALUE)
            if (cur == prev) return cur
            prev = cur
        }
    }

    /**
     * Select the minute / half-minute interrupt (requires offset mode [OffsetMode.NORMAL]).
     *
     * @param mode off, once per minute, or every 30 s
     */
    fun setMinuteInterrupt(mode: MinuteInterrupt) {
        val bits = when (mode) {
            MinuteInterrupt.MINUTE -> C2_MI
            MinuteInterrupt.HALF_MINUTE -> C2_HMI
            MinuteInterrupt.OFF -> 0
        }
        updateControl2(C2_MI or C2_HMI, bits)
    }

    // -- CLKOUT --------------------------------------------------------------

    /**
     * Drive `CLKOUT` at one of 32768, 16384, 8192, 4096, 2048, 1024 or 1 Hz
     * (any other value disables it).
     *
     * @param frequencyHz output frequency in Hz
     */
    fun setClockOutput(frequencyHz: Int) {
        val cof = when (frequencyHz) {
            32768 -> 0
            16384 -> 1
            8192 -> 2
            4096 -> 3
            2048 -> 4
            1024 -> 5
            1 -> 6
            else -> 7
        }
        updateControl2(C2_COF, cof)
    }

    /** Disable `CLKOUT` (`COF`=111, pin low) to save power. */
    fun disableClockOutput() {
        updateControl2(C2_COF, C2_COF)
    }

    // -- Offset / oscillator load / RAM --------------------------------------

    /**
     * Read the clock-offset calibration register.
     *
     * @return the offset (−64…+63 LSB) and its mode
     */
    fun getOffset(): Offset {
        val raw = readReg(REG_OFFSET)
        var offset = raw and 0x7F
        if (offset and 0x40 != 0) offset -= 128
        return Offset(offset, if (raw and 0x80 != 0) OffsetMode.COURSE else OffsetMode.NORMAL)
    }

    /**
     * Write the clock-offset calibration register.
     *
     * @param offset two's-complement correction, −64…+63 LSB
     * @param mode correction mode (4.34 or 4.069 ppm per LSB)
     */
    fun setOffset(offset: Int, mode: OffsetMode = OffsetMode.NORMAL) {
        writeReg(REG_OFFSET, (if (mode == OffsetMode.COURSE) 0x80 else 0) or (offset and 0x7F))
    }

    /**
     * Select the oscillator load capacitance (`CAP_SEL`) — match the
     * crystal's specified load.
     *
     * @param picofarads 7 or 12.5
     */
    fun setOscillatorLoad(picofarads: Double) {
        require(picofarads == 7.0 || picofarads == 12.5) { "picofarads must be 7 or 12.5" }
        updateControl1(C1_CAP_SEL, if (picofarads == 12.5) C1_CAP_SEL else 0)
    }

    /** @return the free general-purpose RAM byte, 0–255 */
    fun readRam(): Int = readReg(REG_RAM)

    /**
     * Write the free general-purpose RAM byte.
     *
     * @param value 0–255
     */
    fun writeRam(value: Int) {
        writeReg(REG_RAM, value)
    }

    /**
     * Send the software-reset sequence (0x58 to `Control_1`). Resets ALL
     * registers including the time (2000-01-01 00:00:00, `OS`=1).
     */
    fun softwareReset() {
        writeReg(REG_CONTROL_1, 0x58)
    }

    // -- Interrupt API -------------------------------------------------------

    /**
     * Subscribe to interrupts using `connection.intPin()` (or a 5 ms polling
     * thread if none is wired).
     *
     * @param callback called with the status mask ([SOURCE_ALARM] / [SOURCE_TIMER])
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
        }, "pcf85063a-poll").apply {
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
     * Read `Control_2`, clear the set `AF`/`TF` flags (enable bits and `COF`
     * preserved).
     *
     * @return the pre-clear status mask — [SOURCE_ALARM] and/or [SOURCE_TIMER] (the timer, minute and half-minute sources all report as `SOURCE_TIMER`)
     */
    fun pollInterrupt(): Int {
        val ctrl2 = readReg(REG_CONTROL_2)
        var status = 0
        if (ctrl2 and C2_AF != 0) status = status or SOURCE_ALARM
        if (ctrl2 and C2_TF != 0) status = status or SOURCE_TIMER
        if (status != 0) {
            // Write 0 only to the flags seen set, 1 to the others, so a flag
            // that sets between the read and this write is not lost.
            writeReg(REG_CONTROL_2, (ctrl2 and C2_FLAGS.inv() and 0xFF) or (C2_FLAGS and ctrl2.inv() and 0xFF))
        }
        return status
    }

    /**
     * Enable one or more interrupt sources (`AIE`, `TIE`, `MI`, `HMI`, `CIE`).
     *
     * @param source bitwise OR of `SOURCE_*` constants
     */
    fun enableInterrupt(source: Int) {
        setInterruptEnables(source, true)
    }

    /**
     * Disable one or more interrupt sources.
     *
     * @param source bitwise OR of `SOURCE_*` constants
     */
    fun disableInterrupt(source: Int) {
        setInterruptEnables(source, false)
    }

    private fun setInterruptEnables(source: Int, enable: Boolean) {
        val bits2 = (if (source and SOURCE_ALARM != 0) C2_AIE else 0) or
            (if (source and SOURCE_MINUTE != 0) C2_MI else 0) or
            (if (source and SOURCE_HALF_MINUTE != 0) C2_HMI else 0)
        if (bits2 != 0) updateControl2(if (enable) 0 else bits2, if (enable) bits2 else 0)
        if (source and SOURCE_TIMER != 0) updateTimerMode(if (enable) 0 else TM_TIE, if (enable) TM_TIE else 0)
        if (source and SOURCE_CORRECTION != 0) updateControl1(if (enable) 0 else C1_CIE, if (enable) C1_CIE else 0)
    }
}
