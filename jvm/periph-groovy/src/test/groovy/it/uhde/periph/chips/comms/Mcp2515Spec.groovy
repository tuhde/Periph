package it.uhde.periph.chips.comms

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

// CNF presets from the MCP2515 spec (mirrored so tests can assert on byte values).
class Mcp2515Spec extends Specification {

    private static final int[] CNF1_8MHZ = [0x01, 0x00, 0x00, 0x00] as int[]   // 125, 250, 500, 1000
    private static final int[] CNF2_8MHZ = [0xBA, 0xBA, 0x91, 0x80] as int[]
    private static final int[] CNF3_8MHZ = [0x03, 0x03, 0x01, 0x00] as int[]
    private static final int[] CNF1_16MHZ = [0x03, 0x01, 0x00, 0x00] as int[]
    private static final int[] CNF2_16MHZ = [0xBA, 0xBA, 0xBA, 0x91] as int[]
    private static final int[] CNF3_16MHZ = [0x03, 0x03, 0x03, 0x01] as int[]

    // SPI instruction opcodes (mirrored from the driver).
    private static final int INSTR_RESET       = 0xC0
    private static final int INSTR_WRITE       = 0x02
    private static final int INSTR_READ        = 0x03
    private static final int INSTR_LOAD_TX_BUF = 0x40
    private static final int INSTR_RTS         = 0x80
    private static final int INSTR_BIT_MODIFY  = 0x05

    /**
     * MockConnection that understands the MCP2515 SPI framing.
     *
     * The base MockConnection's writeRead interprets data[0] as the register
     * address. The MCP2515 sends an INSTR opcode first, so this subclass strips
     * the opcode and feeds the real register address to the parent map. It also
     * adds a per-address FIFO of writeRead responses so the driver can poll the
     * same register multiple times and get different values each read.
     *
     * The write-side log records the original data (driver-shape,
     * [INSTR_READ, addr]) so test assertions match what the driver sent.
     */
    private static class McpMock extends MockConnection {
        private final Map<Integer, Deque<byte[]>> wrQueue = [:]

        void queueWriteRead(int addr, byte[] response) {
            wrQueue.computeIfAbsent(addr) { new ArrayDeque<byte[]>() }.addLast(response)
        }

        @Override
        byte[] writeRead(byte[] data, int n) {
            if (data == null || data.length == 0) return super.writeRead(data, n)
            int opcode = data[0] & 0xFF
            int addr
            if (data.length >= 2 && opcode == INSTR_READ) {
                addr = data[1] & 0xFF
            } else if (data.length == 1 && (opcode & 0xF0) == Mcp2515Minimal.INSTR_READ_RX_BUF) {
                int n2 = opcode & 0x07
                addr = (n2 == 0) ? (Mcp2515Minimal.REG_RXB0CTRL + 1)
                                  : (Mcp2515Minimal.REG_RXB1CTRL + 1)
            } else if (data.length >= 2 && (opcode == Mcp2515Minimal.INSTR_READ_STATUS
                    || opcode == Mcp2515Minimal.INSTR_RX_STATUS)) {
                addr = 0
            } else {
                return super.writeRead(data, n)
            }

            // Record the original MCP2515-shaped write in the parent's log.
            super.writes().add(data.clone())

            Deque<byte[]> q = wrQueue[addr]
            if (q != null && !q.isEmpty()) {
                byte[] front = q.pollFirst()
                byte[] out = new byte[n]
                System.arraycopy(front, 0, out, 0, Math.min(n, front.length))
                return out
            }

            // Pull from parent's register map without re-recording.
            Map<Integer, Integer> regs = super.registers()
            byte[] out = new byte[n]
            for (int i = 0; i < n; i++) {
                out[i] = (regs.getOrDefault(addr + i, 0) as int) as byte
            }
            return out
        }
    }

    private static McpMock freshConnection() {
        McpMock connection = new McpMock()
        connection.queueWriteRead(Mcp2515Minimal.REG_CANSTAT,
            [(byte) 0x80] as byte[])  // init verification: OPMOD=Config
        connection.queueWriteRead(Mcp2515Minimal.REG_CANSTAT,
            [(byte) 0x00] as byte[])  // waitMode poll: OPMOD=Normal
        return connection
    }

