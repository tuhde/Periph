package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * HY3131 — 50,000-count DMM analog front end with SPI interface (minimal driver).
 *
 * Reads AD1 as a differential voltage with sensible defaults. The SPI framing is
 * non-standard (7-bit address + R/W bit, one blank clock on reads, auto-increment),
 * so the driver uses the bare [Connection]'s `write` and `writeRead`. Data-ready is
 * polled via `INTF` over SPI; no IRQ pin is needed.
 *
 * Sensible defaults baked in at construction: crystal oscillator on, bandgap REFO
 * (1.2 V), AD1 input PB2 (+) vs AGND (−), reference REFO/AGND, input gain 0.9,
 * reference gain 1.0, buffers off, no chop, OSR 32768 (5 Hz).
 *
 * @property connection SPI connection (Mode 0, MSB first, CS active low).
 * @property vref voltage on REFO in V (1.2 V from the internal bandgap).
 */
open class Hy3131Minimal(
    protected val connection: Connection,
    protected val vref: Float,
) {
    companion object {
        const val REG_AD1 = 0x00
        const val REG_AD2 = 0x03
        const val REG_LPF = 0x06
        const val REG_RMS = 0x09
        const val REG_PKHMIN = 0x0E
        const val REG_PKHMAX = 0x11
        const val REG_CTSTA = 0x14
        const val REG_CTC = 0x15
        const val REG_CTB = 0x18
        const val REG_CTA = 0x1B
        const val REG_INTF = 0x1E
        const val REG_INTE = 0x1F
        const val REG_R20 = 0x20
        const val REG_R22 = 0x22
        const val REG_R23 = 0x23
        const val REG_R24 = 0x24
        const val REG_R25 = 0x25
        const val REG_R26 = 0x26
        const val REG_R27 = 0x27
        const val REG_R28 = 0x28
        const val REG_R29 = 0x29
        const val REG_R2A = 0x2A
        const val REG_R2F = 0x2F
        const val REG_R30 = 0x30
        const val REG_R31 = 0x31
        const val REG_R33 = 0x33
        const val REG_R34 = 0x34
        const val REG_R35 = 0x35
        const val REG_R37 = 0x37

        /** INTF flag: frequency counter measurement done. */
        const val FLAG_CTF = 0x01
        /** INTF flag: AD2/AD3 conversion done. */
        const val FLAG_AD2F = 0x02
        /** INTF flag: AD1 conversion done. */
        const val FLAG_AD1F = 0x04
        /** INTF flag: low-pass filter result updated. */
        const val FLAG_LPFF = 0x08
        /** INTF flag: RMS result updated. */
        const val FLAG_RMSF = 0x10
        /** INTF flag: brown-out. */
        const val FLAG_BORF = 0x80

        private val AD1_GAINS = doubleArrayOf(0.9, 1.8, 2.7, 3.6)
        private const val TIMEOUT_MS = 2000L

        internal fun sext(value: Long, bits: Int): Long {
            val sign = 1L shl (bits - 1)
            return if (value and sign != 0L) value - (sign shl 1) else value
        }
    }

    protected var flags = 0
    protected var ig = 0
    protected var rg = 0
    protected var dcos = 0.0

    init {
        init()
    }

    /** Reset the chip and apply the Minimal defaults. */
    protected fun init() {
        writeRegister(REG_R37, 0x60)
        sleepMs(5)
        writeRegister(REG_R33, 0x28)
        sleepMs(10)
        writeRegister(REG_R31, 0xC0)
        writeRegister(REG_R30, 0x00)
        sleepMs(5)
        writeRegister(REG_R2F, 0x80)
        writeRegister(REG_R2A, ByteArray(5))
        for (reg in intArrayOf(0x32, REG_R20, 0x21, REG_R34, REG_R35, 0x36, REG_R29, REG_R26, REG_R27, REG_INTE)) {
            writeRegister(reg, 0x00)
        }
        writeRegister(REG_R24, 0xA3)
        writeRegister(REG_R28, 0x11)
        ig = 0
        rg = 0
        dcos = 0.0
        writeRegister(REG_R25, 0x00)
        writeRegister(REG_R22, 0x07)
        writeRegister(REG_R23, 0x80)
        readRegister(REG_INTF, 1)
        flags = 0
    }

    private fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("interrupted", e)
        }
    }

    /**
     * Read [n] consecutive registers starting at [reg] (auto-increment).
     *
     * @param reg start address 0x00–0x37
     * @param n number of bytes
     * @return register contents
     */
    fun readRegister(reg: Int, n: Int): ByteArray {
        val rx = connection.writeRead(byteArrayOf((((reg and 0x7F) shl 1) or 1).toByte()), n + 1)
        return ByteArray(n) { i ->
            (((rx[i].toInt() and 0xFF) shl 1) or ((rx[i + 1].toInt() and 0xFF) shr 7)).toByte()
        }
    }

    /** Write [data] starting at [reg] (auto-increment). */
    fun writeRegister(reg: Int, data: ByteArray) {
        val buf = ByteArray(1 + data.size)
        buf[0] = ((reg and 0x7F) shl 1).toByte()
        data.copyInto(buf, 1)
        connection.write(buf)
    }

    /** Write a single byte [value] to [reg]. */
    fun writeRegister(reg: Int, value: Int) = writeRegister(reg, byteArrayOf(value.toByte()))

    protected fun read1(reg: Int): Int = readRegister(reg, 1)[0].toInt() and 0xFF

    protected fun waitFlag(mask: Int) {
        val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000L
        while (true) {
            flags = flags or read1(REG_INTF)
            if (flags and mask != 0) {
                flags = flags and mask.inv()
                return
            }
            if (System.nanoTime() > deadline) throw IOException("HY3131 conversion timeout")
            sleepMs(1)
        }
    }

    protected fun read24(reg: Int): Long {
        val b = readRegister(reg, 3)
        return (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or ((b[2].toLong() and 0xFF) shl 16)
    }

    /** Report whether a new AD1 conversion is pending (latches INTF). */
    fun dataReady(): Boolean {
        flags = flags or read1(REG_INTF)
        return flags and FLAG_AD1F != 0
    }

    /** Wait for a conversion and return the signed 24-bit AD1 code. */
    fun readRaw(): Int {
        waitFlag(FLAG_AD1F)
        return sext(read24(REG_AD1), 24).toInt()
    }

    /** Read AD1 as a differential input voltage in V. */
    fun readVoltage(): Float {
        val code = readRaw()
        val rgv = if (rg == 0) 1.0 else 0.3333333333
        return ((code / 8388608.0 * rgv - dcos) * vref / AD1_GAINS[ig]).toFloat()
    }
}
