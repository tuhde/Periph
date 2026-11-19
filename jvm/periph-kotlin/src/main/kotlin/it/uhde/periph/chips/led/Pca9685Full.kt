package it.uhde.periph.chips.led

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * PCA9685 — full driver: phase-shifted PWM, frequency, servo pulses, output modes, addresses.
 *
 * Extends [Pca9685Minimal].
 *
 * @param connection I²C register connection bound to the chip address
 */
class Pca9685Full(connection: RegisterConnection) : Pca9685Minimal(connection) {

    companion object {
        /** Output state while OE = 1: LEDn = 0. */
        const val OFF_LOW = 0
        /** Output state while OE = 1: LEDn = 1 (totem-pole) or high-Z (open-drain). */
        const val OFF_HIGH_OR_HIGHZ = 1
        /** Output state while OE = 1: high-Z. */
        const val OFF_HIGHZ = 2

        private fun pwmBytes(on: Int, off: Int): IntArray {
            require(on in 0..4095 && off in 0..4095) { "on/off must be 0-4095" }
            require(on != off) { "on must differ from off" }
            return intArrayOf(on and 0xFF, on shr 8, off and 0xFF, off shr 8)
        }

        /**
         * Send the Software Reset call (0x06) over a connection bound to the general call address 0x00.
         * Resets every PCA9685 on the bus; wait at least 500 µs before using the chips again.
         *
         * @param generalCall connection bound to I²C address 0x00
         * @throws IOException on I²C error
         */
        @JvmStatic
        @Throws(IOException::class)
        fun softwareReset(generalCall: Connection) {
            generalCall.write(byteArrayOf(0x06))
        }
    }

    private fun mode1(): Int = readReg(REG_MODE1, 1)[0].toInt() and 0xFF

    /**
     * Write the ON and OFF counts of one channel in one transaction (phase-shifted PWM).
     *
     * @param channel 0–15
     * @param on 0–4095
     * @param off 0–4095, must differ from `on`
     * @throws IOException on I²C error
     */
    @Throws(IOException::class)
    fun setPwm(channel: Int, on: Int, off: Int) {
        checkChannel(channel)
        writeReg(REG_LED0 + 4 * channel, *pwmBytes(on, off))
    }

    /**
     * Read the ON and OFF counts of one channel (without the full-ON/OFF flags).
     *
     * @param channel 0–15
     * @return `[on, off]`, each 0–4095
     * @throws IOException on I²C error
     */
    @Throws(IOException::class)
    fun getPwm(channel: Int): IntArray {
        checkChannel(channel)
        val d = readReg(REG_LED0 + 4 * channel, 4)
        return intArrayOf(
            ((d[1].toInt() and 0x0F) shl 8) or (d[0].toInt() and 0xFF),
            ((d[3].toInt() and 0x0F) shl 8) or (d[2].toInt() and 0xFF))
    }

    /**
     * Force a channel permanently high (full-ON bit set, full-OFF bit cleared).
     *
     * @param channel 0–15
     * @throws IOException on I²C error
     */
    @Throws(IOException::class)
    fun setFullOn(channel: Int) {
        checkChannel(channel)
        writeReg(REG_LED0 + 4 * channel, 0, 0x10, 0, 0)
    }

    /**
     * Force a channel permanently low (full-OFF bit set; it wins over full ON).
     *
     * @param channel 0–15
     * @throws IOException on I²C error
     */
    @Throws(IOException::class)
    fun setFullOff(channel: Int) {
        checkChannel(channel)
        writeReg(REG_LED0 + 4 * channel, 0, 0, 0, 0x10)
    }

    /**
     * Set the same duty cycle on all 16 channels (ALL_LED registers).
     *
     * @param duty 0.0–1.0
     * @throws IOException on I²C error
     */
    @Throws(IOException::class)
    fun setAllDuty(duty: Double) {
        writeReg(REG_ALL_LED, *dutyBytes(duty))
    }

    /**
     * Set the same ON and OFF counts on all 16 channels.
     *
     * @param on 0–4095
     * @param off 0–4095, must differ from `on`
     * @throws IOException on I²C error
     */
    @Throws(IOException::class)
    fun setAllPwm(on: Int, off: Int) {
        writeReg(REG_ALL_LED, *pwmBytes(on, off))
    }