    private static byte[] findWrite(MockConnection connection, int instr, int reg) {
        int r = reg & 0xFF
        for (byte[] w : connection.writes()) {
            int minLen = (instr == INSTR_BIT_MODIFY) ? 4 : 3
            if (w.length < minLen) continue
            if ((w[0] & 0xFF) != (instr & 0xFF)) continue
            if ((w[1] & 0xFF) != r) continue
            return w
        }
        return null
    }

    private static byte[] findLastWrite(MockConnection connection, int instr, int reg) {
        int r = reg & 0xFF
        byte[] last = null
        for (byte[] w : connection.writes()) {
            int minLen = (instr == INSTR_BIT_MODIFY) ? 4 : 3
            if (w.length < minLen) continue
            if ((w[0] & 0xFF) != (instr & 0xFF)) continue
            if ((w[1] & 0xFF) != r) continue
            last = w
        }
        return last
    }

    private static int findWriteByte(MockConnection connection, int instr, int reg) {
        byte[] w = findWrite(connection, instr, reg)
        if (w == null) {
            throw new AssertionError("No ${Integer.toHexString(instr)} write to reg 0x${Integer.toHexString(reg)}")
        }
        return w[2] & 0xFF
    }

    private static byte[] findFirstWrite(MockConnection connection, int firstByte) {
        int target = firstByte & 0xFF
        for (byte[] w : connection.writes()) {
            if (w.length >= 1 && (w[0] & 0xFF) == target) return w
        }
        return null
    }

    private static byte[] buildRxFrame(int sidh, int sidl, int eid8, int eid0,
                                       int dlc, byte[] data) {
        byte[] buf = new byte[14]
        buf[0] = (byte) sidh
        buf[1] = (byte) sidl
        buf[2] = (byte) eid8
        buf[3] = (byte) eid0
        buf[4] = (byte) dlc
        int len = Math.min(8, data.length)
        System.arraycopy(data, 0, buf, 5, len)
        return buf
    }

    def "init writes reset and CNF registers"() {
        given:
        McpMock connection = freshConnection()
        new Mcp2515Minimal(connection, 125, 8)
        byte[] firstWrite = connection.writes()[0]

        expect:
        firstWrite.length == 1
        (firstWrite[0] & 0xFF) == INSTR_RESET
        findWriteByte(connection, INSTR_WRITE, Mcp2515Minimal.REG_CNF1) == 0x01
        findWriteByte(connection, INSTR_WRITE, Mcp2515Minimal.REG_CNF2) == 0xBA
        findWriteByte(connection, INSTR_WRITE, Mcp2515Minimal.REG_CNF3) == 0x03
    }

    def "init accepts all bitrate and oscillator presets"() {
        when:
        for (int osc : [8, 16]) {
            int[] cnf1 = (osc == 8) ? CNF1_8MHZ : CNF1_16MHZ
            int[] cnf2 = (osc == 8) ? CNF2_8MHZ : CNF2_16MHZ
            int[] cnf3 = (osc == 8) ? CNF3_8MHZ : CNF3_16MHZ
            int[] bitrates = [125, 250, 500, 1000] as int[]
            for (int b = 0; b < 4; b++) {
                McpMock connection = freshConnection()
                new Mcp2515Minimal(connection, bitrates[b], osc)

                assert findWriteByte(connection, INSTR_WRITE, Mcp2515Minimal.REG_CNF1) == cnf1[b]
                assert findWriteByte(connection, INSTR_WRITE, Mcp2515Minimal.REG_CNF2) == cnf2[b]
                assert findWriteByte(connection, INSTR_WRITE, Mcp2515Minimal.REG_CNF3) == cnf3[b]
            }
        }

        then:
        notThrown(Throwable)
    }

    def "init rejects bad parameters"() {
        when:
        new Mcp2515Minimal(freshConnection(), 100, 8)

        then:
        thrown(IllegalArgumentException)

        when:
        new Mcp2515Minimal(freshConnection(), 125, 20)

        then:
        thrown(IllegalArgumentException)
    }

