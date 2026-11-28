package it.uhde.periph.chips.rfid

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class St25dvTest {

    private fun mock() = MockConnection().apply { setAddressWidth(2) }

    private fun system(icRef: Int, memLo: Int, memHi: Int) = mock().apply {
        setRegister(0x0014, memLo, memHi, 0x03, icRef)
        setRegister(0x0018, 0x11, 0x22, 0x33, 0x44, 0x55, 0x26, 0x02, 0xE0)
        setRegister(0x0020, 0x12)
        setRegister(0x0000, 0x88)
        setRegister(0x0002, 0x01)
    }

    private fun reg(c: MockConnection, a: Int) = c.registers()[a] ?: 0

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private class FakePin : InputPin {
        var handler: EdgeHandler? = null
        var trigger: EdgeTrigger? = null
        override fun onEdge(handler: EdgeHandler, trigger: EdgeTrigger) { this.handler = handler; this.trigger = trigger }
        override fun offEdge(handler: EdgeHandler) { if (handler === this.handler) this.handler = null }
        override fun isHigh() = true
        override fun close() {}
    }

    @Test
    fun minimalReadWrite() {
        val user = mock()
        val sys = system(0x26, 0xFF, 0x01)
        val tag = St25dvMinimal(user, sys)
        assertEquals(1, sys.writes().size)
        assertTrue(user.writes().isEmpty())
        user.setRegister(0x0100, 1, 2, 3)
        assertArrayEquals(bytes(1, 2, 3), tag.read(0x0100, 3))
        assertThrows(IllegalArgumentException::class.java) { tag.read(2047, 2) }
        val data = ByteArray(40) { it.toByte() }
        user.writes().clear()
        tag.write(0x0008, data)
        val frames = user.writes().filter { it.size > 2 }
        assertEquals(3, frames.size)
        assertEquals(3, user.writes().count { it.size == 2 })
        assertEquals(10, frames[0].size)
        assertEquals(0x10, frames[1][1].toInt())
        assertEquals(0x20, frames[2][1].toInt())
        for (i in 0 until 40) assertEquals(i, reg(user, 0x08 + i))
        assertThrows(IllegalArgumentException::class.java) { tag.write(2040, ByteArray(9)) }
        user.failNextWrites(1000, 1)
        assertThrows(IOException::class.java) { tag.write(0, bytes(1)) }
        user.failNextWrites(0, 0)
        user.setRegister(0x0040, 0x77)
        user.failNextWrites(2, 0)
        assertArrayEquals(bytes(0x77), tag.read(0x0040, 1))
        user.failNextWrites(0, 0)
    }

    @Test
    fun identificationPasswordAreas() {
        assertEquals(512, St25dvFull(mock(), system(0x24, 0x7F, 0x00)).memorySize())
        assertEquals(8192, St25dvFull(mock(), system(0x26, 0xFF, 0x07)).memorySize())
        val user = mock()
        val sys = system(0x26, 0xFF, 0x01)
        val tag = St25dvFull(user, sys)
        assertEquals(0x26, tag.icReference())
        assertArrayEquals(bytes(0xE0, 0x02, 0x26, 0x55, 0x44, 0x33, 0x22, 0x11), tag.uid())
        assertEquals(0x12, tag.icRevision())

        val pw = bytes(1, 2, 3, 4, 5, 6, 7, 8)
        val frame = bytes(0x09, 0x00, 1, 2, 3, 4, 5, 6, 7, 8, 0x09, 1, 2, 3, 4, 5, 6, 7, 8)
        user.setRegister(0x2004, 0x01)
        sys.writes().clear()
        assertTrue(tag.presentPassword(pw))
        assertArrayEquals(frame, sys.writes()[0])
        user.setRegister(0x2004, 0x00)
        assertThrows(IOException::class.java) { tag.writePassword(pw) }
        user.setRegister(0x2004, 0x01)
        sys.writes().clear()
        tag.writePassword(pw)
        frame[10] = 0x07
        assertArrayEquals(frame, sys.writes()[0])

        sys.writes().clear()
        tag.setAreaEnd(2, 0x1F)
        assertArrayEquals(bytes(0x00, 0x07, 0x1F), sys.writes()[0])
        sys.setRegister(0x000B, 0x00)
        tag.setAreaProtection(3, 0x05, 0x03)
        tag.setAreaProtection(2, 0x0C, 0x01)
        assertEquals(0x34, reg(sys, 0x000B))
        assertEquals(St25dvFull.AreaProtection(0x05, 0x03), tag.getAreaProtection(3))
        assertThrows(IllegalArgumentException::class.java) { tag.setAreaProtection(1, 0, 4) }
    }

    @Test
    fun gpoPowerMailbox() {
        val user = mock()
        val sys = system(0x26, 0xFF, 0x01)
        val tag = St25dvFull(user, sys)
        tag.setGpo(St25dvFull.GPO_FIELD_CHANGE or St25dvFull.GPO_RF_WRITE)
        assertEquals(0xC8, reg(sys, 0x0000))
        tag.setGpoRuntime(true)
        assertEquals(0x80, reg(user, 0x2000))
        tag.setInterruptPulse(3)
        assertEquals(3, reg(sys, 0x0001))
        user.setRegister(0x2002, 0x0C)
        assertTrue(tag.fieldPresent())
        sys.setRegister(0x0002, 0x01)
        tag.setEnergyHarvesting(false, true)
        assertEquals(0x00, reg(sys, 0x0002))
        tag.setRfState(sleep = true)
        assertEquals(0x02, reg(user, 0x2003))

        sys.setRegister(0x000D, 0x00)
        tag.enableMailbox()
        assertEquals(0x01, reg(sys, 0x000D))
        user.setRegister(0x2006, 0x01 or 0x04)
        assertTrue(tag.mailboxStatus().rfPut)
        assertThrows(IOException::class.java) { tag.mailboxWrite(bytes(1)) }
        user.setRegister(0x2006, 0x01)
        user.writes().clear()
        tag.mailboxWrite(ByteArray(100) { it.toByte() })
        assertEquals(102, user.writes().last().size)
        user.setRegister(0x2006, 0x05)
        user.setRegister(0x2007, 19)
        for (i in 0 until 20) user.setRegister(0x2008 + i, 0xA0 + i)
        val r = tag.mailboxRead()
        assertEquals(20, r.size)
        assertEquals(0xB3.toByte(), r[19])
        tag.setMailboxWatchdog(4)
        assertEquals(4, reg(sys, 0x000E))
        tag.lockCcfile()
        assertEquals(0x03, reg(sys, 0x000C))
    }

    @Test
    fun ndefAndInterrupts() {
        val user = mock()
        val sys = system(0x26, 0xFF, 0x01)
        val tag = St25dvFull(user, sys)
        val ndef = bytes(0xD1, 0x01, 0x04, 0x54, 0x02, 0x65, 0x6E, 0x48)
        tag.writeNdef(ndef)
        assertEquals(0xE1, reg(user, 0))
        assertEquals(0xFF, reg(user, 2))
        assertArrayEquals(ndef, tag.readNdef())
        val longMsg = ByteArray(300)
        tag.writeNdef(longMsg)
        assertArrayEquals(longMsg, tag.readNdef())
        assertThrows(IllegalArgumentException::class.java) { tag.writeNdef(ByteArray(2048)) }

        sys.setRegister(0x0000, 0x80)
        tag.enableInterrupt(St25dvFull.SOURCE_RF_PUT_MSG or St25dvFull.SOURCE_RF_USER or St25dvFull.SOURCE_FIELD_RISING or St25dvFull.SOURCE_RF_WRITE)
        assertEquals(0xD9, reg(sys, 0x0000))
        tag.disableInterrupt(St25dvFull.SOURCE_RF_WRITE)
        assertEquals(0x99, reg(sys, 0x0000))
        val pin = FakePin()
        val got = mutableListOf<Int>()
        tag.onInterrupt({ got.add(it) }, pin)
        assertEquals(EdgeTrigger.FALLING, pin.trigger)
        user.setRegister(0x2005, 0x10)
        pin.handler!!.onEdge()
        user.setRegister(0x2005, 0x00)
        pin.handler!!.onEdge()
        assertEquals(listOf(0x10), got)
        tag.offInterrupt()
        assertNull(pin.handler)
        val jf = St25dvFull(mock(), system(0x26, 0xFF, 0x01), true)
        val pinJf = FakePin()
        jf.onInterrupt({ }, pinJf)
        assertEquals(EdgeTrigger.RISING, pinJf.trigger)
        jf.offInterrupt()
    }
}
