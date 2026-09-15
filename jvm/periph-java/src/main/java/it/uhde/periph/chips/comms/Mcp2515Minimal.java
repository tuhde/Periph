package it.uhde.periph.chips.comms;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * MCP2515 — stand-alone CAN 2.0B controller with SPI interface (minimal driver).
 *
 * <p>Sends and receives CAN 2.0B data frames with 11-bit standard or 29-bit
 * extended identifiers, payload length 0–8 bytes, and a single zero-configuration
 * API. Uses polled operation only (no INT pin).
 *
 * <p>Sensible defaults baked in at construction:
 * <ul>
 *   <li>RXM[1:0]={@code 11} in both RXB0CTRL and RXB1CTRL (accept all messages,
 *       bypasses filters).</li>
 *   <li>BUKT=1 in RXB0CTRL (rollover from RXB0 to RXB1 on overflow).</li>
 *   <li>CANINTE=0x00 (polled operation).</li>
 *   <li>OSM=0 in CANCTRL (retransmit on error or loss of arbitration).</li>
 *   <li>TXB0 is used exclusively for TX; TXP=11 (highest priority).</li>
 * </ul>
 *
 * <p>Bit-timing presets supported (BTLMODE=1, SAM=0, SJW=1 TQ):
 * <ul>
 *   <li>125 / 250 / 500 / 1000 kbit/s at 8 MHz</li>
 *   <li>125 / 250 / 500 / 1000 kbit/s at 16 MHz</li>
 * </ul>
 *
 * @param transport   SPI transport bound to the device (CS managed by the kernel).
 * @param bitrateKbps bus bit rate in kbit/s; 125, 250, 500, or 1000.
 * @param oscMhz      oscillator frequency in MHz; 8 or 16.
 */
public class Mcp2515Minimal {

    // --- SPI instruction set ---
    protected static final int INSTR_RESET       = 0xC0;
    protected static final int INSTR_READ        = 0x03;
    protected static final int INSTR_READ_RX_BUF = 0x90;
    protected static final int INSTR_WRITE       = 0x02;
    protected static final int INSTR_LOAD_TX_BUF = 0x40;
    protected static final int INSTR_RTS         = 0x80;
    protected static final int INSTR_READ_STATUS = 0xA0;
    protected static final int INSTR_RX_STATUS   = 0xB0;
    protected static final int INSTR_BIT_MODIFY  = 0x05;

    // --- Register addresses ---
    protected static final int REG_CANSTAT      = 0x0E;
    protected static final int REG_CANCTRL      = 0x0F;
    protected static final int REG_CNF3         = 0x28;
    protected static final int REG_CNF2         = 0x29;
    protected static final int REG_CNF1         = 0x2A;
    protected static final int REG_CANINTE      = 0x2B;
    protected static final int REG_CANINTF      = 0x2C;
    protected static final int REG_TXB0CTRL     = 0x30;
    protected static final int REG_TXB0SIDH     = 0x31;
    protected static final int REG_TXB0SIDL     = 0x32;
    protected static final int REG_TXB0EID8     = 0x33;
    protected static final int REG_TXB0EID0     = 0x34;
    protected static final int REG_TXB0DLC      = 0x35;
    protected static final int REG_TXB0D0       = 0x36;
    protected static final int REG_RXB0CTRL     = 0x60;
    protected static final int REG_RXB1CTRL     = 0x70;

    // --- CANCTRL bit fields ---
    protected static final int REQOP_NORMAL     = 0x00;
    protected static final int REQOP_SLEEP      = 0x20;
    protected static final int REQOP_LOOPBACK   = 0x40;
    protected static final int REQOP_LISTEN     = 0x60;
    protected static final int REQOP_CONFIG     = 0x80;
    protected static final int CANCTRL_ABAT     = 0x10;
    protected static final int CANCTRL_OSM      = 0x08;

    // --- CANSTAT OPMOD[2:0] values (mirror REQOP encoding) ---
    protected static final int OPMOD_NORMAL     = 0x00;
    protected static final int OPMOD_SLEEP      = 0x20;
    protected static final int OPMOD_LOOPBACK   = 0x40;
    protected static final int OPMOD_LISTEN_ONLY = 0x60;
    protected static final int OPMOD_CONFIG     = 0x80;

    // --- CANINTF / CANINTE bits ---
    protected static final int RX0IF = 0x01;
    protected static final int RX1IF = 0x02;
    protected static final int TX0IF = 0x04;

