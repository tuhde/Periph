package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class ADS1013Test {

    /** Word-addressed variant of [MockConnection]: one 16-bit word per register pointer. */
    class WordMock : MockConnection() {
        val words = ConcurrentHashMap(mapOf(0 to 0x0000, 1 to 0x8583))
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

        fun lastConfigWrite(): Int {
            var last = -1
            for (w in log) if (w.size == 3 && w[0].toInt() == 1) last = ((w[1].toInt() and 0xFF) shl 8) or (w[2].toInt() and 0xFF)
            return last
        }
    }

    @Test
    fun minimalSingleShot() {
        val mock = WordMock()
        val adc = ADS1013Minimal(mock)
        assertTrue(mock.log.isEmpty(), "init makes no bus traffic")
        mock.words[0] = 0x7FF0
        assertEquals(2047, adc.readRaw())
        assertEquals(0x8583, mock.lastConfigWrite())
        mock.words[0] = 0x8000
        assertEquals(-2048, adc.readRaw())
        mock.words[0] = 0xFFF0
        assertEquals(-1, adc.readRaw())
        mock.words[0] = 0x3E80
        assertEquals(1.0, adc.readVoltage(), 1e-9)
    }

    @Test
    fun dataRateAndFixedRange() {
        val mock = WordMock()
        val adc = ADS1013Full(mock)
        assertEquals(2.048, adc.fullScale(), 0.0)
        assertEquals(0.001, adc.lsb(), 0.0)
        adc.setDataRate(ADS1013Full.DR_128)
        assertEquals(128, adc.dataRateSps())
        assertEquals(0, adc.getDataRate())
        adc.setDataRate(ADS1013Full.DR_3300)
        assertEquals(3300, adc.dataRateSps())
        assertEquals(6, adc.getDataRate())
        mock.words[0] = 0x1000
        adc.readRaw()
        assertEquals(0x8000 or 0x0403 or 0x0100 or (6 shl 5), mock.lastConfigWrite())
        for (bad in intArrayOf(-1, 7)) assertThrows(IllegalArgumentException::class.java) { adc.setDataRate(bad) }
    }

    @Test
    fun continuousModeAndReset() {
        val mock = WordMock()
        val adc = ADS1013Full(mock)
        adc.startContinuous()
        assertEquals(0x0483, mock.lastConfigWrite())
        mock.words[0] = 0x1000
        mock.log.clear()
        assertEquals(256, adc.readContinuousRaw())
        assertEquals(-1, mock.lastConfigWrite(), "reading must not write Config")
        assertEquals(0.256, adc.readContinuous(), 1e-9)
        adc.stopContinuous()
        assertEquals(0x0583, mock.lastConfigWrite())
        mock.words[1] = 0x0583
        assertTrue(adc.isConverting())
        mock.words[1] = 0x8583
        assertFalse(adc.isConverting())
        assertEquals(0x8583, adc.readConfig())
        adc.setDataRate(ADS1013Full.DR_128)
        adc.reset()
        assertEquals(0x8583, mock.words[1])
        assertEquals(ADS1013Full.DR_1600, adc.getDataRate())
    }
}
