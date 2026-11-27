package it.uhde.periph.chips.light

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * Si1145 proximity / UV index / ambient light sensor (Silicon Labs) — minimal interface.
 *
 * One call runs a forced PS + ALS measurement and returns the proximity count, the UV index and
 * the visible/infrared counts. [init] waits 25 ms, verifies PART_ID ∈ {0x45, 0x46, 0x47}, writes
 * HW_KEY = 0x17, UCOEF0..3 = 7B 6B 01 00, CHLIST = 0xB1 (UV, ALS IR, ALS VIS, PS1),
 * PSLED12_SELECT = 0x01, PS_LED21 = 0x03 (LED1 at level 3 of 15) and IRQ_ENABLE = ALS_IE | PS1_IE
 * (INT stays undriven until [Si1145Full.enableInterrupt]).
 *
 * Configuration is split between registers and the parameter RAM (PARAM_SET / PARAM_QUERY); every
 * command increments the RESPONSE counter. I²C address 0x60 (older silicon: 0x5A). The
 * constructor does not touch the bus.
 *
 * @param connection register-addressed I²C connection bound to 0x60
 */
open class Si1145Minimal(protected val connection: RegisterConnection) {
    companion object {
        const val REG_PART_ID    = 0x00
        const val REG_SEQ_ID     = 0x02
        const val REG_INT_CFG    = 0x03
        const val REG_IRQ_ENABLE = 0x04
        const val REG_HW_KEY     = 0x07
        const val REG_MEAS_RATE0 = 0x08
        const val REG_PS_RATE    = 0x0A
        const val REG_PS_LED21   = 0x0F
        const val REG_PS_LED3    = 0x10
        const val REG_UCOEF0     = 0x13
        const val REG_PARAM_WR   = 0x17
        const val REG_COMMAND    = 0x18
        const val REG_RESPONSE   = 0x20
        const val REG_IRQ_STATUS = 0x21
        const val REG_DATA       = 0x22
        const val REG_PARAM_RD   = 0x2E

        const val HW_KEY = 0x17

        const val CMD_NOP         = 0x00
        const val CMD_RESET       = 0x01
        const val CMD_BUSADDR     = 0x02
        const val CMD_PS_FORCE    = 0x05
        const val CMD_ALS_FORCE   = 0x06
        const val CMD_PSALS_FORCE = 0x07
        const val CMD_PSALS_PAUSE = 0x0B
        const val CMD_PS_AUTO     = 0x0D
        const val CMD_ALS_AUTO    = 0x0E
        const val CMD_PSALS_AUTO  = 0x0F
        const val CMD_GET_CAL     = 0x12
        const val CMD_PARAM_QUERY = 0x80
        const val CMD_PARAM_SET   = 0xA0

        const val PARAM_I2C_ADDR       = 0x00
        const val PARAM_CHLIST         = 0x01
        const val PARAM_PSLED12_SELECT = 0x02
        const val PARAM_PSLED3_SELECT  = 0x03
        const val PARAM_PS_ENCODING    = 0x05
        const val PARAM_ALS_ENCODING   = 0x06
        const val PARAM_PS1_ADCMUX     = 0x07
        const val PARAM_PS_ADC_COUNTER = 0x0A
        const val PARAM_PS_ADC_GAIN    = 0x0B
        const val PARAM_PS_ADC_MISC    = 0x0C
        const val PARAM_AUX_ADCMUX     = 0x0F
        const val PARAM_ALS_VIS_ADC_COUNTER = 0x10
        const val PARAM_ALS_VIS_ADC_GAIN    = 0x11
        const val PARAM_ALS_VIS_ADC_MISC    = 0x12
        const val PARAM_ALS_IR_ADC_COUNTER  = 0x1D
        const val PARAM_ALS_IR_ADC_GAIN     = 0x1E
        const val PARAM_ALS_IR_ADC_MISC     = 0x1F

        const val SOURCE_MASK = 0x3D
        /** Command handshake polls (1 ms each). */
        const val CMD_POLLS = 50
        /** Measurement polls (1 ms each); a gain-7 ALS measurement takes ~200 ms. */
        const val MEASURE_POLLS = 1000

        /** CHLIST.EN_PS1. */
        const val CH_PS1 = 0x01
        /** CHLIST.EN_PS2 (Si1146/47). */
        const val CH_PS2 = 0x02
        /** CHLIST.EN_PS3 (Si1147). */
        const val CH_PS3 = 0x04
        /** CHLIST.EN_ALS_VIS. */
        const val CH_ALS_VIS = 0x10
        /** CHLIST.EN_ALS_IR. */
        const val CH_ALS_IR = 0x20
        /** CHLIST.EN_AUX. */
        const val CH_AUX = 0x40
        /** CHLIST.EN_UV. */
        const val CH_UV = 0x80

        /** Interrupt source: ALS/UV measurement complete. */
        const val SOURCE_ALS = 0x01
        /** Interrupt source: PS1 complete. */
        const val SOURCE_PS1 = 0x04
        /** Interrupt source: PS2 complete (Si1146/47). */
        const val SOURCE_PS2 = 0x08
        /** Interrupt source: PS3 complete (Si1147). */
        const val SOURCE_PS3 = 0x10
        /** Interrupt source: command complete. */
        const val SOURCE_CMD = 0x20

        /** Default UV coefficients: AUX_DATA = UV index × 100. */
        val UCOEF_DEFAULT = intArrayOf(0x7B, 0x6B, 0x01, 0x00)

        internal fun sleepMs(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        internal fun toBytes(values: IntArray): ByteArray = ByteArray(values.size) { values[it].toByte() }

        internal fun forceCommand(proximity: Boolean, als: Boolean): Int = when {
            proximity && als -> CMD_PSALS_FORCE
            proximity -> CMD_PS_FORCE
            else -> CMD_ALS_FORCE
        }
    }

    /**
     * One forced PS + ALS measurement.
     *
     * @property proximity raw PS1 count (rises as an object approaches)
     * @property uvIndex   UV index (AUX_DATA / 100)
     * @property visible   raw visible ambient count
     * @property infrared  raw infrared ambient count
     */
    data class Reading(val proximity: Int, val uvIndex: Double, val visible: Int, val infrared: Int)

    protected var partId = 0x45
    protected var seqId = 0
    protected var chList = 0
    protected var irqEnable = 0

    protected fun readByte(reg: Int): Int = connection.read(reg, 1)[0].toInt() and 0xFF

    protected fun writeByte(reg: Int, value: Int) = connection.write(reg, byteArrayOf(value.toByte()))

    /**
     * Verifies the chip, unlocks it and configures UV, ALS and PS1.
     *
     * @throws IOException if PART_ID is not 0x45/0x46/0x47, a command fails or on a bus error
     */
    open fun init() {
        sleepMs(25)
        val part = readByte(REG_PART_ID)
        if (part !in 0x45..0x47) {
            throw IOException(String.format("Unexpected Si1145 PART_ID 0x%02X, expected 0x45/0x46/0x47", part))
        }
        partId = part
        seqId = readByte(REG_SEQ_ID)
        applyDefaults()
    }

    protected fun applyDefaults() {
        writeByte(REG_HW_KEY, HW_KEY)
        connection.write(REG_UCOEF0, toBytes(UCOEF_DEFAULT))
        val list = CH_UV or CH_ALS_IR or CH_ALS_VIS or CH_PS1
        paramSet(PARAM_CHLIST, list)
        chList = list
        paramSet(PARAM_PSLED12_SELECT, 0x01)
        writeByte(REG_PS_LED21, 0x03)
        irqEnable = SOURCE_ALS or SOURCE_PS1
        writeByte(REG_IRQ_ENABLE, irqEnable)
    }

    protected fun counter(): Int {
        var r = readByte(REG_RESPONSE)
        if (r and 0x80 != 0) {
            writeByte(REG_COMMAND, CMD_NOP)
            r = readByte(REG_RESPONSE)
        }
        return r and 0x0F
    }

    protected fun command(cmd: Int) {
        val before = counter()
        writeByte(REG_COMMAND, cmd)
        repeat(CMD_POLLS) {
            val r = readByte(REG_RESPONSE)
            if (r and 0x80 != 0) {
                writeByte(REG_COMMAND, CMD_NOP)
                throw IOException(String.format("Si1145 command 0x%02X error 0x%02X", cmd, r))
            }
            if (r and 0x0F != before) return
            sleepMs(1)
        }
        throw IOException(String.format("Si1145 command 0x%02X timeout", cmd))
    }

    protected fun paramSet(offset: Int, value: Int) {
        writeByte(REG_PARAM_WR, value)
        command(CMD_PARAM_SET or (offset and 0x1F))
    }

    protected fun paramQuery(offset: Int): Int {
        command(CMD_PARAM_QUERY or (offset and 0x1F))
        return readByte(REG_PARAM_RD)
    }

    protected fun clearIrq(): Int {
        val status = readByte(REG_IRQ_STATUS)
        if (status != 0) writeByte(REG_IRQ_STATUS, status)
        return status
    }

    protected fun measure(proximity: Boolean, als: Boolean) {
        clearIrq()
        counter()
        writeByte(REG_COMMAND, forceCommand(proximity, als))
        var wait = 0
        if (als && chList and 0xF0 != 0) wait = wait or SOURCE_ALS
        if (proximity) wait = wait or ((chList and 0x07) shl 2)
        wait = wait and irqEnable
        if (wait == 0) {
            sleepMs(100)
            return
        }
        var seen = 0
        repeat(MEASURE_POLLS) {
            seen = seen or clearIrq()
            if (seen and wait == wait) return
            sleepMs(1)
        }
        throw IOException("Si1145 measurement timeout")
    }

    protected fun readData(): IntArray {
        val b = connection.read(REG_DATA, 12)
        return IntArray(6) { (b[2 * it].toInt() and 0xFF) or ((b[2 * it + 1].toInt() and 0xFF) shl 8) }
    }

    /**
     * Runs one forced PS + ALS measurement (PSALS_FORCE).
     *
     * @return proximity, UV index, visible and infrared values
     * @throws IOException if a command fails, on timeout or on a bus error
     */
    fun read(): Reading {
        measure(true, true)
        val v = readData()
        return Reading(v[2], v[5] / 100.0, v[0], v[1])
    }
}