    // --- TXBnCTRL bits ---
    protected static final int TXREQ = 0x08;
    protected static final int TXP_HIGHEST = 0x03;

    protected static final int RXB0_RXM_ANY = 0x60;
    protected static final int RXB0_BUKT    = 0x04;

    protected static final int TX_RTS_TXB0 = 0x01;

    // --- TX buffer base addresses for LOAD TX BUFFER (instruction = 0x40|n) ---
    protected static final int LOAD_TXB0SIDH = 0x40 | 0x00;

    // --- READ RX BUFFER (instruction = 0x90|n) ---
    protected static final int READ_RXB0SIDH = 0x90 | 0x00;

    // --- DLC bits ---
    protected static final int DLC_RTR = 0x40;

    // --- Pre-computed CNF1/CNF2/CNF3 for (bitrate, F_OSC) pairs ---
    // Indexed by [bitrate index][F_OSC index]; bitrateIdx 0=125, 1=250, 2=500, 3=1000.
    protected static final int[] CNF1_TABLE = {
        0x01, 0x03,  // 125 kbit/s @ 8 MHz, 16 MHz
        0x00, 0x01,  // 250 kbit/s
        0x00, 0x00,  // 500 kbit/s
        0x00, 0x00,  // 1 Mbit/s
    };
    protected static final int[] CNF2_TABLE = {
        0xBA, 0xBA,
        0xBA, 0xBA,
        0x91, 0xBA,
        0x80, 0x91,
    };
    protected static final int[] CNF3_TABLE = {
        0x03, 0x03,
        0x03, 0x03,
        0x01, 0x03,
        0x00, 0x01,
    };

    protected static final int RESET_DELAY_MS = 5;
    protected static final int MODE_POLL_MS = 1;
    protected static final int MODE_TIMEOUT_MS = 50;
    protected static final int TX_POLL_MS = 1;
    protected static final int TX_TIMEOUT_MS = 100;

    protected final Connection connection;
    protected int bitrateKbps;
    protected int oscMhz;
    protected final byte[] rxDataBuf = new byte[8];

    /**
     * Construct the MCP2515 driver with sensible defaults (125 kbit/s, 8 MHz).
     *
     * @param connection SPI connection bound to the device.
     * @throws IOException on SPI error.
     */
    public Mcp2515Minimal(Connection connection) throws IOException {
        this(connection, 125, 8);
    }

    /**
     * Construct the MCP2515 driver and run the initialization sequence.
     *
     * @param connection  SPI connection bound to the device.
     * @param bitrateKbps bus bit rate in kbit/s; 125, 250, 500, or 1000.
     * @param oscMhz      oscillator frequency in MHz; 8 or 16.
     * @throws IOException on SPI error or invalid parameters.
     */
    public Mcp2515Minimal(Connection connection, int bitrateKbps, int oscMhz) throws IOException {
        this.connection = connection;
        this.bitrateKbps = bitrateKbps;
        this.oscMhz = oscMhz;
        init(bitrateKbps, oscMhz);
    }

