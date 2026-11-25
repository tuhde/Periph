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

class TMP102Test {

    /**
     * Word-addressed variant of [MockConnection]: TMP102 registers are 16 bits
     * wide at consecutive pointer values. Each pointer owns one 16-bit word;
     * `osDone` controls whether CONFIGURATION reads back with OS = 1.
     */
    class WordMock : MockConnection() {
        val words = ConcurrentHashMap(mapOf(0x00 to 0x0000, 0x01 to 0x60A0, 0x02 to 0x4B00, 0x03 to 0x5000))
        val log = CopyOnWriteArrayList<ByteArray>()
        @Volatile var osDone = true

        override fun write(data: ByteArray) {
            log.add(data.clone())
            if (data.size == 3) words[data[0].toInt() and 0xFF] = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
        }

        override fun writeRead(data: ByteArray, n: Int): ByteArray {
            log.add(data.clone())
            var w = words[data[0].toInt() and 0xFF] ?: 0
            if ((data[0].toInt() and 0xFF) == 0x01) w = if (osDone) w or 0x8000 else w and 0x7FFF
            return byteArrayOf((w shr 8).toByte(), w.toByte())
        }

        fun writesTo(reg: Int) = log.filter { it.size == 3 && (it[0].toInt() and 0xFF) == reg }
    }

    private val regTemp = 0x00
    private val regConfig = 0x01
    private val regTHigh = 0x03

    @Test
    fun constructorMakesNoBusAccess() {
        val m = WordMock()
        TMP102Minimal(m)
        TMP102Full(m)
        assertTrue(m.log.isEmpty())
    }

    @Test
    fun temperatureDecoding() {
        val m = WordMock()
        val s = TMP102Minimal(m)
        val cases = listOf(0x1900 to 25.0, 0xE700 to -25.0, 0xC900 to -55.0, 0x7FF0 to 127.9375,
            ((2400 shl 3) or 1) to 150.0, ((((-1600) shl 3) and 0xFFFF) or 1) to -100.0)
        for ((raw, want) in cases) {
            m.words[regTemp] = raw
            assertEquals(want, s.readTemperature(), "raw $raw")
        }
    }

    @Test
    fun configReadModifyWrite() {
        val m = WordMock()
        val s = TMP102Full(m)
        assertEquals(0x60A0 or 0x8000, s.readConfig())
        s.setConversionRate(8.0)
        assertEquals(0x00C0, m.words[regConfig]!! and 0x00C0)
        assertEquals(0x6000, m.words[regConfig]!! and 0x6000)
        s.setConversionRate(0.25)
        assertEquals(0, m.words[regConfig]!! and 0x00C0)
        assertThrows(IllegalArgumentException::class.java) { s.setConversionRate(2.0) }
        s.setFaultQueue(6)
        assertEquals(0x1800, m.words[regConfig]!! and 0x1800)
        s.setFaultQueue(2)
        assertEquals(0x0800, m.words[regConfig]!! and 0x1800)
        assertThrows(IllegalArgumentException::class.java) { s.setFaultQueue(3) }
        s.setPolarity(true)
        assertEquals(0x0400, m.words[regConfig]!! and 0x0400)
        s.setPolarity(false)
        assertEquals(0, m.words[regConfig]!! and 0x0400)
        s.setThermostatMode(true)
        assertEquals(0x0200, m.words[regConfig]!! and 0x0200)
        s.setThermostatMode(false)
        assertEquals(0, m.words[regConfig]!! and 0x0200)
        s.setExtendedMode(true)
        assertEquals(0x0010, m.words[regConfig]!! and 0x0010)
        s.setExtendedMode(false)
        assertEquals(0, m.words[regConfig]!! and 0x0010)
        s.shutdown()
        assertEquals(0x0100, m.words[regConfig]!! and 0x0100)
        s.wake()
        assertEquals(0, m.words[regConfig]!! and 0x0100)
        s.writeConfig(0xFFFF)
        assertEquals(0x6000, m.words[regConfig]!! and 0x602F)
        m.words[regTemp] = 0x1900
        assertEquals(400, s.readRaw())
    }

    @Test
    fun limits() {
        val m = WordMock()
        val s = TMP102Full(m)
        s.setTHigh(30.0)
        assertEquals(0x1E00, m.words[regTHigh])
        assertEquals(30.0, s.getTHigh())
        s.setTLow(-10.5)
        assertEquals(-10.5, s.getTLow())
        s.setTHigh(500.0)
        assertEquals(127.9375, s.getTHigh())
        s.setExtendedMode(true)
        s.setTHigh(140.0)
        assertEquals(2240 shl 3, m.words[regTHigh])
        assertEquals(140.0, s.getTHigh())
        s.setTHigh(1000.0)
        assertEquals(150.0, s.getTHigh())
    }

    @Test
    fun oneShot() {
        val m = WordMock()
        val s = TMP102Full(m)
        m.words[regTemp] = 0x1900
        assertEquals(25.0, s.oneShot())
        assertTrue(m.writesTo(regConfig).any { (it[1].toInt() and 0x80) != 0 })
        assertEquals(0, m.words[regConfig]!! and 0x0100)
        m.words[regConfig] = 0x6180
        s.oneShot()
        assertEquals(0x0100, m.words[regConfig]!! and 0x0100)
        m.osDone = false
        assertThrows(IOException::class.java) { s.oneShot() }
    }

    @Test
    fun alertStateAndPoll() {
        val m = WordMock()
        val s = TMP102Full(m)
        m.words[regConfig] = 0x6020
        assertFalse(s.alertState())
        m.words[regConfig] = 0x6000
        assertTrue(s.alertState())
        m.words[regConfig] = 0x6420
        assertTrue(s.alertState())
        m.words[regConfig] = 0x6400
        assertFalse(s.alertState())
        m.log.clear()
        m.words[regConfig] = 0x6000
        assertTrue(s.pollInterrupt())
        assertArrayEquals(byteArrayOf(regTemp.toByte()), m.log.last())
    }

    @Test
    fun generalCallReset() {
        val m = WordMock()
        TMP102Full(m).reset()
        assertEquals(1, m.log.size)
        assertArrayEquals(byteArrayOf(0x00, 0x06), m.log[0])
    }

    @Test
    fun pollingFallbackReportsTemperature() {
        val m = WordMock()
        val s = TMP102Full(m)
        m.words[regTemp] = 0x1900
        m.words[regConfig] = 0x6000
        val calls = CopyOnWriteArrayList<Double>()
        s.onInterrupt({ calls.add(it) }, null)
        Thread.sleep(300)
        s.offInterrupt()
        assertTrue(calls.isNotEmpty() && calls[0] == 25.0)
    }
}
