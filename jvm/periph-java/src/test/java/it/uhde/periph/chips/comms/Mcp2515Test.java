package it.uhde.periph.chips.comms;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Mcp2515Test {

    // --- Pre-computed CNF values from the spec, mirrored here so unit tests can
    //     assert on the exact bytes the driver writes during init ---
    private static final int[] CNF1_8MHZ = { 0x01, 0x00, 0x00, 0x00 };  // 125, 250, 500, 1000
    private static final int[] CNF2_8MHZ = { 0xBA, 0xBA, 0x91, 0x80 };
    private static final int[] CNF3_8MHZ = { 0x03, 0x03, 0x01, 0x00 };
    private static final int[] CNF1_16MHZ = { 0x03, 0x01, 0x00, 0x00 };
    private static final int[] CNF2_16MHZ = { 0xBA, 0xBA, 0xBA, 0x91 };
    private static final int[] CNF3_16MHZ = { 0x03, 0x03, 0x03, 0x01 };

    /** SPI instruction opcodes (mirrored from the driver so we can decode writes). */
    private static final int INSTR_RESET       = 0xC0;
    private static final int INSTR_WRITE       = 0x02;
    private static final int INSTR_READ        = 0x03;
    private static final int INSTR_LOAD_TX_BUF = 0x40;
    private static final int INSTR_RTS         = 0x80;
    private static final int INSTR_BIT_MODIFY  = 0x05;

    /**
     * MockConnection that understands the MCP2515 SPI framing.
     *
     * <p>The base {@link MockConnection#writeRead} interprets {@code data[0]} as
     * the register address — that works for chips whose first byte IS the address
     * (MFRC522, SiPo). The MCP2515 uses an INSTR opcode as the first byte, so
     * this subclass translates MCP2515-specific frames back into the register
     * map the parent uses:
     * <ul>
     *   <li>{@code [INSTR_READ, addr]} → strip opcode, fetch from addr</li>
     *   <li>{@code [0x90]} (READ RX BUFFER 0) → fetch 14 bytes from RXB0SIDH (0x61)</li>
     *   <li>{@code [0x94]} (READ RX BUFFER 1) → fetch 14 bytes from RXB1SIDH (0x71)</li>
     * </ul>
     * Also adds a per-address FIFO of writeRead responses ({@link #queueWriteRead})
     * so the driver can poll the same register multiple times and get different
     * values on each read (e.g. CANSTAT for the OPMOD transition poll).
     *
     * <p>The write-side log records the original {@code data} the driver sent
     * (so {@code [INSTR_READ, addr]} is logged, not {@code [addr]}), keeping
     * the assertion surface in tests driver-shape.
     */
    private static class McpMock extends MockConnection {
        private final java.util.Map<Integer, java.util.Deque<byte[]>> wrQueue = new java.util.HashMap<>();

        /** Queue a response to be returned by the next {@code writeRead(...)} whose
         *  starting register address is {@code addr}. Used for polled reads of
         *  the same register, where {@code setRegister} only stores a single value. */
        void queueWriteRead(int addr, byte[] response) {
            wrQueue.computeIfAbsent(addr, k -> new java.util.ArrayDeque<>()).addLast(response);
        }

        @Override
        public byte[] writeRead(byte[] data, int n) throws IOException {
            if (data == null || data.length == 0) {
                return super.writeRead(data, n);
            }
            int opcode = data[0] & 0xFF;
            int addr = -1;

            if (data.length >= 2 && opcode == INSTR_READ) {
                addr = data[1] & 0xFF;
            } else if (data.length == 1 && (opcode & 0xF0) == Mcp2515Minimal.INSTR_READ_RX_BUF) {
                int n2 = opcode & 0x07;
                addr = (n2 == 0) ? (Mcp2515Minimal.REG_RXB0CTRL + 1)
                                  : (Mcp2515Minimal.REG_RXB1CTRL + 1);
            } else if (data.length >= 2 && (opcode == Mcp2515Minimal.INSTR_READ_STATUS
                    || opcode == Mcp2515Minimal.INSTR_RX_STATUS)) {
                addr = 0;
            } else {
                return super.writeRead(data, n);
            }

            // Record the original MCP2515-shaped write in the parent's log so
            // tests asserting on `connection.writes()` see [INSTR_READ, addr]
            // (driver-shape) rather than [addr] (raw mock).
            super.writes().add(data.clone());

            // Honour queued responses first.
            java.util.Deque<byte[]> q = wrQueue.get(addr);
            if (q != null && !q.isEmpty()) {
                byte[] front = q.pollFirst();
                byte[] out = new byte[n];
                System.arraycopy(front, 0, out, 0, Math.min(n, front.length));
                return out;
            }

            // Otherwise pull from the parent's register map.
            byte[] addrOnly = new byte[] { (byte) addr };
            // Reach into parent's logic without re-recording.
            java.util.Map<Integer, Integer> regs = super.registers();
            byte[] out = new byte[n];
            for (int i = 0; i < n; i++) {
                out[i] = (byte) (int) regs.getOrDefault(addr + i, 0);
            }
            return out;
        }
    }

    /** Fresh mock with CANSTAT=OpmodConfig for the init check + Normal after. */
    private static McpMock freshConnection() {
        McpMock connection = new McpMock();
        // init() reads CANSTAT once (verify Config) and again in waitMode() to
        // confirm the Normal transition. Queue both responses so the driver's
        // two consecutive reads of CANSTAT get distinct values.
        connection.queueWriteRead(Mcp2515Minimal.REG_CANSTAT, new byte[] { (byte) 0x80 /* OPMOD=Config */ });
        connection.queueWriteRead(Mcp2515Minimal.REG_CANSTAT, new byte[] { 0x00 /* OPMOD=Normal */ });
        return connection;
    }

    private static byte[] findWrite(MockConnection connection, int instr, int reg) {
        int r = reg & 0xFF;
        for (byte[] w : connection.writes()) {
            int minLen = (instr == INSTR_BIT_MODIFY) ? 4 : 3;
            if (w.length < minLen) continue;
            if ((w[0] & 0xFF) != (instr & 0xFF)) continue;
            if ((w[1] & 0xFF) != r) continue;
            return w;
        }
        return null;
    }

    private static byte[] findLastWrite(MockConnection connection, int instr, int reg) {
        int r = reg & 0xFF;
        byte[] last = null;
        for (byte[] w : connection.writes()) {
            int minLen = (instr == INSTR_BIT_MODIFY) ? 4 : 3;
            if (w.length < minLen) continue;
            if ((w[0] & 0xFF) != (instr & 0xFF)) continue;
            if ((w[1] & 0xFF) != r) continue;
            last = w;
        }
        return last;
    }

    private static int findWriteByte(MockConnection connection, int instr, int reg) {
        byte[] w = findWrite(connection, instr, reg);
        if (w == null) {
            throw new AssertionError("No " + Integer.toHexString(instr) + " write to reg 0x"
                    + Integer.toHexString(reg) + " in " + connection.writes().size() + " writes");
        }
        return w[2] & 0xFF;
    }

    private static byte[] findFirstWrite(MockConnection connection, int firstByte) {
        int target = firstByte & 0xFF;
        for (byte[] w : connection.writes()) {
            if (w.length >= 1 && (w[0] & 0xFF) == target) return w;
        }
        return null;
    }

    private static byte[] buildRxFrame(int sidh, int sidl, int eid8, int eid0, int dlc, byte[] data) {
        byte[] buf = new byte[14];
        buf[0] = (byte) sidh;
        buf[1] = (byte) sidl;
        buf[2] = (byte) eid8;
        buf[3] = (byte) eid0;
        buf[4] = (byte) dlc;
        int len = Math.min(8, data.length);
        System.arraycopy(data, 0, buf, 5, len);
        return buf;
    }

    @Test
    void initWritesResetAndCnfRegisters() throws Exception {
        McpMock connection = freshConnection();
        new Mcp2515Minimal(connection, 125, 8);

        byte[] firstWrite = connection.writes().get(0);
        assertEquals(1, firstWrite.length);
        assertEquals(INSTR_RESET, firstWrite[0] & 0xFF);

        assertEquals(0x01, findWriteByte(connection, INSTR_WRITE, Mcp2515Minimal.REG_CNF1));
        assertEquals(0xBA, findWriteByte(connection, INSTR_WRITE, Mcp2515Minimal.REG_CNF2));
        assertEquals(0x03, findWriteByte(connection, INSTR_WRITE, Mcp2515Minimal.REG_CNF3));
    }

    @Test
    void initAcceptsAllBitrateAndOscPresets() throws Exception {
        for (int osc : new int[] { 8, 16 }) {
            int[] cnf1 = osc == 8 ? CNF1_8MHZ : CNF1_16MHZ;
            int[] cnf2 = osc == 8 ? CNF2_8MHZ : CNF2_16MHZ;
            int[] cnf3 = osc == 8 ? CNF3_8MHZ : CNF3_16MHZ;
            int[] bitrates = { 125, 250, 500, 1000 };
            for (int b = 0; b < 4; b++) {
                McpMock connection = freshConnection();
                new Mcp2515Minimal(connection, bitrates[b], osc);
                assertEquals(cnf1[b], findWriteByte(connection, INSTR_WRITE, Mcp2515Minimal.REG_CNF1),
                        "CNF1 mismatch at " + bitrates[b] + "/" + osc);
                assertEquals(cnf2[b], findWriteByte(connection, INSTR_WRITE, Mcp2515Minimal.REG_CNF2),
                        "CNF2 mismatch at " + bitrates[b] + "/" + osc);
                assertEquals(cnf3[b], findWriteByte(connection, INSTR_WRITE, Mcp2515Minimal.REG_CNF3),
                        "CNF3 mismatch at " + bitrates[b] + "/" + osc);
            }
        }
    }

    @Test
    void initRejectsBadParameters() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new Mcp2515Minimal(freshConnection(), 100, 8));
        assertThrows(IllegalArgumentException.class,
                () -> new Mcp2515Minimal(freshConnection(), 125, 20));
    }

    @Test
    void initFailsIfNotInConfigModeAfterReset() {
        McpMock connection = new McpMock();
        connection.queueWriteRead(Mcp2515Minimal.REG_CANSTAT, new byte[] { 0x00 });
        connection.queueWriteRead(Mcp2515Minimal.REG_CANSTAT, new byte[] { 0x00 });
        assertThrows(IOException.class, () -> new Mcp2515Minimal(connection, 125, 8));
    }

    @Test
    void initWritesAcceptAllMasksAndRxbMode() throws Exception {
        McpMock connection = freshConnection();
        new Mcp2515Minimal(connection, 125, 8);

        for (int reg = 0x20; reg <= 0x27; reg++) {
            assertEquals(0xFF, findWriteByte(connection, INSTR_WRITE, reg));
        }

        assertEquals(0x64, findWriteByte(connection, INSTR_WRITE, Mcp2515Minimal.REG_RXB0CTRL));
        assertEquals(0x60, findWriteByte(connection, INSTR_WRITE, Mcp2515Minimal.REG_RXB1CTRL));
    }

    @Test
    void sendStandardFramePacksIdCorrectly() throws Exception {
        McpMock connection = freshConnection();
        Mcp2515Full can = new Mcp2515Full(connection, 125, 8);

        connection.queueWriteRead(Mcp2515Minimal.REG_TXB0CTRL, new byte[] { 0x08 /* TXREQ still set */ });
        connection.queueWriteRead(Mcp2515Minimal.REG_TXB0CTRL, new byte[] { 0x00 /* TXREQ clear */ });

        can.send(0x123, new byte[] { 0x11, 0x22, 0x33, 0x44 });

        byte[] loadWrite = findFirstWrite(connection, INSTR_LOAD_TX_BUF);
        assertNotNull(loadWrite);
        assertEquals(0x40, loadWrite[0] & 0xFF);  // LOAD TXB0SIDH opcode
        assertEquals(0x24, loadWrite[1] & 0xFF);  // SIDH
        assertEquals(0x60, loadWrite[2] & 0xFF);  // SIDL
        assertEquals(0x00, loadWrite[3] & 0xFF);
        assertEquals(0x00, loadWrite[4] & 0xFF);
        assertEquals(0x04, loadWrite[5] & 0xFF);  // DLC
        assertEquals(0x11, loadWrite[6] & 0xFF);
        assertEquals(0x22, loadWrite[7] & 0xFF);
        assertEquals(0x33, loadWrite[8] & 0xFF);
        assertEquals(0x44, loadWrite[9] & 0xFF);

        assertEquals(0x0B, findWriteByte(connection, INSTR_WRITE, Mcp2515Minimal.REG_TXB0CTRL));

        byte[] rtsWrite = findFirstWrite(connection, INSTR_RTS | 0x01 /* TXB0 */);
        assertNotNull(rtsWrite);
        assertEquals(0x81, rtsWrite[0] & 0xFF);
    }

    @Test
    void sendExtendedFramePacksIdCorrectly() throws Exception {
        McpMock connection = freshConnection();
        Mcp2515Full can = new Mcp2515Full(connection, 125, 8);

        connection.queueWriteRead(Mcp2515Minimal.REG_TXB0CTRL, new byte[] { 0x00 });

        int id = 0x1FFFFFFF;
        can.send(id, new byte[] { (byte) 0xAA }, true);

        byte[] loadWrite = findFirstWrite(connection, INSTR_LOAD_TX_BUF);
        assertEquals(0xFF, loadWrite[1] & 0xFF);
        assertEquals(0xEB, loadWrite[2] & 0xFF);
        assertEquals(0xFF, loadWrite[3] & 0xFF);
        assertEquals(0xFF, loadWrite[4] & 0xFF);
        assertEquals(0x01, loadWrite[5] & 0xFF);
    }

    @Test
    void sendRejectsOutOfRangeId() throws Exception {
        McpMock connection = freshConnection();
        Mcp2515Full can = new Mcp2515Full(connection, 125, 8);
        assertThrows(IllegalArgumentException.class,
                () -> can.send(0x800, new byte[]{0x01}, false));
        assertThrows(IllegalArgumentException.class,
                () -> can.send(0x20000000, new byte[]{0x01}, true));
    }

    @Test
    void sendRejectsBadBufferIndex() throws Exception {
        McpMock connection = freshConnection();
        Mcp2515Full can = new Mcp2515Full(connection, 125, 8);
        assertThrows(IllegalArgumentException.class,
                () -> can.sendBuffered(0x100, new byte[]{0x01}, false, 3));
        assertThrows(IllegalArgumentException.class,
                () -> can.sendBuffered(0x100, new byte[]{0x01}, false, -1));
    }

    @Test
    void recvReturnsNullWhenNoFramePending() throws Exception {
        McpMock connection = freshConnection();
        Mcp2515Minimal can = new Mcp2515Minimal(connection, 125, 8);

        connection.queueWriteRead(Mcp2515Minimal.REG_CANINTF, new byte[] { 0x00 });
        assertNull(can.recv());
        assertNull(can.recv(10));
    }

    @Test
    void recvUnpacksStandardFrame() throws Exception {
        McpMock connection = freshConnection();
        Mcp2515Minimal can = new Mcp2515Minimal(connection, 125, 8);

        connection.queueWriteRead(Mcp2515Minimal.REG_CANINTF, new byte[] { 0x01 /* RX0IF */ });
        connection.queueWriteRead(Mcp2515Minimal.REG_RXB0CTRL + 1 /* RXB0SIDH */,
                buildRxFrame(0x24, 0x60, 0x00, 0x00, 0x04,
                        new byte[]{0x11, 0x22, 0x33, 0x44}));

        Mcp2515Minimal.CanFrame frame = can.recv(10);
        assertNotNull(frame);
        assertEquals(0x123, frame.id);
        assertFalse(frame.extended);
        assertFalse(frame.rtr);
        assertArrayEquals(new byte[]{0x11, 0x22, 0x33, 0x44}, frame.data);
    }

    @Test
    void recvUnpacksExtendedFrameWithRtr() throws Exception {
        McpMock connection = freshConnection();
        Mcp2515Minimal can = new Mcp2515Minimal(connection, 125, 8);

        connection.queueWriteRead(Mcp2515Minimal.REG_CANINTF, new byte[] { 0x02 /* RX1IF */ });
        // Extended ID 0x12345: SIDH=0x00, SIDL=((0)<<5)|0x08|((1))=0x09,
        // EID8=0x23, EID0=0x45, DLC=0x40 (RTR set, length 0).
        connection.queueWriteRead(Mcp2515Minimal.REG_RXB1CTRL + 1 /* RXB1SIDH */,
                buildRxFrame(0x00, 0x09, 0x23, 0x45, 0x40,
                        new byte[]{0, 0, 0, 0, 0, 0, 0, 0}));

        Mcp2515Minimal.CanFrame frame = can.recv(10);
        assertNotNull(frame);
        assertEquals(0x12345, frame.id);
        assertTrue(frame.extended);
        assertTrue(frame.rtr);
    }

    @Test
    void setModeSwitchesViaCanctrlAndPollsCanstat() throws Exception {
        McpMock connection = freshConnection();
        Mcp2515Full can = new Mcp2515Full(connection, 125, 8);

        connection.queueWriteRead(Mcp2515Minimal.REG_CANSTAT, new byte[] { 0x40 /* OPMOD=loopback */ });

        can.setMode("loopback");

        // Construction already issued a CANCTRL write (REQOP=normal) during
        // Init, so we need the *last* write, not the first.
        byte[] canctrlWrite = findLastWrite(connection, INSTR_WRITE, Mcp2515Minimal.REG_CANCTRL);
        assertEquals(0x40, canctrlWrite[2] & 0xFF);
    }

    @Test
    void setFilterEntersAndExitsConfigMode() throws Exception {
        McpMock connection = freshConnection();
        Mcp2515Full can = new Mcp2515Full(connection, 125, 8);

        // setFilter enters Config (waitMode polls), writes the filter, returns to Normal.
        // We need to queue three CANSTAT reads for: enter config (current normal — just to confirm),
        // then mode transition wait inside setMode (config match), then back to normal (wait).
        // Actually, the chain is: getMode() returns "normal", setMode("config") polls until
        // OPMOD=Config (queue 0x80), then we write filters, then setMode("normal") polls
        // until OPMOD=Normal (queue 0x00).
        connection.queueWriteRead(Mcp2515Minimal.REG_CANSTAT, new byte[] { 0x00 /* current mode = normal */ });
        connection.queueWriteRead(Mcp2515Minimal.REG_CANSTAT, new byte[] { (byte) 0x80 /* config */ });
        connection.queueWriteRead(Mcp2515Minimal.REG_CANSTAT, new byte[] { 0x00 /* back to normal */ });

        can.setFilter(0, 0x123, false);

        // Filter base addr for filter 0 is 0x00 (RXF0SIDH).
        assertEquals(0x24, findWriteByte(connection, INSTR_WRITE, 0x00));
        assertEquals(0x60, findWriteByte(connection, INSTR_WRITE, 0x01));
    }

    @Test
    void sendBufferedWaitsForBusyBuffer() throws Exception {
        McpMock connection = freshConnection();
        Mcp2515Full can = new Mcp2515Full(connection, 125, 8);

        // 4 reads during the pre-RTS busy-wait loop, then the post-RTS poll reads
        // once more (registers[TXB0CTRL] is unset → returns 0 → TXREQ clear).
        connection.queueWriteRead(Mcp2515Minimal.REG_TXB0CTRL, new byte[] { 0x08 });
        connection.queueWriteRead(Mcp2515Minimal.REG_TXB0CTRL, new byte[] { 0x08 });
        connection.queueWriteRead(Mcp2515Minimal.REG_TXB0CTRL, new byte[] { 0x08 });
        connection.queueWriteRead(Mcp2515Minimal.REG_TXB0CTRL, new byte[] { 0x00 });

        can.sendBuffered(0x100, new byte[]{0x01}, false, 0);

        int pollCount = 0;
        for (byte[] w : connection.writes()) {
            if (w.length == 2 && (w[0] & 0xFF) == INSTR_READ && (w[1] & 0xFF) == Mcp2515Minimal.REG_TXB0CTRL) {
                pollCount++;
            }
        }
        assertEquals(5, pollCount);  // 4 pre-RTS + 1 post-RTS
    }

    @Test
    void setRxModeRejectsBadMode() throws Exception {
        McpMock connection = freshConnection();
        Mcp2515Full can = new Mcp2515Full(connection, 125, 8);
        assertThrows(IllegalArgumentException.class, () -> can.setRxMode(0, 2));
        assertThrows(IllegalArgumentException.class, () -> can.setRxMode(0, 4));
        assertThrows(IllegalArgumentException.class, () -> can.setRxMode(2, 0));
    }

    @Test
    void readErrorsReturnsTecRecEflg() throws Exception {
        McpMock connection = freshConnection();
        Mcp2515Full can = new Mcp2515Full(connection, 125, 8);

        connection.queueWriteRead(Mcp2515Full.REG_TEC, new byte[] { 0x42 });
        connection.queueWriteRead(Mcp2515Full.REG_REC, new byte[] { 0x10 });
        connection.queueWriteRead(Mcp2515Full.REG_EFLG, new byte[] { (byte) 0x80 });

        Mcp2515Full.Errors errs = can.readErrors();
        assertEquals(0x42, errs.tec);
        assertEquals(0x10, errs.rec);
        assertEquals(0x80, errs.eflg);
    }

    @Test
    void clearOverflowRejectsBadBuffer() throws Exception {
        McpMock connection = freshConnection();
        Mcp2515Full can = new Mcp2515Full(connection, 125, 8);
        assertThrows(IllegalArgumentException.class, () -> can.clearOverflow(2));
    }

    @Test
    void setOneShotWritesOsmBit() throws Exception {
        McpMock connection = freshConnection();
        Mcp2515Full can = new Mcp2515Full(connection, 125, 8);

        can.setOneShot(true);
        byte[] modifyWrite = findWrite(connection, INSTR_BIT_MODIFY, Mcp2515Minimal.REG_CANCTRL);
        assertEquals(0x08, modifyWrite[2] & 0xFF);
        assertEquals(0x08, modifyWrite[3] & 0xFF);

        can.setOneShot(false);
        byte[] modifyWrite2 = findLastWrite(connection, INSTR_BIT_MODIFY, Mcp2515Minimal.REG_CANCTRL);
        assertEquals(0x08, modifyWrite2[2] & 0xFF);
        assertEquals(0x00, modifyWrite2[3] & 0xFF);
    }

    @Test
    void getModeReturnsString() throws Exception {
        McpMock connection = freshConnection();
        Mcp2515Full can = new Mcp2515Full(connection, 125, 8);

        connection.queueWriteRead(Mcp2515Minimal.REG_CANSTAT, new byte[] { 0x00 });
        assertEquals("normal", can.getMode());

        connection.queueWriteRead(Mcp2515Minimal.REG_CANSTAT, new byte[] { 0x40 });
        assertEquals("loopback", can.getMode());
    }

    @Test
    void setModeRejectsUnknownMode() throws Exception {
        McpMock connection = freshConnection();
        Mcp2515Full can = new Mcp2515Full(connection, 125, 8);
        assertThrows(IllegalArgumentException.class, () -> can.setMode("wibble"));
    }

    @Test
    void resetWritesResetInstruction() throws Exception {
        McpMock connection = freshConnection();
        Mcp2515Full can = new Mcp2515Full(connection, 125, 8);

        can.reset();
        byte[] lastWrite = connection.writes().get(connection.writes().size() - 1);
        assertEquals(1, lastWrite.length);
        assertEquals(INSTR_RESET, lastWrite[0] & 0xFF);
    }
}
