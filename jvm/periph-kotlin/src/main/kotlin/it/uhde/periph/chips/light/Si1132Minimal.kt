package it.uhde.periph.chips.light

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * Si1132 — UV index and ambient light sensor (Silicon Labs), minimal driver.
 *
 * The chip is a small sequencer: configuration lives partly in registers and partly in a parameter
 * RAM written with `PARAM_WR` plus a `PARAM_SET` command. Construction waits 25 ms, verifies
 * `PART_ID = 0x32`, writes `HW_KEY`, the default UV coefficients and enables the UV, infrared and
 * visible channels in forced mode (gain 0, normal range). [read] runs a forced measurement, waits
 * for `ALS_INT` (timeout 500 ms) and returns the UV index (`AUX_DATA / 100`) plus the raw 16-bit
 * visible and infrared counts. I²C address `0x60` (fixed).
 *
 * ## Configuration defaults
 * - Forced mode, UV + infrared + visible channels, gain 0, normal signal range
 * - UV coefficients at the datasheet defaults (`7B 6B 01 00`)
 * - `ALS_IE` set so completed measurements are flagged; INT pin released
 *
 * @param conn I²C register connection bound to 0x60
 * @throws IOException on bus error, a `PART_ID` mismatch or a command error
 */
open class Si1132Minimal(protected val conn: RegisterConnection) {
    companion object {
        const val REG_PART_ID    = 0x00
        const val REG_SEQ_ID     = 0x02
        const val REG_INT_CFG    = 0x03
        const val REG_IRQ_ENABLE = 0x04
        const val REG_HW_KEY     = 0x07
        const val REG_MEAS_RATE0 = 0x08
        const val REG_MEAS_RATE1 = 0x09
        const val REG_MEAS_RATE_SWAPPED = 0x0A
        const val REG_UCOEF      = 0x13
        const val REG_PARAM_WR   = 0x17
        const val REG_COMMAND    = 0x18
        const val REG_RESPONSE   = 0x20
        const val REG_IRQ_STATUS = 0x21
        const val REG_DATA       = 0x22

        const val PART_ID_VALUE  = 0x32
        const val HW_KEY_VALUE   = 0x17

        const val CMD_PARAM_SET  = 0xA0
        const val CMD_NOP        = 0x00
        const val CMD_RESET      = 0x01
        const val CMD_BUSADDR    = 0x02
        const val CMD_ALS_FORCE  = 0x06
        const val CMD_ALS_PAUSE  = 0x0A
        const val CMD_ALS_AUTO   = 0x0E
        const val CMD_GET_CAL    = 0x12

        const val PARAM_I2C_ADDR     = 0x00
        const val PARAM_CHLIST       = 0x01
        const val PARAM_ALS_ENCODING = 0x06
        const val PARAM_AUX_ADCMUX   = 0x0F
        const val PARAM_VIS_COUNTER  = 0x10
        const val PARAM_VIS_GAIN     = 0x11
        const val PARAM_VIS_MISC     = 0x12
        const val PARAM_IR_COUNTER   = 0x1D
        const val PARAM_IR_GAIN      = 0x1E
        const val PARAM_IR_MISC      = 0x1F

        const val CHLIST_ALL     = 0xB0
        const val CHLIST_AUX     = 0x40
        const val IRQ_ALS_INT    = 0x01
        const val IRQ_CLEAR      = 0x21
        const val RESP_ERROR     = 0x80
        const val TIMEOUT_MS     = 500
    }

    protected var chlist = CHLIST_ALL
    protected var seqId = 0

    init {
        sleepMs(25)
        if (readReg(REG_PART_ID) != PART_ID_VALUE) throw IOException("Si1132 not found (PART_ID mismatch)")
        seqId = readReg(REG_SEQ_ID)
        writeReg(REG_HW_KEY, HW_KEY_VALUE)
        conn.write(REG_UCOEF, byteArrayOf(0x7B, 0x6B, 0x01, 0x00))
        // ALS_IE lets IRQ_STATUS.ALS_INT flag completed measurements; INT stays released (INT_OE = 0).
        writeReg(REG_IRQ_ENABLE, IRQ_ALS_INT)
        chlist = CHLIST_ALL
        paramSet(PARAM_CHLIST, chlist)
    }

    protected fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    protected fun writeReg(reg: Int, value: Int) = conn.write(reg, byteArrayOf(value.toByte()))

    protected fun readReg(reg: Int): Int = conn.read(reg, 1)[0].toInt() and 0xFF

    protected fun waitResponse(before: Int) {
        repeat(20) {
            val resp = readReg(REG_RESPONSE)
            if ((resp and RESP_ERROR) != 0) {
                writeReg(REG_COMMAND, CMD_NOP)
                throw IOException("Si1132 command error 0x${Integer.toHexString(resp)}")
            }
            if ((resp and 0x0F) != before) return
            sleepMs(1)
        }
        throw IOException("Si1132 command timeout")
    }

    protected fun command(cmd: Int) {
        val before = readReg(REG_RESPONSE) and 0x0F
        writeReg(REG_COMMAND, cmd)
        waitResponse(before)
    }

    protected fun paramSet(offset: Int, value: Int) {
        val before = readReg(REG_RESPONSE) and 0x0F
        writeReg(REG_PARAM_WR, value)
        writeReg(REG_COMMAND, CMD_PARAM_SET or (offset and 0x1F))
        waitResponse(before)
    }

    protected fun startMeasurement() {
        writeReg(REG_IRQ_STATUS, IRQ_CLEAR)
        command(CMD_ALS_FORCE)
    }

    protected fun waitReady() {
        repeat(TIMEOUT_MS) {
            if ((readReg(REG_IRQ_STATUS) and IRQ_ALS_INT) != 0) {
                writeReg(REG_IRQ_STATUS, IRQ_CLEAR)
                return
            }
            sleepMs(1)
        }
        throw IOException("Si1132 measurement timeout")
    }

    /** Read the data block `0x22`–`0x2D`: {visible, infrared, aux}. */
    protected fun readData(): IntArray {
        val d = conn.read(REG_DATA, 12)
        return intArrayOf(
            (d[0].toInt() and 0xFF) or ((d[1].toInt() and 0xFF) shl 8),
            (d[2].toInt() and 0xFF) or ((d[3].toInt() and 0xFF) shl 8),
            (d[10].toInt() and 0xFF) or ((d[11].toInt() and 0xFF) shl 8)
        )
    }

    protected fun measure(): IntArray {
        startMeasurement()
        waitReady()
        return readData()
    }

    /**
     * Run one forced measurement of the enabled channels.
     *
     * @return {UV index (`AUX_DATA / 100`), raw visible count, raw infrared count}
     * @throws IOException on bus error or when the measurement does not finish within 500 ms
     */
    fun read(): DoubleArray {
        val d = measure()
        return doubleArrayOf(d[2] / 100.0, d[0].toDouble(), d[1].toDouble())
    }
}
