package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class ADS1014Test {

    /**
     * Word-addressed variant of [MockConnection]: the ADS1014 registers are 16 bits wide at
     * consecutive pointer values, which would overlap in the shared mock's byte-slot model.
     * Each pointer owns one 16-bit word.
     */
    class WordMock : MockConnection() {
        val words = ConcurrentHashMap(mapOf(0 to 0x0000, 1 to 0x8583, 2 to 0x8000, 3 to 0x7FFF))
        val log = CopyOnWriteArrayList<ByteArray>()
        var pin: InputPin? = null

        override fun write(data: ByteArray) {
            log.add(data.clone())
            if (data.size == 3) words[data[0].toInt() and 0xFF] = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
        }

        override fun writeRead(data: ByteArray, n: Int): ByteArray {
            log.add(data.clone())
            val w = words[data[0].toInt() and 0xFF] ?: 0
            return byteArrayOf((w shr 8).toByte(), w.toByte())
        }

        override fun intPin(): InputPin? = pin

        fun lastConfigWrite(): Int {
            var last = -1
            for (w in log) if (w.size == 3 && w[0].toInt() == 1) last = ((w[1].toInt() and 0xFF) shl 8) or (w[2].toInt() and 0xFF)
            return last
        }
    }

    class FakePin : InputPin {
        var high = true
        var handler: EdgeHandler? = null
        var trigger: EdgeTrigger? = null
        override fun onEdge(handler: EdgeHandler, trigger: EdgeTrigger) { this.handler = handler; this.trigger = trigger }
        override fun offEdge(handler: EdgeHandler) { if (this.handler === handler) this.handler = null }
        override fun isHigh(): Boolean = high
        override fun close() {}
    }

    @Test
    fun minimalSingleShot() {
        val mock = WordMock()
        val adc = ADS1014Minimal(mock)
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
    fun gainAndDataRate() {
        val mock = WordMock()
        val adc = ADS1014Full(mock)
        adc.setGain(ADS1014Full.PGA_6_144)
        assertEquals(0, adc.getGain())
        assertEquals(6.144, adc.fullScale(), 1e-12)
        assertEquals(0.003, adc.lsb(), 1e-12)
        adc.setGain(ADS1014Full.PGA_0_256)
        assertEquals(0.000125, adc.lsb(), 1e-12)
        mock.words[0] = 0x4000
        assertEquals(0.256 * 0x400 / 2048.0, adc.readVoltage(), 1e-9)
        assertEquals(0x8000 or (5 shl 9) or 0x0100 or (4 shl 5) or 0x3, mock.lastConfigWrite())
        for (bad in intArrayOf(-1, 6, 7)) assertThrows(IllegalArgumentException::class.java) { adc.setGain(bad) }

        adc.setDataRate(ADS1014Full.DR_128)
        assertEquals(128, adc.dataRateSps())
        adc.setDataRate(ADS1014Full.DR_3300)
        assertEquals(3300, adc.dataRateSps())
        assertEquals(6, adc.getDataRate())
        assertThrows(IllegalArgumentException::class.java) { adc.setDataRate(7) }
    }

    @Test
    fun continuousMode() {
        val mock = WordMock()
        val adc = ADS1014Full(mock)
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
    }

    @Test
    fun comparatorAndThresholds() {
        val mock = WordMock()
        val adc = ADS1014Full(mock)
        adc.configureComparator(ADS1014Full.COMP_WINDOW, ADS1014Full.ACTIVE_HIGH, true, ADS1014Full.QUEUE_4)
        assertEquals(0x0580 or 0x10 or 0x08 or 0x04 or 0x02, mock.lastConfigWrite())
        adc.disableComparator()
        assertEquals(3, mock.lastConfigWrite() and 0x3)
        assertThrows(IllegalArgumentException::class.java) { adc.configureComparator(0, 0, false, 3) }

        adc.setThresholds(-0.5, 1.0)
        assertEquals((-500 and 0xFFF) shl 4, mock.words[2])
        assertEquals(1000 shl 4, mock.words[3])
        assertArrayEquals(intArrayOf(-500, 1000), adc.getThresholdsRaw())
        adc.setThresholds(-10.0, 10.0)
        assertArrayEquals(intArrayOf(-2048, 2047), adc.getThresholdsRaw())
        assertThrows(IllegalArgumentException::class.java) { adc.setThresholds(1.0, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { adc.setHighThresholdRaw(2048) }

        mock.log.clear()
        adc.clearAlert()
        assertEquals(1, mock.log.size)
        assertArrayEquals(byteArrayOf(0), mock.log[0])

        adc.reset()
        assertEquals(0x8583, mock.words[1])
        assertEquals(0x8000, mock.words[2])
        assertEquals(0x7FFF, mock.words[3])
    }

    @Test
    fun interruptRouting() {
        val mock = WordMock()
        val pin = FakePin()
        mock.pin = pin
        val adc = ADS1014Full(mock)

        adc.enableInterrupt(ADS1014Full.SOURCE_CONVERSION_READY)
        assertEquals(0x8000, mock.words[3])
        assertEquals(0x0000, mock.words[2])
        assertEquals(0, mock.words[1]!! and 0x3)
        assertEquals(0, adc.pollInterrupt(), "pin high = idle for an active-low alert")
        pin.high = false
        assertEquals(ADS1014Full.SOURCE_CONVERSION_READY, adc.pollInterrupt())

        adc.setHighThresholdRaw(-5)
        adc.setLowThresholdRaw(10)
        assertThrows(IllegalArgumentException::class.java) { adc.enableInterrupt(ADS1014Full.SOURCE_COMPARATOR) }
        assertThrows(IllegalArgumentException::class.java) { adc.enableInterrupt(0x04) }
        adc.setThresholds(-0.1, 0.1)
        adc.configureComparator(ADS1014Full.COMP_TRADITIONAL, ADS1014Full.ACTIVE_LOW, true, ADS1014Full.QUEUE_1)
        adc.enableInterrupt(ADS1014Full.SOURCE_COMPARATOR)
        assertArrayEquals(intArrayOf(-100, 100), adc.getThresholdsRaw())
        mock.log.clear()
        assertEquals(ADS1014Full.SOURCE_COMPARATOR, adc.pollInterrupt())
        assertTrue(mock.log.any { it.size == 1 && it[0].toInt() == 0 }, "latched alert cleared by reading CONVERSION")

        val got = ArrayList<Int>()
        adc.onInterrupt({ got.add(it) })
        assertEquals(EdgeTrigger.FALLING, pin.trigger)
        pin.handler!!.onEdge()
        assertEquals(listOf(ADS1014Full.SOURCE_COMPARATOR), got)
        adc.offInterrupt()
        assertNull(pin.handler)

        adc.disableInterrupt(ADS1014Full.SOURCE_COMPARATOR)
        assertEquals(3, mock.words[1]!! and 0x3)
        assertEquals(0, adc.pollInterrupt())
    }

    @Test
    fun pollFallbackWithoutPin() {
        val mock = WordMock()
        val adc = ADS1014Full(mock)
        adc.enableInterrupt(ADS1014Full.SOURCE_CONVERSION_READY)
        mock.words[1] = mock.words[1]!! or 0x8000  // the real chip reads OS = 1 when idle
        assertEquals(ADS1014Full.SOURCE_CONVERSION_READY, adc.pollInterrupt())
        mock.words[1] = mock.words[1]!! and 0x7FFF
        assertEquals(0, adc.pollInterrupt())
    }
}
