package it.uhde.periph.chips.other

import it.uhde.periph.connection.RegisterConnection

/**
 * PCA9541 — 2-to-1 I²C-bus master selector (NXP) — minimal interface.
 *
 * Two upstream masters share one downstream bus of slave devices through the
 * PCA9541's pass gates; this driver is used by **one** of the two masters (the
 * same software runs on both). There is no arbitration: whichever master writes
 * its CONTROL register last before a STOP wins. Each master sees its own copy of
 * the IE, CONTROL and ISTAT registers behind the same addresses. The 7-bit I²C
 * address is 0x70 + A (A3..A0); prefer 0x70–0x77.
 *
 * Construction reads CONTROL as a bus check (it throws if the chip does not
 * acknowledge) and ISTAT once to clear stale flags; it writes nothing. The
 * CONTROL byte to write depends on the read-only complement bits, so every
 * change reads CONTROL first and applies the datasheet's Table 12 rule.
 *
 * @param connection I²C register connection bound to the device (0x70–0x77)
 */
open class PCA9541Minimal(protected val connection: RegisterConnection) {

    companion object {
        /** 7-bit I²C address with A3..A0 = 0. */
        const val DEFAULT_ADDRESS = 0x70

        const val REG_IE = 0x00
        const val REG_CONTROL = 0x01
        const val REG_ISTAT = 0x02
        const val AI = 0x10
        const val C_MYBUS = 0x01
        const val C_NMYBUS = 0x02
        const val C_BUSON = 0x04
        const val C_NBUSON = 0x08
        const val C_BUSINIT = 0x10
        const val C_TESTON = 0x40
        const val C_NTESTON = 0x80

        internal fun hasControlBits(c: Int): Boolean = ((c and C_MYBUS) != 0) == ((c and C_NMYBUS) != 0)
        internal fun connectedBits(c: Int): Boolean = ((c and C_BUSON) != 0) != ((c and C_NBUSON) != 0)
        internal fun nbuson(c: Int): Int = if (c and C_NBUSON != 0) 1 else 0
        internal fun nmybus(c: Int): Int = if (c and C_NMYBUS != 0) 1 else 0

        internal fun sleepMs(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    init {
        readReg(REG_CONTROL)
        readReg(REG_ISTAT)
    }

    protected fun readReg(reg: Int): Int = connection.read(reg, 1)[0].toInt() and 0xFF

    protected fun writeReg(reg: Int, value: Int) {
        connection.write(reg, byteArrayOf(value.toByte()))
    }

    /**
     * Report whether this master's channel is connected to the downstream bus.
     *
     * @return true if MYBUS equals NMYBUS and the bus is connected (BUSON differs from NBUSON)
     */
    fun hasControl(): Boolean {
        val c = readReg(REG_CONTROL)
        return hasControlBits(c) && connectedBits(c)
    }

    /**
     * Take the downstream bus. Reads CONTROL and writes MYBUS / BUSON per the
     * datasheet rule; the write ends with a STOP, which makes the switch. With
     * [busInit] the chip first sends nine clocks, a NACK and a STOP on the
     * downstream bus and flags BUSINIT in ISTAT.
     *
     * @param busInit true to request bus initialization / recovery
     * @param timeoutMs maximum time to wait for the switch, in milliseconds
     * @return true if this master has control afterwards, false if not within [timeoutMs]
     */
    fun acquire(busInit: Boolean = false, timeoutMs: Int = 20): Boolean {
        val c = readReg(REG_CONTROL)
        var w = ((1 - nbuson(c)) shl 2) or nmybus(c)
        if (busInit) w = w or C_BUSINIT
        writeReg(REG_CONTROL, w)
        for (i in 0..timeoutMs) {
            if (busInit && (readReg(REG_ISTAT) and 0x02) == 0) {
                sleepMs(1)
                continue
            }
            if (hasControl()) return true
            sleepMs(1)
        }
        return false
    }

    /** Give the downstream bus to the other master (MYBUS = NOT NMYBUS; the bus stays connected). */
    fun release() {
        val c = readReg(REG_CONTROL)
        writeReg(REG_CONTROL, ((1 - nbuson(c)) shl 2) or (1 - nmybus(c)))
    }
}
