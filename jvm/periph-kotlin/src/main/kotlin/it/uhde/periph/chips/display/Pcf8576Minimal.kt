package it.uhde.periph.chips.display

import it.uhde.periph.transport.Transport
import java.io.IOException

/**
 * PCF8576 — 40x4 universal LCD segment driver (minimal driver).
 *
 * Drives a single 7-segment LCD display (static or 1:4 multiplex) out of
 * the box. The chip is write-only — the host never reads back. I2C address
 * is 0x38 (SA0 = VSS) or 0x39 (SA0 = VDD).
 *
 * Default configuration: 1:4 multiplex drive mode, 1/3 bias, display
 * enabled, and a 7-segment digit lookup table for the default multiplex mode.
 */
open class Pcf8576Minimal(protected val transport: Transport) {

    companion object {
        const val ADDR_SA0_LOW  = 0x38
        const val ADDR_SA0_HIGH = 0x39

        const val CMD_MODE_SET      = 0x40
        const val CMD_LOAD_PTR      = 0x00
        const val CMD_DEVICE_SELECT = 0x60
        const val CMD_BANK_SELECT   = 0x78
        const val CMD_BLINK_SELECT  = 0x70

        const val MODE_1_4    = 0x00
        const val MODE_STATIC = 0x01
        const val MODE_1_2    = 0x02
        const val MODE_1_3    = 0x03

        const val BIAS_1_3 = 0x00
        const val BIAS_1_2 = 0x04

        const val DISPLAY_OFF = 0x00
        const val DISPLAY_ON  = 0x08

        val SEVEN_SEG = intArrayOf(
            0xED, 0x60, 0xA7, 0xE3, 0x6A,
            0xCB, 0xCF, 0xE0, 0xEF, 0xEB,
        )
    }

    protected var backplanes: Int = MODE_1_4

    init {
        doClear()
    }

    protected fun cmdMode(enable: Boolean, bias: Int, mode: Int): Int {
        return CMD_MODE_SET or (if (enable) DISPLAY_ON else DISPLAY_OFF) or bias or mode
    }

    protected fun sendCommands(vararg cmds: Int) {
        val buf = ByteArray(cmds.size)
        for (i in cmds.indices) {
            buf[i] = if (i + 1 < cmds.size) {
                (0x80 or (cmds[i] and 0x7F)).toByte()
            } else {
                (cmds[i] and 0x7F).toByte()
            }
        }
        transport.write(buf)
    }

    protected fun sendCommandsWithData(cmd: Int, data: ByteArray) {
        val buf = ByteArray(1 + data.size)
        buf[0] = (cmd and 0x7F).toByte()
        System.arraycopy(data, 0, buf, 1, data.size)
        transport.write(buf)
    }

    private fun doClear() {
        sendCommands(cmdMode(true, BIAS_1_3, MODE_1_4))
        val zeros = ByteArray(20)
        sendCommandsWithData(CMD_LOAD_PTR, zeros)
    }

    /**
     * Zero all 40 columns of display RAM; all segments off.
     */
    fun clear() {
        doClear()
    }

    /**
     * Set the data pointer to [address] and write raw data bytes.
     *
     * @param address RAM column address, 0-39
     * @param data    bytes to write to display RAM; one byte covers two
     *                adjacent columns in 1:4 multiplex mode
     */
    fun writeRaw(address: Int, data: ByteArray) {
        if (data.isEmpty()) return
        sendCommandsWithData(CMD_LOAD_PTR or (address and 0x3F), data)
    }

    /**
     * Write one 7-segment byte at column [position] * 2.
     *
     * @param position digit index, 0-19. Maps to RAM address [position] * 2
     * @param segments 7-segment byte (a/c/b/DP/f/e/g/d packed, MSB-first).
     *                 Add 0x10 to set the decimal point.
     */
    fun setDigit7seg(position: Int, segments: Int) {
        writeRaw(position * 2, byteArrayOf((segments and 0xFF).toByte()))
    }
}
