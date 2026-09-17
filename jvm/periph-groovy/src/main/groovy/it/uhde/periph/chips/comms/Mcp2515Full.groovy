package it.uhde.periph.chips.comms

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * MCP2515 — stand-alone CAN 2.0B controller with SPI interface (full driver).
 *
 * <p>Extends {@link Mcp2515Minimal} with the complete chip API: explicit TX
 * buffer selection, acceptance filter and mask configuration, operating-mode
 * switching (Normal, Loopback, Listen-Only, Sleep, Configuration), error
 * counter and error-flag access, RX buffer overflow clearing, TX abort, and
 * one-shot mode control.
 *
 * <p>Filter and mask registers can only be written while OPMOD=<code>100</code>
 * (Configuration mode). {@link #setFilter(int, int, boolean)} and
 * {@link #setMask(int, int, boolean)} enter Config mode automatically and
 * return to the previously active mode on completion.
 */
@CompileStatic
class Mcp2515Full extends Mcp2515Minimal {

    /** RX buffer indices. */
    static final int RXB0 = 0
    static final int RXB1 = 1

    /** TX buffer indices. */
    static final int TXB0 = 0
    static final int TXB1 = 1
    static final int TXB2 = 2

    /** Operating-mode names returned by {@link #getMode()}. */
    static final String MODE_NORMAL      = "normal"
    static final String MODE_LOOPBACK    = "loopback"
    static final String MODE_LISTEN_ONLY = "listen_only"
    static final String MODE_SLEEP       = "sleep"
    static final String MODE_CONFIG      = "config"

    // --- EFLG bits ---
    protected static final int EFLG_RX1OVR = 0x80
    protected static final int EFLG_RX0OVR = 0x40
    protected static final int EFLG_TXBO   = 0x20
    protected static final int EFLG_TXEP   = 0x10
    protected static final int EFLG_RXEP   = 0x08
    protected static final int EFLG_TXWAR  = 0x04
    protected static final int EFLG_RXWAR  = 0x02
    protected static final int EFLG_EWARN  = 0x01

    protected static final int REG_EFLG = 0x2D
    protected static final int REG_TEC  = 0x1C
    protected static final int REG_REC  = 0x1D

    private static final int ABAT_TIMEOUT_MS = 50

    Mcp2515Full(Connection connection, int bitrateKbps = 125, int oscMhz = 8) {
        super(connection, bitrateKbps, oscMhz)
    }

    /** Send a CAN frame using an explicit TX buffer (0, 1, or 2). */
    int sendBuffered(int id, byte[] data, boolean extended = false, int buf = 0) {
        return super.sendBuffered(id, data, extended, buf)
    }

    /**
     * Configure one acceptance filter (RXF0..RXF5).
     *
     * <p>Enters Configuration mode, writes the filter, and returns to the
     * previously active mode.
     */
    void setFilter(int filterNum, int id, boolean extended = false) {
        if (filterNum < 0 || filterNum > 5) {
            throw new IllegalArgumentException("filterNum must be 0..5 (got ${filterNum})")
        }
        String prev = getMode()
        setMode(MODE_CONFIG)
        writeFilterId(filterBaseAddr(filterNum), id, extended)
        if (prev != MODE_CONFIG) setMode(prev)
    }

    /**
     * Configure one acceptance mask (RXM0 for RXB0 / filters 0–1, RXM1 for RXB1
     * / filters 2–5).
     *
     * <p>Enters Configuration mode, writes the mask, and returns to the
     * previously active mode.
     */
    void setMask(int maskNum, int mask, boolean extended = false) {
        if (maskNum < 0 || maskNum > 1) {
            throw new IllegalArgumentException("maskNum must be 0..1 (got ${maskNum})")
        }
        String prev = getMode()
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
    void setRxMode(int buf, int mode) {
        if (buf != RXB0 && buf != RXB1) {
            throw new IllegalArgumentException("RX buffer index must be 0 or 1 (got ${buf})")
        }
        if (mode != 0 && mode != 1 && mode != 3) {
            throw new IllegalArgumentException("RX mode must be 0, 1, or 3 (got ${mode})")
        }
        int reg = (buf == RXB0) ? REG_RXB0CTRL : REG_RXB1CTRL
        modifyReg(reg, 0x60, (mode & 0x03) << 5)
    }

    /**
     * Switch the chip to a different operating mode and wait for it to take effect.
     */
    void setMode(String mode) {
        int reqop
        int opmod
        switch (mode) {
            case MODE_NORMAL:
                reqop = REQOP_NORMAL;     opmod = OPMOD_NORMAL
                break
            case MODE_LOOPBACK:
                reqop = REQOP_LOOPBACK;   opmod = OPMOD_LOOPBACK
                break
            case MODE_LISTEN_ONLY:
                reqop = REQOP_LISTEN;     opmod = OPMOD_LISTEN_ONLY
                break
            case MODE_SLEEP:
                reqop = REQOP_SLEEP;      opmod = OPMOD_SLEEP
                break
            case MODE_CONFIG:
                reqop = REQOP_CONFIG;     opmod = OPMOD_CONFIG
                break
            default:
                throw new IllegalArgumentException("Unknown mode: ${mode}")
        }
        int canctrl = readReg(REG_CANCTRL)
        writeReg(REG_CANCTRL, (canctrl & 0x1F) | reqop)
        waitMode(opmod)
    }

    /**
     * @return the current operating mode as a string.
     */
    String getMode() {
        int opmod = readReg(REG_CANSTAT) & 0xE0
        if (opmod == OPMOD_NORMAL)      return MODE_NORMAL
        if (opmod == OPMOD_LOOPBACK)    return MODE_LOOPBACK
        if (opmod == OPMOD_LISTEN_ONLY) return MODE_LISTEN_ONLY
        if (opmod == OPMOD_SLEEP)       return MODE_SLEEP
        if (opmod == OPMOD_CONFIG)      return MODE_CONFIG
        return "unknown(0x" + Integer.toHexString(opmod) + ")"
    }

    /** Issue an SPI RESET command; the device returns to Configuration mode. */
    void reset() {
        byte[] resetBytes = new byte[1]
        resetBytes[0] = (byte) INSTR_RESET
        connection.write(resetBytes)
        sleepMs(RESET_DELAY_MS)
    }

    /**
     * Read TEC (transmit error counter), REC (receive error counter), and EFLG
     * (error-flag register).
     */
    Errors readErrors() {
        int tec  = readReg(REG_TEC)
        int rec  = readReg(REG_REC)
        int eflg = readReg(REG_EFLG)
        return new Errors(tec, rec, eflg)
    }

    /**
     * Clear the RX0OVR or RX1OVR flag in EFLG.
     */
    void clearOverflow(int buf) {
        if (buf == RXB0) {
            modifyReg(REG_EFLG, EFLG_RX0OVR, 0)
        } else if (buf == RXB1) {
            modifyReg(REG_EFLG, EFLG_RX1OVR, 0)
        } else {
            throw new IllegalArgumentException("RX buffer index must be 0 or 1 (got ${buf})")
        }
    }

    /**
     * Abort all pending TX transmissions by setting ABAT in CANCTRL.
     */
    void abortTx() {
        modifyReg(REG_CANCTRL, CANCTRL_ABAT, CANCTRL_ABAT)
        int elapsed = 0
        while (true) {
            int canctrl = readReg(REG_CANCTRL)
            if ((canctrl & CANCTRL_ABAT) == 0) return
            if (elapsed >= ABAT_TIMEOUT_MS) {
                throw new IOException("MCP2515 ABAT did not clear within ${ABAT_TIMEOUT_MS} ms")
            }
            sleepMs(MODE_POLL_MS)
            elapsed += MODE_POLL_MS
        }
    }

    /** Enable or disable one-shot mode. */
    void setOneShot(boolean enable) {
        modifyReg(REG_CANCTRL, CANCTRL_OSM, enable ? CANCTRL_OSM : 0)
    }

    /** Filter base address in the register map (4 bytes each). */
    private static int filterBaseAddr(int n) {
        return n * 4
    }

    /** Mask base address (RXM0=0x20, RXM1=0x24). */
    private static int maskBaseAddr(int n) {
        return n == 0 ? 0x20 : 0x24
    }

    /** Write a 4-byte ID register set (SIDH/SIDL/EID8/EID0). */
    private void writeFilterId(int baseAddr, int id, boolean extended) {
        int sidh, sidl, eid8, eid0
        if (extended) {
            sidh = (id >>> 21) & 0xFF
            sidl = (((id >>> 18) & 0x07) << 5) | 0x08 | ((id >>> 16) & 0x03)
            eid8 = (id >>> 8) & 0xFF
            eid0 = id & 0xFF
        } else {
            sidh = (id >>> 3) & 0xFF
            sidl = (id & 0x07) << 5
            eid8 = 0
            eid0 = 0
        }
        writeReg(baseAddr + 0, sidh)
        writeReg(baseAddr + 1, sidl)
        writeReg(baseAddr + 2, eid8)
        writeReg(baseAddr + 3, eid0)
    }

    /** Snapshot of the chip's error counters and EFLG register. */
    static class Errors {
        /** Transmit error counter, 0..255. */
        final int tec
        /** Receive error counter, 0..255. */
        final int rec
        /** Raw EFLG register value (bit flags). */
        final int eflg

        Errors(int tec, int rec, int eflg) {
            this.tec = tec
            this.rec = rec
            this.eflg = eflg
        }
    }
}
