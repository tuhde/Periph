package it.uhde.periph.chips.comms

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.ArrayDeque

// CNF presets from the MCP2515 spec (mirrored so tests can assert on byte values).
private val CNF1_8MHZ = intArrayOf(0x01, 0x00, 0x00, 0x00)   // 125, 250, 500, 1000
private val CNF2_8MHZ = intArrayOf(0xBA, 0xBA, 0x91, 0x80)
private val CNF3_8MHZ = intArrayOf(0x03, 0x03, 0x01, 0x00)
private val CNF1_16MHZ = intArrayOf(0x03, 0x01, 0x00, 0x00)
private val CNF2_16MHZ = intArrayOf(0xBA, 0xBA, 0xBA, 0x91)
private val CNF3_16MHZ = intArrayOf(0x03, 0x03, 0x03, 0x01)

// SPI instruction opcodes (mirrored from the driver).
private const val INSTR_RESET       = 0xC0
private const val INSTR_WRITE       = 0x02
private const val INSTR_READ        = 0x03
private const val INSTR_LOAD_TX_BUF = 0x40
private const val INSTR_RTS         = 0x80
private const val INSTR_BIT_MODIFY  = 0x05

/**
 * MockConnection that understands the MCP2515 SPI framing.
 *
 * The base MockConnection's `writeRead` interprets `data[0]` as the register
 * address. The MCP2515 sends an INSTR opcode first (`0x03` + addr), so this
 * subclass strips the opcode and feeds the real register address to the parent
 * map. It also adds a per-address FIFO of `writeRead` responses so the driver
 * can poll the same register multiple times and get different values each read
 * (e.g. CANSTAT for the OPMOD transition poll).
 *
 * The write-side log records the original `data` (driver-shape, `[INSTR_READ,
 * addr]`) so test assertions match what the driver actually sent.
 */
private class McpMock : MockConnection() {
    private val wrQueue: MutableMap<Int, ArrayDeque<ByteArray>> = HashMap()

    /** Queue a response for the next `writeRead` of the given register address. */
    fun queueWriteRead(addr: Int, response: ByteArray) {
        wrQueue.getOrPut(addr) { ArrayDeque() }.addLast(response)
    }

    @Throws(IOException::class)
    override fun writeRead(data: ByteArray, n: Int): ByteArray {
        if (data == null || data.isEmpty()) return super.writeRead(data, n)
        val opcode = data[0].toInt() and 0xFF
        val addr: Int = when {
            data.size >= 2 && opcode == INSTR_READ -> data[1].toInt() and 0xFF
            data.size == 1 && (opcode and 0xF0) == Mcp2515Minimal.INSTR_READ_RX_BUF -> {
                val n2 = opcode and 0x07
                if (n2 == 0) Mcp2515Minimal.REG_RXB0CTRL + 1
                else Mcp2515Minimal.REG_RXB1CTRL + 1
            }
            data.size >= 2 && (opcode == Mcp2515Minimal.INSTR_READ_STATUS
                    || opcode == Mcp2515Minimal.INSTR_RX_STATUS) -> 0
            else -> return super.writeRead(data, n)
        }

        // Record the original MCP2515-shaped write in the parent's log so
        // tests asserting on `connection.writes()` see [INSTR_READ, addr].
        super.writes().add(data.clone())

        val q = wrQueue[addr]
        if (q != null && q.isNotEmpty()) {
            val front = q.pollFirst()
            val out = ByteArray(n)
            System.arraycopy(front, 0, out, 0, minOf(n, front.size))
            return out
        }

        // Pull from parent's register map without re-recording.
        val regs = super.registers()
        val out = ByteArray(n)
        for (i in 0 until n) {
            out[i] = (regs[addr + i] ?: 0).toByte()
        }
        return out
    }
}

class Mcp2515Test {

    private fun freshConnection(): McpMock {
        val connection = McpMock()
        connection.queueWriteRead(Mcp2515Minimal.REG_CANSTAT,
            byteArrayOf(0x80.toByte()))  // init verification: OPMOD=Config
        connection.queueWriteRead(Mcp2515Minimal.REG_CANSTAT,
            byteArrayOf(0x00))  // waitMode poll: OPMOD=Normal
        return connection
    }

