package it.uhde.periph.chips.display

import it.uhde.periph.connection.MockDisplayConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class St7036Test {

    private val init162 = "R d40 I39 u30 I14 u30 I78 u30 I55 u30 I6D u30 d200 I38 u30 I0C u30 I01 d2 I06 u30"

    /** R reset, I<hh> instruction, D<hh> data byte, d<ms> delay, u<us> microsecond delay. */
    private fun trace(m: MockDisplayConnection, from: Int): String =
        m.events().drop(from).joinToString(" ") { e ->
            when {
                e == "reset" -> "R"
                e.startsWith("command:") -> {
                    val p = e.split(":")
                    assertEquals("", p[2], "instructions are single bytes")
                    "I" + p[1].uppercase()
                }
                e.startsWith("data:") -> {
                    val h = e.substring(5)
                    "D" + h.substring(0, 2).uppercase() + (if (h.length != 2) "!" else "")
                }
                e.startsWith("delayUs:") -> "u" + e.substring(8)
                e.startsWith("delay:") -> "d" + e.substring(6)
                else -> "?"
            }
        }

    private fun full(m: MockDisplayConnection, lines: Int) = St7036Full(m, lines)

    @Test
    fun initSequenceDogm162() {
        val m = MockDisplayConnection()
        val d = St7036Minimal(m)
        assertEquals(init162, trace(m, 0))
        assertEquals(16, d.columns())
        assertEquals(2, d.rows())
    }

    @Test
    fun minimalOperations() {
        val m = MockDisplayConnection()
        val d = St7036Minimal(m)
        var n = m.events().size
        d.clear()
        assertEquals("I01 d2", trace(m, n))
        n = m.events().size
        d.home()
        assertEquals("I02 d2", trace(m, n))
        n = m.events().size
        d.setCursor(3, 1)
        assertEquals("IC3 u30", trace(m, n))
        n = m.events().size
        d.setCursor(0, 0)
        assertEquals("I80 u30", trace(m, n))
        n = m.events().size
        d.setCursor(16, 0)
        d.setCursor(0, 2)
        d.setCursor(-1, 0)
        assertEquals(n, m.events().size)
        d.write("Hi")
        assertEquals("D48 u30 D69 u30", trace(m, n))
        n = m.events().size
        d.write("a\u00e4z~")
        assertEquals("D61 u30 D3F u30 D7A u30 D3F u30", trace(m, n))
        n = m.events().size
        d.writeChar(0x05)
        assertEquals("D05 u30", trace(m, n))
        n = m.events().size
        d.printAt(2, 1, "x")
        assertEquals("IC2 u30 D78 u30", trace(m, n))
        n = m.events().size
        d.write(null)
        assertEquals(n, m.events().size)
    }

    @Test
    fun presets() {
        val cases = listOf(
            Triple(1, St7036Full.SUPPLY_3V3, "R d40 I31 u30 I14 u30 I7C u30 I55 u30 I6D u30 d200 I30 u30 I0C u30 I01 d2 I06 u30"),
            Triple(1, St7036Full.SUPPLY_5V, "R d40 I31 u30 I1C u30 I74 u30 I51 u30 I6A u30 d200 I30 u30 I0C u30 I01 d2 I06 u30"),
            Triple(2, St7036Full.SUPPLY_3V3, "R d40 I39 u30 I14 u30 I78 u30 I55 u30 I6D u30 d200 I38 u30 I0C u30 I01 d2 I06 u30"),
            Triple(2, St7036Full.SUPPLY_5V, "R d40 I39 u30 I1C u30 I74 u30 I52 u30 I69 u30 d200 I38 u30 I0C u30 I01 d2 I06 u30"),
            Triple(3, St7036Full.SUPPLY_3V3, "R d40 I39 u30 I15 u30 I72 u30 I55 u30 I6E u30 d200 I38 u30 I0C u30 I01 d2 I06 u30"),
            Triple(3, St7036Full.SUPPLY_5V, "R d40 I39 u30 I1D u30 I7C u30 I50 u30 I6C u30 d200 I38 u30 I0C u30 I01 d2 I06 u30"),
        )
        for ((lines, supply, want) in cases) {
            val m = MockDisplayConnection()
            St7036Full(m, lines, 0, supply, -1)
            assertEquals(want, trace(m, 0), "lines $lines supply $supply")
        }
    }

    @Test
    fun geometryAndOverrides() {
        val m = MockDisplayConnection()
        val d = full(m, 3)
        assertEquals(16, d.columns())
        assertEquals(3, d.rows())
        val n = m.events().size
        d.setCursor(0, 2)
        assertEquals("IA0 u30", trace(m, n))
        val one = full(MockDisplayConnection(), 1)
        assertEquals(8, one.columns())
        assertEquals(1, one.rows())
        val m2 = MockDisplayConnection()
        St7036Full(m2, 2, 0, St7036Full.SUPPLY_3V3, 0x2A)
        assertEquals("R d40 I39 u30 I14 u30 I7A u30 I56 u30 I6D u30 d200 I38 u30 I0C u30 I01 d2 I06 u30", trace(m2, 0))
        assertEquals(20, St7036Full(MockDisplayConnection(), 2, 20).columns())
        assertThrows(IllegalArgumentException::class.java) { full(MockDisplayConnection(), 9) }
        val m3 = MockDisplayConnection()
        St7036Full(m3)
        assertEquals(init162, trace(m3, 0))
    }

    @Test
    fun controlInstructions() {
        val m = MockDisplayConnection()
        val d = full(m, 2)
        fun check(want: String, f: () -> Unit) {
            val n = m.events().size
            f()
            assertEquals(want, trace(m, n))
        }
        check("I08 u30") { d.display(false) }
        check("I0C u30") { d.display(true) }
        check("I0E u30 I0F u30") { d.cursor(true, true) }
        check("I0D u30 I0C u30") { d.cursor(false, false) }
        check("I05 u30") { d.setEntryMode(false, true) }
        check("I06 u30") { d.setEntryMode(true, false) }
        check("I14 u30 I10 u30") { d.shiftCursor(true); d.shiftCursor(false) }
        check("I1C u30 I18 u30") { d.shiftDisplay(true); d.shiftDisplay(false) }
        check("I02 d2") { d.returnHome() }
        check("IC5 u30") { d.setDdramAddress(0x45) }
        check("I1F u30 D41 u30") { d.sendCommand(0x1F); d.sendData(0x41) }
    }

    @Test
    fun extensionInstructions() {
        val m = MockDisplayConnection()
        val d = full(m, 2)
        fun check(want: String, f: () -> Unit) {
            val n = m.events().size
            f()
            assertEquals(want, trace(m, n))
        }
        check("I39 u30 I56 u30 I7D u30 I38 u30") { d.setContrast(0x2D) }
        check("I39 u30 I52 u30 I38 u30") { d.setBooster(false) }
        check("I39 u30 I56 u30 I38 u30") { d.setBooster(true) }
        check("I39 u30 I6B u30 I38 u30") { d.setFollower(true, 3) }
        check("I39 u30 I67 u30 I38 u30") { d.setFollower(false, 7) }
        check("I39 u30 I1C u30 I38 u30") { d.setBias(true) }
        check("I39 u30 I14 u30 I38 u30") { d.setBias(false) }
        check("I39 u30 I5E u30 I38 u30") { d.icon(true) }
        check("I39 u30 I43 u30 D15 u30 I38 u30 I80 u30") { d.writeIcon(3, 0x15) }
        val m3 = MockDisplayConnection()
        val three = full(m3, 3)
        val n = m3.events().size
        three.setBias(false)
        assertEquals("I39 u30 I15 u30 I38 u30", trace(m3, n))
    }

    @Test
    fun customCharsAndDoubleHeight() {
        val m = MockDisplayConnection()
        val d = full(m, 2)
        d.setCursor(5, 1)
        var n = m.events().size
        val glyph = intArrayOf(0x1F, 0x00, 0xFF, 0x01, 0x02, 0x04, 0x08, 0x10)
        d.createChar(2, glyph)
        assertEquals("I50 u30 D1F u30 D00 u30 D1F u30 D01 u30 D02 u30 D04 u30 D08 u30 D10 u30 IC5 u30", trace(m, n))
        n = m.events().size
        d.createChar(9, glyph)
        assertTrue(trace(m, n).startsWith("I48 u30"))
        n = m.events().size
        d.setDoubleHeight(true)
        assertEquals(n, m.events().size)
        val m1 = MockDisplayConnection()
        val one = full(m1, 1)
        n = m1.events().size
        one.setDoubleHeight(true)
        assertEquals("I34 u30 I34 u30", trace(m1, n))
        n = m1.events().size
        one.setDoubleHeight(false)
        assertEquals("I30 u30 I30 u30", trace(m1, n))
        val m3 = MockDisplayConnection()
        val three = full(m3, 3)
        n = m3.events().size
        three.setDoubleHeight(true, true)
        assertEquals("I3E u30 I18 u30 I3C u30", trace(m3, n))
        n = m3.events().size
        three.setDoubleHeight(true, false)
        assertEquals("I3E u30 I10 u30 I3C u30", trace(m3, n))
    }
}
