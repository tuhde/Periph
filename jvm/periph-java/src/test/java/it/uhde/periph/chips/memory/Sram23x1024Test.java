package it.uhde.periph.chips.memory;

import it.uhde.periph.connection.Connection;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.OutputPin;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Sram23x1024Test {

    /**
     * Behavioural 23X1024: parses the instruction frames the driver sends and
     * models the array, the MODE register and the Byte/Page/Sequential
     * address-increment rules. Every frame is recorded on {@code frames}.
     */
    private static class SramModel implements Connection {
        final byte[] mem = new byte[131072];
        final List<byte[]> frames = new ArrayList<>();
        int mode = 0x40;
        boolean dead = false;

        private int next(int addr) {
            if ((mode & 0xC0) == 0x80) return (addr & ~31) | ((addr + 1) & 31);
            return (addr + 1) & 0x1FFFF;
        }

        @Override public void enable() {}
        @Override public void disable() {}
        @Override public boolean isEnabled() { return true; }
        @Override public InputPin intPin() { return null; }
        @Override public OutputPin enPin() { return null; }

        @Override
        public void write(byte[] data) {
            frames.add(data.clone());
            if ((data[0] & 0xFF) == 0x01 && data.length == 2) {
                mode = data[1] & 0xFF;
            } else if ((data[0] & 0xFF) == 0x02 && data.length >= 4) {
                int addr = ((data[1] & 0xFF) << 16) | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
                for (int i = 4; i < data.length; i++) {
                    mem[addr] = data[i];
                    addr = next(addr);
                    if ((mode & 0xC0) == 0x00) break;
                }
            }
        }

        @Override public byte[] read(int n) { return new byte[n]; }

        @Override
        public byte[] writeRead(byte[] data, int n) {
            frames.add(data.clone());
            byte[] out = new byte[n];
            if ((data[0] & 0xFF) == 0x05) {
                out[0] = dead ? 0 : (byte) mode;
            } else if ((data[0] & 0xFF) == 0x03 && data.length == 4) {
                int addr = ((data[1] & 0xFF) << 16) | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
                for (int i = 0; i < n; i++) {
                    out[i] = mem[addr];
                    addr = next(addr);
                    if ((mode & 0xC0) == 0x00) break;
                }
            }
            return out;
        }

        @Override public void close() {}
    }

    private static byte[] bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) b[i] = (byte) values[i];
        return b;
    }

    private static byte[] pattern(int n) {
        byte[] p = new byte[n];
        for (int i = 0; i < n; i++) p[i] = (byte) (i * 7 + 3);
        return p;
    }

    @Test
    void initWritesSequentialAndVerifies() throws IOException {
        SramModel conn = new SramModel();
        new Sram23x1024Minimal(conn);
        assertEquals(2, conn.frames.size());
        assertArrayEquals(bytes(0x01, 0x40), conn.frames.get(0));
        assertArrayEquals(bytes(0x05), conn.frames.get(1));
    }

    @Test
    void initThrowsOnModeMismatch() {
        SramModel dead = new SramModel();
        dead.dead = true;
        assertThrows(IOException.class, () -> new Sram23x1024Minimal(dead));
    }

    @Test
    void byteAccessFramesAndRoundTrip() throws IOException {
        SramModel conn = new SramModel();
        Sram23x1024Minimal chip = new Sram23x1024Minimal(conn);
        chip.writeByte(0x1ABCD, 0x5A);
        assertArrayEquals(bytes(0x02, 0x01, 0xAB, 0xCD, 0x5A), conn.frames.get(conn.frames.size() - 1));
        assertEquals((byte) 0x5A, conn.mem[0x1ABCD]);
        assertEquals(0x5A, chip.readByte(0x1ABCD));
        assertArrayEquals(bytes(0x03, 0x01, 0xAB, 0xCD), conn.frames.get(conn.frames.size() - 1));
        chip.writeByte(0x1FFFF, 0x77);
        assertEquals(0x77, chip.readByte(0x1FFFF));
        assertThrows(IllegalArgumentException.class, () -> chip.readByte(0x20000));
    }

    @Test
    void blockAccessIsChunked() throws IOException {
        SramModel fc = new SramModel();
        Sram23x1024Full full = new Sram23x1024Full(fc);
        assertEquals(131072, full.size());
        byte[] p = pattern(600);
        full.write(0x100, p);
        assertArrayEquals(p, Arrays.copyOfRange(fc.mem, 0x100, 0x100 + 600));
        assertArrayEquals(p, full.read(0x100, 600));
        assertEquals(3, fc.frames.stream().filter(f -> (f[0] & 0xFF) == 0x02).count());

        full.setMaxChunk(100);
        assertEquals(100, full.maxChunk());
        fc.frames.clear();
        full.write(0, Arrays.copyOf(p, 250));
        assertEquals(3, fc.frames.size());
        assertEquals(104, fc.frames.get(0).length);
        assertEquals(54, fc.frames.get(2).length);
        assertThrows(IllegalArgumentException.class, () -> full.setMaxChunk(257));
    }

    @Test
    void fillWritesOnlyTheRange() throws IOException {
        SramModel fc = new SramModel();
        Sram23x1024Full full = new Sram23x1024Full(fc);
        full.fill(0x2000, 100, 0xA5);
        for (int i = 0; i < 100; i++) assertEquals((byte) 0xA5, fc.mem[0x2000 + i]);
        assertEquals(0, fc.mem[0x2064]);
        assertEquals(0, fc.mem[0x1FFF]);
        full.fill(0x3000, 5, 0);
        assertEquals(0, fc.mem[0x3004]);
    }

    @Test
    void rangeChecks() throws IOException {
        Sram23x1024Full full = new Sram23x1024Full(new SramModel());
        assertThrows(IllegalArgumentException.class, () -> full.read(0x1FFFF, 2));
        assertThrows(IllegalArgumentException.class, () -> full.write(0x1FFFF, bytes(1, 2)));
        assertThrows(IllegalArgumentException.class, () -> full.fill(0x1FFFF, 2, 0));
    }

    @Test
    void pageModeSplitsAtPageBoundaries() throws IOException {
        SramModel fc = new SramModel();
        Sram23x1024Full full = new Sram23x1024Full(fc);
        full.setMode(Sram23x1024Full.MODE_PAGE);
        assertEquals(0x80, fc.mode);
        assertEquals(Sram23x1024Full.MODE_PAGE, full.getMode());
        fc.frames.clear();
        byte[] data = new byte[64];
        for (int i = 0; i < 64; i++) data[i] = (byte) (0xC0 + i);
        full.write(0x4010, data);
        assertEquals(3, fc.frames.size());
        assertArrayEquals(data, Arrays.copyOfRange(fc.mem, 0x4010, 0x4050));
        assertEquals(0, fc.mem[0x4050]);
        assertArrayEquals(data, full.read(0x4010, 64));
    }

    @Test
    void byteModeSendsOneByteFrames() throws IOException {
        SramModel fc = new SramModel();
        Sram23x1024Full full = new Sram23x1024Full(fc);
        full.setMode(Sram23x1024Full.MODE_BYTE);
        fc.frames.clear();
        byte[] data = bytes(1, 2, 3, 4);
        full.write(0x500, data);
        assertEquals(4, fc.frames.size());
        assertArrayEquals(data, full.read(0x500, 4));
        full.setMode(Sram23x1024Full.MODE_SEQUENTIAL);
        assertEquals(Sram23x1024Full.MODE_SEQUENTIAL, full.getMode());
        assertThrows(IllegalArgumentException.class, () -> full.setMode(0xC0));
    }

    @Test
    void getModeMasksReservedBits() throws IOException {
        SramModel fc = new SramModel();
        Sram23x1024Full full = new Sram23x1024Full(fc);
        fc.mode = 0x4F;
        assertEquals(Sram23x1024Full.MODE_SEQUENTIAL, full.getMode());
    }

    @Test
    void resetIoSendsFf() throws IOException {
        SramModel fc = new SramModel();
        Sram23x1024Full full = new Sram23x1024Full(fc);
        fc.frames.clear();
        full.resetIo();
        assertArrayEquals(bytes(0xFF), fc.frames.get(0));
        assertTrue(fc.frames.size() >= 2);
    }
}