    private fun findWrite(connection: MockConnection, instr: Int, reg: Int): ByteArray? {
        val r = reg and 0xFF
        for (w in connection.writes()) {
            val minLen = if (instr == INSTR_BIT_MODIFY) 4 else 3
            if (w.size < minLen) continue
            if ((w[0].toInt() and 0xFF) != (instr and 0xFF)) continue
            if ((w[1].toInt() and 0xFF) != r) continue
            return w
        }
        return null
    }

    private fun findLastWrite(connection: MockConnection, instr: Int, reg: Int): ByteArray? {
        val r = reg and 0xFF
        var last: ByteArray? = null
        for (w in connection.writes()) {
            val minLen = if (instr == INSTR_BIT_MODIFY) 4 else 3
            if (w.size < minLen) continue
            if ((w[0].toInt() and 0xFF) != (instr and 0xFF)) continue
            if ((w[1].toInt() and 0xFF) != r) continue
            last = w
        }
        return last
    }

    private fun findWriteByte(connection: MockConnection, instr: Int, reg: Int): Int {
        val w = findWrite(connection, instr, reg)
            ?: throw AssertionError("No ${Integer.toHexString(instr)} write to reg 0x" +
                    Integer.toHexString(reg))
        return w[2].toInt() and 0xFF
    }

    private fun findFirstWrite(connection: MockConnection, firstByte: Int): ByteArray? {
        val target = firstByte and 0xFF
        for (w in connection.writes()) {
            if (w.isNotEmpty() && (w[0].toInt() and 0xFF) == target) return w
        }
        return null
    }

    private fun buildRxFrame(sidh: Int, sidl: Int, eid8: Int, eid0: Int,
                             dlc: Int, data: ByteArray): ByteArray {
        val buf = ByteArray(14)
        buf[0] = sidh.toByte()
        buf[1] = sidl.toByte()
        buf[2] = eid8.toByte()
        buf[3] = eid0.toByte()
        buf[4] = dlc.toByte()
        val len = minOf(8, data.size)
        System.arraycopy(data, 0, buf, 5, len)
        return buf
    }

    @Test
    fun initWritesResetAndCnfRegisters() {
        val connection = freshConnection()
        Mcp2515Minimal(connection, 125, 8)

        val firstWrite = connection.writes()[0]
        assertEquals(1, firstWrite.size)
        assertEquals(INSTR_RESET, firstWrite[0].toInt() and 0xFF)

        assertEquals(0x01, findWriteByte(connection, INSTR_WRITE, Mcp2515Minimal.REG_CNF1))
        assertEquals(0xBA, findWriteByte(connection, INSTR_WRITE, Mcp2515Minimal.REG_CNF2))
        assertEquals(0x03, findWriteByte(connection, INSTR_WRITE, Mcp2515Minimal.REG_CNF3))
    }

    @Test
    fun initAcceptsAllBitrateAndOscPresets() {
        for (osc in intArrayOf(8, 16)) {
            val cnf1 = if (osc == 8) CNF1_8MHZ else CNF1_16MHZ
            val cnf2 = if (osc == 8) CNF2_8MHZ else CNF2_16MHZ
            val cnf3 = if (osc == 8) CNF3_8MHZ else CNF3_16MHZ
            val bitrates = intArrayOf(125, 250, 500, 1000)
            for (b in 0..3) {
                val connection = freshConnection()
                Mcp2515Minimal(connection, bitrates[b], osc)
                assertEquals(cnf1[b], findWriteByte(connection, INSTR_WRITE, Mcp2515Minimal.REG_CNF1),
                    "CNF1 mismatch at ${bitrates[b]}/$osc")
                assertEquals(cnf2[b], findWriteByte(connection, INSTR_WRITE, Mcp2515Minimal.REG_CNF2),
                    "CNF2 mismatch at ${bitrates[b]}/$osc")
                assertEquals(cnf3[b], findWriteByte(connection, INSTR_WRITE, Mcp2515Minimal.REG_CNF3),
                    "CNF3 mismatch at ${bitrates[b]}/$osc")
            }
        }
    }

    @Test
    fun initRejectsBadParameters() {
        assertThrows(IllegalArgumentException::class.java) {
            Mcp2515Minimal(freshConnection(), 100, 8)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Mcp2515Minimal(freshConnection(), 125, 20)
        }
    }