    def "init fails if not in Configuration mode after reset"() {
        given:
        McpMock connection = new McpMock()
        connection.queueWriteRead(Mcp2515Minimal.REG_CANSTAT, [(byte) 0x00] as byte[])
        connection.queueWriteRead(Mcp2515Minimal.REG_CANSTAT, [(byte) 0x00] as byte[])

        when:
        new Mcp2515Minimal(connection, 125, 8)

        then:
        thrown(IOException)
    }

    def "init writes accept-all masks and RXB mode"() {
        given:
        McpMock connection = freshConnection()
        new Mcp2515Minimal(connection, 125, 8)

        expect:
        for (int reg = 0x20; reg <= 0x27; reg++) {
            assert findWriteByte(connection, INSTR_WRITE, reg) == 0xFF
        }
        findWriteByte(connection, INSTR_WRITE, Mcp2515Minimal.REG_RXB0CTRL) == 0x64
        findWriteByte(connection, INSTR_WRITE, Mcp2515Minimal.REG_RXB1CTRL) == 0x60
    }

    def "send standard frame packs id correctly"() {
        given:
        McpMock connection = freshConnection()
        Mcp2515Full can = new Mcp2515Full(connection, 125, 8)
        connection.queueWriteRead(Mcp2515Minimal.REG_TXB0CTRL, [(byte) 0x08] as byte[])
        connection.queueWriteRead(Mcp2515Minimal.REG_TXB0CTRL, [(byte) 0x00] as byte[])

        when:
        can.send(0x123, [0x11, 0x22, 0x33, 0x44] as byte[])

        then:
        byte[] loadWrite = findFirstWrite(connection, INSTR_LOAD_TX_BUF)
        loadWrite != null
        (loadWrite[0] & 0xFF) == 0x40   // LOAD TXB0SIDH opcode
        (loadWrite[1] & 0xFF) == 0x24   // SIDH
        (loadWrite[2] & 0xFF) == 0x60   // SIDL
        (loadWrite[3] & 0xFF) == 0x00
        (loadWrite[4] & 0xFF) == 0x00
        (loadWrite[5] & 0xFF) == 0x04   // DLC
        (loadWrite[6] & 0xFF) == 0x11
        (loadWrite[7] & 0xFF) == 0x22
        (loadWrite[8] & 0xFF) == 0x33
        (loadWrite[9] & 0xFF) == 0x44
        findWriteByte(connection, INSTR_WRITE, Mcp2515Minimal.REG_TXB0CTRL) == 0x0B
        byte[] rtsWrite = findFirstWrite(connection, INSTR_RTS | 0x01)
        rtsWrite != null
        (rtsWrite[0] & 0xFF) == 0x81
    }

    def "send extended frame packs id correctly"() {
        given:
        McpMock connection = freshConnection()
        Mcp2515Full can = new Mcp2515Full(connection, 125, 8)
        connection.queueWriteRead(Mcp2515Minimal.REG_TXB0CTRL, [(byte) 0x00] as byte[])

        when:
        int id = 0x1FFFFFFF
        can.send(id, [(byte) 0xAA] as byte[], true)

        then:
        byte[] loadWrite = findFirstWrite(connection, INSTR_LOAD_TX_BUF)
        (loadWrite[1] & 0xFF) == 0xFF
        (loadWrite[2] & 0xFF) == 0xEB
        (loadWrite[3] & 0xFF) == 0xFF
        (loadWrite[4] & 0xFF) == 0xFF
        (loadWrite[5] & 0xFF) == 0x01
    }

    def "send rejects out-of-range id"() {
        given:
        Mcp2515Full can = new Mcp2515Full(freshConnection(), 125, 8)

        when:
        can.send(0x800, [(byte) 0x01] as byte[], false)

        then:
        thrown(IllegalArgumentException)

        when:
        can.send(0x20000000, [(byte) 0x01] as byte[], true)

        then:
        thrown(IllegalArgumentException)
    }

    def "send rejects bad buffer index"() {
        given:
        Mcp2515Full can = new Mcp2515Full(freshConnection(), 125, 8)

        when:
        can.sendBuffered(0x100, [(byte) 0x01] as byte[], false, 3)

        then:
        thrown(IllegalArgumentException)

        when:
        can.sendBuffered(0x100, [(byte) 0x01] as byte[], false, -1)

        then:
        thrown(IllegalArgumentException)
    }

