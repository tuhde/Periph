package it.uhde.periph.chips.led

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * PCA9685 — 16-channel, 12-bit PWM LED/servo controller (minimal driver).
 *
 * Dims LEDs / drives servos on 16 channels with one call each. I²C address 0x40–0x6F or
 * 0x71–0x77 (A0–A5 pins; 0x70 is the All Call address).
 *
 * Defaults baked in at construction: the chip is put to sleep, PRE_SCALE is set for 1 kHz (5),
 * then it is woken with auto-increment on and ALLCALL off and the oscillator start is waited for
 * (1 ms). Outputs are totem-pole, not inverted, and change on the I²C STOP; all channels keep their
 * power-on state (off).
 *
 * @param connection I²C register connection bound to the chip address (0x40–0x6F, 0x71–0x77)
 * @throws IOException on I²C error during initialization
 */
open class Pca9685Minimal(protected val connection: RegisterConnection) {

    companion object {
        const val CHANNELS = 16

        const val REG_MODE1 = 0x00
        const val REG_MODE2 = 0x01
        const val REG_SUBADR1 = 0x02
        const val REG_ALLCALLADR = 0x05
        const val REG_LED0 = 0x06
        const val REG_ALL_LED = 0xFA
        const val REG_PRE_SCALE = 0xFE

        const val RESTART = 0x80
        const val EXTCLK = 0x40
        const val AI = 0x20
        const val SLEEP = 0x10

        @JvmStatic
        protected fun pauseMs(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        @JvmStatic
        protected fun checkChannel(channel: Int) {
            require(channel in 0..15) { "channel must be 0-15" }
        }

        /** ON/OFF register bytes for a duty cycle; 0.0 → full OFF, 1.0 → full ON. */
        @JvmStatic
        protected fun dutyBytes(duty: Double): IntArray {
            require(duty in 0.0..1.0) { "duty must be 0.0-1.0" }
            val off = Math.floor(duty * 4096 + 0.5).toInt()
            return when {
                off <= 0 -> intArrayOf(0, 0, 0, 0x10)
                off >= 4096 -> intArrayOf(0, 0x10, 0, 0)
                else -> intArrayOf(0, 0, off and 0xFF, off shr 8)
            }
        }
    }

    init {
        writeReg(REG_MODE1, AI or SLEEP)
        writeReg(REG_PRE_SCALE, 5)
        writeReg(REG_MODE1, AI)
        pauseMs(1)
    }

    @Synchronized
    @Throws(IOException::class)
    protected fun writeReg(reg: Int, vararg data: Int) {
        connection.write(reg, ByteArray(data.size) { data[it].toByte() })
    }

    @Synchronized
    @Throws(IOException::class)
    protected fun readReg(reg: Int, length: Int): ByteArray = connection.read(reg, length)

    /**
     * Set the duty cycle of one channel (ON = 0, OFF = round(duty × 4096)); 0.0 writes full OFF and
     * 1.0 writes full ON, so no glitch pulses occur.
     *
     * @param channel 0–15
     * @param duty 0.0–1.0
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if an argument is out of range
     */
    @Throws(IOException::class)
    fun setDuty(channel: Int, duty: Double) {
        checkChannel(channel)
        writeReg(REG_LED0 + 4 * channel, *dutyBytes(duty))
    }
}
