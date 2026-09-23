package it.uhde.periph.chips.temperature

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class MCP9808Test {

    /**
     * Word-addressed variant of [MockConnection]: MCP9808 registers are 16 bits
     * wide at consecutive pointer values (MANUFACTURER_ID at 0x06, DEVICE_ID at
     * 0x07), which would overlap in the shared mock's byte-slot model. Each
     * pointer owns one 16-bit word; 1-byte accesses use the word's low byte.
     */
    class WordMock : MockConnection() {
        val words = ConcurrentHashMap(mapOf(0x06 to 0x0054, 0x07 to 0x0400, 0x08 to 0x0003))
        val log = CopyOnWriteArrayList<ByteArray>()

        override fun write(data: ByteArray) {
            log.add(data.clone())
            when (data.size) {
                3 -> words[data[0].toInt() and 0xFF] = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
                2 -> words[data[0].toInt() and 0xFF] = data[1].toInt() and 0xFF
            }
        }

        override fun writeRead(data: ByteArray, n: Int): ByteArray {
            log.add(data.clone())
            val w = words[data[0].toInt() and 0xFF] ?: 0
            return if (n == 1) byteArrayOf(w.toByte()) else byteArrayOf((w shr 8).toByte(), w.toByte())
        }

        fun writesTo(reg: Int) = log.filter { it.size >= 2 && (it[0].toInt() and 0xFF) == reg }
    }

    @Test
    fun identityCheck() {
        val m = WordMock()
        MCP9808Minimal(m)
        assertTrue(m.log.all { it.size == 1 }, "init makes no register writes")
        assertThrows(IOException::class.java) { MCP9808Minimal(WordMock().apply { words[0x06] = 0x1234 }) }
        assertThrows(IOException::class.java) { MCP9808Full(WordMock().apply { words[0x07] = 0x0500 }) }
        MCP9808Minimal(WordMock().apply { words[0x07] = 0x0401 })
    }

    @Test
    fun temperatureDecoding() {
        val m = WordMock()
        val s = MCP9808Minimal(m)
        for ((raw, want) in listOf(0x0194 to 25.25, 0xE194 to 25.25, 0x1FF0 to -1.0, 0x1E6C to -25.25, 0x0001 to 0.0625)) {
            m.words[MCP9808Minimal.REG_TA] = raw
            assertEquals(want, s.readTemperature(), "TA=0x%04X".format(raw))
        }
    }

    @Test
    fun limits() {
        val m = WordMock()
        val s = MCP9808Full(m)
        s.setUpperLimit(80.0)
        assertEquals(0x0500, m.words[0x02])
        assertEquals(80.0, s.getUpperLimit())
        s.setLowerLimit(-25.0)
        assertEquals(0x1E70, m.words[0x03])
        assertEquals(-25.0, s.getLowerLimit())
        s.setCriticalLimit(-5.1)
        assertEquals(-5.0, s.getCriticalLimit())
        s.setCriticalLimit(22.13)
        assertEquals(22.25, s.getCriticalLimit())
        s.setUpperLimit(1000.0)
        assertEquals(255.75, s.getUpperLimit())
        s.setLowerLimit(-1000.0)
        assertEquals(-256.0, s.getLowerLimit())
    }

    @Test
    fun resolutionAndHysteresis() {
        val m = WordMock()
        val s = MCP9808Full(m)
        s.setResolution(0.25)
        assertArrayEquals(byteArrayOf(0x08, 0x01), m.writesTo(0x08).last())
        assertEquals(0.25, s.getResolution())
        val before = m.writesTo(0x08).size
        assertThrows(IllegalArgumentException::class.java) { s.setResolution(0.3) }
        assertEquals(before, m.writesTo(0x08).size)

        m.words[0x01] = 0x0000
        s.setHysteresis(3.0)
        assertEquals(0x0400, m.words[0x01])
        assertEquals(3.0, s.getHysteresis())
        assertThrows(IllegalArgumentException::class.java) { s.setHysteresis(2.0) }
    }

    @Test
    fun shutdownLocksAndAlert() {
        val m = WordMock()
        val s = MCP9808Full(m)
        m.words[0x01] = 0x0400
        s.shutdown()
        assertEquals(0x0500, m.words[0x01])
        assertTrue(s.isShutdown())
        s.wake()
        assertEquals(0x0400, m.words[0x01])
        assertFalse(s.isShutdown())
        m.words[0x01] = 0x0080
        val before = m.writesTo(0x01).size
        s.shutdown()
        assertEquals(before, m.writesTo(0x01).size, "shutdown is a no-op while locked")

        m.words[0x01] = 0x0000
        s.lockCriticalLimit()
        assertEquals(0x0080, m.words[0x01])
        assertTrue(s.isCriticalLimitLocked())
        assertFalse(s.isWindowLimitsLocked())
        m.words[0x01] = 0x0000
        s.lockWindowLimits()
        assertEquals(0x0040, m.words[0x01])
        assertTrue(s.isWindowLimitsLocked())

        m.words[0x01] = 0x0000
        s.configureAlert(MCP9808Full.AlertMode.CRITICAL_ONLY, MCP9808Full.AlertOutput.INTERRUPT,
            MCP9808Full.AlertPolarity.ACTIVE_HIGH)
        assertEquals(0x0007, m.words[0x01])
        s.configureAlert()
        assertEquals(0x0000, m.words[0x01])
        m.words[0x01] = 0x0040
        assertThrows(IllegalStateException::class.java) { s.configureAlert(output = MCP9808Full.AlertOutput.INTERRUPT) }
        assertEquals(0x0040, m.words[0x01])

        m.words[0x01] = 0x0000
        s.enableAlert()
        assertEquals(0x0008, m.words[0x01])
        s.disableAlert()
        assertEquals(0x0000, m.words[0x01])

        m.words[0x01] = 0x0019
        assertTrue(s.isAlertAsserted())
        s.clearInterrupt()
        assertArrayEquals(byteArrayOf(0x01, 0x00, 0x29), m.writesTo(0x01).last())
        m.words[0x01] = 0x0009
        assertFalse(s.isAlertAsserted())
    }

    @Test
    fun pollAndOnInterrupt() {
        val m = WordMock()
        val s = MCP9808Full(m)
        m.words[0x05] = 0x0194
        assertEquals(0, s.pollInterrupt())
        m.words[0x05] = 0x2194
        assertEquals(MCP9808Full.SOURCE_LOWER, s.pollInterrupt())
        m.words[0x05] = 0xC194
        assertEquals(MCP9808Full.SOURCE_UPPER or MCP9808Full.SOURCE_CRITICAL, s.pollInterrupt())

        // Polling fallback (no intPin): calls back only when the mask changes.
        m.words[0x05] = 0x0194
        val calls = CopyOnWriteArrayList<Int>()
        s.onInterrupt({ calls.add(it) })
        Thread.sleep(30)
        assertTrue(calls.isEmpty(), "no callback without a change")
        m.words[0x05] = 0x4194
        val deadline = System.currentTimeMillis() + 1000
        while (calls.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        s.offInterrupt()
        assertEquals(listOf(MCP9808Full.SOURCE_UPPER), calls)
    }
}
