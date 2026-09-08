package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Mcp4728Test {

    @Test
    void fullApi() throws Exception {
        MockConnection connection = new MockConnection();
        MockConnection generalCall = new MockConnection();
        Mcp4728Full dac = new Mcp4728Full(connection, generalCall);

        // setVoltage(1, 0.5) -> code=2048 (0x800). Multi-Write: byte1=0x42, byte2=0x08, byte3=0x00.
        dac.setVoltage(1, 0.5);
        assertArrayEquals(new byte[]{0x42, 0x08, 0x00}, connection.writes().get(connection.writes().size() - 1));

        dac.setVoltage(1, 2.0);
        assertArrayEquals(new byte[]{0x42, 0x0F, (byte) 0xFF}, connection.writes().get(connection.writes().size() - 1));

        // setRaw(3, 4095) -> byte1=0x46, byte2=0x0F, byte3=0xFF.
        dac.setRaw(3, 4095);
        assertArrayEquals(new byte[]{0x46, 0x0F, (byte) 0xFF}, connection.writes().get(connection.writes().size() - 1));

        dac.setRaw(9, 9000);
        assertArrayEquals(new byte[]{0x46, 0x0F, (byte) 0xFF}, connection.writes().get(connection.writes().size() - 1));

        // setAll([0.0, 1.0, 0.5, 0.25]) -> Fast Write, 8 bytes.
        dac.setAll(new double[]{0.0, 1.0, 0.5, 0.25});
        assertArrayEquals(new byte[]{0x00, 0x00, 0x0F, (byte) 0xFF, 0x08, 0x00, 0x04, 0x00},
                connection.writes().get(connection.writes().size() - 1));

        assertThrows(IllegalArgumentException.class, () -> dac.setAll(new double[]{0.0, 1.0, 0.5}));

        // setVoltageEeprom(2, 0.5, vref=1, gain=2) -> code=2048. byte1=0x5C, byte2=0x98, byte3=0x00.
        dac.setVoltageEeprom(2, 0.5, 1, 2);
        assertArrayEquals(new byte[]{0x5C, (byte) 0x98, 0x00}, connection.writes().get(connection.writes().size() - 1));

        // setRawEeprom(0, 4095, vref=0, gain=1) -> byte1=0x58, byte2=0x0F, byte3=0xFF.
        dac.setRawEeprom(0, 4095, 0, 1);
        assertArrayEquals(new byte[]{0x58, 0x0F, (byte) 0xFF}, connection.writes().get(connection.writes().size() - 1));

        // setAllEeprom: fractions=[0.0,1.0,0.5,0.25], vrefs=[0,1,0,1], gains=[1,2,1,2].
        dac.setAllEeprom(new double[]{0.0, 1.0, 0.5, 0.25}, new int[]{0, 1, 0, 1}, new int[]{1, 2, 1, 2});
        assertArrayEquals(
                new byte[]{0x50, 0x00, 0x00, (byte) 0x9F, (byte) 0xFF, 0x08, 0x00, (byte) 0x94, 0x00},
                connection.writes().get(connection.writes().size() - 1));

        assertThrows(IllegalArgumentException.class,
                () -> dac.setAllEeprom(new double[]{0.0, 1.0}, new int[]{0, 1}, new int[]{1, 2}));

        // setVref(1, 0, 1, 0) -> byte1 = 0x8A.
        dac.setVref(1, 0, 1, 0);
        assertArrayEquals(new byte[]{(byte) 0x8A}, connection.writes().get(connection.writes().size() - 1));

        // setGain(1, 2, 1, 2) -> byte1 = 0xC5.
        dac.setGain(1, 2, 1, 2);
        assertArrayEquals(new byte[]{(byte) 0xC5}, connection.writes().get(connection.writes().size() - 1));

        // setPowerDown(0, 1, 2, 3) -> byte1=0xA2, byte2=0x58.
        dac.setPowerDown(0, 1, 2, 3);
        assertArrayEquals(new byte[]{(byte) 0xA2, 0x58}, connection.writes().get(connection.writes().size() - 1));

        // read(): 24-byte response, no register-select write.
        byte[] buf = new byte[24];
        buf[0] = (byte) 0x80;
        buf[1] = 0x01;
        buf[2] = 0x23;
        buf[13] = (byte) 0x90;
        buf[14] = (byte) 0xAB;
        connection.queueRead(buf);
        Mcp4728Full.ReadResult result = dac.read();
        assertTrue(result.eepromReady());
        assertEquals(0x123, result.channel()[0].code());
        assertEquals(0, result.channel()[0].vref());
        assertEquals(Mcp4728Full.GAIN_X1, result.channel()[0].gain());
        assertEquals(0, result.channel()[0].powerDown());
        assertEquals(0xAB, result.channel()[0].eepromCode());
        assertEquals(1, result.channel()[0].eepromVref());
        assertEquals(Mcp4728Full.GAIN_X2, result.channel()[0].eepromGain());

        connection.queueRead(new byte[]{(byte) 0x80});
        assertTrue(dac.isEepromReady());
        connection.queueRead(new byte[]{0x00});
        assertFalse(dac.isEepromReady());

        // softwareUpdate()/wakeUp()/reset(): General Call, single command byte
        // on the separate generalCall connection.
        dac.softwareUpdate();
        assertArrayEquals(new byte[]{0x08}, generalCall.writes().get(generalCall.writes().size() - 1));
        dac.wakeUp();
        assertArrayEquals(new byte[]{0x09}, generalCall.writes().get(generalCall.writes().size() - 1));
        dac.reset();
        assertArrayEquals(new byte[]{0x06}, generalCall.writes().get(generalCall.writes().size() - 1));
    }
}
