package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Mcp4728Test {

    @Test
    fun fullApi() {
        val connection = MockConnection()
        val generalCall = MockConnection()
        val dac = Mcp4728Full(connection, generalCall)

        // setVoltage(1, 0.5): Kotlin's Double.toInt() truncates (0.5*4095).toInt() to
        // 2047 (0x7FF), not 2048. Multi-Write byte1=0x42, byte2=0x07, byte3=0xFF.
        dac.setVoltage(1, 0.5)
        assertArrayEquals(byteArrayOf(0x42, 0x07, 0xFF.toByte()), connection.writes().last())

        dac.setVoltage(1, 2.0)
        assertArrayEquals(byteArrayOf(0x42, 0x0F, 0xFF.toByte()), connection.writes().last())

        // setRaw(3, 4095) -> byte1=0x46, byte2=0x0F, byte3=0xFF.
        dac.setRaw(3, 4095)
        assertArrayEquals(byteArrayOf(0x46, 0x0F, 0xFF.toByte()), connection.writes().last())

        dac.setRaw(9, 9000)
        assertArrayEquals(byteArrayOf(0x46, 0x0F, 0xFF.toByte()), connection.writes().last())

        // setAll([0.0, 1.0, 0.5, 0.25]): truncation gives codes 0, 4095, 2047, 1023.
        dac.setAll(doubleArrayOf(0.0, 1.0, 0.5, 0.25))
        assertArrayEquals(
            byteArrayOf(0x00, 0x00, 0x0F, 0xFF.toByte(), 0x07, 0xFF.toByte(), 0x03, 0xFF.toByte()),
            connection.writes().last()
        )

        assertThrows(IllegalArgumentException::class.java) { dac.setAll(doubleArrayOf(0.0, 1.0, 0.5)) }

        // setVoltageEeprom(2, 0.5, vref=1, gain=2): code truncates to 2047 (0x7FF).
        // byte1=0x5C, byte2=(1<<7)|(1<<4)|(2047>>8&0xF)=0x97, byte3=0xFF.
        dac.setVoltageEeprom(2, 0.5, 1, 2)
        assertArrayEquals(byteArrayOf(0x5C, 0x97.toByte(), 0xFF.toByte()), connection.writes().last())

        // setRawEeprom(0, 4095, vref=0, gain=1) -> byte1=0x58, byte2=0x0F, byte3=0xFF.
        dac.setRawEeprom(0, 4095, 0, 1)
        assertArrayEquals(byteArrayOf(0x58, 0x0F, 0xFF.toByte()), connection.writes().last())

        // setAllEeprom: fractions=[0.0,1.0,0.5,0.25], vrefs=[0,1,0,1], gains=[1,2,1,2].
        // Truncation gives codes 0, 4095, 2047, 1023 for channels A-D.
        dac.setAllEeprom(doubleArrayOf(0.0, 1.0, 0.5, 0.25), intArrayOf(0, 1, 0, 1), intArrayOf(1, 2, 1, 2))
        assertArrayEquals(
            byteArrayOf(0x50, 0x00, 0x00, 0x9F.toByte(), 0xFF.toByte(), 0x07, 0xFF.toByte(), 0x93.toByte(), 0xFF.toByte()),
            connection.writes().last()
        )

        assertThrows(IllegalArgumentException::class.java) {
            dac.setAllEeprom(doubleArrayOf(0.0, 1.0), intArrayOf(0, 1), intArrayOf(1, 2))
        }

        // setVref(1, 0, 1, 0) -> byte1 = 0x8A.
        dac.setVref(1, 0, 1, 0)
        assertArrayEquals(byteArrayOf(0x8A.toByte()), connection.writes().last())

        // setGain(1, 2, 1, 2) -> byte1 = 0xC5.
        dac.setGain(1, 2, 1, 2)
        assertArrayEquals(byteArrayOf(0xC5.toByte()), connection.writes().last())

        // setPowerDown(0, 1, 2, 3) -> byte1=0xA2, byte2=0x58.
        dac.setPowerDown(0, 1, 2, 3)
        assertArrayEquals(byteArrayOf(0xA2.toByte(), 0x58), connection.writes().last())

        // read(): 24-byte response, no register-select write.
        val buf = ByteArray(24)
        buf[0] = 0x80.toByte()
        buf[1] = 0x01
        buf[2] = 0x23
        buf[13] = 0x90.toByte()
        buf[14] = 0xAB.toByte()
        connection.queueRead(buf)
        val result = dac.read()
        assertTrue(result.eepromReady)
        assertEquals(0x123, result.channel[0].code)
        assertEquals(0, result.channel[0].vref)
        assertEquals(Mcp4728Full.GAIN_X1, result.channel[0].gain)
        assertEquals(0, result.channel[0].powerDown)
        assertEquals(0xAB, result.channel[0].eepromCode)
        assertEquals(1, result.channel[0].eepromVref)
        assertEquals(Mcp4728Full.GAIN_X2, result.channel[0].eepromGain)

        connection.queueRead(byteArrayOf(0x80.toByte()))
        assertTrue(dac.isEepromReady())
        connection.queueRead(byteArrayOf(0x00))
        assertFalse(dac.isEepromReady())

        // softwareUpdate()/wakeUp()/reset(): General Call, single command byte
        // on the separate generalCall connection.
        dac.softwareUpdate()
        assertArrayEquals(byteArrayOf(0x08), generalCall.writes().last())
        dac.wakeUp()
        assertArrayEquals(byteArrayOf(0x09), generalCall.writes().last())
        dac.reset()
        assertArrayEquals(byteArrayOf(0x06), generalCall.writes().last())
    }
}
