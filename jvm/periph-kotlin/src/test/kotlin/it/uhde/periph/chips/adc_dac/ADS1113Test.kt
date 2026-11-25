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

class ADS1113Test {

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
    fun fixedRangeContinuousAndReset() {
        val m = WordMock()
        val min = ADS1113Minimal(m)
        m.words[0] = 0x0200
        assertEquals(0x0200 * 0.0000625, min.readVoltage())
        val adc = ADS1113Full(m)
        assertEquals(2.048, adc.fullScale())
        assertEquals(2.048 / 32768, adc.lsb())
        adc.setDataRate(ADS1113Full.DR_8)
        adc.startContinuous()
        assertEquals(0x0403, m.lastConfig())
        adc.reset()
        assertEquals(0x8583, m.words[1])
        assertEquals(128, adc.dataRateSps())
    }
}