    @Test
    fun initFailsIfNotInConfigModeAfterReset() {
        val connection = McpMock()
        connection.queueWriteRead(Mcp2515Minimal.REG_CANSTAT, byteArrayOf(0x00))
        connection.queueWriteRead(Mcp2515Minimal.REG_CANSTAT, byteArrayOf(0x00))
        assertThrows(IOException::class.java) { Mcp2515Minimal(connection, 125, 8) }
    }

    @Test
    fun initWritesAcceptAllMasksAndRxbMode() {
        val connection = freshConnection()
        Mcp2515Minimal(connection, 125, 8)

        for (reg in 0x20..0x27) {
            assertEquals(0xFF, findWriteByte(connection, INSTR_WRITE, reg))
        }
        assertEquals(0x64, findWriteByte(connection, INSTR_WRITE, Mcp2515Minimal.REG_RXB0CTRL))
        assertEquals(0x60, findWriteByte(connection, INSTR_WRITE, Mcp2515Minimal.REG_RXB1CTRL))
    }

    @Test
    fun sendStandardFramePacksIdCorrectly() {
        val connection = freshConnection()
        val can = Mcp2515Full(connection, 125, 8)

        connection.queueWriteRead(Mcp2515Minimal.REG_TXB0CTRL,
            byteArrayOf(0x08))  // TXREQ still set
        connection.queueWriteRead(Mcp2515Minimal.REG_TXB0CTRL,
            byteArrayOf(0x00))  // TXREQ clear

        can.send(0x123, byteArrayOf(0x11, 0x22, 0x33, 0x44))

        val loadWrite = findFirstWrite(connection, INSTR_LOAD_TX_BUF)
        assertNotNull(loadWrite)
        assertEquals(0x40, loadWrite!![0].toInt() and 0xFF)  // LOAD TXB0SIDH opcode
        assertEquals(0x24, loadWrite[1].toInt() and 0xFF)  // SIDH
        assertEquals(0x60, loadWrite[2].toInt() and 0xFF)  // SIDL
        assertEquals(0x00, loadWrite[3].toInt() and 0xFF)
        assertEquals(0x00, loadWrite[4].toInt() and 0xFF)
        assertEquals(0x04, loadWrite[5].toInt() and 0xFF)  // DLC
        assertEquals(0x11, loadWrite[6].toInt() and 0xFF)
        assertEquals(0x22, loadWrite[7].toInt() and 0xFF)
        assertEquals(0x33, loadWrite[8].toInt() and 0xFF)
        assertEquals(0x44, loadWrite[9].toInt() and 0xFF)

        assertEquals(0x0B, findWriteByte(connection, INSTR_WRITE, Mcp2515Minimal.REG_TXB0CTRL))

        val rtsWrite = findFirstWrite(connection, INSTR_RTS or 0x01 /* TXB0 */)
        assertNotNull(rtsWrite)
        assertEquals(0x81, rtsWrite!![0].toInt() and 0xFF)
    }

    @Test
    fun sendExtendedFramePacksIdCorrectly() {
        val connection = freshConnection()
        val can = Mcp2515Full(connection, 125, 8)

        connection.queueWriteRead(Mcp2515Minimal.REG_TXB0CTRL, byteArrayOf(0x00))

        val id = 0x1FFFFFFF
        can.send(id, byteArrayOf(0xAA.toByte()), true)

        val loadWrite = findFirstWrite(connection, INSTR_LOAD_TX_BUF)
        assertEquals(0xFF, loadWrite!![1].toInt() and 0xFF)
        assertEquals(0xEB, loadWrite[2].toInt() and 0xFF)
        assertEquals(0xFF, loadWrite[3].toInt() and 0xFF)
        assertEquals(0xFF, loadWrite[4].toInt() and 0xFF)
        assertEquals(0x01, loadWrite[5].toInt() and 0xFF)
    }

