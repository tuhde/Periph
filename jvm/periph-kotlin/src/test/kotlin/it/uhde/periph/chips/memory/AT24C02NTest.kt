package it.uhde.periph.chips.memory

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class AT24C02NTest {

    private fun mock() = MockConnection()

    /** Frames carrying data (one address byte plus at least one data byte). */
    private fun dataFrames(c: MockConnection) = c.writes().filter { it.size > 1 }

    @Test
    fun byteAccess() {
        val connection = mock()
        val ee = AT24C02NFull(connection)
        assertTrue(connection.writes().isEmpty())
        assertEquals(256, ee.size())
        assertEquals(8, ee.pageSize())

        connection.setRegister(0x0034, 0x42)
        assertEquals(0x42, ee.readByte(0x0034))
        assertArrayEquals(byteArrayOf(0x34), connection.writes().last())
        assertEquals(0, ee.readByte(0x00FF))

        connection.writes().clear()
        ee.writeByte(0x0010, 0x99)
        assertEquals(0x99, connection.registers()[0x0010])
        assertArrayEquals(byteArrayOf(0x10, 0x99.toByte()), connection.writes()[0])
        assertArrayEquals(byteArrayOf(0x00), connection.writes()[1])

        assertThrows(IllegalArgumentException::class.java) { ee.readByte(0x0100) }
        assertThrows(IllegalArgumentException::class.java) { ee.readByte(-1) }
        assertThrows(IllegalArgumentException::class.java) { ee.writeByte(0x0100, 1) }
        assertNotNull(AT24C02NMinimal(connection))
    }

    @Test
    fun ackPolling() {
        val connection = mock()
        val ee = AT24C02NFull(connection)
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
        val ee = AT24C02NFull(connection)
        assertEquals(30, ee.maxChunk)
        val data = ByteArray(20) { it.toByte() }
        ee.write(0x0074, data)
        val frames = dataFrames(connection)
        assertEquals(3, frames.size)
        assertArrayEquals(byteArrayOf(0x74) + data.copyOfRange(0, 4), frames[0])
        assertArrayEquals(byteArrayOf(0x78) + data.copyOfRange(4, 12), frames[1])
        assertArrayEquals(byteArrayOf(0x80.toByte()) + data.copyOfRange(12, 20), frames[2])
        assertEquals(6, connection.writes().size)
        assertArrayEquals(data, ee.read(0x0074, 20))

        ee.maxChunk = 8
        connection.writes().clear()
        ee.write(0x00E0, ByteArray(20))
        assertEquals(3, dataFrames(connection).size)
        assertThrows(IllegalArgumentException::class.java) { ee.maxChunk = 0 }
    }

    @Test
    fun writePageAndFill() {
        val connection = mock()
        val ee = AT24C02NFull(connection)
        val page = ByteArray(8) { it.toByte() }
        ee.writePage(0x00C0, page)
        for (i in 0 until 8) assertEquals(i, connection.registers()[0x00C0 + i])
        assertThrows(IllegalArgumentException::class.java) { ee.writePage(0x00CC, ByteArray(8)) }
        assertThrows(IllegalArgumentException::class.java) { ee.writePage(0x00D0, ByteArray(9)) }
        assertThrows(IllegalArgumentException::class.java) { ee.writePage(0x00FF, ByteArray(2)) }

        ee.fill(0x0040, 100, 0xAB)
        for (i in 0 until 100) assertEquals(0xAB, connection.registers()[0x0040 + i])
        assertNull(connection.registers()[0x0040 + 100])
        ee.fill(0x0040, 4)
        assertEquals(0, connection.registers()[0x0040])
    }

    @Test
    fun rangeRules() {
        val connection = mock()
        val ee = AT24C02NFull(connection)
        ee.write(0x00FE, byteArrayOf(1, 2))
        assertArrayEquals(byteArrayOf(1, 2), ee.read(0x00FE, 2))
        assertThrows(IllegalArgumentException::class.java) { ee.write(0x00FF, byteArrayOf(1, 2)) }
        assertThrows(IllegalArgumentException::class.java) { ee.read(0x00F0, 0x20) }
        assertThrows(IllegalArgumentException::class.java) { ee.fill(0x00FF, 2, 0) }
        assertEquals(0, ee.read(0, 0).size)
    }
}
