package it.uhde.periph.chips.memory

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.OutputPin
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * Behavioural 23X1024: parses the instruction frames the driver sends and
 * models the array, the MODE register and the Byte/Page/Sequential
 * address-increment rules. Every frame is recorded on [frames].
 */
private class SramModel : Connection {
    val mem = ByteArray(131072)
    val frames = ArrayList<ByteArray>()
    var mode = 0x40
    var dead = false

    private fun next(addr: Int): Int =
        if ((mode and 0xC0) == 0x80) (addr and 31.inv()) or ((addr + 1) and 31) else (addr + 1) and 0x1FFFF

    override fun enable() {}
    override fun disable() {}
    override fun isEnabled(): Boolean = true
    override fun intPin(): InputPin? = null
    override fun enPin(): OutputPin? = null

    override fun write(data: ByteArray) {
        frames.add(data.clone())
        val cmd = data[0].toInt() and 0xFF
        if (cmd == 0x01 && data.size == 2) {
            mode = data[1].toInt() and 0xFF
        } else if (cmd == 0x02 && data.size >= 4) {
            var addr = ((data[1].toInt() and 0xFF) shl 16) or ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
            for (i in 4 until data.size) {
                mem[addr] = data[i]
                addr = next(addr)
                if ((mode and 0xC0) == 0x00) break
            }
        }
    }

    override fun read(n: Int): ByteArray = ByteArray(n)

    override fun writeRead(data: ByteArray, n: Int): ByteArray {
        frames.add(data.clone())
        val out = ByteArray(n)
        val cmd = data[0].toInt() and 0xFF
        if (cmd == 0x05) {
            out[0] = if (dead) 0 else mode.toByte()
        } else if (cmd == 0x03 && data.size == 4) {
            var addr = ((data[1].toInt() and 0xFF) shl 16) or ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
            for (i in 0 until n) {
                out[i] = mem[addr]
                addr = next(addr)
                if ((mode and 0xC0) == 0x00) break
            }
        }
        return out
    }

    override fun close() {}
}

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
private fun pattern(n: Int) = ByteArray(n) { (it * 7 + 3).toByte() }

class Sram23x1024Test {

    @Test
    fun initWritesSequentialAndVerifies() {
        val conn = SramModel()
        Sram23x1024Minimal(conn)
        assertEquals(2, conn.frames.size)
        assertArrayEquals(bytes(0x01, 0x40), conn.frames[0])
        assertArrayEquals(bytes(0x05), conn.frames[1])
    }

    @Test
    fun initThrowsOnModeMismatch() {
        val dead = SramModel().also { it.dead = true }
        assertThrows(IOException::class.java) { Sram23x1024Minimal(dead) }
    }

    @Test
    fun byteAccessFramesAndRoundTrip() {
        val conn = SramModel()
        val chip = Sram23x1024Minimal(conn)
        chip.writeByte(0x1ABCD, 0x5A)
        assertArrayEquals(bytes(0x02, 0x01, 0xAB, 0xCD, 0x5A), conn.frames.last())
        assertEquals(0x5A.toByte(), conn.mem[0x1ABCD])
        assertEquals(0x5A, chip.readByte(0x1ABCD))
        assertArrayEquals(bytes(0x03, 0x01, 0xAB, 0xCD), conn.frames.last())
        chip.writeByte(0x1FFFF, 0xA7)
        assertEquals(0xA7, chip.readByte(0x1FFFF))
        assertThrows(IllegalArgumentException::class.java) { chip.readByte(0x20000) }
    }

    @Test
    fun blockAccessIsChunked() {
        val fc = SramModel()
        val full = Sram23x1024Full(fc)
        assertEquals(131072, full.size())
        val p = pattern(600)
        full.write(0x100, p)
        assertArrayEquals(p, fc.mem.copyOfRange(0x100, 0x100 + 600))
        assertArrayEquals(p, full.read(0x100, 600))
        assertEquals(3, fc.frames.count { (it[0].toInt() and 0xFF) == 0x02 })

        full.setMaxChunk(100)
        assertEquals(100, full.maxChunk())
        fc.frames.clear()
        full.write(0, p.copyOf(250))
        assertEquals(3, fc.frames.size)
        assertEquals(104, fc.frames[0].size)
        assertEquals(54, fc.frames[2].size)
        assertThrows(IllegalArgumentException::class.java) { full.setMaxChunk(257) }
    }

    @Test
    fun fillWritesOnlyTheRange() {
        val fc = SramModel()
        val full = Sram23x1024Full(fc)
        full.fill(0x2000, 100, 0xA5)
        for (i in 0 until 100) assertEquals(0xA5.toByte(), fc.mem[0x2000 + i])
        assertEquals(0, fc.mem[0x2064].toInt())
        assertEquals(0, fc.mem[0x1FFF].toInt())
    }

    @Test
    fun rangeChecks() {
        val full = Sram23x1024Full(SramModel())
        assertThrows(IllegalArgumentException::class.java) { full.read(0x1FFFF, 2) }
        assertThrows(IllegalArgumentException::class.java) { full.write(0x1FFFF, bytes(1, 2)) }
        assertThrows(IllegalArgumentException::class.java) { full.fill(0x1FFFF, 2, 0) }
    }

    @Test
    fun pageModeSplitsAtPageBoundaries() {
        val fc = SramModel()
        val full = Sram23x1024Full(fc)
        full.setMode(Sram23x1024Full.MODE_PAGE)
        assertEquals(0x80, fc.mode)
        assertEquals(Sram23x1024Full.MODE_PAGE, full.getMode())
        fc.frames.clear()
        val data = ByteArray(64) { (0xC0 + it).toByte() }
        full.write(0x4010, data)
        assertEquals(3, fc.frames.size)
        assertArrayEquals(data, fc.mem.copyOfRange(0x4010, 0x4050))
        assertEquals(0, fc.mem[0x4050].toInt())
        assertArrayEquals(data, full.read(0x4010, 64))
    }

    @Test
    fun byteModeSendsOneByteFrames() {
        val fc = SramModel()
        val full = Sram23x1024Full(fc)
        full.setMode(Sram23x1024Full.MODE_BYTE)
        fc.frames.clear()
        val data = bytes(1, 2, 3, 4)
        full.write(0x500, data)
        assertEquals(4, fc.frames.size)
        assertArrayEquals(data, full.read(0x500, 4))
        full.setMode(Sram23x1024Full.MODE_SEQUENTIAL)
        assertEquals(Sram23x1024Full.MODE_SEQUENTIAL, full.getMode())
        assertThrows(IllegalArgumentException::class.java) { full.setMode(0xC0) }
    }

    @Test
    fun getModeMasksReservedBitsAndResetIo() {
        val fc = SramModel()
        val full = Sram23x1024Full(fc)
        fc.mode = 0x4F
        assertEquals(Sram23x1024Full.MODE_SEQUENTIAL, full.getMode())
        fc.mode = 0x40
        fc.frames.clear()
        full.resetIo()
        assertArrayEquals(bytes(0xFF), fc.frames[0])
    }
}
