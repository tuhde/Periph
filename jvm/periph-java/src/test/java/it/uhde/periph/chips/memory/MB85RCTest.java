package it.uhde.periph.chips.memory;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MB85RCTest {

    private static MockConnection mock(int width) {
        MockConnection c = new MockConnection();
        c.setAddressWidth(width);
        return c;
    }

    @Test
    void fullApi() throws Exception {
        MockConnection connection = mock(2);
        MB85RCFull fram = new MB85RCFull(connection);
        assertTrue(connection.writes().isEmpty());
        assertEquals(32768, fram.size());

        connection.setRegister(0x1234, 0x42);
        assertEquals(0x42, fram.readByte(0x1234));
        assertArrayEquals(new byte[]{0x12, 0x34}, connection.writes().get(connection.writes().size() - 1));

        fram.writeByte(0x0010, 0x99);
        assertEquals(0x99, connection.registers().get(0x0010));
        assertArrayEquals(new byte[]{0x00, 0x10, (byte) 0x99}, connection.writes().get(connection.writes().size() - 1));

        assertThrows(IllegalArgumentException.class, () -> fram.readByte(0x8000));
        assertThrows(IllegalArgumentException.class, () -> fram.readByte(-1));
        assertThrows(IllegalArgumentException.class, () -> fram.writeByte(0x8000, 1));

        // Chunking: 70 bytes at maxChunk=30 -> 30 + 30 + 10.
        assertEquals(30, fram.getMaxChunk());
        byte[] data = new byte[70];
        for (int i = 0; i < 70; i++) data[i] = (byte) i;
        connection.writes().clear();
        fram.write(0x0100, data);
        assertEquals(3, connection.writes().size());
        assertEquals(32, connection.writes().get(0).length);
        assertEquals(12, connection.writes().get(2).length);
        assertEquals(30, connection.writes().get(1)[1] & 0xFF);
        connection.writes().clear();
        assertArrayEquals(data, fram.read(0x0100, 70));
        assertEquals(3, connection.writes().size());

        fram.setMaxChunk(8);
        connection.writes().clear();
        fram.write(0x0200, new byte[20]);
        assertEquals(3, connection.writes().size());
        assertThrows(IllegalArgumentException.class, () -> fram.setMaxChunk(0));
        fram.setMaxChunk(30);

        fram.fill(0x0300, 65, 0xAB);
        for (int i = 0; i < 65; i++) assertEquals(0xAB, connection.registers().get(0x0300 + i));
        assertNull(connection.registers().get(0x0300 + 65));
        fram.fill(0x0300, 4);
        for (int i = 0; i < 4; i++) assertEquals(0, connection.registers().get(0x0300 + i));

        fram.write(0x7FFE, new byte[]{1, 2});
        assertArrayEquals(new byte[]{1, 2}, fram.read(0x7FFE, 2));
        assertThrows(IllegalArgumentException.class, () -> fram.write(0x7FFF, new byte[]{1, 2}));
        assertThrows(IllegalArgumentException.class, () -> fram.read(0x7FF0, 0x20));
        assertThrows(IllegalArgumentException.class, () -> fram.fill(0x7FFF, 2));
        assertEquals(0, fram.read(0, 0).length);
    }

    @Test
    void deviceId() throws Exception {
        MB85RCFull fram = new MB85RCFull(mock(2));
        MockConnection id = mock(1);
        id.setRegister(0xA6, 0x00, 0xA5, 0x10);
        assertArrayEquals(new int[]{0x00A, 0x5, 0x10}, fram.readDeviceId(id, 0x53));
        assertArrayEquals(new byte[]{(byte) 0xA6}, id.writes().get(id.writes().size() - 1));
        id.setRegister(0xA0, 0x00, 0xA5, 0x10);
        assertArrayEquals(new int[]{0x00A, 0x5, 0x10}, fram.readDeviceId(id));
    }

    @Test
    void minimal() throws Exception {
        MockConnection connection = mock(2);
        MB85RCMinimal fram = new MB85RCMinimal(connection);
        fram.writeByte(0x0005, 0x77);
        assertEquals(0x77, fram.readByte(0x0005));
    }
}