    /**
     * Re-run the initialization sequence with the given bitrate/oscillator pair.
     *
     * @param bitrateKbps bus bit rate in kbit/s; 125, 250, 500, or 1000.
     * @param oscMhz      oscillator frequency in MHz; 8 or 16.
     * @throws IOException on SPI error or invalid parameters.
     */
    public void init(int bitrateKbps, int oscMhz) throws IOException {
        this.bitrateKbps = bitrateKbps;
        this.oscMhz = oscMhz;
        int bIdx = bitrateIndex(bitrateKbps);
        int oIdx = oscIndex(oscMhz);
        int cnf1 = CNF1_TABLE[bIdx * 2 + oIdx];
        int cnf2 = CNF2_TABLE[bIdx * 2 + oIdx];
        int cnf3 = CNF3_TABLE[bIdx * 2 + oIdx];

        // 1. Reset the chip and wait ≥ 2 µs for POR settling.
        connection.write(new byte[] { (byte) INSTR_RESET });
        sleepMs(RESET_DELAY_MS);

        // 2. Verify Configuration mode.
        int canstat = readReg(REG_CANSTAT);
        if ((canstat & 0xE0) != OPMOD_CONFIG) {
            // Some boards need extra time after RESET; try once more.
            sleepMs(RESET_DELAY_MS);
            canstat = readReg(REG_CANSTAT);
            if ((canstat & 0xE0) != OPMOD_CONFIG) {
                throw new IOException("MCP2515 not in Configuration mode after RESET (CANSTAT=0x"
                        + Integer.toHexString(canstat) + ")");
            }
        }

        // 3. Write bit-timing registers.
        writeReg(REG_CNF1, cnf1);
        writeReg(REG_CNF2, cnf2);
        writeReg(REG_CNF3, cnf3);

        // 4. Acceptance masks all-ones so any ID passes until the user sets filters.
        //    RXM0 (RXB0) and RXM1 (RXB1) are 4-byte registers at 0x20–0x23 and 0x24–0x27.
        writeReg(0x20, 0xFF);
        writeReg(0x21, 0xFF);
        writeReg(0x22, 0xFF);
        writeReg(0x23, 0xFF);
        writeReg(0x24, 0xFF);
        writeReg(0x25, 0xFF);
        writeReg(0x26, 0xFF);
        writeReg(0x27, 0xFF);

        // 5. RXBnCTRL: RXM[1:0]=11 (accept all), BUKT=1 in RXB0CTRL for rollover.
        writeReg(REG_RXB0CTRL, RXB0_RXM_ANY | RXB0_BUKT);
        writeReg(REG_RXB1CTRL, 0x60 /* RXM=11 */);

        // 6. No interrupts — polled operation.
        writeReg(REG_CANINTE, 0x00);

        // 7. Clear any pending interrupt flags.
        writeReg(REG_CANINTF, 0x00);

        // 8. Request Normal mode (REQOP=000), OSM=0.
        writeReg(REG_CANCTRL, REQOP_NORMAL);
        waitMode(OPMOD_NORMAL);
    }

    /** Re-run initialization with the current bitrate/oscillator pair. */
    public void init() throws IOException {
        init(bitrateKbps, oscMhz);
    }

    /**
     * Send a CAN frame using TXB0 (priority TXP=11, highest).
     *
     * <p>Blocks until a TX buffer is free (up to ~10 ms) and the message has
     * been acknowledged. Throws if no buffer becomes free in time.
     *
     * @param id       11-bit (standard) or 29-bit (extended) CAN identifier.
     * @param data     payload, 0–8 bytes.
     * @param extended true for 29-bit identifier, false for 11-bit.
     * @return the TX buffer used (always 0 here).
     * @throws IOException on SPI error or TX timeout.
     */
    public int send(int id, byte[] data, boolean extended) throws IOException {
        return sendBuffered(id, data, extended, 0);
    }

    /**
     * Send a standard (11-bit) CAN frame.
     *
     * @param id   standard CAN identifier, 0..0x7FF.
     * @param data payload, 0–8 bytes.
     * @return the TX buffer used (always 0).
     * @throws IOException on SPI error or TX timeout.
     */
    public int send(int id, byte[] data) throws IOException {
        return send(id, data, false);
    }

    /**
     * Receive one CAN frame, blocking up to {@code timeoutMs} for a frame to arrive.
     *
     * @param timeoutMs timeout in milliseconds; 0 means non-blocking (returns
     *                  {@code null} immediately if no frame is pending).
     * @return the received frame, or {@code null} on timeout.
     * @throws IOException on SPI error.
     */
    public CanFrame recv(int timeoutMs) throws IOException {
        int elapsed = 0;
        while (true) {
            int intf = readReg(REG_CANINTF);
            if ((intf & RX0IF) != 0) return readFrameFromBuffer(READ_RXB0SIDH, 0, true);
            if ((intf & RX1IF) != 0) return readFrameFromBuffer(0x90 | 0x04, 1, true);
            if (timeoutMs <= 0) return null;
            if (elapsed >= timeoutMs) return null;
            sleepMs(MODE_POLL_MS);
            elapsed += MODE_POLL_MS;
        }
    }

    /** Non-blocking receive. */
    public CanFrame recv() throws IOException {
        return recv(0);
    }

    // --- SPI-level helpers (used by Full via inheritance) ---

    protected void writeReg(int reg, int value) throws IOException {
        connection.write(new byte[] { (byte) INSTR_WRITE, (byte) (reg & 0xFF), (byte) (value & 0xFF) });
    }

    protected int readReg(int reg) throws IOException {
        byte[] buf = connection.writeRead(new byte[] { (byte) INSTR_READ, (byte) (reg & 0xFF) }, 1);
        return buf[0] & 0xFF;
    }

