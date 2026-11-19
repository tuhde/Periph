package it.uhde.periph.chips.color

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/** Six calibrated AS7262 channel values (floats as delivered by the chip). */
data class As7262Channels(
    val violet: Float, val blue: Float, val green: Float,
    val yellow: Float, val orange: Float, val red: Float,
) {
    /** The channels in the order V, B, G, Y, O, R. */
    fun toArray(): FloatArray = floatArrayOf(violet, blue, green, yellow, orange, red)
}

/** Six raw 16-bit AS7262 channel counts. */
data class As7262RawChannels(
    val violet: Int, val blue: Int, val green: Int,
    val yellow: Int, val orange: Int, val red: Int,
) {
    /** The counts in the order V, B, G, Y, O, R. */
    fun toArray(): IntArray = intArrayOf(violet, blue, green, yellow, orange, red)
}

/**
 * AS7262 — 6-channel visible spectral sensor (ams OSRAM), minimal driver. I²C address 0x49.
 *
 * Measures violet (450 nm), blue (500 nm), green (550 nm), yellow (570 nm), orange (600 nm) and red
 * (650 nm). Every sensor register is a *virtual register* reached through three hardware registers
 * (STATUS 0x00, WRITE 0x01, READ 0x02); this class implements the handshake.
 *
 * ## Configuration defaults (written by [init])
 * - Soft reset first
 * - Gain 16x, `INT_T = 0x3B` (165.2 ms), one-shot bank mode 3
 * - Both LED outputs off, INT pin disabled
 *
 * The constructor does no bus access; call [init] once.
 */
open class As7262Minimal(protected val connection: RegisterConnection) {
    companion object {
        const val HW_STATUS = 0x00
        const val HW_WRITE = 0x01
        const val HW_READ = 0x02
        const val TX_VALID = 0x02
        const val RX_VALID = 0x01

        const val REG_HW_VERSION = 0x00
        const val REG_FW_VERSION = 0x02
        const val REG_CONTROL = 0x04
        const val REG_INT_T = 0x05
        const val REG_TEMP = 0x06
        const val REG_LED = 0x07
        const val REG_RAW = 0x08
        const val REG_CAL = 0x14

        const val CTRL_RST = 0x80
        const val CTRL_INT = 0x40
        const val CTRL_DATA_RDY = 0x02

        const val INT_T_DEFAULT = 0x3B
        const val GAIN_DEFAULT = 2
        const val STEP_MS = 2.8

        fun sleepMs(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun hwRead(reg: Int): Int = connection.read(reg, 1)[0].toInt() and 0xFF

    private fun waitStatus(mask: Int, want: Int) {
        val deadline = System.currentTimeMillis() + 2000
        while (true) {
            if ((hwRead(HW_STATUS) and mask) == want) return
            if (System.currentTimeMillis() >= deadline) throw IOException("AS7262 virtual-register handshake time-out")
        }
    }

    /**
     * Read one virtual register through the STATUS/WRITE/READ handshake.
     *
     * @param reg virtual register address (0x00–0x2B)
     * @return the register byte (0–255)
     * @throws IOException on a bus error or a handshake time-out
     */
    fun readVirtual(reg: Int): Int {
        waitStatus(TX_VALID, 0)
        if ((hwRead(HW_STATUS) and RX_VALID) != 0) hwRead(HW_READ) // drain a stale byte
        connection.write(HW_WRITE, byteArrayOf((reg and 0x7F).toByte()))
        waitStatus(RX_VALID, RX_VALID)
        return hwRead(HW_READ)
    }

    /**
     * Write one virtual register through the STATUS/WRITE handshake.
     *
     * @param reg virtual register address (0x04, 0x05 or 0x07 are writable)
     * @param value byte to write
     * @throws IOException on a bus error or a handshake time-out
     */
    fun writeVirtual(reg: Int, value: Int) {
        waitStatus(TX_VALID, 0)
        connection.write(HW_WRITE, byteArrayOf(((reg and 0x7F) or 0x80).toByte()))
        waitStatus(TX_VALID, 0)
        connection.write(HW_WRITE, byteArrayOf(value.toByte()))
    }

    private fun waitBoot() {
        val deadline = System.currentTimeMillis() + 2000
        while (true) {
            try {
                if (readVirtual(REG_HW_VERSION) == 0x40) return
            } catch (_: IOException) {
                // retry until the chip answers
            }
            if (System.currentTimeMillis() >= deadline) throw IOException("AS7262 not found (HW_VERSION != 0x40)")
            sleepMs(10)
        }
    }

    /** Soft-reset and wait until `CONTROL_SETUP.RST` clears (time-out 1 s). */
    protected fun reset() {
        writeVirtual(REG_CONTROL, CTRL_RST)
        val deadline = System.currentTimeMillis() + 1000
        while (true) {
            sleepMs(10)
            try {
                if ((readVirtual(REG_CONTROL) and CTRL_RST) == 0) return
            } catch (_: IOException) {
                // retry until the chip answers
            }
            if (System.currentTimeMillis() >= deadline) throw IOException("AS7262 reset time-out")
        }
    }

    /**
     * Wait for the chip to boot, soft-reset it and write the Minimal defaults.
     *
     * @throws IOException if the chip is missing or does not answer
     */
    fun init() {
        waitBoot()
        reset()
        writeVirtual(REG_INT_T, INT_T_DEFAULT)
        writeVirtual(REG_LED, 0x00)
        writeVirtual(REG_CONTROL, GAIN_DEFAULT shl 4)
    }

    protected fun startOneShot() {
        val ctrl = readVirtual(REG_CONTROL)
        writeVirtual(REG_CONTROL, (ctrl and 0x70) or (3 shl 2))
    }

    protected fun waitData() {
        val budgetMs = (2 * readVirtual(REG_INT_T) * STEP_MS).toLong() + 1000
        val deadline = System.currentTimeMillis() + budgetMs
        while (true) {
            if ((readVirtual(REG_CONTROL) and CTRL_DATA_RDY) != 0) return
            if (System.currentTimeMillis() >= deadline) throw IOException("AS7262 conversion time-out")
            sleepMs(5)
        }
    }

    protected fun readFloat(firstReg: Int): Float {
        var bits = 0
        for (i in 0 until 4) bits = (bits shl 8) or readVirtual(firstReg + i)
        return java.lang.Float.intBitsToFloat(bits)
    }

    protected fun readCalibrated(): As7262Channels {
        val v = FloatArray(6) { readFloat(REG_CAL + 4 * it) }
        return As7262Channels(v[0], v[1], v[2], v[3], v[4], v[5])
    }

    /**
     * Run one one-shot conversion and return the six calibrated channels.
     *
     * @throws IOException on a bus error or a conversion time-out
     */
    fun read(): As7262Channels {
        startOneShot()
        waitData()
        return readCalibrated()
    }

    /**
     * Read the device temperature.
     *
     * @return temperature in °C
     * @throws IOException on bus error
     */
    fun temperature(): Double = readVirtual(REG_TEMP).toDouble()
}
