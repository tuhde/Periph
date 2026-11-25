package it.uhde.periph.chips.power

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * MAX17048 / MAX17049 lithium fuel gauge (ModelGauge) — minimal driver.
 *
 * Reports the cell voltage and the state of charge. The constructor makes no bus access; the chip
 * runs with its power-on defaults. The first SOC result is valid about 1 s after power-up.
 *
 * I²C address: 0x36 (fixed). All registers are 16-bit big-endian words.
 *
 * @param connection I²C connection bound to address 0x36
 * @param cells 1 for the MAX17048, 2 for the MAX17049 (scales the voltage)
 */
open class Max17048Minimal(
    protected val connection: RegisterConnection,
    cells: Int = 1
) {
    companion object {
        const val REG_VCELL   = 0x02
        const val REG_SOC     = 0x04
        const val REG_MODE    = 0x06
        const val REG_VERSION = 0x08
        const val REG_HIBRT   = 0x0A
        const val REG_CONFIG  = 0x0C
        const val REG_VALRT   = 0x14
        const val REG_CRATE   = 0x16
        const val REG_VRESET  = 0x18
        const val REG_STATUS  = 0x1A
        const val REG_CMD     = 0xFE
    }

    protected val cellCount: Int = if (cells == 2) 2 else 1

    /**
     * Read the cell voltage.
     *
     * @return voltage in V ((VCELL >> 4) × 1.25 mV × cells)
     * @throws IOException on I²C error
     */
    @Throws(IOException::class)
    fun voltage(): Double = (readWord(REG_VCELL) shr 4) * 1.25e-3 * cellCount

    /**
     * Read the state of charge.
     *
     * @return state of charge in %, clamped to 0.0–100.0 (SOC / 256)
     * @throws IOException on I²C error
     */
    @Throws(IOException::class)
    fun percent(): Double = (readWord(REG_SOC) / 256.0).coerceIn(0.0, 100.0)

    /** Read a 16-bit big-endian register. */
    @Throws(IOException::class)
    protected fun readWord(reg: Int): Int {
        val b = connection.read(reg, 2)
        return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
    }

    /** Write a 16-bit big-endian register. */
    @Throws(IOException::class)
    protected fun writeWord(reg: Int, value: Int) {
        connection.write(reg, byteArrayOf((value shr 8).toByte(), value.toByte()))
    }

    /** Read-modify-write a register: clear the [clear] bits, then set the [set] bits. */
    @Throws(IOException::class)
    protected fun modify(reg: Int, clear: Int, set: Int) {
        writeWord(reg, (readWord(reg) and clear.inv() and 0xFFFF) or set)
    }
}