    protected void modifyReg(int reg, int mask, int value) throws IOException {
        connection.write(new byte[] {
            (byte) INSTR_BIT_MODIFY,
            (byte) (reg & 0xFF),
            (byte) (mask & 0xFF),
            (byte) (value & 0xFF),
        });
    }

    /**
     * Send a CAN frame using a specific TX buffer (0, 1, or 2).
     *
     * @param id       11-bit or 29-bit CAN identifier.
     * @param data     payload, 0–8 bytes.
     * @param extended true for 29-bit identifier.
     * @param buf      TX buffer index, 0..2.
     * @return the TX buffer index used.
     * @throws IOException on SPI error, invalid buffer index, or TX timeout.
     */
    protected int sendBuffered(int id, byte[] data, boolean extended, int buf) throws IOException {
        if (buf < 0 || buf > 2) {
            throw new IllegalArgumentException("TX buffer index must be 0..2 (got " + buf + ")");
        }
        int len = data == null ? 0 : Math.min(data.length, 8);

        // Wait for the chosen TX buffer to be free.
        int txCtrlReg = buf == 0 ? REG_TXB0CTRL : (0x40 + buf * 0x10);
        int elapsed = 0;
        while (true) {
            int ctrl = readReg(txCtrlReg);
            if ((ctrl & TXREQ) == 0) break;
            if (elapsed >= TX_TIMEOUT_MS) {
                throw new IOException("MCP2515 TXB" + buf + " busy after " + TX_TIMEOUT_MS + " ms");
            }
            sleepMs(TX_POLL_MS);
            elapsed += TX_POLL_MS;
        }

        // Pack ID into SIDH/SIDL/EID8/EID0 + DLC + data bytes.
        byte[] frame = new byte[5 + 8];
        int sidh, sidl, eid8, eid0;
        if (extended) {
            if ((id & ~0x1FFFFFFF) != 0) {
                throw new IllegalArgumentException("29-bit id out of range: " + id);
            }
            sidh = (id >> 21) & 0xFF;
            sidl = (((id >> 18) & 0x07) << 5) | 0x08 /* EXIDE */ | ((id >> 16) & 0x03);
            eid8 = (id >> 8) & 0xFF;
            eid0 = id & 0xFF;
        } else {
            if ((id & ~0x7FF) != 0) {
                throw new IllegalArgumentException("11-bit id out of range: " + id);
            }
            sidh = (id >> 3) & 0xFF;
            sidl = (id & 0x07) << 5;
            eid8 = 0;
            eid0 = 0;
        }
        frame[0] = (byte) sidh;
        frame[1] = (byte) sidl;
        frame[2] = (byte) eid8;
        frame[3] = (byte) eid0;
        frame[4] = (byte) (len & 0x0F);
        for (int i = 0; i < len; i++) frame[5 + i] = data[i];

        // LOAD TX BUFFER instruction index 0->TXB0SIDH, 2->TXB0D0, 4->TXB1SIDH, ...
        int loadInstr = 0x40 | (buf * 4);
        byte[] txBuf = new byte[1 + frame.length];
        txBuf[0] = (byte) loadInstr;
        System.arraycopy(frame, 0, txBuf, 1, frame.length);
        connection.write(txBuf);

        // Set TXBnCTRL = TXREQ | TXP=11 (highest priority).
        // Bit 0 must be set on every TXBnCTRL write (buffered-mode flag must stay 0
        // and TXREQ is bit 3); we set TXP[1:0]=11 and TXREQ=1.
        writeReg(txCtrlReg, TXREQ | TXP_HIGHEST);

        // Issue RTS for this buffer (or wait for polled completion).
        connection.write(new byte[] { (byte) (INSTR_RTS | (1 << buf)) });

        // Wait until TXREQ clears (the chip drops it when the message has been sent,
        // aborted, or hit a TX error with OSM=1).
        elapsed = 0;
        while (true) {
            int ctrl = readReg(txCtrlReg);
            if ((ctrl & TXREQ) == 0) return buf;
            if (elapsed >= TX_TIMEOUT_MS) {
                throw new IOException("MCP2515 TXB" + buf + " did not complete within "
                        + TX_TIMEOUT_MS + " ms (ctrl=0x" + Integer.toHexString(ctrl) + ")");
            }
            sleepMs(TX_POLL_MS);
            elapsed += TX_POLL_MS;
        }
    }

