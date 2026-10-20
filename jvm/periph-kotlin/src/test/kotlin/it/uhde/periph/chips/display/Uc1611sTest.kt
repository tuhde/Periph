package it.uhde.periph.chips.display

import it.uhde.periph.connection.MockConnection
import it.uhde.periph.connection.MockDisplayConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class Uc1611sTest {

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    private val de = bytes(0xDA, 0x80, 0x10)   // Status1 with DE set

    private fun last(c: MockConnection): ByteArray = c.writes()[c.writes().size - 1]

    private fun command(): MockConnection {
        val c = MockConnection()
        c.queueRead(de)   // Status1.DE set: init does not wait for the pump
        return c
    }

    @Test
    fun initSequence() {
        val cmd = command()
        val dat = MockConnection()
        val lcd = Uc1611sMinimal(cmd, dat)
        assertEquals(240, lcd.width())
        assertEquals(128, lcd.height())
        // 1 System Reset + 1 configuration + 20 page addresses + 1 display enable + 1 NOP before the status poll
        assertEquals(24, cmd.writes().size)
        assertArrayEquals(bytes(0xE2), cmd.writes()[0])
        assertArrayEquals(bytes(0x25, 0xC0, 0x02, 0xA3, 0xF1, 0x7F, 0xF2, 0x00, 0xF3, 0x7F, 0x81, 0x8F, 0x89, 0xD1), cmd.writes()[1])
        assertArrayEquals(bytes(0x60, 0x70, 0x00, 0x10), cmd.writes()[2])
        assertArrayEquals(bytes(0x63, 0x71, 0x00, 0x10), cmd.writes()[21])
        assertArrayEquals(bytes(0xA9), cmd.writes()[22])
        assertArrayEquals(bytes(0xE3), cmd.writes()[23])
        assertEquals(160, dat.writes().size)
        for ((i, w) in dat.writes().withIndex()) {
            assertEquals(if (i % 8 == 7) 16 else 32, w.size)
            assertArrayEquals(ByteArray(w.size), w)
        }
    }

    @Test
    fun invalidSizesAreRejected() {
        for (s in listOf(241 to 128, 240 to 130, 240 to 136, 0 to 128)) {
            val cmd = command()
            assertThrows(IllegalArgumentException::class.java) { Uc1611sMinimal(cmd, MockConnection(), s.first, s.second) }
            assertTrue(cmd.writes().isEmpty())
        }
    }

    @Test
    fun pixelsAndShow() {
        val cmd = command()
        val dat = MockConnection()
        val lcd = Uc1611sMinimal(cmd, dat)
        val c0 = cmd.writes().size
        var d0 = dat.writes().size
        lcd.setPixel(3, 10, true)
        lcd.setPixel(239, 127, true)
        lcd.setPixel(240, 0, true)   // out of range, ignored
        lcd.setPixel(-1, 5, true)
        assertEquals(c0, cmd.writes().size)
        assertEquals(d0, dat.writes().size)
        lcd.show()
        assertEquals(16, cmd.writes().size - c0)
        assertEquals(128, dat.writes().size - d0)
        assertArrayEquals(bytes(0x61, 0x70, 0x00, 0x10), cmd.writes()[c0 + 1])
        assertEquals(0x04.toByte(), dat.writes()[d0 + 8][3])
        assertEquals(16, dat.writes()[d0 + 127].size)
        assertEquals(0x80.toByte(), dat.writes()[d0 + 127][15])
        lcd.clear()
        d0 = dat.writes().size
        lcd.show()
        for (w in dat.writes().subList(d0, dat.writes().size)) assertArrayEquals(ByteArray(w.size), w)
    }

    @Test
    fun commands() {
        val cmd = command()
        val c = Uc1611sFull(cmd, MockConnection())
        fun check(vararg expected: Int) = assertArrayEquals(bytes(*expected), last(cmd))
        c.setContrast(0x55); check(0x81, 0x55)
        c.setDisplay(false); check(0xA8)
        c.sleep(); check(0xA8)
        c.setInvert(true); check(0xA7)
        c.setInvert(false); check(0xA6)
        c.setAllOn(true); check(0xA5)
        c.setAllOn(false); check(0xA4)
        c.setFlip(true, true); check(0xC0, 0x06)
        c.setFlip(false, true); check(0xC0, 0x04)
        c.setScrollLine(40); check(0x48, 0x52)
        c.setFixedLines(3); check(0x93)
        c.setBiasRatio(3); check(0xEB)
        c.setTemperatureCompensation(2); check(0x26)
        c.setLineRate(1); check(0xA1)
        c.setPump(false); check(0x2C)
        c.setPump(true); check(0x2F)
        c.setPanelLoading(true); check(0x2B)
        c.setPanelLoading(false); check(0x28)
        c.setPartialDisplay(true, 10, 100); check(0xF2, 10, 0xF3, 100, 0x87)
        c.setPartialDisplay(true, 4, -1); check(0xF2, 4, 0xF3, 127, 0x87)
        c.setPartialDisplay(false, 0, -1); check(0x84)
        c.setNLineInversion(5, true); check(0xC8, 0x45)
        val k = cmd.writes().size
        c.setIsolationClock(2, 3)
        assertEquals(2, cmd.writes().size - k)
        assertArrayEquals(bytes(0x82, 0x13, 0x02), cmd.writes()[k])
        assertArrayEquals(bytes(0x82, 0x14, 0x03), cmd.writes()[k + 1])
        c.setDisplayPattern(2); check(0xD5)
        c.setDisplayPattern(0); check(0xD1)
        c.sendCommand(0xE3, 0xE3); check(0xE3, 0xE3)
    }

    @Test
    fun writePatternWakeResetAndRawData() {
        val cmd = command()
        val dat = MockConnection()
        val c = Uc1611sFull(cmd, dat)
        val d0 = dat.writes().size
        c.setWritePattern(1)
        c.showPage(0)
        assertArrayEquals(bytes(0x60, 0x72, 0x00, 0x10), last(cmd))
        assertEquals(8, dat.writes().size - d0)
        c.setWritePattern(0)
        var n = dat.writes().size
        c.showPage(16)
        assertEquals(n, dat.writes().size)
        n = dat.writes().size
        c.sendData(ByteArray(70))
        assertEquals(3, dat.writes().size - n)
        assertEquals(6, last(dat).size)

        cmd.queueRead(de)
        c.sleep()
        c.wake()
        assertArrayEquals(bytes(0xA9), cmd.writes()[cmd.writes().size - 2])
        assertArrayEquals(bytes(0xE3), last(cmd))

        c.setContrast(0x55)
        c.setFlip(false, true)
        cmd.queueRead(de)
        val k = cmd.writes().size
        c.reset()
        assertEquals(4, cmd.writes().size - k)
        assertArrayEquals(bytes(0xE2), cmd.writes()[k])
        assertArrayEquals(bytes(0x25, 0xC0, 0x04, 0xA3, 0xF1, 0x7F, 0xF2, 0x00, 0xF3, 0x7F, 0x81, 0x55, 0x89, 0xD1), cmd.writes()[k + 1])
        assertArrayEquals(bytes(0xA9), cmd.writes()[k + 2])
    }

    @Test
    fun drawing() {
        val c = Uc1611sFull(command(), MockConnection())
        c.fill(true)
        assertTrue(c.getPixel(0, 0) && c.getPixel(239, 127))
        c.fill(false)
        assertFalse(c.getPixel(0, 0) || c.getPixel(239, 127) || c.getPixel(-1, 0) || c.getPixel(0, 128))
        c.drawLine(0, 0, 9, 9, true)
        assertTrue(c.getPixel(0, 0) && c.getPixel(5, 5) && c.getPixel(9, 9) && !c.getPixel(5, 6))
        c.fill(false)
        c.drawRect(10, 20, 5, 4, true, false)
        assertTrue(c.getPixel(10, 20) && c.getPixel(14, 23) && c.getPixel(12, 20) && !c.getPixel(12, 21))
        c.fill(false)
        c.drawRect(10, 20, 3, 3, true, true)
        assertTrue(c.getPixel(11, 21) && c.getPixel(12, 22) && !c.getPixel(13, 22))
        c.fill(false)
        c.blit(4, 8, bytes(0xFF, 0x0F, 0x01, 0x80), 2)
        assertTrue(c.getPixel(4, 8) && c.getPixel(4, 15) && c.getPixel(5, 8) && !c.getPixel(5, 12) &&
            c.getPixel(5, 11) && c.getPixel(4, 16) && c.getPixel(5, 23) && !c.getPixel(4, 17))
        assertThrows(IllegalArgumentException::class.java) { c.blit(4, 3, bytes(1, 2), 1) }
    }

    @Test
    fun topViewAndCustomSize() {
        val cmd = command()
        Uc1611sFull(cmd, MockConnection(), 240, 128, true, 0x60)
        assertArrayEquals(bytes(0x25, 0xC0, 0x04, 0xA3, 0xF1, 0x7F, 0xF2, 0x00, 0xF3, 0x7F, 0x81, 0x60, 0x89, 0xD1), cmd.writes()[1])
        val cmd2 = command()
        Uc1611sMinimal(cmd2, MockConnection(), 100, 64)
        assertArrayEquals(bytes(0x25, 0xC0, 0x02, 0xA3, 0xF1, 0x3F, 0xF2, 0x00, 0xF3, 0x3F, 0x81, 0x8F, 0x89, 0xD1), cmd2.writes()[1])
    }

    @Test
    fun readsDiscardTheDummyByteAndStatusSendsNopFirst() {
        val cmd = command()
        val dat = MockConnection()
        val c = Uc1611sFull(cmd, dat)
        cmd.queueRead(bytes(0xDA, 0x80, 0x10))
        assertArrayEquals(bytes(0xDA, 0x80, 0x10), c.readStatus())
        assertArrayEquals(bytes(0xE3), last(cmd))
        dat.queueRead(bytes(0xEE, 0x11, 0x22, 0x33))
        val k = cmd.writes().size
        assertArrayEquals(bytes(0x11, 0x22, 0x33), c.readRam(2, 5, 3))
        assertArrayEquals(bytes(0x62, 0x70, 0x05, 0x10), cmd.writes()[k])
    }

    // ---- error handling ----------------------------------------------------------------

    @Test
    fun commandIsRetriedUntilAcknowledgedAndGivesUpAfterMaxAttempts() {
        val cmd = command()
        val c = Uc1611sFull(cmd, MockConnection())
        assertTrue(c.ok())
        var e0 = cmd.writes().size
        cmd.failNextWrites(2, 0)
        c.setContrast(0x66)
        assertEquals(3, cmd.writes().size - e0)
        assertArrayEquals(bytes(0x81, 0x66), cmd.writes()[e0])
        assertEquals(2, c.retryCount())
        assertTrue(c.ok())

        cmd.failNextWrites(100, 0)
        e0 = cmd.writes().size
        assertThrows(IOException::class.java) { c.setInvert(true) }
        assertEquals(Uc1611sMinimal.MAX_ATTEMPTS, cmd.writes().size - e0)
        assertFalse(c.ok())
        assertEquals(1, c.errorCount())
        assertEquals(2 + Uc1611sMinimal.MAX_ATTEMPTS, c.retryCount())
        cmd.failNextWrites(0, 0)
        c.clearErrors()
        assertTrue(c.ok())
        assertEquals(0, c.retryCount())
    }

    @Test
    fun aFailedDataChunkRepeatsTheWholePage() {
        val cmd = command()
        val dat = MockConnection()
        val c = Uc1611sFull(cmd, dat)
        var e0 = cmd.writes().size
        var d0 = dat.writes().size
        dat.failNextWrites(1, 0)
        c.showPage(1)
        assertEquals(2, cmd.writes().size - e0)
        assertArrayEquals(bytes(0x61, 0x70, 0x00, 0x10), cmd.writes()[e0])
        assertArrayEquals(bytes(0x61, 0x70, 0x00, 0x10), cmd.writes()[e0 + 1])
        assertEquals(1 + 8, dat.writes().size - d0)
        assertEquals(1, c.retryCount())
        e0 = cmd.writes().size
        d0 = dat.writes().size
        dat.failNextWrites(1, 2)   // third chunk fails
        c.showPage(2)
        assertEquals(2, cmd.writes().size - e0)
        assertEquals(3 + 8, dat.writes().size - d0)
        assertTrue(c.ok())
    }

    @Test
    fun deadChipThrowsFromTheConstructor() {
        val cmd = MockConnection()
        cmd.failNextWrites(1000, 0)
        val dat = MockConnection()
        assertThrows(IOException::class.java) { Uc1611sMinimal(cmd, dat) }
        assertEquals(Uc1611sMinimal.MAX_ATTEMPTS, cmd.writes().size)
        assertTrue(dat.writes().isEmpty())
    }

    @Test
    fun readsAreRetried() {
        val cmd = command()
        val dat = MockConnection()
        val c = Uc1611sFull(cmd, dat)
        cmd.failNextReads(2)
        cmd.queueRead(bytes(0xDA, 0x80, 0x10))
        assertArrayEquals(bytes(0xDA, 0x80, 0x10), c.readStatus())
        assertTrue(c.retryCount() >= 2)
        cmd.failNextReads(100)
        assertThrows(IOException::class.java) { c.readStatus() }
        assertFalse(c.ok())
        c.clearErrors()
        cmd.failNextReads(0)
        dat.failNextReads(1)
        dat.queueRead(bytes(0xEE, 0x0A, 0x0B))
        val k = cmd.writes().size
        assertArrayEquals(bytes(0x0A, 0x0B), c.readRam(1, 4, 2))
        assertEquals(2, cmd.writes().size - k)
    }

    // ---- SPI (4-wire) via the display connection ------------------------------------------

    private fun countEvents(dc: MockDisplayConnection, from: Int, prefix: String): Int =
        dc.events().subList(from, dc.events().size).count { it.startsWith(prefix) }

    @Test
    fun spiInitSequence() {
        val dc = MockDisplayConnection()
        val lcd = Uc1611sSpiMinimal(dc)
        assertEquals(240, lcd.width())
        assertEquals(128, lcd.height())
        assertEquals("reset", dc.events()[0])
        val cmds = dc.commands()
        assertEquals(listOf(0xE2, 0x25, 0xC0, 0x02, 0xA3, 0xF1, 0x7F, 0xF2, 0x00, 0xF3, 0x7F, 0x81, 0x8F), cmds.subList(0, 13))
        for (i in cmds.indices) assertEquals(0, dc.commandData(i).size, "arguments are commands too")
        assertEquals(20, countEvents(dc, 0, "data:"))
        assertEquals(0, lcd.retryCount())
        assertTrue(lcd.ok())
    }

    @Test
    fun spiShowSendsSixteenPagesOfTwoHundredForty() {
        val dc = MockDisplayConnection()
        val lcd = Uc1611sSpiMinimal(dc)
        val n = dc.events().size
        lcd.setPixel(0, 0, true)
        lcd.show()
        assertEquals(16, countEvents(dc, n, "data:"))
        assertTrue(dc.events()[n + 4].startsWith("data:01"), dc.events()[n + 4])
    }

    @Test
    fun spiReadsAreUnsupported() {
        val dc = MockDisplayConnection()
        val c = Uc1611sSpiFull(dc)
        assertThrows(UnsupportedOperationException::class.java) { c.readStatus() }
        assertThrows(UnsupportedOperationException::class.java) { c.readRam(0, 0, 2) }
        assertTrue(c.ok())
        assertEquals(0, c.errorCount())
    }

    @Test
    fun spiContrastAndWake() {
        val dc = MockDisplayConnection()
        val c = Uc1611sSpiFull(dc)
        var n = dc.commands().size
        c.setContrast(0x50)
        assertEquals(listOf(0x81, 0x50), dc.commands().subList(n, dc.commands().size))
        n = dc.commands().size
        c.wake()
        assertEquals(listOf(0xA9), dc.commands().subList(n, dc.commands().size))
    }
}