    @Test
    fun sendRejectsOutOfRangeId() {
        val connection = freshConnection()
        val can = Mcp2515Full(connection, 125, 8)
        assertThrows(IllegalArgumentException::class.java) {
            can.send(0x800, byteArrayOf(0x01), false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            can.send(0x20000000, byteArrayOf(0x01), true)
        }
    }

    @Test
    fun sendRejectsBadBufferIndex() {
        val connection = freshConnection()
        val can = Mcp2515Full(connection, 125, 8)
        assertThrows(IllegalArgumentException::class.java) {
            can.sendBuffered(0x100, byteArrayOf(0x01), false, 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            can.sendBuffered(0x100, byteArrayOf(0x01), false, -1)
        }
    }

    @Test
    fun recvReturnsNullWhenNoFramePending() {
        val connection = freshConnection()
        val can = Mcp2515Minimal(connection, 125, 8)

        connection.queueWriteRead(Mcp2515Minimal.REG_CANINTF, byteArrayOf(0x00))
        assertNull(can.recv())
        assertNull(can.recv(10))
    }

    @Test
    fun recvUnpacksStandardFrame() {
        val connection = freshConnection()
        val can = Mcp2515Minimal(connection, 125, 8)

        connection.queueWriteRead(Mcp2515Minimal.REG_CANINTF, byteArrayOf(0x01))
        connection.queueWriteRead(Mcp2515Minimal.REG_RXB0CTRL + 1,
            buildRxFrame(0x24, 0x60, 0x00, 0x00, 0x04,
                byteArrayOf(0x11, 0x22, 0x33, 0x44)))

        val frame = can.recv(10)
        assertNotNull(frame)
        assertEquals(0x123, frame!!.id)
        assertFalse(frame.extended)
        assertFalse(frame.rtr)
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33, 0x44), frame.data)
    }

    @Test
    fun recvUnpacksExtendedFrameWithRtr() {
        val connection = freshConnection()
        val can = Mcp2515Minimal(connection, 125, 8)

        connection.queueWriteRead(Mcp2515Minimal.REG_CANINTF, byteArrayOf(0x02))
        connection.queueWriteRead(Mcp2515Minimal.REG_RXB1CTRL + 1,
            buildRxFrame(0x00, 0x09, 0x23, 0x45, 0x40,
                byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)))

