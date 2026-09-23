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

class TMP117Test {

    /**
     * Word-addressed variant of [MockConnection]: TMP117 registers are 16 bits
     * wide at consecutive pointer values, which would overlap in the shared
     * mock's byte-slot model. Each pointer owns one 16-bit word.
     */
    class WordMock : MockConnection() {
        val words = ConcurrentHashMap(mapOf(0x00 to 0x8000, 0x01 to 0x0220, 0x02 to 0x6000, 0x03 to 0x8000, 0x0F to 0x1117))
        val log = CopyOnWriteArrayList<ByteArray>()

        override fun write(data: ByteArray) {
            log.add(data.clone())
            if (data.size == 3) words[data[0].toInt() and 0xFF] = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
        }

        override fun writeRead(data: ByteArray, n: Int): ByteArray {
            log.add(data.clone())
            val w = words[data[0].toInt() and 0xFF] ?: 0
            return byteArrayOf((w shr 8).toByte(), w.toByte())
        }

        fun lastWriteTo(reg: Int) = log.last { it.size >= 2 && (it[0].toInt() and 0xFF) == reg }
    }

    @Test
    fun identityCheck() {
        val m = WordMock()
        TMP117Minimal(m)
        assertEquals(1, m.log.size, "init only reads DEVICE_ID")
        assertArrayEquals(byteArrayOf(0x0F), m.log[0])
        assertThrows(IOException::class.java) { TMP117Minimal(WordMock().apply { words[0x0F] = 0x0118 }) }
        assertThrows(IOException::class.java) { TMP117Full(WordMock().apply { words[0x0F] = 0x0000 }) }
        TMP117Minimal(WordMock().apply { words[0x0F] = 0x2117 })
    }

    @Test
    fun temperatureDecoding() {
        val m = WordMock()
        val s = TMP117Minimal(m)
        for ((raw, want) in listOf(0x0C80 to 25.0, 0xFFFF to -0.0078125, 0xF380 to -25.0,
                0x8000 to -256.0, 0x7FFF to 255.9921875)) {
            m.words[0x00] = raw
            assertEquals(want, s.readTemperature())
        }
    }

    @Test
    fun limitsAndOffset() {
        val m = WordMock()
        val s = TMP117Full(m)
        s.setHighLimit(30.0)
        assertEquals(0x0F00, m.words[0x02])
        assertEquals(30.0, s.getHighLimit())
        s.setLowLimit(-10.25)
        assertEquals(0xFAE0, m.words[0x03])
        assertEquals(-10.25, s.getLowLimit())
        s.setLowLimit(0.004)
        assertEquals(0x0001, m.words[0x03])
        s.setHighLimit(1000.0)
        assertEquals(0x7FFF, m.words[0x02])
        s.setLowLimit(-1000.0)
        assertEquals(0x8000, m.words[0x03])
        s.setTemperatureOffset(-0.5)
        assertEquals(0xFFC0, m.words[0x07])
        assertEquals(-0.5, s.getTemperatureOffset())
    }

    @Test
    fun conversionConfig() {
        val m = WordMock()
        val s = TMP117Full(m)
        assertEquals(TMP117Full.Config(TMP117Full.Mode.CONTINUOUS, 8, 1.0), s.getConfig())
        s.configure(TMP117Full.Mode.SHUTDOWN, 64, 16.0)
        assertEquals(0x07E0, m.words[0x01])
        assertEquals(TMP117Full.Config(TMP117Full.Mode.SHUTDOWN, 64, 16.0), s.getConfig())
        assertTrue(s.isShutdown())
        s.configure(TMP117Full.Mode.CONTINUOUS, 0, 0.01)
        assertEquals(0x0000, m.words[0x01])
        assertFalse(s.isShutdown())
        s.configure(cycleSeconds = 0.3)
        assertEquals(0x0120, m.words[0x01], "nearest step is 250 ms")
        s.configure(TMP117Full.Mode.ONE_SHOT, 32, 2.0)
        assertEquals(0x0E40, m.words[0x01], "nearest step is 1 s")
        m.words[0x01] = 0x0800
        assertEquals(TMP117Full.Mode.CONTINUOUS, s.getConfig().mode, "MOD=10 reads as continuous")
        m.words[0x01] = 0xF01C
        s.configure()
        assertEquals(0x023C, m.words[0x01], "alert bits preserved, flags never written")
        assertThrows(IllegalArgumentException::class.java) { s.configure(averaging = 16) }

        m.words[0x01] = 0xE660
        s.triggerOneShot()
        assertEquals(0x0E60, m.words[0x01])
        m.words[0x01] = 0x2220
        assertTrue(s.isDataReady())
        m.words[0x01] = 0x0220
        assertFalse(s.isDataReady())

        s.reset()
        assertArrayEquals(byteArrayOf(0x01, 0x00, 0x02), m.lastWriteTo(0x01))
    }

    @Test
    fun eeprom() {
        val m = WordMock()
        val s = TMP117Full(m)
        s.unlockEeprom()
        assertEquals(0x8000, m.words[0x04])
        s.lockEeprom()
        assertEquals(0x0000, m.words[0x04])
        m.words[0x04] = 0x4000
        assertTrue(s.isEepromBusy())
        m.words[0x04] = 0x8000
        assertFalse(s.isEepromBusy())

        m.words[0x05] = 0x1111
        m.words[0x06] = 0x2222
        m.words[0x08] = 0x3333
        assertEquals(0x1111, s.readEepromScratch(1))
        assertEquals(0x2222, s.readEepromScratch(2))
        assertEquals(0x3333, s.readEepromScratch(3))
        assertThrows(IllegalArgumentException::class.java) { s.readEepromScratch(4) }
        s.writeEepromScratch(2, 0xBEEF)
        assertEquals(0xBEEF, m.words[0x06])
        assertThrows(IllegalArgumentException::class.java) { s.writeEepromScratch(1, 0) }
        assertThrows(IllegalArgumentException::class.java) { s.writeEepromScratch(3, 0) }
        assertEquals(0x1111, m.words[0x05])
        assertEquals(0x3333, m.words[0x08])
    }

    @Test
    fun alertAndInterrupt() {
        val m = WordMock()
        val s = TMP117Full(m)
        s.configureAlert(TMP117Full.AlertMode.THERM, TMP117Full.AlertPolarity.ACTIVE_HIGH,
            TMP117Full.AlertPinFunction.DATA_READY)
        assertEquals(0x023C, m.words[0x01])
        s.configureAlert()
        assertEquals(0x0220, m.words[0x01])

        for ((raw, want) in listOf(0x2220 to 0, 0x8220 to TMP117Full.SOURCE_HIGH, 0x4220 to TMP117Full.SOURCE_LOW,
                0xC220 to (TMP117Full.SOURCE_HIGH or TMP117Full.SOURCE_LOW))) {
            m.words[0x01] = raw
            assertEquals(want, s.pollInterrupt())
        }

        // Polling fallback (no intPin): calls back only when the mask changes.
        m.words[0x01] = 0x0220
        val calls = CopyOnWriteArrayList<Int>()
        s.onInterrupt({ calls.add(it) }, null)
        Thread.sleep(30)
        assertTrue(calls.isEmpty())
        m.words[0x01] = 0x8220
        Thread.sleep(50)
        s.offInterrupt()
        assertEquals(listOf(TMP117Full.SOURCE_HIGH), calls)
    }
}
