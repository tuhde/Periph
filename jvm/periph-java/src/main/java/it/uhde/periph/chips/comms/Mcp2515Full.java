package it.uhde.periph.chips.comms;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * MCP2515 — stand-alone CAN 2.0B controller with SPI interface (full driver).
 *
 * <p>Extends {@link Mcp2515Minimal} with the complete chip API: explicit TX
 * buffer selection, acceptance filter and mask configuration, operating-mode
 * switching (Normal, Loopback, Listen-Only, Sleep, Configuration), error
 * counter and error-flag access, RX buffer overflow clearing, TX abort, and
 * one-shot mode control.
 *
 * <p>Filter and mask registers can only be written while OPMOD={@code 100}
 * (Configuration mode). {@link #setFilter(int, int, boolean)} and
 * {@link #setMask(int, int, boolean)} enter Config mode automatically and
 * return to the previously active mode on completion.
 *
 * <p>The accepted bitrate/oscillator pair is the same as
 * {@link Mcp2515Minimal}: 125/250/500/1000 kbit/s × 8/16 MHz.
 */
public class Mcp2515Full extends Mcp2515Minimal {

    /** RX buffer indices. */
    public static final int RXB0 = 0;
    public static final int RXB1 = 1;

    /** TX buffer indices. */
    public static final int TXB0 = 0;
    public static final int TXB1 = 1;
    public static final int TXB2 = 2;

    /** Operating-mode names returned by {@link #getMode()}. */
    public static final String MODE_NORMAL      = "normal";
    public static final String MODE_LOOPBACK    = "loopback";
    public static final String MODE_LISTEN_ONLY = "listen_only";
    public static final String MODE_SLEEP       = "sleep";
    public static final String MODE_CONFIG      = "config";

    // --- EFLG bits ---
    protected static final int EFLG_RX1OVR = 0x80;
    protected static final int EFLG_RX0OVR = 0x40;
    protected static final int EFLG_TXBO   = 0x20;
    protected static final int EFLG_TXEP   = 0x10;
    protected static final int EFLG_RXEP   = 0x08;
    protected static final int EFLG_TXWAR  = 0x04;
    protected static final int EFLG_RXWAR  = 0x02;
    protected static final int EFLG_EWARN  = 0x01;

    protected static final int REG_EFLG = 0x2D;
    protected static final int REG_TEC  = 0x1C;
    protected static final int REG_REC  = 0x1D;

    protected static final int ABAT_TIMEOUT_MS = 50;

    /**
     * Construct the MCP2515 driver with sensible defaults (125 kbit/s, 8 MHz).
     *
     * @param connection SPI connection bound to the device.
     * @throws IOException on SPI error.
     */
    public Mcp2515Full(Connection connection) throws IOException {
        super(connection);
    }

    /**
     * Construct the MCP2515 driver.
     *
     * @param connection  SPI connection bound to the device.
     * @param bitrateKbps bus bit rate in kbit/s; 125, 250, 500, or 1000.
     * @param oscMhz      oscillator frequency in MHz; 8 or 16.
     * @throws IOException on SPI error.
     */
    public Mcp2515Full(Connection connection, int bitrateKbps, int oscMhz) throws IOException {
        super(connection, bitrateKbps, oscMhz);
    }

    /**
     * Send a CAN frame using an explicit TX buffer (0, 1, or 2).
     *
     * @param id       11-bit or 29-bit CAN identifier.
     * @param data     payload, 0–8 bytes.
     * @param extended true for 29-bit identifier.
     * @param buf      TX buffer index, 0..2.
     * @return the TX buffer used.
     * @throws IOException on SPI error or TX timeout.
     */
    public int sendBuffered(int id, byte[] data, boolean extended, int buf) throws IOException {
        return super.sendBuffered(id, data, extended, buf);
    }

    /**
     * Configure one acceptance filter (RXF0..RXF5).
     *
     * <p>Enters Configuration mode, writes the filter, and returns to the
     * previously active mode.
     *
     * @param filterNum filter index, 0..5.
     * @param id        11-bit (standard) or 29-bit (extended) identifier.
     * @param extended  true for 29-bit identifier.
     * @throws IOException on SPI error.
     */
    public void setFilter(int filterNum, int id, boolean extended) throws IOException {
        if (filterNum < 0 || filterNum > 5) {
            throw new IllegalArgumentException("filterNum must be 0..5 (got " + filterNum + ")");
        }
        String prev = getMode();
        setMode(MODE_CONFIG);
        int base = filterBaseAddr(filterNum);
        writeFilterId(base, id, extended);
        if (!MODE_CONFIG.equals(prev)) setMode(prev);
    }

    /**
     * Configure one acceptance mask (RXM0 for RXB0 / filters 0–1, RXM1 for RXB1
     * / filters 2–5).
     *
     * <p>Enters Configuration mode, writes the mask, and returns to the
     * previously active mode.
     *
     * @param maskNum mask index, 0..1.
     * @param mask    11-bit (standard) or 29-bit (extended) mask value.
     * @param extended true for 29-bit identifier.
     * @throws IOException on SPI error.
     */
    public void setMask(int maskNum, int mask, boolean extended) throws IOException {
        if (maskNum < 0 || maskNum > 1) {
            throw new IllegalArgumentException("maskNum must be 0..1 (got " + maskNum + ")");
        }
        String prev = getMode();
        setMode(MODE_CONFIG);
        int base = maskBaseAddr(maskNum);
        writeFilterId(base, mask, extended);
        if (!MODE_CONFIG.equals(prev)) setMode(prev);
    }

    /**
     * Set the RXM[1:0] bits of one RX buffer control register.
     *
     * @param buf  RX buffer index, 0 or 1.
     * @param mode RX mode: 0=accept standard filter matches, 1=accept extended
     *             filter matches, 3=accept all (filters bypassed).
     * @throws IOException on SPI error.
     */
    public void setRxMode(int buf, int mode) throws IOException {
        if (buf != RXB0 && buf != RXB1) {
            throw new IllegalArgumentException("RX buffer index must be 0 or 1 (got " + buf + ")");
        }
        if (mode < 0 || mode > 3 || mode == 2) {
            throw new IllegalArgumentException("RX mode must be 0, 1, or 3 (got " + mode + ")");
        }
        int reg = (buf == RXB0) ? REG_RXB0CTRL : REG_RXB1CTRL;
        modifyReg(reg, 0x60, (mode & 0x03) << 5);
    }

    /**
     * Switch the chip to a different operating mode and wait for it to take effect.
     *
     * @param mode one of "normal", "loopback", "listen_only", "sleep", "config".
     * @throws IOException on SPI error or invalid mode string.
     */
    public void setMode(String mode) throws IOException {
        int reqop;
        int opmod;
        switch (mode) {
            case MODE_NORMAL:
                reqop = REQOP_NORMAL;     opmod = OPMOD_NORMAL;     break;
            case MODE_LOOPBACK:
                reqop = REQOP_LOOPBACK;   opmod = OPMOD_LOOPBACK;   break;
            case MODE_LISTEN_ONLY:
                reqop = REQOP_LISTEN;     opmod = OPMOD_LISTEN_ONLY;break;
            case MODE_SLEEP:
                reqop = REQOP_SLEEP;      opmod = OPMOD_SLEEP;      break;
            case MODE_CONFIG:
                reqop = REQOP_CONFIG;     opmod = OPMOD_CONFIG;     break;
            default:
                throw new IllegalArgumentException("Unknown mode: " + mode);
        }
        int canctrl = readReg(REG_CANCTRL);
        writeReg(REG_CANCTRL, (canctrl & 0x1F) | reqop);
        waitMode(opmod);
    }

    /**
     * @return the current operating mode as a string ("normal", "loopback",
     *         "listen_only", "sleep", "config").
     * @throws IOException on SPI error.
     */
    public String getMode() throws IOException {
        int opmod = readReg(REG_CANSTAT) & 0xE0;
        if (opmod == OPMOD_NORMAL)       return MODE_NORMAL;
        if (opmod == OPMOD_LOOPBACK)     return MODE_LOOPBACK;
        if (opmod == OPMOD_LISTEN_ONLY)  return MODE_LISTEN_ONLY;
        if (opmod == OPMOD_SLEEP)        return MODE_SLEEP;
        if (opmod == OPMOD_CONFIG)       return MODE_CONFIG;
        return "unknown(0x" + Integer.toHexString(opmod) + ")";
    }

    /** Issue an SPI RESET command; the device returns to Configuration mode. */
    public void reset() throws IOException {
        connection.write(new byte[] { (byte) INSTR_RESET });
        sleepMs(RESET_DELAY_MS);
    }

    /**
     * Read TEC (transmit error counter), REC (receive error counter), and EFLG
     * (error-flag register).
     *
     * @return snapshot of the error state.
     * @throws IOException on SPI error.
     */
    public Errors readErrors() throws IOException {
        int tec  = readReg(REG_TEC);
        int rec  = readReg(REG_REC);
        int eflg = readReg(REG_EFLG);
        return new Errors(tec, rec, eflg);
    }

    /**
     * Clear the RX0OVR or RX1OVR flag in EFLG (a sticky overflow indicator that
     * must be cleared before the affected buffer can receive again).
     *
     * @param buf RX buffer index, 0 or 1.
     * @throws IOException on SPI error.
     */
    public void clearOverflow(int buf) throws IOException {
        if (buf == RXB0) {
            modifyReg(REG_EFLG, EFLG_RX0OVR, 0);
        } else if (buf == RXB1) {
            modifyReg(REG_EFLG, EFLG_RX1OVR, 0);
        } else {
            throw new IllegalArgumentException("RX buffer index must be 0 or 1 (got " + buf + ")");
        }
    }

    /**
     * Abort all pending TX transmissions by setting ABAT in CANCTRL. Polls
     * CANCTRL until the chip clears ABAT (i.e. abort completes).
     *
     * @throws IOException on SPI error.
     */
    public void abortTx() throws IOException {
        modifyReg(REG_CANCTRL, CANCTRL_ABAT, CANCTRL_ABAT);
        int elapsed = 0;
        while (true) {
            int canctrl = readReg(REG_CANCTRL);
            if ((canctrl & CANCTRL_ABAT) == 0) return;
            if (elapsed >= ABAT_TIMEOUT_MS) {
                throw new IOException("MCP2515 ABAT did not clear within " + ABAT_TIMEOUT_MS + " ms");
            }
            sleepMs(MODE_POLL_MS);
            elapsed += MODE_POLL_MS;
        }
    }

    /**
     * Enable or disable one-shot mode (no retransmission on error or loss of
     * arbitration).
     *
     * @param enable true to enable OSM, false for normal retransmit behaviour.
     * @throws IOException on SPI error.
     */
    public void setOneShot(boolean enable) throws IOException {
        modifyReg(REG_CANCTRL, CANCTRL_OSM, enable ? CANCTRL_OSM : 0);
    }

    // --- Helpers ---

    /** Filter base address in the register map (4 bytes each). */
    private static int filterBaseAddr(int n) {
        // 0x00..0x03 -> RXF0, 0x04..0x07 -> RXF1, ..., 0x18..0x1B -> RXF5
        return n * 4;
    }

    /** Mask base address (RXM0=0x20, RXM1=0x24). */
    private static int maskBaseAddr(int n) {
        return n == 0 ? 0x20 : 0x24;
    }

    /**
     * Write a 4-byte ID register set (SIDH/SIDL/EID8/EID0). Same layout is used
     * for both filters and masks.
     */
    private void writeFilterId(int baseAddr, int id, boolean extended) throws IOException {
        int sidh, sidl, eid8, eid0;
        if (extended) {
            sidh = (id >> 21) & 0xFF;
            sidl = (((id >> 18) & 0x07) << 5) | 0x08 | ((id >> 16) & 0x03);
            eid8 = (id >> 8) & 0xFF;
            eid0 = id & 0xFF;
        } else {
            sidh = (id >> 3) & 0xFF;
            sidl = (id & 0x07) << 5;
            eid8 = 0;
            eid0 = 0;
        }
        writeReg(baseAddr + 0, sidh);
        writeReg(baseAddr + 1, sidl);
        writeReg(baseAddr + 2, eid8);
        writeReg(baseAddr + 3, eid0);
    }

    /**
     * Snapshot of the chip's error counters and EFLG register.
     */
    public static class Errors {
        public final int tec;
        public final int rec;
        public final int eflg;

        /**
         * @param tec  transmit error counter, 0..255.
         * @param rec  receive error counter, 0..255.
         * @param eflg raw EFLG register value (bit flags).
         */
        public Errors(int tec, int rec, int eflg) {
            this.tec = tec;
            this.rec = rec;
            this.eflg = eflg;
        }
    }
}