    def "recv returns null when no frame pending"() {
        given:
        McpMock connection = freshConnection()
        Mcp2515Minimal can = new Mcp2515Minimal(connection, 125, 8)
        connection.queueWriteRead(Mcp2515Minimal.REG_CANINTF, [(byte) 0x00] as byte[])

        expect:
        can.recv() == null
        can.recv(10) == null
    }

    def "recv unpacks standard frame"() {
        given:
        McpMock connection = freshConnection()
        Mcp2515Minimal can = new Mcp2515Minimal(connection, 125, 8)
        connection.queueWriteRead(Mcp2515Minimal.REG_CANINTF, [(byte) 0x01] as byte[])
        connection.queueWriteRead(Mcp2515Minimal.REG_RXB0CTRL + 1,
            buildRxFrame(0x24, 0x60, 0x00, 0x00, 0x04, [0x11, 0x22, 0x33, 0x44] as byte[]))

        when:
        def frame = can.recv(10)

        then:
        frame != null
        frame.id == 0x123
        !frame.extended
        !frame.rtr
        frame.data == [0x11, 0x22, 0x33, 0x44] as byte[]
    }

    def "recv unpacks extended frame with RTR"() {
        given:
        McpMock connection = freshConnection()
        Mcp2515Minimal can = new Mcp2515Minimal(connection, 125, 8)
        connection.queueWriteRead(Mcp2515Minimal.REG_CANINTF, [(byte) 0x02] as byte[])
        connection.queueWriteRead(Mcp2515Minimal.REG_RXB1CTRL + 1,
            buildRxFrame(0x00, 0x09, 0x23, 0x45, 0x40, [0, 0, 0, 0, 0, 0, 0, 0] as byte[]))

        when:
        def frame = can.recv(10)

        then:
        frame != null
        frame.id == 0x12345
        frame.extended
        frame.rtr
    }

    def "sendBuffered waits for busy buffer"() {
        given:
        McpMock connection = freshConnection()
        Mcp2515Full can = new Mcp2515Full(connection, 125, 8)
        // 4 reads during the pre-RTS busy-wait loop, then the post-RTS poll
        // reads once more (registers[TXB0CTRL] is unset → returns 0).
        connection.queueWriteRead(Mcp2515Minimal.REG_TXB0CTRL, [(byte) 0x08] as byte[])
        connection.queueWriteRead(Mcp2515Minimal.REG_TXB0CTRL, [(byte) 0x08] as byte[])
        connection.queueWriteRead(Mcp2515Minimal.REG_TXB0CTRL, [(byte) 0x08] as byte[])
        connection.queueWriteRead(Mcp2515Minimal.REG_TXB0CTRL, [(byte) 0x00] as byte[])

        when:
        can.sendBuffered(0x100, [(byte) 0x01] as byte[], false, 0)

        then:
        int pollCount = 0
        for (byte[] w : connection.writes()) {
            if (w.length == 2 && (w[0] & 0xFF) == INSTR_READ &&
                    (w[1] & 0xFF) == Mcp2515Minimal.REG_TXB0CTRL) {
                pollCount++
            }
        }
        pollCount == 5  // 4 pre-RTS + 1 post-RTS
    }

    def "setMode switches via CANCTRL and polls CANSTAT"() {
        given:
        McpMock connection = freshConnection()
        Mcp2515Full can = new Mcp2515Full(connection, 125, 8)
        connection.queueWriteRead(Mcp2515Minimal.REG_CANSTAT, [(byte) 0x40] as byte[])

        when:
        can.setMode("loopback")

        then:
        // Construction already issued a CANCTRL write (REQOP=normal) during
        // Init, so we need the *last* write, not the first.
        byte[] canctrlWrite = findLastWrite(connection, INSTR_WRITE, Mcp2515Minimal.REG_CANCTRL)
        (canctrlWrite[2] & 0xFF) == 0x40
    }

