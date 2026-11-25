package it.uhde.periph.chips.memory

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class AT24C64Test {

    private fun mock() = MockConnection().apply { setAddressWidth(2) }

    /** Frames carrying data (two address bytes plus at least one data byte). */
    private fun dataFrames(c: MockConnection) = c.writes().filter { it.size > 2 }

    @Test
    fun byteAccess() {
        val connection = mock()
        val ee = AT24C64Full(connection)
        assertTrue(connection.writes().isEmpty())
        assertEquals(8192, ee.size())
        assertEquals(32, ee.pageSize())

        connection.setRegister(0x1234, 0x42)
        assertEquals(0x42, ee.readByte(0x1234))
        assertArrayEquals(byteArrayOf(0x12, 0x34), connection.writes().last())
        assertEquals(0, ee.readByte(0x1FFF))

        connection.writes().clear()
        ee.writeByte(0x0010, 0x99)
        assertEquals(0x99, connection.registers()[0x0010])
        assertArrayEquals(byteArrayOf(0x00, 0x10, 0x99.toByte()), connection.writes()[0])
        assertArrayEquals(byteArrayOf(0x00, 0x00), connection.writes()[1])

        assertThrows(IllegalArgumentException::class.java) { ee.readByte(0x2000) }
        assertThrows(IllegalArgumentException::class.java) { ee.readByte(-1) }
        assertThrows(IllegalArgumentException::class.java) { ee.writeByte(0x2000, 1) }
        assertNotNull(AT24C64Minimal(connection))
    }

    @Test
    fun ackPolling() {
        val connection = mock()
        val ee = AT24C64Full(connection)
        connection.failNextWrites(2, 0)
        connection.writes().clear()
        assertTrue(ee.waitReady(50))
        assertEquals(3, connection.writes().size)
        connection.failNextWrites(100000, 0)
        assertFalse(ee.waitReady(5))
        connection.failNextWrites(0, 0)
        connection.failNextWrites(100000, 1)
        assertThrows(IOException::class.java) { ee.writeByte(0x0021, 2) }
        connection.failNextWrites(0, 0)
    }

    @Test
    fun pageSplitWrite() {
        val connection = mock()
        val ee = AT24C64Full(connection)
        assertEquals(30, ee.maxChunk)
        val data = ByteArray(70) { it.toByte() }
        ee.write(0x0070, data)
        val frames = dataFrames(connection)
        assertEquals(4, frames.size)
        assertArrayEquals(byteArrayOf(0x00, 0x70) + data.copyOfRange(0, 16), frames[0])
        assertArrayEquals(byteArrayOf(0x00, 0x80.toByte()) + data.copyOfRange(16, 46), frames[1])
        assertArrayEquals(byteArrayOf(0x00, 0x9E.toByte()) + data.copyOfRange(46, 48), frames[2])
        assertArrayEquals(byteArrayOf(0x00, 0xA0.toByte()) + data.copyOfRange(48, 70), frames[3])
        assertEquals(8, connection.writes().size)
        assertArrayEquals(data, ee.read(0x0070, 70))

        ee.maxChunk = 8
        connection.writes().clear()
        ee.write(0x0200, ByteArray(20))
        assertEquals(3, dataFrames(connection).size)
        assertThrows(IllegalArgumentException::class.java) { ee.maxChunk = 0 }
    }

    @Test
    fun writePageAndFill() {
        val connection = mock()
        val ee = AT24C64Full(connection)
        val page = ByteArray(32) { it.toByte() }
        ee.writePage(0x0300, page)
        for (i in 0 until 32) assertEquals(i, connection.registers()[0x0300 + i])
        assertThrows(IllegalArgumentException::class.java) { ee.writePage(0x037C, ByteArray(8)) }
        assertThrows(IllegalArgumentException::class.java) { ee.writePage(0x0400, ByteArray(33)) }
        assertThrows(IllegalArgumentException::class.java) { ee.writePage(0x1FFF, ByteArray(2)) }

        ee.fill(0x0500, 200, 0xAB)
        for (i in 0 until 200) assertEquals(0xAB, connection.registers()[0x0500 + i])
        assertNull(connection.registers()[0x0500 + 200])
        ee.fill(0x0500, 4)
        assertEquals(0, connection.registers()[0x0500])
    }

    @Test
    fun rangeRules() {
        val connection = mock()
        val ee = AT24C64Full(connection)
        ee.write(0x1FFE, byteArrayOf(1, 2))
        assertArrayEquals(byteArrayOf(1, 2), ee.read(0x1FFE, 2))
        assertThrows(IllegalArgumentException::class.java) { ee.write(0x1FFF, byteArrayOf(1, 2)) }
        assertThrows(IllegalArgumentException::class.java) { ee.read(0x1FF0, 0x20) }
        assertThrows(IllegalArgumentException::class.java) { ee.fill(0x1FFF, 2, 0) }
        assertEquals(0, ee.read(0, 0).size)
    }
}
