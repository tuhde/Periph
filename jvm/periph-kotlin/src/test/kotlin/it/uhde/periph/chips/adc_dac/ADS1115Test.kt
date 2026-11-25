package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class ADS1115Test {

    /**
     * Word-addressed variant of [MockConnection]: the four ADS111x registers are 16 bits wide at
     * consecutive pointer values, which would overlap in the shared mock's byte-slot model. Each
     * pointer owns one 16-bit word.
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

        fun lastConfig(): Int =
            log.lastOrNull { it.size == 3 && it[0].toInt() == 1 }
                ?.let { ((it[1].toInt() and 0xFF) shl 8) or (it[2].toInt() and 0xFF) } ?: 0
    }

    class FakePin(@Volatile var high: Boolean) : InputPin {
        override fun onEdge(handler: EdgeHandler, trigger: EdgeTrigger) {}
        override fun offEdge(handler: EdgeHandler) {}
        override fun isHigh(): Boolean = high
        override fun close() {}
    }

    @Test
    fun minimalSingleEnded() {
        val m = WordMock()
        val adc = ADS1115Minimal(m)
        assertTrue(m.log.isEmpty(), "init makes no bus traffic")
        m.words[0] = 0x4000
        assertEquals(0x4000, adc.readRaw())
        assertEquals(0xC583, m.lastConfig())
        m.words[0] = 0xFFFF
        assertEquals(-1, adc.readRaw(2))
        assertEquals(0xE583, m.lastConfig())
        adc.readRaw(9)
        assertEquals(0xF583, m.lastConfig(), "channel clamps to 3")
        m.words[0] = 0x4000
        assertEquals(0x4000 * 0.0000625, adc.readVoltage())
    }

    @Test
    fun fullGainRateDifferentialContinuous() {
        val m = WordMock()
        val adc = ADS1115Full(m)
        adc.setGain(ADS1115Full.PGA_4_096)
        assertEquals(4.096, adc.fullScale())
        assertEquals(4.096 / 32768, adc.lsb())
        m.words[0] = 0x2000
        assertEquals(0x2000 * (4.096 / 32768), adc.readVoltage(1))
        assertEquals(0xD383, m.lastConfig())
        assertThrows(IllegalArgumentException::class.java) { adc.setGain(6) }
        adc.setDataRate(ADS1115Full.DR_860)
        assertEquals(7, adc.getDataRate())
        assertEquals(860, adc.dataRateSps())
        adc.readRaw()
        assertEquals(0xC3E3, m.lastConfig())
        assertEquals(0x2000, adc.readDifferentialRaw(ADS1115Full.DIFF_2_3))
        assertEquals(0xB3E3, m.lastConfig())
        assertThrows(IllegalArgumentException::class.java) { adc.readDifferentialRaw(4) }
        adc.startContinuous(ADS1115Full.MUX_AIN1_GND)
        assertEquals(0x52E3, m.lastConfig())
        m.words[0] = 0x0100
        assertEquals(256, adc.readContinuousRaw())
        adc.stopContinuous()
        assertEquals(0x53E3, m.lastConfig())
        m.words[1] = 0x0583
        assertTrue(adc.isConverting())
        m.words[1] = 0x8583
        assertFalse(adc.isConverting())
        assertEquals(0x8583, adc.readConfig())
    }

    @Test
    fun thresholdsAndComparator() {
        val m = WordMock()
        val adc = ADS1115Full(m)
        adc.setThresholds(0.5, 3.0)
        assertEquals(8000, m.words[2])
        assertEquals(0x7FFF, m.words[3], "3.0 V clamps to the code range")
        adc.setHighThresholdRaw(-100)
        assertEquals(0xFF9C, m.words[3])
        adc.setLowThresholdRaw(-200)
        assertArrayEquals(intArrayOf(-200, -100), adc.getThresholdsRaw())
        assertThrows(IllegalArgumentException::class.java) { adc.setThresholds(1.0, 1.0) }
        adc.setThresholds(0.5, 1.5)
        adc.configureComparator(ADS1115Full.COMP_WINDOW, ADS1115Full.ACTIVE_HIGH, true, ADS1115Full.QUEUE_2)
        assertEquals(0x0400 or 0x0100 or 0x0080 or 0x0010 or 0x0008 or 0x0004 or 0x0001, m.lastConfig())
        adc.disableComparator()
        assertEquals(3, m.lastConfig() and 3)
        m.log.clear()
        adc.clearAlert()
        assertEquals(1, m.log.size)
        assertEquals(0, m.log[0][0].toInt())
    }

    @Test
    fun interruptRoutingAndPolling() {
        val m = WordMock()
        val adc = ADS1115Full(m)
        adc.enableInterrupt(ADS1115Full.SOURCE_CONVERSION_READY)
        assertEquals(0x8000, m.words[3])
        assertEquals(0x0000, m.words[2])
        assertEquals(0, m.lastConfig() and 3)
        m.words[1] = 0x8583
        assertEquals(ADS1115Full.SOURCE_CONVERSION_READY, adc.pollInterrupt())
        m.words[1] = 0x0583
        assertEquals(0, adc.pollInterrupt())
        val pin = FakePin(false)
        m.pin = pin
        assertEquals(ADS1115Full.SOURCE_CONVERSION_READY, adc.pollInterrupt())
        pin.high = true
        assertEquals(0, adc.pollInterrupt())
        adc.disableInterrupt(ADS1115Full.SOURCE_CONVERSION_READY)
        assertEquals(3, m.lastConfig() and 3)
        assertEquals(0, adc.pollInterrupt())
        adc.setThresholds(0.5, 1.5)
        adc.enableInterrupt(ADS1115Full.SOURCE_COMPARATOR)
        assertEquals(0, m.lastConfig() and 3)
        pin.high = false
        assertEquals(ADS1115Full.SOURCE_COMPARATOR, adc.pollInterrupt())
        assertThrows(IllegalArgumentException::class.java) { adc.enableInterrupt(0x80) }
        adc.setGain(0)
        adc.reset()
        assertEquals(0x8583, m.words[1])
        assertEquals(0x8000, m.words[2])
        assertEquals(0x7FFF, m.words[3])
        assertEquals(2, adc.getGain())
        assertEquals(4, adc.getDataRate())
    }
}