    /**
     * Set the PWM frequency: PRE_SCALE = round(oscHz / (4096 × hz)) − 1, clamped 3–255. Puts the chip
     * to sleep, writes PRE_SCALE, wakes it, waits 500 µs and restarts running channels.
     *
     * @param hz wanted frequency in Hz (24–1526 at 25 MHz)
     * @param oscHz oscillator frequency in Hz (25 MHz internal, or the EXTCLK frequency)
     * @throws IOException on I²C error
     */
    @JvmOverloads
    @Throws(IOException::class)
    fun setFrequency(hz: Double, oscHz: Double = 25_000_000.0) {
        require(hz > 0 && oscHz > 0) { "hz and oscHz must be positive" }
        val prescale = Math.max(3.0, Math.min(255.0, Math.floor(oscHz / (4096.0 * hz) + 0.5) - 1)).toInt()
        val old = mode1()
        val awake = old and (RESTART or SLEEP).inv() and 0xFF
        writeReg(REG_MODE1, (old and 0x7F) or SLEEP)
        writeReg(REG_PRE_SCALE, prescale)
        writeReg(REG_MODE1, awake)
        pauseMs(1)
        if (old and RESTART != 0) writeReg(REG_MODE1, awake or RESTART)
    }

    /**
     * Read PRE_SCALE and return the PWM frequency.
     *
     * @param oscHz oscillator frequency in Hz
     * @return `oscHz / (4096 × (PRE_SCALE + 1))` in Hz
     * @throws IOException on I²C error
     */
    @JvmOverloads
    @Throws(IOException::class)
    fun getFrequency(oscHz: Double = 25_000_000.0): Double =
        oscHz / (4096.0 * ((readReg(REG_PRE_SCALE, 1)[0].toInt() and 0xFF) + 1))

    /**
     * Set a pulse width in seconds (servo/ESC mode): OFF = round(seconds × frequency × 4096). Uses the
     * frequency programmed in PRE_SCALE (25 MHz oscillator).
     *
     * @param channel 0–15
     * @param seconds pulse width, 0 up to one PWM period
     * @throws IOException on I²C error
     */
    @Throws(IOException::class)
    fun setPulseWidth(channel: Int, seconds: Double) {
        checkChannel(channel)
        require(seconds >= 0) { "seconds must be >= 0" }
        setDuty(channel, Math.min(1.0, seconds * getFrequency()))
    }

    /**
     * Enter low-power mode (MODE1.SLEEP = 1, oscillator off; outputs stop).
     *
     * @throws IOException on I²C error
     */
    @Throws(IOException::class)
    fun sleep() {
        writeReg(REG_MODE1, (mode1() and 0x7F) or SLEEP)
    }

    /**
     * Leave low-power mode, wait 500 µs and restart channels that were running.
     *
     * @throws IOException on I²C error
     */
    @Throws(IOException::class)
    fun wake() {
        val old = mode1()
        writeReg(REG_MODE1, old and (RESTART or SLEEP).inv() and 0xFF)
        pauseMs(1)
        if (old and RESTART != 0) writeReg(REG_MODE1, (old and SLEEP.inv() and 0xFF) or RESTART)
    }

    /**
     * Run the datasheet restart sequence (read MODE1, clear SLEEP, wait 500 µs, write RESTART).
     *
     * @throws IOException on I²C error
     */
    @Throws(IOException::class)
    fun restart() {
        val old = mode1()
        writeReg(REG_MODE1, old and (RESTART or SLEEP).inv() and 0xFF)
        pauseMs(1)
        writeReg(REG_MODE1, (old and SLEEP.inv() and 0xFF) or RESTART)
    }

