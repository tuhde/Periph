package it.uhde.periph.chips.memory;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class Eeprom24Aa02UidTest {

    @Test
    void fullApi() throws Exception {
        MockConnection connection = new MockConnection();
        // UID (0xFC-0xFF), MSB first.
        connection.setRegister(Eeprom24Aa02UidMinimal.ADDR_UID_BASE, 0xAA, 0xBB, 0xCC, 0xDD);

        Eeprom24Aa02UidFull eeprom = new Eeprom24Aa02UidFull(connection);

        assertArrayEquals(new byte[]{(byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD}, eeprom.readUid());

        connection.setRegister(0x10, 0x42);
        assertEquals(0x42, eeprom.readByte(0x10));

        eeprom.writeByte(0x10, 0x99);
        assertEquals(0x99, connection.registers().get(0x10));
        byte[] lastWrite = connection.writes().get(connection.writes().size() - 1);
        assertArrayEquals(new byte[]{0x10, (byte) 0x99}, lastWrite);

        // Sequential read (0x05-0x08).
        connection.setRegister(0x05, 1, 2, 3, 4);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, eeprom.read(0x05, 4));

        eeprom.writePage(0x08, new byte[]{10, 20, 30});
        assertEquals(10, connection.registers().get(0x08));
        assertEquals(20, connection.registers().get(0x09));
        assertEquals(30, connection.registers().get(0x0A));

        // write() spanning a page boundary: page 0 is 0x00-0x07, page 1 is
        // 0x08-0x0F. Starting at 0x05 with 10 bytes -> [0x05,0x06,0x07] (3
        // bytes, page 0) then [0x08..0x0E] (7 bytes, page 1). writePage()
        // issues exactly one write per call (no ack-poll traffic in Java),
        // so the two page-chunk writes are the last two writes.
        byte[] data10 = new byte[10];
        for (int i = 0; i < 10; i++) data10[i] = (byte) (100 + i);
        eeprom.write(0x05, data10);
        byte[] page1Chunk = connection.writes().get(connection.writes().size() - 1);
        byte[] page0Chunk = connection.writes().get(connection.writes().size() - 2);
        assertArrayEquals(new byte[]{0x05, 100, 101, 102}, page0Chunk);
        assertArrayEquals(new byte[]{0x08, 103, 104, 105, 106, 107, 108, 109}, page1Chunk);
        assertEquals(100, connection.registers().get(0x05));
        assertEquals(101, connection.registers().get(0x06));
        assertEquals(102, connection.registers().get(0x07));
        assertEquals(103, connection.registers().get(0x08));
        assertEquals(109, connection.registers().get(0x0E));

        connection.setRegister(Eeprom24Aa02UidMinimal.ADDR_MFR_CODE, 0x29);
        assertEquals(0x29, eeprom.readManufacturerCode());

        connection.setRegister(Eeprom24Aa02UidMinimal.ADDR_DEV_CODE, 0x41);
        assertEquals(0x41, eeprom.readDeviceCode());
    }
}
