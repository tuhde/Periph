package it.uhde.periph.chips.light

import it.uhde.periph.connection.RegisterConnection

/**
 * APDS-9250 — digital RGB, IR and ambient light sensor (Broadcom) — minimal
 * interface.
 *
 * Four simultaneously integrating photodiode channels (red, green, blue, IR;
 * green doubles as the ambient-light channel) with a 13- to 20-bit ADC, five
 * gains and a 25 ms to 2 s measurement rate. Results are unsigned counts in
 * 3-byte registers (LSB first). The I²C address is fixed at 0x52.
 *
 * Construction reads PART_ID (the upper nibble must be 0xB), writes the
 * defaults (18 bit, 100 ms, gain ×3, interrupt off) and sets LS_EN and CS_MODE.
 * Reading MAIN_STATUS clears its flags, so the driver keeps the interrupt and
 * power-on bits it has seen until [Apds9250Full.pollInterrupt] /
 * [Apds9250Full.status] hands them out.
 *
 * @param connection I²C register connection bound to the device (0x52)
 * @throws IllegalStateException if PART_ID does not identify an APDS-9250
 */
open class Apds9250Minimal(protected val connection: RegisterConnection) {

    /** The four channel counts of one conversion (unsigned, up to 20 bit). */
    data class Counts(val red: Int, val green: Int, val blue: Int, val ir: Int)

    companion object {
        /** Fixed 7-bit I²C address. */
        const val DEFAULT_ADDRESS = 0x52

        /** Wait after enabling the sensor so the support blocks settle, in ms (tests may set it to 0). */
        @Volatile
        @JvmStatic
        var startWaitMs: Int = 5

        /** Poll step while waiting for LS_DATA, in ms (tests may set it to 0). */
        @Volatile
        @JvmStatic
        var pollStepMs: Int = 5

        const val REG_MAIN_CTRL = 0x00
        const val REG_LS_MEAS_RATE = 0x04
        const val REG_LS_GAIN = 0x05
        const val REG_PART_ID = 0x06
        const val REG_MAIN_STATUS = 0x07
        const val REG_DATA_IR = 0x0A
        const val REG_DATA_GREEN = 0x0D
        const val REG_DATA_BLUE = 0x10
        const val REG_DATA_RED = 0x13
        const val REG_INT_CFG = 0x19
        const val REG_INT_PERSISTENCE = 0x1A
        const val REG_THRES_UP = 0x21
        const val REG_THRES_LOW = 0x24
        const val REG_THRES_VAR = 0x27
        const val CTRL_SW_RESET = 0x10
        const val CTRL_CS_MODE = 0x04
        const val CTRL_LS_EN = 0x02
        const val INT_EN = 0x04
        const val INT_VAR_MODE = 0x08
        const val INT_SEL_MASK = 0x30

        /** MAIN_STATUS bit 5: power-up event occurred. */
        const val STATUS_POWER_ON = 0x20

        /** MAIN_STATUS bit 4: interrupt condition fulfilled. */
        const val STATUS_INTERRUPT = 0x10

        /** MAIN_STATUS bit 3: new data available. */
        const val STATUS_DATA_READY = 0x08

        internal fun count(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or ((b[off + 2].toInt() and 0x0F) shl 16)

        internal fun sleepMs(ms: Int) {
            if (ms <= 0) return
            try {
                Thread.sleep(ms.toLong())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    protected var pending: Int = 0
    protected var integrationS: Double = 0.1

    init {
        val part = readReg(REG_PART_ID)
        check((part and 0xF0) == 0xB0) { "APDS-9250 not found (PART_ID 0x%02X)".format(part) }
        writeReg(REG_LS_MEAS_RATE, 0x22)
        writeReg(REG_LS_GAIN, 0x01)
        writeReg(REG_INT_CFG, 0x10)
        writeReg(REG_MAIN_CTRL, CTRL_LS_EN or CTRL_CS_MODE)
        sleepMs(startWaitMs)
    }

    protected fun readReg(reg: Int): Int = connection.read(reg, 1)[0].toInt() and 0xFF

    protected fun writeReg(reg: Int, value: Int) {
        connection.write(reg, byteArrayOf(value.toByte()))
    }

    protected fun updateReg(reg: Int, setBits: Int, clearBits: Int) {
        val value = readReg(reg)
        writeReg(reg, (value and clearBits.inv()) or setBits)
    }

    /** Read MAIN_STATUS once and keep the POWER_ON / LS_INT bits until handed out. */
    protected fun readStatus(): Int {
        val value = readReg(REG_MAIN_STATUS)
        pending = pending or (value and (STATUS_POWER_ON or STATUS_INTERRUPT))
        return value
    }

    protected fun readBlock(): Counts {
        val raw = connection.read(REG_DATA_IR, 12)
        return Counts(red = count(raw, 9), green = count(raw, 3), blue = count(raw, 6), ir = count(raw, 0))
    }

    protected fun readChannel(reg: Int): Int = count(connection.read(reg, 3), 0)

    /**
     * Read all four channels from one conversion. Waits for a new result (at
     * most one integration time plus 50 ms), then block-reads 0x0A–0x15.
     *
     * @return the four unsigned counts
     */
    fun read(): Counts {
        val limitMs = ((integrationS + 0.05) * 1000.0).toInt()
        val step = if (pollStepMs > 0) pollStepMs else 1
        var waited = 0
        while (waited <= limitMs) {
            if ((readStatus() and STATUS_DATA_READY) != 0) break
            sleepMs(pollStepMs)
            waited += step
        }
        return readBlock()
    }
}