    /**
     * Read a frame from one of the RX buffers using the READ RX BUFFER instruction.
     *
     * <p>{@code loadInstr} selects which RX buffer: 0x90 for RXB0 (clears RX0IF),
     * 0x94 for RXB1 (clears RX1IF). The frame layout in the response is
     * SIDH/SIDL/EID8/EID0/DLC/8 data bytes (14 bytes total).
     *
     * @param loadInstr LOAD TX/RX BUFFER opcode (0x90 for RXB0, 0x94 for RXB1).
     * @param bufIndex  RX buffer index (0 or 1), used for return bookkeeping.
     * @param clearFlag ignored — the chip auto-clears RXnIF on CS-deassert.
     * @return the decoded frame.
     * @throws IOException on SPI error or malformed frame.
     */
    protected CanFrame readFrameFromBuffer(int loadInstr, int bufIndex, boolean clearFlag) throws IOException {
        byte[] buf = connection.writeRead(new byte[] { (byte) loadInstr }, 14);
        int sidh = buf[0] & 0xFF;
        int sidl = buf[1] & 0xFF;
        int eid8 = buf[2] & 0xFF;
        int eid0 = buf[3] & 0xFF;
        int dlc  = buf[4] & 0xFF;

        boolean extended = (sidl & 0x08) != 0;
        int id;
        boolean rtr;
        if (extended) {
            id = (sidh << 21) | (((sidl >> 5) & 0x07) << 18) | ((sidl & 0x03) << 16) | (eid8 << 8) | eid0;
            rtr = (dlc & DLC_RTR) != 0;
        } else {
            id = (sidh << 3) | (sidl >> 5);
            rtr = (sidl & 0x10) != 0;
        }
        int dataLen = dlc & 0x0F;
        if (dataLen > 8) dataLen = 8;
        byte[] payload = new byte[dataLen];
        System.arraycopy(buf, 5, payload, 0, dataLen);

        // Clear the corresponding RXnIF flag explicitly for safety. The READ RX
        // BUFFER instruction already cleared it on CS-deassert, but a defensive
        // BIT MODIFY makes the intent obvious.
        int intfBit = (bufIndex == 0) ? RX0IF : RX1IF;
        modifyReg(REG_CANINTF, intfBit, 0);
        return new CanFrame(id, payload, extended, rtr);
    }

    /**
     * Poll CANSTAT until OPMOD matches the requested mode.
     *
     * @param opmod expected OPMOD[2:0] value (after masking with 0xE0).
     * @throws IOException on timeout.
     */
    protected void waitMode(int opmod) throws IOException {
        int elapsed = 0;
        while (true) {
            int canstat = readReg(REG_CANSTAT);
            if ((canstat & 0xE0) == opmod) return;
            if (elapsed >= MODE_TIMEOUT_MS) {
                throw new IOException("MCP2515 mode transition timed out (CANSTAT=0x"
                        + Integer.toHexString(canstat) + ", want OPMOD=0x"
                        + Integer.toHexString(opmod) + ")");
            }
            sleepMs(MODE_POLL_MS);
            elapsed += MODE_POLL_MS;
        }
    }

    protected static void sleepMs(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int bitrateIndex(int bitrateKbps) {
        switch (bitrateKbps) {
            case 125:  return 0;
            case 250:  return 1;
            case 500:  return 2;
            case 1000: return 3;
            default:   throw new IllegalArgumentException(
                    "bitrateKbps must be 125, 250, 500, or 1000 (got " + bitrateKbps + ")");
        }
    }

    private static int oscIndex(int oscMhz) {
        if (oscMhz == 8)  return 0;
        if (oscMhz == 16) return 1;
        throw new IllegalArgumentException("oscMhz must be 8 or 16 (got " + oscMhz + ")");
    }

    /**
     * A CAN frame — standard or extended, with payload and an RTR flag.
     *
     * <p>{@code id} is 11 bits (standard) or 29 bits (extended); check
     * {@link #extended} to disambiguate. {@code data} may be 0–8 bytes.
     */
    public static class CanFrame {
        public final int id;
        public final byte[] data;
        public final boolean extended;
        public final boolean rtr;

        /**
         * Construct a CAN frame value object.
         *
         * @param id       11-bit or 29-bit CAN identifier.
         * @param data     payload, 0–8 bytes (defensively cloned).
         * @param extended true if 29-bit extended identifier.
         * @param rtr      true if remote transmission request frame.
         */
        public CanFrame(int id, byte[] data, boolean extended, boolean rtr) {
            this.id = id;
            this.data = data == null ? new byte[0] : data.clone();
            this.extended = extended;
            this.rtr = rtr;
        }
    }
}
