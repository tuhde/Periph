package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Mcp4725Test {

    @Test
    void fullApi() throws Exception {
        MockConnection connection = new MockConnection();
        MockConnection generalCall = new MockConnection();
        Mcp4725Full dac = new Mcp4725Full(connection, generalCall);

        // setVoltage(0.5) -> code=2048 (0x800), PD=00. Fast Write byte1=0x08, byte2=0x00.
        dac.setVoltage(0.5);
        assertArrayEquals(new byte[]{0x08, 0x00}, connection.writes().get(connection.writes().size() - 1));

        dac.setVoltage(2.0);
        assertArrayEquals(new byte[]{0x0F, (byte) 0xFF}, connection.writes().get(connection.writes().size() - 1));
        dac.setVoltage(-1.0);
        assertArrayEquals(new byte[]{0x00, 0x00}, connection.writes().get(connection.writes().size() - 1));

        // setRaw(4095) -> byte1=0x0F, byte2=0xFF.
        dac.setRaw(4095);
        assertArrayEquals(new byte[]{0x0F, (byte) 0xFF}, connection.writes().get(connection.writes().size() - 1));

        dac.setRaw(5000);
        assertArrayEquals(new byte[]{0x0F, (byte) 0xFF}, connection.writes().get(connection.writes().size() - 1));

        // setVoltageEeprom(0.5) -> code=2048. Write DAC+EEPROM: byte1=0x60, byte2=0x80, byte3=0x00.
        dac.setVoltageEeprom(0.5);
        assertArrayEquals(new byte[]{0x60, (byte) 0x80, 0x00}, connection.writes().get(connection.writes().size() - 1));

        // setRawEeprom(4095) -> byte2=0xFF, byte3=0xF0.
        dac.setRawEeprom(4095);
        assertArrayEquals(new byte[]{0x60, (byte) 0xFF, (byte) 0xF0}, connection.writes().get(connection.writes().size() - 1));

        // read(): a plain 5-byte read (not register-addressed). rdy_bsy=1, por=1,
        // pd_dac=2, code=0x123, pd_eeprom=2 (byte4 bits 6:5), eeprom_code=0xAB.
        connection.queueRead(new byte[]{(byte) 0xC8, 0x12, 0x30, 0x40, (byte) 0xAB});
        Mcp4725Full.ReadResult r = dac.read();
        assertEquals(0x123, r.code());
        assertEquals(0x123 / 4095.0, r.voltageFraction(), 1e-9);
        assertEquals(2, r.powerDown());
        assertEquals(0xAB, r.eepromCode());
        assertEquals(2, r.eepromPowerDown());
        assertTrue(r.eepromReady());

        // setPowerDown(2): unlike the other languages, the Java driver preserves the
        // output level from its own cached lastCode (set by the most recent setRaw*
        // call) rather than re-reading the chip. lastCode is 4095 from setRawEeprom
        // above. Fast Write byte1=(2<<4)|((4095>>8)&0xF)=0x2F, byte2=4095&0xFF=0xFF.
        dac.setPowerDown(2);
        assertArrayEquals(new byte[]{0x2F, (byte) 0xFF}, connection.writes().get(connection.writes().size() - 1));

        // wakeUp() / reset(): General Call commands, sent on the separate generalCall connection
        // (bound to address 0x00), single command byte only.
        dac.wakeUp();
        assertArrayEquals(new byte[]{0x09}, generalCall.writes().get(generalCall.writes().size() - 1));
        dac.reset();
        assertArrayEquals(new byte[]{0x06}, generalCall.writes().get(generalCall.writes().size() - 1));

        // isEepromReady(): RDY/BSY bit, plain 1-byte read.
        connection.queueRead(new byte[]{(byte) 0x80});
        assertTrue(dac.isEepromReady());
        connection.queueRead(new byte[]{0x00});
        assertFalse(dac.isEepromReady());
    }
}