    /**
     * Configure MODE2: output driver, inversion and the output state while OE = 1. The update-on-ACK
     * bit is preserved.
     *
     * @param openDrain true for open-drain, false for totem-pole
     * @param invert true to invert the output logic
     * @param offState [OFF_LOW], [OFF_HIGH_OR_HIGHZ] or [OFF_HIGHZ]
     * @throws IOException on I²C error
     */
    @JvmOverloads
    @Throws(IOException::class)
    fun setOutputMode(openDrain: Boolean = false, invert: Boolean = false, offState: Int = OFF_LOW) {
        require(offState in 0..2) { "offState must be 0-2" }
        val mode2 = offState or (if (invert) 0x10 else 0) or (if (openDrain) 0 else 0x04)
        val old = readReg(REG_MODE2, 1)[0].toInt() and 0xFF
        writeReg(REG_MODE2, (old and 0x08) or mode2)
    }

    /**
     * Select when outputs change: on ACK (true, MODE2.OCH = 1) or on STOP (false, default).
     *
     * @param enabled true to update on ACK
     * @throws IOException on I²C error
     */
    @Throws(IOException::class)
    fun setUpdateOnAck(enabled: Boolean) {
        val old = readReg(REG_MODE2, 1)[0].toInt() and 0xFF
        writeReg(REG_MODE2, if (enabled) (old or 0x08) else (old and 0x08.inv() and 0xFF))
    }

    /**
     * Switch to the EXTCLK pin as clock. Sticky until power cycle / software reset; afterwards pass
     * the external clock frequency as `oscHz` to [setFrequency] / [getFrequency].
     *
     * @throws IOException on I²C error
     */
    @Throws(IOException::class)
    fun useExternalClock() {
        val sleeping = (mode1() and 0x7F) or SLEEP
        writeReg(REG_MODE1, sleeping)
        writeReg(REG_MODE1, sleeping or EXTCLK)
        writeReg(REG_MODE1, (sleeping or EXTCLK) and SLEEP.inv() and 0xFF)
        pauseMs(1)
    }

    /**
     * Enable or disable the LED All Call address and set its value.
     *
     * @param enabled true to respond to the All Call address
     * @param address 7-bit address (default 0x70)
     * @throws IOException on I²C error
     */
    @JvmOverloads
    @Throws(IOException::class)
    fun setAllcall(enabled: Boolean, address: Int = 0x70) {
        require(address in 0..0x7F) { "address must be 0-127" }
        writeReg(REG_ALLCALLADR, address shl 1)
        writeReg(REG_MODE1, (mode1() and 0x7E) or (if (enabled) 1 else 0))
    }

    /**
     * Program and enable/disable one of the three LED Sub Call addresses.
     *
     * @param index 1–3
     * @param address 7-bit address
     * @param enabled true to respond to the sub address
     * @throws IOException on I²C error
     */
    @JvmOverloads
    @Throws(IOException::class)
    fun setSubaddress(index: Int, address: Int, enabled: Boolean = true) {
        require(index in 1..3) { "index must be 1-3" }
        require(address in 0..0x7F) { "address must be 0-127" }
        writeReg(REG_SUBADR1 + index - 1, address shl 1)
        val mask = 1 shl (4 - index)
        val old = mode1() and 0x7F
        writeReg(REG_MODE1, if (enabled) (old or mask) else (old and mask.inv() and 0xFF))
    }

    /**
     * Return the chip registers to their power-on values by writing the documented defaults
     * (MODE1 = 0x11, MODE2 = 0x04, PRE_SCALE = 0x1E, outputs off). Does not send the general-call
     * software reset (see [softwareReset]).
     *
     * @throws IOException on I²C error
     */
    @Throws(IOException::class)
    fun reset() {
        writeReg(REG_MODE1, 0x10)
        writeReg(REG_PRE_SCALE, 0x1E)
        writeReg(REG_MODE2, 0x04)
        writeReg(REG_ALL_LED, 0, 0, 0, 0x10)
        writeReg(REG_SUBADR1, 0xE2, 0xE4, 0xE8, 0xE0)
        writeReg(REG_MODE1, 0x11)
    }

    /**
     * Read the raw mode registers.
     *
     * @return `[mode1, mode2]`
     * @throws IOException on I²C error
     */
    @Throws(IOException::class)
    fun readMode(): IntArray {
        val d = readReg(REG_MODE1, 2)
        return intArrayOf(d[0].toInt() and 0xFF, d[1].toInt() and 0xFF)
    }
}
