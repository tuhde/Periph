package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.OutputPin
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * CS43L22 — low-power stereo DAC with headphone and Class-D speaker amplifiers (Cirrus Logic) —
 * minimal interface.
 *
 * Configured entirely through the I²C control port (7-bit address 0x4A with AD0 = DGND, 0x4B with
 * AD0 = VL; 100 kHz maximum). Audio data travels over a separate I²S / PCM port that this driver
 * does not touch: the application supplies MCLK and the stream, calls [start] once the clocks run
 * and [stop] before they go away. Registers have a one-byte address and are written one at a time.
 *
 * Defaults: I²S slave, 24-bit, clock speed auto-detected from the MCLK / LRCK ratio, headphone
 * amplifiers always on, speaker amplifiers off, headphone gain 0.6047, master volume 0 dB, digital
 * soft ramp on. Construction performs no bus traffic; [init] resets the chip (when a RESET pin is
 * given), checks its ID, loads these defaults and runs the required initialization block. The chip
 * stays powered down until [start].
 *
 * @param connection I²C register connection bound to 0x4A / 0x4B (one-byte register address)
 * @param resPin optional output pin wired to the active-low RESET pin
 */
open class CS43L22Minimal(
    protected val connection: RegisterConnection,
    protected val resPin: OutputPin? = null,
) {
    companion object {
        /** 7-bit I²C address with AD0 = DGND (0x4B with AD0 = VL). */
        const val DEFAULT_ADDRESS = 0x4A

        const val REG_ID = 0x01
        const val REG_POWER_CTL_1 = 0x02
        const val REG_POWER_CTL_2 = 0x04
        const val REG_CLOCKING_CTL = 0x05
        const val REG_INTERFACE_CTL_1 = 0x06
        const val REG_INTERFACE_CTL_2 = 0x07
        const val REG_PASSTHRU_A_SEL = 0x08
        const val REG_PASSTHRU_B_SEL = 0x09
        const val REG_ANALOG_ZC_SR = 0x0A
        const val REG_PLAYBACK_CTL_1 = 0x0D
        const val REG_MISC_CTL = 0x0E
        const val REG_PLAYBACK_CTL_2 = 0x0F
        const val REG_PASSTHRU_A_VOL = 0x14
        const val REG_PASSTHRU_B_VOL = 0x15
        const val REG_PCM_A_VOL = 0x1A
        const val REG_PCM_B_VOL = 0x1B
        const val REG_BEEP_FREQ_ON = 0x1C
        const val REG_BEEP_VOL_OFF = 0x1D
        const val REG_BEEP_TONE_CFG = 0x1E
        const val REG_TONE_CTL = 0x1F
        const val REG_MASTER_A_VOL = 0x20
        const val REG_MASTER_B_VOL = 0x21
        const val REG_HP_A_VOL = 0x22
        const val REG_HP_B_VOL = 0x23
        const val REG_SPK_A_VOL = 0x24
        const val REG_SPK_B_VOL = 0x25
        const val REG_PCM_MIXER_SWAP = 0x26
        const val REG_LIMIT_CTL_1 = 0x27
        const val REG_LIMIT_CTL_2 = 0x28
        const val REG_LIMIT_ATTACK = 0x29
        const val REG_STATUS = 0x2E
        const val REG_BATTERY_COMP = 0x2F
        const val REG_VP_LEVEL = 0x30
        const val REG_SPEAKER_STATUS = 0x31
        const val REG_CHARGE_PUMP_FREQ = 0x34
        const val REG_INIT_00 = 0x00
        const val REG_INIT_32 = 0x32
        const val REG_INIT_47 = 0x47
    }

    protected fun w(reg: Int, value: Int) {
        connection.write(reg, byteArrayOf(value.toByte()))
    }

    protected fun r(reg: Int): Int = connection.read(reg, 1)[0].toInt() and 0xFF

    protected fun update(reg: Int, mask: Int, value: Int) {
        w(reg, (r(reg) and mask.inv() and 0xFF) or (value and mask))
    }

    protected fun setBit(reg: Int, bit: Int, on: Boolean) {
        update(reg, 1 shl bit, if (on) 1 shl bit else 0)
    }

    protected fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    protected fun roundHalfUp(x: Double): Int = floor(x + 0.5).toInt()

    protected fun halfDbCode(db: Double, lo: Double, hi: Double): Int =
        roundHalfUp(max(lo, min(hi, db)) * 2) and 0xFF

    protected fun limitedVolumeCode(db: Double): Int =
        if (db < -96.0) 0x01 else roundHalfUp(max(-96.0, min(0.0, db)) * 2) and 0xFF

    /**
     * Reset (when a RESET pin is given), verify the chip ID, load the defaults and run the
     * required initialization. The chip stays powered down.
     *
     * @throws IOException on I²C error, or if the ID register does not read 0x1C in bits 7:3
     */
    open fun init() {
        if (resPin != null) {
            resPin.set(false)
            sleepMs(2)
            resPin.set(true)
            sleepMs(2)
        }
        if ((r(REG_ID) and 0xF8) != 0xE0) {
            throw IOException("CS43L22 not found: chip ID mismatch")
        }
        w(REG_POWER_CTL_1, 0x01)
        w(REG_POWER_CTL_2, 0xAF)
        w(REG_CLOCKING_CTL, 0xA0)
        w(REG_INTERFACE_CTL_1, 0x04)
        w(REG_PLAYBACK_CTL_1, 0x60)
        w(REG_MASTER_A_VOL, 0x00)
        w(REG_MASTER_B_VOL, 0x00)
        // Required initialization settings (datasheet §4.11).
        w(REG_INIT_00, 0x99)
        w(REG_INIT_47, 0x80)
        val v = r(REG_INIT_32)
        w(REG_INIT_32, v or 0x80)
        w(REG_INIT_32, v and 0x7F)
        w(REG_INIT_00, 0x00)
    }

    /** Power the chip up (`POWER_CTL_1` = 0x9E). Call after MCLK, SCLK and LRCK are running. */
    fun start() {
        w(REG_POWER_CTL_1, 0x9E)
    }

    /**
     * Power-down sequence: mute, disable soft ramp / zero-cross, `POWER_CTL_1` = 0x9F, wait
     * 100 µs. MCLK may be removed after it returns.
     */
    fun stop() {
        update(REG_PLAYBACK_CTL_2, 0xF0, 0xF0)
        update(REG_MISC_CTL, 0x03, 0x00)
        update(REG_ANALOG_ZC_SR, 0x0F, 0x00)
        w(REG_POWER_CTL_1, 0x9F)
        sleepMs(1)
    }

    /**
     * Set the master volume of both channels.
     *
     * @param db −102 … +12 dB, rounded to the nearest 0.5 dB step (clamped)
     */
    fun setVolume(db: Double) {
        val code = halfDbCode(db, -102.0, 12.0)
        w(REG_MASTER_A_VOL, code)
        w(REG_MASTER_B_VOL, code)
    }

    /** Master volume of channel A in dB. */
    fun getVolume(): Double = r(REG_MASTER_A_VOL).toByte().toInt() / 2.0

    /**
     * Mute or unmute both headphone channels.
     *
     * @param on `true` to mute
     */
    fun mute(on: Boolean = true) {
        update(REG_PLAYBACK_CTL_2, 0xC0, if (on) 0xC0 else 0)
    }
}