        val frame = can.recv(10)
        assertNotNull(frame)
        assertEquals(0x12345, frame!!.id)
        assertTrue(frame.extended)
        assertTrue(frame.rtr)
    }

    @Test
    fun sendBufferedWaitsForBusyBuffer() {
        val connection = freshConnection()
        val can = Mcp2515Full(connection, 125, 8)

        // 4 reads during the pre-RTS busy-wait loop, then the post-RTS poll
        // reads once more (registers[TXB0CTRL] is unset → returns 0).
        connection.queueWriteRead(Mcp2515Minimal.REG_TXB0CTRL, byteArrayOf(0x08))
        connection.queueWriteRead(Mcp2515Minimal.REG_TXB0CTRL, byteArrayOf(0x08))
        connection.queueWriteRead(Mcp2515Minimal.REG_TXB0CTRL, byteArrayOf(0x08))
        connection.queueWriteRead(Mcp2515Minimal.REG_TXB0CTRL, byteArrayOf(0x00))

        can.sendBuffered(0x100, byteArrayOf(0x01), false, 0)

        var pollCount = 0
        for (w in connection.writes()) {
            if (w.size == 2 && (w[0].toInt() and 0xFF) == INSTR_READ &&
                    (w[1].toInt() and 0xFF) == Mcp2515Minimal.REG_TXB0CTRL) {
                pollCount++
            }
        }
        assertEquals(5, pollCount)  // 4 pre-RTS + 1 post-RTS
    }

    @Test
    fun setModeSwitchesViaCanctrlAndPollsCanstat() {
        val connection = freshConnection()
        val can = Mcp2515Full(connection, 125, 8)

        connection.queueWriteRead(Mcp2515Minimal.REG_CANSTAT, byteArrayOf(0x40))

        can.setMode("loopback")

        // Construction already issued a CANCTRL write (REQOP=normal) during
        // Init, so we need the *last* write, not the first.
        val canctrlWrite = findLastWrite(connection, INSTR_WRITE, Mcp2515Minimal.REG_CANCTRL)
        assertEquals(0x40, canctrlWrite!![2].toInt() and 0xFF)
    }

    @Test
    fun setFilterEntersAndExitsConfigMode() {
        val connection = freshConnection()
        val can = Mcp2515Full(connection, 125, 8)

        connection.queueWriteRead(Mcp2515Minimal.REG_CANSTAT, byteArrayOf(0x00))  // current = normal
        connection.queueWriteRead(Mcp2515Minimal.REG_CANSTAT, byteArrayOf(0x80.toByte()))  // config
        connection.queueWriteRead(Mcp2515Minimal.REG_CANSTAT, byteArrayOf(0x00))  // back to normal

        can.setFilter(0, 0x123, false)

        assertEquals(0x24, findWriteByte(connection, INSTR_WRITE, 0x00))
        assertEquals(0x60, findWriteByte(connection, INSTR_WRITE, 0x01))
    }

    @Test
    fun setMaskRejectsBadMaskNum() {
        val connection = freshConnection()
        val can = Mcp2515Full(connection, 125, 8)
        assertThrows(IllegalArgumentException::class.java) { can.setMask(2, 0, false) }
    }

    @Test
    fun setRxModeRejectsBadMode() {
        val connection = freshConnection()
        val can = Mcp2515Full(connection, 125, 8)
        assertThrows(IllegalArgumentException::class.java) { can.setRxMode(0, 2) }
        assertThrows(IllegalArgumentException::class.java) { can.setRxMode(0, 4) }
        assertThrows(IllegalArgumentException::class.java) { can.setRxMode(2, 0) }
    }

    @Test
    fun readErrorsReturnsTecRecEflg() {
        val connection = freshConnection()
        val can = Mcp2515Full(connection, 125, 8)

        connection.queueWriteRead(Mcp2515Full.REG_TEC, byteArrayOf(0x42))
        connection.queueWriteRead(Mcp2515Full.REG_REC, byteArrayOf(0x10))
        connection.queueWriteRead(Mcp2515Full.REG_EFLG, byteArrayOf(0x80.toByte()))

        val errs = can.readErrors()
        assertEquals(0x42, errs.tec)
        assertEquals(0x10, errs.rec)
        assertEquals(0x80, errs.eflg)
    }

    @Test
    fun clearOverflowRejectsBadBuffer() {
        val connection = freshConnection()
        val can = Mcp2515Full(connection, 125, 8)
        assertThrows(IllegalArgumentException::class.java) { can.clearOverflow(2) }
    }

    @Test
    fun setOneShotWritesOsmBit() {
        val connection = freshConnection()
        val can = Mcp2515Full(connection, 125, 8)

        can.setOneShot(true)
        val modifyWrite = findWrite(connection, INSTR_BIT_MODIFY, Mcp2515Minimal.REG_CANCTRL)
        assertEquals(0x08, modifyWrite!![2].toInt() and 0xFF)
        assertEquals(0x08, modifyWrite[3].toInt() and 0xFF)

        can.setOneShot(false)
        val modifyWrite2 = findLastWrite(connection, INSTR_BIT_MODIFY, Mcp2515Minimal.REG_CANCTRL)
        assertEquals(0x08, modifyWrite2!![2].toInt() and 0xFF)
        assertEquals(0x00, modifyWrite2[3].toInt() and 0xFF)
    }

    @Test
    fun getModeReturnsString() {
        val connection = freshConnection()
        val can = Mcp2515Full(connection, 125, 8)

        connection.queueWriteRead(Mcp2515Minimal.REG_CANSTAT, byteArrayOf(0x00))
        assertEquals("normal", can.getMode())

        connection.queueWriteRead(Mcp2515Minimal.REG_CANSTAT, byteArrayOf(0x40))
        assertEquals("loopback", can.getMode())
    }

    @Test
    fun setModeRejectsUnknownMode() {
        val connection = freshConnection()
        val can = Mcp2515Full(connection, 125, 8)
        assertThrows(IllegalArgumentException::class.java) { can.setMode("wibble") }
    }

    @Test
    fun resetWritesResetInstruction() {
        val connection = freshConnection()
        val can = Mcp2515Full(connection, 125, 8)

        can.reset()
        val lastWrite = connection.writes()[connection.writes().size - 1]
        assertEquals(1, lastWrite.size)
        assertEquals(INSTR_RESET, lastWrite[0].toInt() and 0xFF)
    }
}
