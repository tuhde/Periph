package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import it.uhde.periph.connection.OutputPin
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class MCP4XXXTest {

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun last(c: MockConnection) = c.writes().last()

    /** Models the MCP4XXX command protocol: sixteen nine-bit cells, inc/dec of the volatile wipers, two-byte reads. */
    class ChipMock(private val maxCode: Int) : MockConnection() {
        val mem = IntArray(16)
        var status = 0x1F0

        init {
            mem[0] = maxCode / 2; mem[1] = maxCode / 2; mem[2] = maxCode / 2; mem[3] = maxCode / 2
            mem[4] = 0x1FF
        }

        override fun write(data: ByteArray) {
            super.write(data)
            var i = 0
            while (i < data.size) {
                val cmd = data[i].toInt() and 0xFF
                val addr = cmd shr 4
                val op = (cmd shr 2) and 3
                if (op == 0) {
                    mem[addr] = ((cmd and 1) shl 8) or (data[i + 1].toInt() and 0xFF)
                    i += 2
                } else {
                    if (addr < 2) mem[addr] = if (op == 1) minOf(maxCode, mem[addr] + 1) else maxOf(0, mem[addr] - 1)
                    i += 1
                }
            }
        }

        override fun writeRead(data: ByteArray, n: Int): ByteArray {
            super.writeRead(data, n)
            val addr = (data[0].toInt() and 0xFF) shr 4
            val v = if (addr == 5) status else mem[addr]
            return byteArrayOf(((v shr 8) and 1).toByte(), v.toByte())
        }
    }

    class Pin : OutputPin {
        val log = mutableListOf<String>()
        override fun set(high: Boolean) { log.add(if (high) "HVC1" else "HVC0") }
        override fun close() {}
    }

    private fun mock(part: String) = ChipMock(MCP4XXXMinimal.lookup(part).maxCode)

    @Test
    fun partTable() {
        assertEquals(0x2E, MCP4XXXMinimal.defaultAddress("mcp4531"))
        assertEquals(0x58, MCP4XXXMinimal.defaultAddress("MCP4632"))
        assertEquals(0x28, MCP4XXXMinimal.defaultAddress("MCP4661"))
        assertThrows(IllegalArgumentException::class.java) { MCP4XXXMinimal(MockConnection(), "MCP9999") }
    }

    @Test
    fun initReadsOnly() {
        val c = mock("MCP4662")
        val chip = MCP4XXXFull(c, "MCP4662")
        chip.init()
        assertEquals(4, c.writes().size)
        assertArrayEquals(b(0x0C), c.writes()[0])
        assertArrayEquals(b(0x1C), c.writes()[1])
        assertArrayEquals(b(0x4C), c.writes()[2])
        assertArrayEquals(b(0x5C), c.writes()[3])
        assertEquals(2, chip.channels())
        assertEquals(257, chip.steps())
        assertFalse(chip.isPotentiometer())
        assertTrue(chip.isNonvolatile())
    }

    @Test
    fun wiperFormats() {
        val c = mock("MCP4531")
        val chip = MCP4XXXFull(c, "MCP4531")
        chip.setWiper(100)
        assertArrayEquals(b(0x00, 100), last(c))
        chip.setWiper(999)
        assertArrayEquals(b(0x00, 128), last(c))
        chip.setWiper(-3)
        assertArrayEquals(b(0x00, 0), last(c))
        assertThrows(IllegalArgumentException::class.java) { chip.setWiper(1, 1) }

        val c8 = mock("MCP4652")
        val chip8 = MCP4XXXFull(c8, "MCP4652")
        chip8.setWiper(256, 1)
        assertArrayEquals(b(0x11, 0x00), last(c8))
        assertEquals(256, chip8.getWiper(1))
        c8.mem[0] = 0x3FF
        assertEquals(256, chip8.getWiper(0))
    }

    @Test
    fun resistanceAndRatio() {
        val chip = MCP4XXXFull(mock("MCP4531"), "MCP4531")
        chip.setWiper(64)
        assertEquals(5075.0, chip.getResistance(), 1e-9)
        assertEquals(5075.0, chip.getResistanceWa(0), 1e-9)
        chip.setResistance(1e9)
        assertEquals(128, chip.getWiper())
        chip.setResistanceWa(5075.0, 0)
        assertEquals(64, chip.getWiper())
        chip.setRatio(0.5, 0)
        assertEquals(0.5, chip.getRatio(0), 1e-9)
        assertEquals(10000.0 / 128, chip.getResistanceStep(), 1e-9)
        val rheo = MCP4XXXFull(mock("MCP4532"), "MCP4532")
        assertThrows(UnsupportedOperationException::class.java) { rheo.getResistanceWa(0) }
        assertThrows(UnsupportedOperationException::class.java) { rheo.setBoth(1, 2) }
    }

    @Test
    fun incrementDecrement() {
        val c = mock("MCP4631")
        val chip = MCP4XXXFull(c, "MCP4631")
        chip.setBoth(10, 20)
        assertEquals(10, c.mem[0])
        assertEquals(20, c.mem[1])
        chip.setWiper(120, 1)
        val n = c.writes().size
        assertEquals(128, chip.increment(10, 1))
        assertEquals(n + 2, c.writes().size)
        val run = c.writes()[n]
        assertEquals(10, run.size)
        assertEquals(0x14, run[0].toInt() and 0xFF)
        chip.setWiper(5, 0)
        assertEquals(0, chip.decrement(10, 0))
        assertEquals(1, chip.increment(1, 0))
    }

    @Test
    fun tcon() {
        val c = mock("MCP4631")
        val chip = MCP4XXXFull(c, "MCP4631")
        chip.shutdown(1)
        assertEquals(0x1FF and 0x80.inv(), c.mem[4])
        assertTrue(chip.isShutdown(1))
        assertFalse(chip.isShutdown(0))
        chip.resume(1)
        assertEquals(0x1FF, c.mem[4])
        chip.shutdown(0)
        assertEquals(0x1FF and 0x08.inv(), c.mem[4])
        chip.resume(0)
        chip.setTerminals(true, false, true, 1)
        assertEquals(0x1FF and 0x20.inv(), c.mem[4])
        assertEquals(Triple(true, false, true), chip.getTerminals(1))
        assertEquals(Triple(true, true, true), chip.getTerminals(0))
        chip.setGeneralCall(false)
        assertEquals(0, chip.getTcon() and 0x100)
        chip.setGeneralCall(true)
        assertEquals(0x100, c.mem[4] and 0x100)
        chip.setWiper(3, 0)
        chip.shutdown(1)
        chip.reset()
        assertEquals(64, c.mem[0])
        assertEquals(64, c.mem[1])
        assertEquals(0x1FF, c.mem[4])
    }

    @Test
    fun rawAccess() {
        val c = mock("MCP4532")
        val chip = MCP4XXXFull(c, "MCP4532")
        assertEquals(0x1FF, chip.readRegister(4))
        chip.writeRegister(0, 77)
        assertEquals(77, c.mem[0])
        assertThrows(IllegalArgumentException::class.java) { chip.writeRegister(5, 0) }
        assertThrows(IllegalArgumentException::class.java) { chip.readRegister(2) }
        assertThrows(IllegalArgumentException::class.java) { chip.readRegister(16) }
    }

    @Test
    fun nonvolatileUnsupportedOnVolatile() {
        val chip = MCP4XXXFull(mock("MCP4532"), "MCP4532")
        assertThrows(UnsupportedOperationException::class.java) { chip.readStatus() }
        assertThrows(UnsupportedOperationException::class.java) { chip.waitReady(5) }
        assertThrows(UnsupportedOperationException::class.java) { chip.getNvWiper(0) }
        assertThrows(UnsupportedOperationException::class.java) { chip.storeWiper(0) }
        assertThrows(UnsupportedOperationException::class.java) { chip.readData(0) }
        assertThrows(UnsupportedOperationException::class.java) { chip.setWriteProtect(true) }
    }

    @Test
    fun nonvolatile() {
        val pin = Pin()
        val c = mock("MCP4662")
        val chip = MCP4XXXFull(c, "MCP4662", 10000.0, 75.0, pin, 1)
        var st = chip.readStatus()
        assertFalse(st.eepromBusy)
        assertFalse(st.writeProtect)
        c.status = 0x1F0 or 0x08 or 0x02 or 0x01
        st = chip.readStatus()
        assertTrue(st.eepromBusy && st.wiperLock[0] && !st.wiperLock[1] && st.writeProtect)
        assertTrue(chip.isWiperLocked(0))
        assertFalse(chip.isWiperLocked(1))
        assertTrue(chip.isWriteProtected())
        assertThrows(IOException::class.java) { chip.waitReady(3) }
        c.status = 0x1F0

        chip.setNvWiper(200, 1)
        assertEquals(200, c.mem[3])
        chip.setWiper(150, 0)
        assertEquals(150, chip.storeWiper(0))
        assertEquals(150, c.mem[2])
        chip.writeData(3, 0x1AB)
        assertEquals(0x1AB, chip.readData(3))
        assertTrue(c.writes().any { it.contentEquals(b(0x91, 0xAB)) })
        assertThrows(IllegalArgumentException::class.java) { chip.readData(10) }
        chip.setWiper(7, 0)
        chip.reset()
        assertEquals(150, c.mem[0])
        assertEquals(200, c.mem[1])

        var n = c.writes().size
        chip.setWiperLock(true, 0)
        assertArrayEquals(b(0x28), c.writes()[n])
        assertEquals(listOf("HVC1", "HVC0"), pin.log.takeLast(2))
        n = c.writes().size
        chip.setWiperLock(false, 1)
        assertArrayEquals(b(0x34), c.writes()[n])
        n = c.writes().size
        chip.setWiperLock(true, 1)
        assertArrayEquals(b(0x38), c.writes()[n])
        n = c.writes().size
        chip.setWriteProtect(true)
        assertArrayEquals(b(0xF8), c.writes()[n])
        n = c.writes().size
        chip.setWriteProtect(false)
        assertArrayEquals(b(0xF4), c.writes()[n])
        val nohv = MCP4XXXFull(mock("MCP4562"), "MCP4562")
        assertThrows(UnsupportedOperationException::class.java) { nohv.setWiperLock(true, 0) }
    }

    @Test
    fun setRabHasNoTraffic() {
        val c = mock("MCP4532")
        val chip = MCP4XXXFull(c, "MCP4532")
        val n = c.writes().size
        chip.setRab(50000.0, 100.0)
        assertEquals(n, c.writes().size)
        chip.setWiper(128)
        assertEquals(50100.0, chip.getResistance(), 1e-9)
    }
}
