package it.uhde.periph.chips.memory;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AT24C64Test {

    private static MockConnection mock() {
        MockConnection c = new MockConnection();
        c.setAddressWidth(2);
        return c;
    }

    /** Frames carrying data (two address bytes plus at least one data byte). */
    private static List<byte[]> dataFrames(MockConnection c) {
        List<byte[]> out = new ArrayList<>();
        for (byte[] w : c.writes()) if (w.length > 2) out.add(w);
        return out;
    }

    @Test
    void byteAccess() throws Exception {
        MockConnection connection = mock();
        AT24C64Full ee = new AT24C64Full(connection);
        assertTrue(connection.writes().isEmpty());
        assertEquals(8192, ee.size());
        assertEquals(32, ee.pageSize());

        connection.setRegister(0x1234, 0x42);
        assertEquals(0x42, ee.readByte(0x1234));
        assertArrayEquals(new byte[]{0x12, 0x34}, connection.writes().get(connection.writes().size() - 1));
        assertEquals(0, ee.readByte(0x1FFF));

        connection.writes().clear();
        ee.writeByte(0x0010, 0x99);
        assertEquals(0x99, connection.registers().get(0x0010));
        assertArrayEquals(new byte[]{0x00, 0x10, (byte) 0x99}, connection.writes().get(0));
        assertArrayEquals(new byte[]{0x00, 0x00}, connection.writes().get(1));

        assertThrows(IllegalArgumentException.class, () -> ee.readByte(0x2000));
        assertThrows(IllegalArgumentException.class, () -> ee.readByte(-1));
        assertThrows(IllegalArgumentException.class, () -> ee.writeByte(0x2000, 1));
        assertNotNull(new AT24C64Minimal(connection));
    }

    @Test
    void ackPolling() throws Exception {
        MockConnection connection = mock();
        AT24C64Full ee = new AT24C64Full(connection);
        connection.failNextWrites(2, 0);
        connection.writes().clear();
        assertTrue(ee.waitReady(50));
        assertEquals(3, connection.writes().size());
        connection.failNextWrites(100000, 0);
        assertFalse(ee.waitReady(5));
        connection.failNextWrites(0, 0);
        connection.failNextWrites(100000, 1);
        assertThrows(IOException.class, () -> ee.writeByte(0x0021, 2));
        connection.failNextWrites(0, 0);
    }

    @Test
    void pageSplitWrite() throws Exception {
        MockConnection connection = mock();
        AT24C64Full ee = new AT24C64Full(connection);
        assertEquals(30, ee.getMaxChunk());
        byte[] data = new byte[70];
        for (int i = 0; i < 70; i++) data[i] = (byte) i;
        ee.write(0x0070, data);
        List<byte[]> frames = dataFrames(connection);
        assertEquals(3, frames.size());
        assertArrayEquals(concat(new byte[]{0x00, 0x70}, Arrays.copyOfRange(data, 0, 16)), frames.get(0));
        assertArrayEquals(concat(new byte[]{0x00, (byte) 0x80}, Arrays.copyOfRange(data, 16, 46)), frames.get(1));
        assertArrayEquals(concat(new byte[]{0x00, (byte) 0x9E}, Arrays.copyOfRange(data, 46, 70)), frames.get(2));
        assertEquals(6, connection.writes().size());
        assertArrayEquals(data, ee.read(0x0070, 70));

        ee.setMaxChunk(8);
        connection.writes().clear();
        ee.write(0x0200, new byte[20]);
        assertEquals(3, dataFrames(connection).size());
        assertThrows(IllegalArgumentException.class, () -> ee.setMaxChunk(0));
    }

    @Test
    void writePageAndFill() throws Exception {
        MockConnection connection = mock();
        AT24C64Full ee = new AT24C64Full(connection);
        byte[] page = new byte[32];
        for (int i = 0; i < 32; i++) page[i] = (byte) i;
        ee.writePage(0x0300, page);
        for (int i = 0; i < 32; i++) assertEquals(i, connection.registers().get(0x0300 + i));
        assertThrows(IllegalArgumentException.class, () -> ee.writePage(0x037C, new byte[8]));
        assertThrows(IllegalArgumentException.class, () -> ee.writePage(0x0400, new byte[33]));
        assertThrows(IllegalArgumentException.class, () -> ee.writePage(0x1FFF, new byte[2]));

        ee.fill(0x0500, 200, 0xAB);
        for (int i = 0; i < 200; i++) assertEquals(0xAB, connection.registers().get(0x0500 + i));
        assertNull(connection.registers().get(0x0500 + 200));
        ee.fill(0x0500, 4);
        assertEquals(0, connection.registers().get(0x0500));
    }

    @Test
    void rangeRules() throws Exception {
        MockConnection connection = mock();
        AT24C64Full ee = new AT24C64Full(connection);
        ee.write(0x1FFE, new byte[]{1, 2});
        assertArrayEquals(new byte[]{1, 2}, ee.read(0x1FFE, 2));
        assertThrows(IllegalArgumentException.class, () -> ee.write(0x1FFF, new byte[]{1, 2}));
        assertThrows(IllegalArgumentException.class, () -> ee.read(0x1FF0, 0x20));
        assertThrows(IllegalArgumentException.class, () -> ee.fill(0x1FFF, 2, 0));
        assertEquals(0, ee.read(0, 0).length);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
