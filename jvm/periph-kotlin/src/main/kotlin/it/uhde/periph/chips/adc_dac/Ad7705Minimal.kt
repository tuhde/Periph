package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * AD7705 — 2-channel, 16-bit sigma-delta ADC with SPI interface (minimal driver).
 *
 * Reads calibrated voltage from Channel 1 with sensible defaults. The driver
 * uses the chip's two-phase register-access protocol (Communication Register
 * write followed by the data transfer within one CS assertion) and polls the
 * 0/DRDY bit of the Communication Register over SPI rather than requiring a
 * dedicated DRDY GPIO.
 *
 * Sensible defaults baked in at construction: Channel 1 selected, gain 1,
 * bipolar, unbuffered analog input, 50 Hz output rate on a 2.4576/4.9152 MHz
 * clock (or 20 Hz on 1/2 MHz), with Channel 1 self-calibrated once.
 *
 * @property connection SPI connection bound to the device.
 * @property vref externally-supplied reference voltage in V.
 * @property mclkHz master clock frequency in Hz.
 */
open class Ad7705Minimal(
    protected val connection: Connection,
    protected val vref: Float,
    protected val mclkHz: Int,
) {
    companion object {
        /** Master clock frequency 1 MHz. */
        const val MCLK_1MHZ      = 1000000
        /** Master clock frequency 2 MHz. */
        const val MCLK_2MHZ      = 2000000
        /** Master clock frequency 2.4576 MHz. */
        const val MCLK_2_4576MHZ = 2457600
        /** Master clock frequency 4.9152 MHz. */
        const val MCLK_4_9152MHZ = 4915200

        protected const val REG_COMM   = 0x00
        protected const val REG_SETUP  = 0x10
        protected const val REG_CLOCK  = 0x20
        protected const val REG_DATA   = 0x30
        protected const val REG_OFFSET = 0x60
        protected const val REG_GAIN   = 0x70

        protected const val RW_WRITE = 0x00
        protected const val RW_READ  = 0x08

        protected const val CH1 = 0x00
        protected const val CH2 = 0x01

        protected const val MODE_NORMAL   = 0x00
        protected const val MODE_SELF_CAL = 0x40
        protected const val MODE_ZERO_SYS = 0x80
        protected const val MODE_FULL_SYS = 0xC0

        protected val GAIN_BITS = intArrayOf(0x00, 0x08, 0x10, 0x18, 0x20, 0x28, 0x30, 0x38)

        protected const val BIPOLAR    = 0x00
        protected const val UNIPOLAR   = 0x04
        protected const val UNBUFFERED = 0x00
        protected const val BUFFERED   = 0x02
        protected const val FSYNC_RUN  = 0x00
        protected const val STBY_RUN   = 0x00
        protected const val STBY_SLEEP = 0x04
        protected const val DRDY_MASK  = 0x80

        protected val FS_RATES_1MHZ   = intArrayOf(20, 25, 100, 200)
        protected val FS_RATES_2_4MHZ = intArrayOf(50, 60, 250, 500)

        protected fun commByte(reg: Int, read: Boolean, channel: Int): Int =
            (reg or (if (read) RW_READ else RW_WRITE) or (channel and 0x03)) and 0xFF
    }

    /** Currently-configured PGA gain. */
    var gain: Int = 1
        protected set
    /** True if bipolar mode (offset binary), false if unipolar. */
    var bipolar: Boolean = true
        protected set
    /** True if the analog input buffer is enabled. */
    var buffered: Boolean = false
        protected set

    init {
        require(mclkHz == MCLK_1MHZ || mclkHz == MCLK_2MHZ || mclkHz == MCLK_2_4576MHZ || mclkHz == MCLK_4_9152MHZ) {
            "mclkHz must be 1000000, 2000000, 2457600, or 4915200"
        }

        val defaultRate = if (mclkHz >= MCLK_2_4576MHZ) FS_RATES_2_4MHZ[0] else FS_RATES_1MHZ[0]
        configureClock(defaultRate)

        val setup = MODE_SELF_CAL or GAIN_BITS[0] or BIPOLAR or UNBUFFERED or FSYNC_RUN
        writeRegChannel(REG_SETUP, setup, CH1, 1)
        waitDrdy()
    }

    protected fun waitDrdy() {
        while (true) {
            val buf = connection.writeRead(byteArrayOf(commByte(REG_COMM, true, CH1).toByte()), 1)
            if ((buf[0].toInt() and DRDY_MASK) == 0) return
        }
    }

    protected fun configureClock(outputRateHz: Int) {
        val clkBit = if (mclkHz >= MCLK_2_4576MHZ) 0x04 else 0x00
        val clkdivBit = if (mclkHz == MCLK_2MHZ || mclkHz == MCLK_4_9152MHZ) 0x08 else 0x00
        val rates = if (mclkHz >= MCLK_2_4576MHZ) FS_RATES_2_4MHZ else FS_RATES_1MHZ
        val fsBits = rates.indexOf(outputRateHz).coerceAtLeast(0)
        writeRegChannel(REG_CLOCK, clkdivBit or clkBit or fsBits, CH1, 1)
    }

    protected fun writeRegChannel(reg: Int, value: Int, channel: Int, nBytes: Int) {
        val buf = ByteArray(1 + nBytes)
        buf[0] = commByte(reg, false, channel).toByte()
        for (i in nBytes - 1 downTo 0) {
            buf[1 + (nBytes - 1 - i)] = ((value shr (8 * i)) and 0xFF).toByte()
        }
        connection.write(buf)
    }

    protected fun readRegChannel(reg: Int, channel: Int, nBytes: Int): Int {
        val comm = byteArrayOf(commByte(reg, true, channel).toByte())
        val raw = connection.writeRead(comm, nBytes)
        var value = 0
        for (i in 0 until nBytes) {
            value = (value shl 8) or (raw[i].toInt() and 0xFF)
        }
        return value
    }

    protected fun codeToVoltage(code: Int, gainValue: Int, bipolarFlag: Boolean): Float {
        return if (bipolarFlag) {
            ((code - 32768).toFloat() / 32768.0f) * (vref / gainValue.toFloat())
        } else {
            (code.toFloat() / 65536.0f) * (vref / gainValue.toFloat())
        }
    }

    /** Block until DRDY, then read and return the raw 16-bit Data Register code on Channel 1. */
    fun readRaw(): Int {
        waitDrdy()
        return readRegChannel(REG_DATA, CH1, 2)
    }

    /** Block until DRDY, then return the input voltage on Channel 1 in V. */
    fun readVoltage(): Float {
        val code = readRaw()
        return codeToVoltage(code, gain, bipolar)
    }
}