    def "setFilter enters and exits Config mode"() {
        given:
        McpMock connection = freshConnection()
        Mcp2515Full can = new Mcp2515Full(connection, 125, 8)
        connection.queueWriteRead(Mcp2515Minimal.REG_CANSTAT, [(byte) 0x00] as byte[])
        connection.queueWriteRead(Mcp2515Minimal.REG_CANSTAT, [(byte) 0x80] as byte[])
        connection.queueWriteRead(Mcp2515Minimal.REG_CANSTAT, [(byte) 0x00] as byte[])

        when:
        can.setFilter(0, 0x123, false)

        then:
        findWriteByte(connection, INSTR_WRITE, 0x00) == 0x24
        findWriteByte(connection, INSTR_WRITE, 0x01) == 0x60
    }

    def "setMask rejects bad maskNum"() {
        given:
        Mcp2515Full can = new Mcp2515Full(freshConnection(), 125, 8)

        when:
        can.setMask(2, 0, false)

        then:
        thrown(IllegalArgumentException)
    }

    def "setRxMode rejects bad mode"() {
        given:
        Mcp2515Full can = new Mcp2515Full(freshConnection(), 125, 8)

        when:
        can.setRxMode(0, 2)

        then:
        thrown(IllegalArgumentException)

        when:
        can.setRxMode(0, 4)

        then:
        thrown(IllegalArgumentException)

        when:
        can.setRxMode(2, 0)

        then:
        thrown(IllegalArgumentException)
    }

    def "readErrors returns TEC/REC/EFLG"() {
        given:
        McpMock connection = freshConnection()
        Mcp2515Full can = new Mcp2515Full(connection, 125, 8)
        connection.queueWriteRead(Mcp2515Full.REG_TEC, [(byte) 0x42] as byte[])
        connection.queueWriteRead(Mcp2515Full.REG_REC, [(byte) 0x10] as byte[])
        connection.queueWriteRead(Mcp2515Full.REG_EFLG, [(byte) 0x80] as byte[])

        when:
        def errs = can.readErrors()

        then:
        errs.tec == 0x42
        errs.rec == 0x10
        errs.eflg == 0x80
    }

    def "clearOverflow rejects bad buffer"() {
        given:
        Mcp2515Full can = new Mcp2515Full(freshConnection(), 125, 8)

        when:
        can.clearOverflow(2)

        then:
        thrown(IllegalArgumentException)
    }

    def "setOneShot writes OSM bit"() {
        given:
        McpMock connection = freshConnection()
        Mcp2515Full can = new Mcp2515Full(connection, 125, 8)

        when:
        can.setOneShot(true)
        byte[] modifyWrite = findWrite(connection, INSTR_BIT_MODIFY, Mcp2515Minimal.REG_CANCTRL)

        then:
        (modifyWrite[2] & 0xFF) == 0x08
        (modifyWrite[3] & 0xFF) == 0x08

        when:
        can.setOneShot(false)
        byte[] modifyWrite2 = findLastWrite(connection, INSTR_BIT_MODIFY, Mcp2515Minimal.REG_CANCTRL)

        then:
        (modifyWrite2[2] & 0xFF) == 0x08
        (modifyWrite2[3] & 0xFF) == 0x00
    }

    def "getMode returns string"() {
        given:
        McpMock connection = freshConnection()
        Mcp2515Full can = new Mcp2515Full(connection, 125, 8)
        connection.queueWriteRead(Mcp2515Minimal.REG_CANSTAT, [(byte) 0x00] as byte[])

        expect:
        can.getMode() == "normal"

        when:
        connection.queueWriteRead(Mcp2515Minimal.REG_CANSTAT, [(byte) 0x40] as byte[])

        then:
        can.getMode() == "loopback"
    }

    def "setMode rejects unknown mode"() {
        given:
        Mcp2515Full can = new Mcp2515Full(freshConnection(), 125, 8)

        when:
        can.setMode("wibble")

        then:
        thrown(IllegalArgumentException)
    }

    def "reset writes reset instruction"() {
        given:
        McpMock connection = freshConnection()
        Mcp2515Full can = new Mcp2515Full(connection, 125, 8)

        when:
        can.reset()
        byte[] lastWrite = connection.writes()[connection.writes().size() - 1]

        then:
        lastWrite.length == 1
        (lastWrite[0] & 0xFF) == INSTR_RESET
    }
}
