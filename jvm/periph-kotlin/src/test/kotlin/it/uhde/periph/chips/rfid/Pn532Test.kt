package it.uhde.periph.chips.rfid

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class Pn532Test {

    companion object {
        val ACK = b(0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00)
        val NACK = b(0x00, 0x00, 0xFF, 0xFF, 0x00, 0x00)
        val ERROR_FRAME = b(0x00, 0x00, 0xFF, 0x01, 0xFF, 0x7F, 0x81, 0x00)

        fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

        fun frame(tfi: Int, data: ByteArray): ByteArray {
            val len = data.size + 1
            var sum = tfi
            data.forEach { sum += it.toInt() and 0xFF }
            return b(0x00, 0x00, 0xFF, len, 0x100 - len, tfi) + data + b(0x100 - (sum and 0xFF), 0x00)
        }
    }

    class Cmd(val cmd: Int, val params: ByteArray) {
        fun isCmd(c: Int, vararg p: Int) = cmd == c && params.contentEquals(b(*p))
    }

    /** Simulates the PN532 I²C frame protocol: ACK, response, NACK resend, ACK abort, status byte. */
    private class Sim : MockConnection() {
        val queue = ArrayDeque<ByteArray>()
        var lastFrame: ByteArray? = null
        val commands = mutableListOf<Cmd>()
        val written = mutableListOf<ByteArray>()
        val reads = mutableListOf<Int>()
        var card = true
        var noAck = false
        var noResponse = false
        var errorFrame = false
        var badDcs = false
        var ic = 0x32
        var status = 0
        var failWrites = 0
        val uid = b(0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
        var listResponse: ByteArray? = null
        val regs = mutableMapOf<Int, Int>()
        var pin: InputPin? = null

        fun last() = commands.last()

        fun respond(cmd: Int, p: ByteArray): ByteArray = when (cmd) {
            0x02 -> b(ic, 0x01, 0x06, 0x07)
            0x04 -> b(0x00, 0x01, 0x01, 0x01, 0x00, 0x00, 0x10, 0x01)
            0x4A -> listResponse ?: if (!card) b(0) else b(1, 1, 0x00, 0x44, 0x00, uid.size) + uid
            0x52, 0x54, 0x44, 0x16 -> b(status)
            0x40 -> when {
                status != 0 -> b(status)
                p[1].toInt() == 0x30 -> b(0) + ByteArray(16) { (p[2] + it).toByte() }
                else -> b(0)
            }
            0x42 -> b(0) + p.reversedArray()
            0x06 -> b(regs[((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)] ?: 0)
            0x08 -> { regs[((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)] = p[2].toInt() and 0xFF; ByteArray(0) }
            0x00 -> if (p[0].toInt() == 0) p.copyOf() else b(0)
            else -> ByteArray(0)
        }

        override fun write(data: ByteArray) {
            written.add(data.copyOf())
            if (failWrites > 0) { failWrites--; throw IOException("NACK") }
            if (data.contentEquals(ACK)) { queue.clear(); return }
            if (data.contentEquals(NACK)) { lastFrame?.let { queue.addLast(it) }; return }
            val ln = data[3].toInt() and 0xFF
            val c = Cmd(data[6].toInt() and 0xFF, data.copyOfRange(7, 5 + ln))
            commands.add(c)
            if (noAck) return
            queue.clear()
            queue.addLast(ACK)
            if (noResponse) return
            if (errorFrame) { queue.addLast(ERROR_FRAME); return }
            val f = frame(0xD5, b(c.cmd + 1) + respond(c.cmd, c.params))
            if (badDcs) f[f.size - 2]++
            queue.addLast(f)
        }

        override fun read(n: Int): ByteArray {
            reads.add(n)
            val out = ByteArray(n)
            if (queue.isEmpty()) return out
            out[0] = 0x01
            if (n == 1) return out
            val item = queue.removeFirst()
            if (!item.contentEquals(ACK)) lastFrame = item
            for (i in 1 until n) out[i] = if (i - 1 < item.size) item[i - 1] else 0xFF.toByte()
            return out
        }

        override fun intPin(): InputPin? = pin
    }

    @Test
    fun frameMatchesDatasheetVector() {
        assertArrayEquals(b(0x00, 0x00, 0xFF, 0x02, 0xFE, 0xD4, 0x02, 0x2A, 0x00), Pn532Minimal.frame(0x02, ByteArray(0)))
    }

    @Test
    fun minimalInitAndReadUid() {
        val sim = Sim()
        val m = Pn532Minimal(sim)
        m.init()
        assertEquals(3, sim.commands.size)
        assertTrue(sim.commands[1].isCmd(0x14, 0x01, 0x14, 0x01))
        assertTrue(sim.commands[2].isCmd(0x32, 0x05, 0xFF, 0x01, 0x02))
        assertArrayEquals(NACK, sim.written[1])
        assertEquals(3, sim.reads.count { it == 7 })
        assertTrue(sim.reads.contains(14))
        assertArrayEquals(sim.uid, m.readUid())
        assertTrue(sim.commands[3].isCmd(0x4A, 0x01, 0x00))
        assertTrue(sim.commands[4].isCmd(0x52, 0x01))
        sim.card = false
        assertNull(m.readUid())
        sim.noResponse = true
        assertNull(m.readUid(0.01))
        assertArrayEquals(ACK, sim.written.last())
        sim.noResponse = false
        sim.errorFrame = true
        assertThrows(Pn532Exception::class.java) { m.readUid() }
        sim.errorFrame = false
        sim.card = true
        sim.badDcs = true
        assertThrows(Pn532Exception::class.java) { m.readUid() }
    }

    @Test
    fun minimalErrors() {
        val bad = Sim().apply { ic = 0x31 }
        assertThrows(Pn532Exception::class.java) { Pn532Minimal(bad).init() }
        val retry = Sim().apply { failWrites = 3 }
        Pn532Minimal(retry).init()
        assertEquals(3, retry.commands.size)
        val noAck = Sim().apply { noAck = true }
        assertThrows(Pn532Exception::class.java) { Pn532Minimal(noAck).init() }
        assertEquals(3, noAck.commands.size)
    }

    @Test
    fun fullInfoAndList() {
        val sim = Sim()
        val f = Pn532Full(sim)
        assertEquals(Pn532Full.FirmwareVersion(0x32, 1, 6, 7, true, true, true), f.firmwareVersion())
        assertEquals(Pn532Full.GeneralStatus(0, true, 1, 1), f.generalStatus())
        sim.listResponse = b(2, 1, 0x00, 0x04, 0x20, 4, 1, 2, 3, 4, 5, 0x75, 0x77, 0x81, 0x02,
            2, 0x00, 0x44, 0x00, 7, 9, 9, 9, 9, 9, 9, 9)
        var t = f.listPassiveTargets(Pn532Full.BAUD_106_A, 2)
        assertEquals(2, t.size)
        assertEquals(1, t[0].tg)
        assertEquals(0x0004, t[0].sensRes)
        assertEquals(0x20, t[0].selRes)
        assertArrayEquals(b(1, 2, 3, 4), t[0].uid)
        assertArrayEquals(b(5, 0x75, 0x77, 0x81, 0x02), t[0].ats)
        assertEquals(0x0044, t[1].sensRes)
        assertEquals(7, t[1].uid.size)
        assertEquals(0, t[1].ats.size)
        assertTrue(sim.last().isCmd(0x4A, 0x02, 0x00))
        sim.listResponse = b(1, 1, 20, 0x01, 1, 2, 3, 4, 5, 6, 7, 8, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x12, 0xFC)
        t = f.listPassiveTargets(Pn532Full.BAUD_212_F, 1, b(0x00, 0xFF, 0xFF, 0x01, 0x00))
        assertArrayEquals(b(1, 2, 3, 4, 5, 6, 7, 8), t[0].uid)
        assertArrayEquals(b(0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x12, 0xFC), t[0].ats)
        assertTrue(sim.last().isCmd(0x4A, 0x01, 0x01, 0x00, 0xFF, 0xFF, 0x01, 0x00))
        sim.listResponse = b(1, 1, 0x50, 0xA1, 0xA2, 0xA3, 0xA4, 0, 0, 0, 0, 0x81, 0x81, 0x00, 1, 0x00)
        t = f.listPassiveTargets(Pn532Full.BAUD_106_B, 1, b(0x00))
        assertArrayEquals(b(0xA1, 0xA2, 0xA3, 0xA4), t[0].uid)
        assertArrayEquals(b(0x00), t[0].ats)
        sim.listResponse = b(0)
        assertTrue(f.listPassiveTargets().isEmpty())
    }

    @Test
    fun fullTagOperations() {
        val sim = Sim()
        val f = Pn532Full(sim)
        val block = f.mifareReadBlock(1, 4)
        assertEquals(4, block[0].toInt())
        assertEquals(19, block[15].toInt())
        assertTrue(sim.last().isCmd(0x40, 0x01, 0x30, 0x04))
        f.mifareAuthenticate(1, 7, b(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF), sim.uid)
        assertTrue(sim.last().isCmd(0x40, 0x01, 0x60, 0x07, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x33, 0x44, 0x55, 0x66))
        f.mifareAuthenticate(1, 7, ByteArray(6), b(0xDE, 0xAD, 0xBE, 0xEF), true)
        assertEquals(0x61, sim.last().params[1].toInt())
        f.mifareWriteBlock(1, 4, ByteArray(16))
        assertTrue(sim.last().isCmd(0x40, 0x01, 0xA0, 0x04, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        assertEquals(4, f.ntagReadPage(1, 4)[0].toInt())
        f.ntagWritePage(1, 5, b(1, 2, 3, 4))
        assertTrue(sim.last().isCmd(0x40, 0x01, 0xA2, 0x05, 1, 2, 3, 4))
        assertThrows(IllegalArgumentException::class.java) { f.ntagWritePage(1, 5, b(1)) }
        assertArrayEquals(b(3, 2, 1), f.communicateThru(b(1, 2, 3)))
        sim.status = 0x14
        assertEquals(0x14, assertThrows(Pn532Exception::class.java) { f.dataExchange(1, b(0x30, 0x00)) }.code)
        assertEquals(0x14, assertThrows(Pn532Exception::class.java) { f.release(1) }.code)
        sim.status = 0
        f.release()
        assertTrue(sim.last().isCmd(0x52, 0x00))
        f.select(1)
        assertTrue(sim.last().isCmd(0x54, 0x01))
        f.deselect(1)
        assertTrue(sim.last().isCmd(0x44, 0x01))
    }

    @Test
    fun fullConfigAndRaw() {
        val sim = Sim()
        val f = Pn532Full(sim)
        f.setRfField(false)
        assertTrue(sim.last().isCmd(0x32, 0x01, 0x00))
        f.setRfField(true, true)
        assertTrue(sim.last().isCmd(0x32, 0x01, 0x03))
        f.setMaxRetries(0xFF)
        assertTrue(sim.last().isCmd(0x32, 0x05, 0xFF, 0x01, 0xFF))
        f.writeRegister(0x6302, 0x5A)
        assertTrue(sim.last().isCmd(0x08, 0x63, 0x02, 0x5A))
        assertEquals(0x5A, f.readRegister(0x6302))
        assertTrue(sim.last().isCmd(0x06, 0x63, 0x02))
        assertTrue(f.selfTest())
        assertTrue(sim.last().isCmd(0x00, 0x00, 'P'.code, 'E'.code, 'R'.code, 'I'.code, 'P'.code, 'H'.code, '0'.code, '1'.code))
        assertTrue(f.selfTest(0x01))
        assertArrayEquals(b(0x32, 0x01, 0x06, 0x07), f.sendCommand(0x02))
        f.powerDown()
        assertTrue(sim.last().isCmd(0x16, 0x20))
        f.startCommand(0x02)
        assertTrue(f.waitReady(0.01))
        assertTrue(f.pollInterrupt())
        assertArrayEquals(b(0x32, 0x01, 0x06, 0x07), f.readResponse(0x02))
        assertFalse(f.pollInterrupt())
        assertFalse(f.waitReady(0.005))
    }

    @Test
    fun interrupt() {
        val sim = Sim()
        var handler: EdgeHandler? = null
        sim.pin = object : InputPin {
            override fun onEdge(h: EdgeHandler, t: EdgeTrigger) { handler = h }
            override fun offEdge(h: EdgeHandler) { if (handler === h) handler = null }
            override fun isHigh() = true
            override fun close() {}
        }
        val f = Pn532Full(sim)
        var hits = 0
        f.onInterrupt({ hits++ })
        assertNotNull(handler)
        handler!!.onEdge()
        assertEquals(1, hits)
        f.offInterrupt()
        assertNull(handler)
    }
}
