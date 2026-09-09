package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Mcp4725Test {

    @Test
    fun fullApi() {
        val connection = MockConnection()
        val generalCall = MockConnection()
        val dac = Mcp4725Full(connection, generalCall)

        // setVoltage(0.5) -> code=2048 (0x800), PD=00. Fast Write byte1=0x08, byte2=0x00.
        dac.setVoltage(0.5)
        assertArrayEquals(byteArrayOf(0x08, 0x00), connection.writes().last())

        dac.setVoltage(2.0)
        assertArrayEquals(byteArrayOf(0x0F, 0xFF.toByte()), connection.writes().last())
        dac.setVoltage(-1.0)
        assertArrayEquals(byteArrayOf(0x00, 0x00), connection.writes().last())

        // setRaw(4095) -> byte1=0x0F, byte2=0xFF.
        dac.setRaw(4095)
        assertArrayEquals(byteArrayOf(0x0F, 0xFF.toByte()), connection.writes().last())

        dac.setRaw(5000)
        assertArrayEquals(byteArrayOf(0x0F, 0xFF.toByte()), connection.writes().last())

        // setVoltageEeprom(0.5) -> code=2048. Write DAC+EEPROM: byte1=0x60, byte2=0x80, byte3=0x00.
        dac.setVoltageEeprom(0.5)
        assertArrayEquals(byteArrayOf(0x60, 0x80.toByte(), 0x00), connection.writes().last())

        // setRawEeprom(4095) -> byte2=0xFF, byte3=0xF0.
        dac.setRawEeprom(4095)
        assertArrayEquals(byteArrayOf(0x60, 0xFF.toByte(), 0xF0.toByte()), connection.writes().last())

        // read(): a plain 5-byte read. rdy_bsy=1, por=1, pd_dac=2, code=0x123,
        // eeprom byte4=0x40 (0100_0000) -> PD1:PD0 bits 6:5 = 2, eeprom_code=0xAB.
        connection.queueRead(byteArrayOf(0xC8.toByte(), 0x12, 0x30, 0x40, 0xAB.toByte()))
        val r = dac.read()
        assertEquals(0x123, r.code)
        assertEquals(0x123 / 4095.0, r.voltageFraction, 1e-9)
        assertEquals(2, r.powerDown)
        assertEquals(0xAB, r.eepromCode)
        assertEquals(2, r.eepromPowerDown)
        assertTrue(r.eepromReady)

        // setPowerDown(2): uses the driver's cached lastCode (4095, from setRawEeprom
        // above) rather than re-reading the chip. byte1=(2<<4)|((4095>>8)&0xF)=0x2F.
        dac.setPowerDown(2)
        assertArrayEquals(byteArrayOf(0x2F, 0xFF.toByte()), connection.writes().last())

        // wakeUp() / reset(): General Call, sent on the separate generalCall connection.
        dac.wakeUp()
        assertArrayEquals(byteArrayOf(0x09), generalCall.writes().last())
        dac.reset()
        assertArrayEquals(byteArrayOf(0x06), generalCall.writes().last())

        // isEepromReady(): RDY/BSY bit, plain 1-byte read.
        connection.queueRead(byteArrayOf(0x80.toByte()))
        assertTrue(dac.isEepromReady())
        connection.queueRead(byteArrayOf(0x00))
        assertFalse(dac.isEepromReady())
    }
}
