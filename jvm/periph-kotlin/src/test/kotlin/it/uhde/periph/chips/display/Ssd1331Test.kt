package it.uhde.periph.chips.display

import it.uhde.periph.connection.MockDisplayConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Ssd1331Test {

    /** Command/parameter bytes as ints (asserting none carries parameters); data events as -length. */
    private fun stream(m: MockDisplayConnection, from: Int): List<Int> {
        val out = ArrayList<Int>()
        for (e in m.events().drop(from)) {
            if (e.startsWith("command:")) {
                val p = e.split(":")
                assertEquals("", p[2], "parameters must be separate commands (D/C# low)")
                out.add(p[1].toInt(16))
            } else if (e.startsWith("data:")) {
                out.add(-(e.length - 5) / 2)
            }
        }
        return out
    }

    private fun dataBytes(m: MockDisplayConnection, from: Int): Int =
        m.events().drop(from).filter { it.startsWith("data:") }.sumOf { (it.length - 5) / 2 }

    @Test
    fun initSequenceVendor() {
        val m = MockDisplayConnection()
        val d = Ssd1331Minimal(m)
        assertEquals("reset", m.events()[0])
        assertEquals("delay:100", m.events()[1])
        assertEquals(
            listOf(
                0xAE, 0x81, 0xFF, 0x82, 0xFF, 0x83, 0xFF, 0x87, 0x06, 0x8A, 0x64, 0x8B, 0x78, 0x8C, 0x64,
                0xA0, 0x72, 0xA1, 0x00, 0xA2, 0x00, 0xA4, 0xA8, 0x3F, 0xAD, 0x8E, 0xB0, 0x0B, 0xB1, 0x31,
                0xB3, 0xF0, 0xBB, 0x3A, 0xBE, 0x3E, 0x2E, 0x25, 0x00, 0x00, 0x5F, 0x3F, 0xAF,
            ),
            stream(m, 0),
        )
        assertEquals("delay:100", m.events()[m.events().size - 2])
        assertEquals(0, dataBytes(m, 0))
        assertEquals(96, d.width())
        assertEquals(64, d.height())
    }

    @Test
    fun framebufferAndDrawing() {
        val d = Ssd1331Full(MockDisplayConnection())
        d.setPixel(0, 0, Ssd1331Minimal.RED)
        d.setPixel(95, 63, Ssd1331Minimal.BLUE)
        d.setPixel(1, 2, 0x1234)
        assertEquals(Ssd1331Minimal.RED, d.getPixel(0, 0))
        assertEquals(Ssd1331Minimal.BLUE, d.getPixel(95, 63))
        assertEquals(0x1234, d.getPixel(1, 2))
        d.setPixel(-1, 0, 1)
        d.setPixel(0, -1, 1)
        d.setPixel(96, 0, 1)
        d.setPixel(0, 64, 1)
        assertEquals(0, d.getPixel(99, 99))
        assertEquals(Ssd1331Minimal.RED, d.getPixel(0, 0))
        d.clear(Ssd1331Minimal.GREEN)
        assertEquals(Ssd1331Minimal.GREEN, d.getPixel(0, 0))
        assertEquals(Ssd1331Minimal.GREEN, d.getPixel(95, 63))
        assertEquals(0xF800, Ssd1331Minimal.rgb(255, 0, 0))
        assertEquals(0x07E0, Ssd1331Minimal.rgb(0, 255, 0))
        assertEquals(0x001F, Ssd1331Minimal.rgb(0, 0, 255))
        assertEquals(0xFFFF, Ssd1331Minimal.rgb(255, 255, 255))
        d.clear(0)
        d.drawLine(0, 0, 4, 4, Ssd1331Minimal.WHITE)
        assertTrue(d.getPixel(0, 0) == 0xFFFF && d.getPixel(2, 2) == 0xFFFF && d.getPixel(4, 4) == 0xFFFF)
        assertEquals(0, d.getPixel(4, 0))
        d.clear(0)
        d.drawRect(2, 2, 4, 3, Ssd1331Minimal.WHITE, false)
        assertTrue(d.getPixel(2, 2) == 0xFFFF && d.getPixel(5, 4) == 0xFFFF)
        assertTrue(d.getPixel(3, 3) == 0 && d.getPixel(6, 2) == 0)
        d.drawRect(10, 10, 3, 3, Ssd1331Minimal.RED, true)
        assertTrue(d.getPixel(11, 11) == Ssd1331Minimal.RED && d.getPixel(13, 13) == 0)
        d.blit(20, 20, intArrayOf(1, 2, 3, 4), 2, 2)
        assertEquals(listOf(1, 2, 3, 4), listOf(d.getPixel(20, 20), d.getPixel(21, 20), d.getPixel(20, 21), d.getPixel(21, 21)))
    }

    @Test
    fun showAndShowRegion() {
        val m = MockDisplayConnection()
        val d = Ssd1331Full(m)
        d.clear(Ssd1331Minimal.RED)
        var n = m.events().size
        d.show()
        val s = stream(m, n)
        assertEquals(listOf(0x15, 0, 0x5F, 0x75, 0, 0x3F), s.subList(0, 6))
        assertEquals(-192, s[6])
        assertEquals(6 + 64, s.size)
        assertEquals(12288, dataBytes(m, n))
        assertTrue(m.events()[n + 6].startsWith("data:f8"))
        n = m.events().size
        d.showRegion(10, 20, 4, 2)
        assertEquals(listOf(0x15, 10, 13, 0x75, 20, 21, -8, -8), stream(m, n))
        n = m.events().size
        d.showRegion(90, 60, 20, 20)
        assertEquals(listOf(0x15, 90, 95, 0x75, 60, 63), stream(m, n).subList(0, 6))
        assertEquals(48, dataBytes(m, n))
        n = m.events().size
        d.showRegion(200, 0, 5, 5)
        assertEquals(n, m.events().size)
    }

    @Test
    fun hardwarePrimitivesAndSettings() {
        val m = MockDisplayConnection()
        val d = Ssd1331Full(m)
        fun run(expect: List<Int>, block: () -> Unit) {
            val n = m.events().size
            block()
            assertEquals(expect, stream(m, n))
        }
        run(listOf(0x21, 1, 2, 3, 4, 0x3E, 0, 0)) { d.hwLine(1, 2, 3, 4, Ssd1331Minimal.RED) }
        run(listOf(0x26, 1, 0x22, 10, 20, 14, 25, 0, 0x3F, 0, 0, 0, 0x3E)) {
            d.hwRect(10, 20, 5, 6, Ssd1331Minimal.GREEN, Ssd1331Minimal.BLUE, true)
        }
        run(listOf(0x26, 0, 0x22, 10, 20, 14, 25, 0x3E, 0x3F, 0x3E, 0, 0, 0)) {
            d.hwRect(10, 20, 5, 6, Ssd1331Minimal.WHITE, 0, false)
        }
        run(emptyList()) { d.hwRect(10, 20, 0, 6, Ssd1331Minimal.WHITE, 0, false) }
        run(listOf(0x25, 0, 1, 2, 3)) { d.hwClear(0, 1, 2, 3) }
        run(listOf(0x24, 0, 1, 2, 3)) { d.hwDim(0, 1, 2, 3) }
        run(listOf(0x23, 1, 2, 3, 4, 5, 6)) { d.hwCopy(1, 2, 3, 4, 5, 6) }
        run(listOf(0x27, 1, 0, 64, 2, 3, 0x2F)) { d.scroll(1, 0, 64, 2, 7) }
        run(listOf(0x2E)) { d.scrollStop() }
        run(listOf(0x81, 1, 0x82, 2, 0x83, 3)) { d.setContrast(1, 2, 3) }
        run(listOf(0x87, 0x0F)) { d.setMasterCurrent(16) }
        run(listOf(0x87, 0x00)) { d.setMasterCurrent(0) }
        run(listOf(0xA7)) { d.setDisplayMode(Ssd1331Full.MODE_INVERSE) }
        run(listOf(0xA0, 0x60)) { d.setFlip(false, false) }
        run(listOf(0xA0, 0x72)) { d.setFlip(true, true) }
        run(listOf(0xB0, 0x1A)) { d.setPowerSave(true) }
        run(listOf(0xB0, 0x0B)) { d.setPowerSave(false) }
        run(listOf(0xAB, 0, 1, 2, 3, 0x1F, 0xAC)) { d.dimMode(1, 2, 3, 0xFF) }
        run(listOf(0xAE, 0xAF)) { d.displayOff(); d.displayOn() }
        run(listOf(0xB9)) { d.grayLinear() }
        run(listOf(0xAA, 0x12, 0x34)) { d.sendCommand(0xAA, 0x12, 0x34) }
    }
}
