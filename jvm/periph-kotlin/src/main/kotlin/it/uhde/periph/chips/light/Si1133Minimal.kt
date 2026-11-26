package it.uhde.periph.chips.light

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * Si1133 UV index and ambient light sensor (Silicon Labs) — minimal interface.
 *
 * One call runs a forced measurement and returns the UV and white-light counts. [init] waits
 * 25 ms, verifies PART_ID = 0x33, sends RESET_SW and configures channel 0 = UV (ADCMUX 0x18,
 * HW_GAIN 9, 24-bit output), channel 1 = white (ADCMUX 0x0B, HW_GAIN 7, 24-bit), CHAN_LIST = 0x03
 * and IRQ_ENABLE = 0x3F (so IRQ_STATUS flags finished channels).
 *
 * All configuration goes through the indirect parameter table (PARAM_SET / PARAM_QUERY); every
 * command is confirmed by the RESPONSE0 counter. Results are raw ADC counts — the datasheet gives
 * no lux or UV-index formula. INT is open-drain and must be high at power-up. I²C address 0x55
 * (AD pulled up) or 0x52 (AD pulled down). The constructor does not touch the bus.
 *
 * @param connection register-addressed I²C connection bound to 0x55 or 0x52
 */
open class Si1133Minimal(protected val connection: RegisterConnection) {
    companion object {
        const val REG_PART_ID    = 0x00
        const val REG_HOSTIN0    = 0x0A
        const val REG_COMMAND    = 0x0B
        const val REG_IRQ_ENABLE = 0x0F
        const val REG_RESPONSE1  = 0x10
        const val REG_RESPONSE0  = 0x11
        const val REG_IRQ_STATUS = 0x12
        const val REG_HOSTOUT0   = 0x13

        const val PART_ID = 0x33

        const val CMD_RESET_CMD_CTR = 0x00
        const val CMD_RESET_SW      = 0x01
        const val CMD_FORCE         = 0x11
        const val CMD_PAUSE         = 0x12
        const val CMD_START         = 0x13
        const val CMD_PARAM_QUERY   = 0x40
        const val CMD_PARAM_SET     = 0x80

        const val PARAM_I2C_ADDR     = 0x00
        const val PARAM_CHAN_LIST    = 0x01
        const val PARAM_ADCCONFIG0   = 0x02
        const val PARAM_ADCSENS0     = 0x03
        const val PARAM_ADCPOST0     = 0x04
        const val PARAM_MEASCONFIG0  = 0x05
        const val PARAM_MEASRATE_H   = 0x1A
        const val PARAM_MEASRATE_L   = 0x1B
        const val PARAM_MEASCOUNT0   = 0x1C
        const val PARAM_THRESHOLD0_H = 0x25
        const val PARAM_BURST        = 0x2B

        /** Command handshake polls (1 ms each). */
        const val CMD_POLLS = 50
        /** Measurement polls (1 ms each); covers HW_GAIN 11 with SW_GAIN 128. */
        const val MEASURE_POLLS = 15000

        /** RESPONSE0: sequencer running. */
        const val RESP_RUNNING = 0x80
        /** RESPONSE0: suspended. */
        const val RESP_SUSPEND = 0x40
        /** RESPONSE0: sleeping (idle). */
        const val RESP_SLEEP   = 0x20
        /** RESPONSE0: command error. */
        const val RESP_CMD_ERR = 0x10

        /** ADCMUX: small IR photodiode. */
        const val MUX_SMALL_IR    = 0x00
        /** ADCMUX: medium IR photodiode. */
        const val MUX_MEDIUM_IR   = 0x01
        /** ADCMUX: large IR photodiode. */
        const val MUX_LARGE_IR    = 0x02
        /** ADCMUX: white photodiode. */
        const val MUX_WHITE       = 0x0B
        /** ADCMUX: large white photodiode. */
        const val MUX_LARGE_WHITE = 0x0D
        /** ADCMUX: UV photodiode. */
        const val MUX_UV          = 0x18
        /** ADCMUX: deep UV photodiode. */
        const val MUX_UV_DEEP     = 0x19

        internal fun sleepMs(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /** UV and white counts of one forced measurement (signed 24-bit raw counts of channels 0 and 1). */
    data class Reading(val uv: Int, val white: Int)

    protected var chanList = 0
    protected var bits24 = 0
    protected var irqEnable = 0

    protected fun readByte(reg: Int): Int = connection.read(reg, 1)[0].toInt() and 0xFF

    protected fun writeByte(reg: Int, value: Int) = connection.write(reg, byteArrayOf(value.toByte()))

    /**
     * Verify the chip, reset it and configure the UV and white channels.
     *
     * @throws IOException if PART_ID is not 0x33, a command fails or on a bus error
     */
    open fun init() {
        sleepMs(25)
        val part = readByte(REG_PART_ID)
        if (part != PART_ID) throw IOException("Unexpected Si1133 PART_ID 0x%02X, expected 0x33".format(part))
        softwareReset()
        applyDefaults()
    }

    protected fun softwareReset() {
        writeByte(REG_COMMAND, CMD_RESET_SW)
        sleepMs(10)
        chanList = 0
        bits24 = 0
        irqEnable = 0
        repeat(CMD_POLLS) {
            if (readByte(REG_RESPONSE0) and RESP_SLEEP != 0) return
            sleepMs(1)
        }
        throw IOException("Si1133 reset timeout")
    }

    protected fun applyDefaults() {
        setChannel(0, MUX_UV, 9, 0, 0, false, true, 0)
        setChannel(1, MUX_WHITE, 7, 0, 0, false, true, 0)
        paramSet(PARAM_CHAN_LIST, 0x03)
        chanList = 0x03
        irqEnable = 0x3F
        writeByte(REG_IRQ_ENABLE, irqEnable)
    }

    private fun counter(): Int {
        var r = readByte(REG_RESPONSE0)
        if (r and RESP_CMD_ERR != 0) {
            writeByte(REG_COMMAND, CMD_RESET_CMD_CTR)
            r = readByte(REG_RESPONSE0)
        }
        return r and 0x0F
    }

    private fun waitCounter(before: Int) {
        repeat(CMD_POLLS) {
            val r = readByte(REG_RESPONSE0)
            if (r and RESP_CMD_ERR != 0) {
                writeByte(REG_COMMAND, CMD_RESET_CMD_CTR)
                throw IOException("Si1133 command error 0x%02X".format(r and 0x1F))
            }
            if (r and 0x0F != before) return
            sleepMs(1)
        }
        throw IOException("Si1133 command timeout")
    }

    protected fun command(cmd: Int) {
        val before = counter()
        writeByte(REG_COMMAND, cmd)
        waitCounter(before)
    }

    protected fun paramSet(address: Int, value: Int) {
        val before = counter()
        connection.write(REG_HOSTIN0, byteArrayOf(value.toByte(), (CMD_PARAM_SET or (address and 0x3F)).toByte()))
        waitCounter(before)
    }

    protected fun paramQuery(address: Int): Int {
        command(CMD_PARAM_QUERY or (address and 0x3F))
        return readByte(REG_RESPONSE1)
    }

    protected fun setChannel(
        channel: Int, adcmux: Int, hwGain: Int, swGain: Int, decimRate: Int,
        highSignal: Boolean, bits24Out: Boolean, postshift: Int,
    ) {
        val base = 4 * channel
        paramSet(PARAM_ADCCONFIG0 + base, (decimRate shl 5) or adcmux)
        paramSet(PARAM_ADCSENS0 + base, (if (highSignal) 0x80 else 0) or (swGain shl 4) or hwGain)
        paramSet(PARAM_ADCPOST0 + base, (if (bits24Out) 0x40 else 0) or (postshift shl 3))
        bits24 = if (bits24Out) bits24 or (1 shl channel) else bits24 and (1 shl channel).inv()
    }

    protected fun readOutputs(): IntArray {
        var n = 0
        val enabled = (0 until 6).filter { chanList and (1 shl it) != 0 }
        for (ch in enabled) n += if (bits24 and (1 shl ch) != 0) 3 else 2
        if (n == 0) return IntArray(0)
        val d = connection.read(REG_HOSTOUT0, n)
        var pos = 0
        return IntArray(enabled.size) { k ->
            val ch = enabled[k]
            if (bits24 and (1 shl ch) != 0) {
                val u = ((d[pos].toInt() and 0xFF) shl 16) or ((d[pos + 1].toInt() and 0xFF) shl 8) or (d[pos + 2].toInt() and 0xFF)
                pos += 3
                (u shl 8) shr 8
            } else {
                val u = ((d[pos].toInt() and 0xFF) shl 8) or (d[pos + 1].toInt() and 0xFF)
                pos += 2
                u
            }
        }
    }

    protected fun measure(): IntArray {
        readByte(REG_IRQ_STATUS)  // clear stale flags
        command(CMD_FORCE)
        val wait = chanList and irqEnable
        if (wait == 0) {
            sleepMs(100)
        } else {
            var seen = 0
            var i = 0
            while (true) {
                seen = seen or readByte(REG_IRQ_STATUS)
                if (seen and wait == wait) break
                if (++i >= MEASURE_POLLS) throw IOException("Si1133 measurement timeout")
                sleepMs(1)
            }
        }
        return readOutputs()
    }

    /**
     * Run one forced measurement and return the UV and white counts of channels 0 and 1.
     *
     * @return signed 24-bit raw counts (near zero or slightly negative in the dark)
     * @throws IOException if channel 0 or 1 is disabled, a command fails or the measurement times out
     */
    fun read(): Reading {
        if (chanList and 0x03 != 0x03) throw IOException("Si1133 channels 0 and 1 must be enabled for read()")
        val v = measure()
        return Reading(v[0], v[1])
    }
}
