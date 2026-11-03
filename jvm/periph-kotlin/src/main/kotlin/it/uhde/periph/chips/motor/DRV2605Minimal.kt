package it.uhde.periph.chips.motor

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * DRV2605 — haptic driver for ERM and LRA actuators with I²C interface (Texas
 * Instruments) — minimal interface.
 *
 * Plays the built-in 123-effect TS2200 ROM library. Construction runs the
 * initialization sequence: `DEV_RESET`, `DEVICE_ID` check, leave standby,
 * actuator selection (ERM: library A, open loop; LRA: LRA library, closed loop
 * with auto-resonance). The I²C address is fixed at 0x5A and the `EN` pin must
 * be high for register access.
 *
 * @param connection configured I²C connection bound to the device (0x5A)
 * @param lra `false` for an ERM, `true` for an LRA
 * @throws IllegalStateException if `DEVICE_ID` is not 3
 * @throws IOException on bus error or if the reset does not complete
 */
open class DRV2605Minimal @JvmOverloads constructor(
    protected val connection: RegisterConnection,
    lra: Boolean = false,
) {

    companion object {
        /** Fixed 7-bit I²C address (no address pin). */
        const val DEFAULT_ADDRESS = 0x5A

        /** Expected `DEVICE_ID` of the DRV2605. */
        const val DEVICE_ID = 3

        /** Named ROM effect: Strong Click. */
        const val EFFECT_STRONG_CLICK = 1
        /** Named ROM effect: Sharp Click. */
        const val EFFECT_SHARP_CLICK = 4
        /** Named ROM effect: Soft Bump. */
        const val EFFECT_SOFT_BUMP = 7
        /** Named ROM effect: Double Click. */
        const val EFFECT_DOUBLE_CLICK = 10
        /** Named ROM effect: Triple Click. */
        const val EFFECT_TRIPLE_CLICK = 12
        /** Named ROM effect: Strong Buzz. */
        const val EFFECT_STRONG_BUZZ = 14
        /** Named ROM effect: 750 ms Alert. */
        const val EFFECT_ALERT_750MS = 15
        /** Named ROM effect: 1000 ms Alert. */
        const val EFFECT_ALERT_1000MS = 16
        /** Named ROM effect: Buzz 1. */
        const val EFFECT_BUZZ = 47
        /** Named ROM effect: Pulsing Strong 1. */
        const val EFFECT_PULSING_STRONG = 52
        /** Named ROM effect: Long Buzz for programmatic stopping. */
        const val EFFECT_LONG_BUZZ = 118

        /** `MODE` value: internal trigger (`GO` bit). */
        const val MODE_INTERNAL_TRIGGER = 0
        /** `MODE` value: external trigger, edge. */
        const val MODE_EXTERNAL_EDGE = 1
        /** `MODE` value: external trigger, level. */
        const val MODE_EXTERNAL_LEVEL = 2
        /** `MODE` value: PWM / analog input. */
        const val MODE_PWM_ANALOG = 3
        /** `MODE` value: audio-to-vibe. */
        const val MODE_AUDIO_TO_VIBE = 4
        /** `MODE` value: real-time playback. */
        const val MODE_RTP = 5
        /** `MODE` value: diagnostics. */
        const val MODE_DIAGNOSTICS = 6
        /** `MODE` value: auto-calibration. */
        const val MODE_AUTO_CALIBRATION = 7

        // Register map.
        const val REG_STATUS = 0x00
        const val REG_MODE = 0x01
        const val REG_RTP_INPUT = 0x02
        const val REG_LIBRARY = 0x03
        const val REG_SEQ1 = 0x04
        const val REG_GO = 0x0C
        const val REG_ODT = 0x0D
        const val REG_ATH_CTRL = 0x11
        const val REG_RATED_VOLTAGE = 0x16
        const val REG_OD_CLAMP = 0x17
        const val REG_A_CAL_COMP = 0x18
        const val REG_FEEDBACK = 0x1A
        const val REG_CONTROL1 = 0x1B
        const val REG_CONTROL2 = 0x1C
        const val REG_CONTROL3 = 0x1D
        const val REG_CONTROL4 = 0x1E
        const val REG_VBAT = 0x21
        const val REG_LRA_PERIOD = 0x22

        const val MODE_DEV_RESET = 0x80
        const val MODE_STANDBY = 0x40

        /** Round to the nearest integer and clamp to a register code 0–255. */
        internal fun clampCode(value: Double): Int = (value + 0.5).toInt().coerceIn(0, 255)

        internal fun sleepMs(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("interrupted", e)
            }
        }
    }

    protected var currentMode: Int = MODE_INTERNAL_TRIGGER
    protected var inStandby: Boolean = false

    init {
        resetChip()
        check(readReg(REG_STATUS) shr 5 == DEVICE_ID) { "DRV2605 not found (DEVICE_ID != 3)" }
        writeMode(MODE_INTERNAL_TRIGGER, false)
        if (lra) {
            writeReg(REG_FEEDBACK, 0xB6)
            writeReg(REG_LIBRARY, 0x06)
        } else {
            writeReg(REG_FEEDBACK, 0x36)
            writeReg(REG_LIBRARY, 0x01)
        }
    }

    protected fun readReg(reg: Int): Int = connection.read(reg, 1)[0].toInt() and 0xFF

    protected fun writeReg(reg: Int, value: Int) {
        connection.write(reg, byteArrayOf(value.toByte()))
    }

    protected fun writeRegs(reg: Int, data: ByteArray) {
        connection.write(reg, data)
    }

    /** Read-modify-write: replace the bits in [mask] with [value] (already shifted). */
    protected fun updateReg(reg: Int, mask: Int, value: Int) {
        val old = readReg(reg)
        writeReg(reg, (old and mask.inv()) or (value and mask))
    }

    protected fun writeMode(mode: Int, standby: Boolean) {
        writeReg(REG_MODE, (if (standby) MODE_STANDBY else 0) or mode)
        currentMode = mode
        inStandby = standby
    }

    protected fun resetChip() {
        writeReg(REG_MODE, MODE_DEV_RESET)
        currentMode = MODE_INTERNAL_TRIGGER
        inStandby = true
        sleepMs(2)
        repeat(100) {
            if (readReg(REG_MODE) and MODE_DEV_RESET == 0) return
            sleepMs(1)
        }
        throw IOException("DRV2605 reset did not complete")
    }

    protected fun ensureInternal() {
        if (currentMode != MODE_INTERNAL_TRIGGER || inStandby) writeMode(MODE_INTERNAL_TRIGGER, false)
    }

    /**
     * Play one ROM effect and return immediately. Writes the effect to
     * sequencer slot 1 and 0 (end) to slot 2, switches to internal-trigger mode
     * if needed, then sets `GO`.
     *
     * @param effectId effect identifier 1–123 (see the `EFFECT_*` constants)
     * @throws IllegalArgumentException if [effectId] is outside 1–123
     */
    fun playEffect(effectId: Int) {
        require(effectId in 1..123) { "effectId must be 1-123" }
        ensureInternal()
        writeRegs(REG_SEQ1, byteArrayOf(effectId.toByte(), 0))
        writeReg(REG_GO, 1)
    }

    /** Cancel playback by clearing the `GO` bit. */
    open fun stop() {
        writeReg(REG_GO, 0)
    }

    /** Whether a sequence is playing (`GO` bit set). */
    fun isPlaying(): Boolean = readReg(REG_GO) and 0x01 != 0
}
