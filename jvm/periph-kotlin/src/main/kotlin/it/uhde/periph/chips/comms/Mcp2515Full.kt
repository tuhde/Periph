package it.uhde.periph.chips.comms

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * MCP2515 — stand-alone CAN 2.0B controller with SPI interface (full driver).
 *
 * Extends [Mcp2515Minimal] with the complete chip API: explicit TX buffer
 * selection, acceptance filter and mask configuration, operating-mode switching
 * (Normal, Loopback, Listen-Only, Sleep, Configuration), error counter and
 * error-flag access, RX buffer overflow clearing, TX abort, and one-shot mode
 * control.
 *
 * Filter and mask registers can only be written while OPMOD=`100` (Configuration
 * mode). [setFilter] and [setMask] enter Config mode automatically and return
 * to the previously active mode on completion.
 *
 * @param connection  SPI connection bound to the device.
 * @param bitrateKbps bus bit rate in kbit/s (125, 250, 500, or 1000).
 * @param oscMhz      oscillator frequency in MHz (8 or 16).
 */
open class Mcp2515Full @JvmOverloads constructor(
    connection: Connection,
    bitrateKbps: Int = 125,
    oscMhz: Int = 8
) : Mcp2515Minimal(connection, bitrateKbps, oscMhz) {

    companion object {
        /** RX buffer indices. */
        const val RXB0 = 0
        const val RXB1 = 1

        /** TX buffer indices. */
        const val TXB0 = 0
        const val TXB1 = 1
        const val TXB2 = 2

        /** Operating-mode names returned by [getMode]. */
        const val MODE_NORMAL      = "normal"
        const val MODE_LOOPBACK    = "loopback"
        const val MODE_LISTEN_ONLY = "listen_only"
        const val MODE_SLEEP       = "sleep"
        const val MODE_CONFIG      = "config"

        // --- EFLG bits ---
        const val EFLG_RX1OVR = 0x80
        const val EFLG_RX0OVR = 0x40
        const val EFLG_TXBO   = 0x20
        const val EFLG_TXEP   = 0x10
        const val EFLG_RXEP   = 0x08
        const val EFLG_TXWAR  = 0x04
        const val EFLG_RXWAR  = 0x02
        const val EFLG_EWARN  = 0x01

        const val REG_EFLG = 0x2D
        const val REG_TEC  = 0x1C
        const val REG_REC  = 0x1D

        private const val ABAT_TIMEOUT_MS = 50

        /** Filter base address in the register map (4 bytes each). */
        private fun filterBaseAddr(n: Int): Int = n * 4

        /** Mask base address (RXM0=0x20, RXM1=0x24). */
        private fun maskBaseAddr(n: Int): Int = if (n == 0) 0x20 else 0x24
    }

    /**
     * Send a CAN frame using an explicit TX buffer (0, 1, or 2).
     *
     * @param id       11-bit or 29-bit CAN identifier.
     * @param data     payload, 0–8 bytes.
     * @param extended true for 29-bit identifier.
     * @param buf      TX buffer index, 0..2.
     * @return the TX buffer used.
     */
    public override fun sendBuffered(id: Int, data: ByteArray?, extended: Boolean, buf: Int): Int {
        return super.sendBuffered(id, data, extended, buf)
    }

    /**
     * Configure one acceptance filter (RXF0..RXF5).
     *
     * Enters Configuration mode, writes the filter, and returns to the
     * previously active mode.
     */
    open fun setFilter(filterNum: Int, id: Int, extended: Boolean = false) {
        require(filterNum in 0..5) { "filterNum must be 0..5 (got $filterNum)" }
        val prev = getMode()
        setMode(MODE_CONFIG)
        writeFilterId(filterBaseAddr(filterNum), id, extended)
        if (prev != MODE_CONFIG) setMode(prev)
    }

    /**
     * Configure one acceptance mask (RXM0 for RXB0 / filters 0–1, RXM1 for RXB1
     * / filters 2–5).
     *
     * Enters Configuration mode, writes the mask, and returns to the
     * previously active mode.
     */
    open fun setMask(maskNum: Int, mask: Int, extended: Boolean = false) {
        require(maskNum in 0..1) { "maskNum must be 0..1 (got $maskNum)" }
        val prev = getMode()
        setMode(MODE_CONFIG)
        writeFilterId(maskBaseAddr(maskNum), mask, extended)
        if (prev != MODE_CONFIG) setMode(prev)
    }

    /**
     * Set the RXM[1:0] bits of one RX buffer control register.
     *
     * @param buf  RX buffer index, 0 or 1.
     * @param mode RX mode: 0=accept standard filter matches, 1=accept extended
     *             filter matches, 3=accept all (filters bypassed).
     */
    open fun setRxMode(buf: Int, mode: Int) {
        require(buf == RXB0 || buf == RXB1) { "RX buffer index must be 0 or 1 (got $buf)" }
        require(mode == 0 || mode == 1 || mode == 3) { "RX mode must be 0, 1, or 3 (got $mode)" }
        val reg = if (buf == RXB0) REG_RXB0CTRL else REG_RXB1CTRL
        modifyReg(reg, 0x60, (mode and 0x03) shl 5)
    }

    /**
     * Switch the chip to a different operating mode and wait for it to take effect.
     *
     * @param mode one of "normal", "loopback", "listen_only", "sleep", "config".
     */
    open fun setMode(mode: String) {
        val reqop: Int
        val opmod: Int
        when (mode) {
            MODE_NORMAL ->       { reqop = REQOP_NORMAL;     opmod = OPMOD_NORMAL }
            MODE_LOOPBACK ->     { reqop = REQOP_LOOPBACK;   opmod = OPMOD_LOOPBACK }
            MODE_LISTEN_ONLY ->  { reqop = REQOP_LISTEN;     opmod = OPMOD_LISTEN_ONLY }
            MODE_SLEEP ->        { reqop = REQOP_SLEEP;      opmod = OPMOD_SLEEP }
            MODE_CONFIG ->       { reqop = REQOP_CONFIG;     opmod = OPMOD_CONFIG }
            else -> throw IllegalArgumentException("Unknown mode: $mode")
        }
        val canctrl = readReg(REG_CANCTRL)
        writeReg(REG_CANCTRL, (canctrl and 0x1F) or reqop)
        waitMode(opmod)
    }

    /**
     * @return the current operating mode as a string ("normal", "loopback",
     *         "listen_only", "sleep", "config").
     */
    open fun getMode(): String {
        val opmod = readReg(REG_CANSTAT) and 0xE0
        return when (opmod) {
            OPMOD_NORMAL ->       MODE_NORMAL
            OPMOD_LOOPBACK ->     MODE_LOOPBACK
            OPMOD_LISTEN_ONLY ->  MODE_LISTEN_ONLY
            OPMOD_SLEEP ->        MODE_SLEEP
            OPMOD_CONFIG ->       MODE_CONFIG
            else -> "unknown(0x${Integer.toHexString(opmod)})"
        }
    }

    /** Issue an SPI RESET command; the device returns to Configuration mode. */
    open fun reset() {
        connection.write(byteArrayOf(INSTR_RESET.toByte()))
        sleepMs(RESET_DELAY_MS)
    }

    /**
     * Read TEC (transmit error counter), REC (receive error counter), and EFLG
     * (error-flag register).
     *
     * @return snapshot of the error state.
     */
    open fun readErrors(): Errors {
        val tec = readReg(REG_TEC)
        val rec = readReg(REG_REC)
        val eflg = readReg(REG_EFLG)
        return Errors(tec, rec, eflg)
    }

    /**
     * Clear the RX0OVR or RX1OVR flag in EFLG (a sticky overflow indicator that
     * must be cleared before the affected buffer can receive again).
     */
    open fun clearOverflow(buf: Int) {
        when (buf) {
            RXB0 -> modifyReg(REG_EFLG, EFLG_RX0OVR, 0)
            RXB1 -> modifyReg(REG_EFLG, EFLG_RX1OVR, 0)
            else -> throw IllegalArgumentException("RX buffer index must be 0 or 1 (got $buf)")
        }
    }

    /**
     * Abort all pending TX transmissions by setting ABAT in CANCTRL. Polls
     * CANCTRL until the chip clears ABAT (i.e. abort completes).
     */
    open fun abortTx() {
        modifyReg(REG_CANCTRL, CANCTRL_ABAT, CANCTRL_ABAT)
        var elapsed = 0
        while (true) {
            val canctrl = readReg(REG_CANCTRL)
            if ((canctrl and CANCTRL_ABAT) == 0) return
            if (elapsed >= ABAT_TIMEOUT_MS) {
                throw IOException("MCP2515 ABAT did not clear within $ABAT_TIMEOUT_MS ms")
            }
            sleepMs(MODE_POLL_MS)
            elapsed += MODE_POLL_MS
        }
    }

    /**
     * Enable or disable one-shot mode (no retransmission on error or loss of
     * arbitration).
     */
    open fun setOneShot(enable: Boolean) {
        modifyReg(REG_CANCTRL, CANCTRL_OSM, if (enable) CANCTRL_OSM else 0)
    }

    /**
     * Write a 4-byte ID register set (SIDH/SIDL/EID8/EID0). Same layout is used
     * for both filters and masks.
     */
    private fun writeFilterId(baseAddr: Int, id: Int, extended: Boolean) {
        val sidh: Int; val sidl: Int; val eid8: Int; val eid0: Int
        if (extended) {
            sidh = (id ushr 21) and 0xFF
            sidl = (((id ushr 18) and 0x07) shl 5) or 0x08 or ((id ushr 16) and 0x03)
            eid8 = (id ushr 8) and 0xFF
            eid0 = id and 0xFF
        } else {
            sidh = (id ushr 3) and 0xFF
            sidl = (id and 0x07) shl 5
            eid8 = 0
            eid0 = 0
        }
        writeReg(baseAddr + 0, sidh)
        writeReg(baseAddr + 1, sidl)
        writeReg(baseAddr + 2, eid8)
        writeReg(baseAddr + 3, eid0)
    }

    /**
     * Snapshot of the chip's error counters and EFLG register.
     */
    data class Errors(
        /** Transmit error counter, 0..255. */
        val tec: Int,
        /** Receive error counter, 0..255. */
        val rec: Int,
        /** Raw EFLG register value (bit flags). */
        val eflg: Int
    )
}
