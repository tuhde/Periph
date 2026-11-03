package it.uhde.periph.chips.memory

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MB85RCTest {

    private fun mock(width: Int) = MockConnection().apply { setAddressWidth(width) }

    @Test
    fun fullApi() {
        val connection = mock(2)
        val fram = MB85RCFull(connection)
        assertTrue(connection.writes().isEmpty())
        assertEquals(32768, fram.size())

        connection.setRegister(0x1234, 0x42)
        assertEquals(0x42, fram.readByte(0x1234))
        assertArrayEquals(byteArrayOf(0x12, 0x34), connection.writes().last())

        fram.writeByte(0x0010, 0x99)
        assertEquals(0x99, connection.registers()[0x0010])
        assertArrayEquals(byteArrayOf(0x00, 0x10, 0x99.toByte()), connection.writes().last())

        assertThrows(IllegalArgumentException::class.java) { fram.readByte(0x8000) }
        assertThrows(IllegalArgumentException::class.java) { fram.readByte(-1) }
        assertThrows(IllegalArgumentException::class.java) { fram.writeByte(0x8000, 1) }

        // Chunking: 70 bytes at maxChunk=30 -> 30 + 30 + 10.
        assertEquals(30, fram.maxChunk)
        val data = ByteArray(70) { it.toByte() }
        connection.writes().clear()
        fram.write(0x0100, data)
        assertEquals(3, connection.writes().size)
        assertEquals(32, connection.writes()[0].size)
        assertEquals(12, connection.writes()[2].size)
        assertEquals(30, connection.writes()[1][1].toInt())
        connection.writes().clear()
        assertArrayEquals(data, fram.read(0x0100, 70))
        assertEquals(3, connection.writes().size)

        fram.maxChunk = 8
        connection.writes().clear()
        fram.write(0x0200, ByteArray(20))
        assertEquals(3, connection.writes().size)
        assertThrows(IllegalArgumentException::class.java) { fram.maxChunk = 0 }
        fram.maxChunk = 30

        fram.fill(0x0300, 65, 0xAB)
        for (i in 0 until 65) assertEquals(0xAB, connection.registers()[0x0300 + i])
        assertNull(connection.registers()[0x0300 + 65])
        fram.fill(0x0300, 4)
        for (i in 0 until 4) assertEquals(0, connection.registers()[0x0300 + i])

        fram.write(0x7FFE, byteArrayOf(1, 2))
        assertArrayEquals(byteArrayOf(1, 2), fram.read(0x7FFE, 2))
        assertThrows(IllegalArgumentException::class.java) { fram.write(0x7FFF, byteArrayOf(1, 2)) }
        assertThrows(IllegalArgumentException::class.java) { fram.read(0x7FF0, 0x20) }
        assertThrows(IllegalArgumentException::class.java) { fram.fill(0x7FFF, 2) }
        assertEquals(0, fram.read(0, 0).size)
    }

    @Test
    fun deviceId() {
        val fram = MB85RCFull(mock(2))
        val id = mock(1)
        id.setRegister(0xA6, 0x00, 0xA5, 0x10)
        assertArrayEquals(intArrayOf(0x00A, 0x5, 0x10), fram.readDeviceId(id, 0x53))
        assertArrayEquals(byteArrayOf(0xA6.toByte()), id.writes().last())
        id.setRegister(0xA0, 0x00, 0xA5, 0x10)
        assertArrayEquals(intArrayOf(0x00A, 0x5, 0x10), fram.readDeviceId(id))
    }

    @Test
    fun minimal() {
        val fram = MB85RCMinimal(mock(2))
        fram.writeByte(0x0005, 0x77)
        assertEquals(0x77, fram.readByte(0x0005))
    }
}
