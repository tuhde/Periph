package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.MockConnection;
import it.uhde.periph.connection.OutputPin;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MCP4XXXTest {

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i];
        return out;
    }

    private static byte[] last(MockConnection c) {
        return c.writes().get(c.writes().size() - 1);
    }

    /** Models the MCP4XXX command protocol: sixteen nine-bit cells, inc/dec of the volatile wipers, two-byte reads. */
    static class ChipMock extends MockConnection {
        final int[] mem = new int[16];
        final int maxCode;
        int status = 0x1F0;

        ChipMock(int maxCode) {
            this.maxCode = maxCode;
            mem[0] = mem[1] = mem[2] = mem[3] = maxCode / 2;
            mem[4] = 0x1FF;
        }

        @Override
        public void write(byte[] data) throws IOException {
            super.write(data);
            for (int i = 0; i < data.length; ) {
                int cmd = data[i] & 0xFF;
                int addr = cmd >> 4;
                int op = (cmd >> 2) & 3;
                if (op == 0) {
                    mem[addr] = ((cmd & 1) << 8) | (data[i + 1] & 0xFF);
                    i += 2;
                } else {
                    if (addr < 2) mem[addr] = op == 1 ? Math.min(maxCode, mem[addr] + 1) : Math.max(0, mem[addr] - 1);
                    i += 1;
                }
            }
        }

        @Override
        public byte[] writeRead(byte[] data, int n) throws IOException {
            super.writeRead(data, n);
            int addr = (data[0] & 0xFF) >> 4;
            int v = addr == 5 ? status : mem[addr];
            return new byte[] {(byte) ((v >> 8) & 1), (byte) v};
        }
    }

    static class Pin implements OutputPin {
        final List<String> log = new ArrayList<>();
        @Override public void set(boolean high) { log.add(high ? "HVC1" : "HVC0"); }
        @Override public void close() {}
    }

    private static ChipMock mock(String part) {
        return new ChipMock(MCP4XXXMinimal.lookup(part).maxCode);
    }

    @Test
    void partTable() {
        assertEquals(0x2E, MCP4XXXMinimal.defaultAddress("mcp4531"));
        assertEquals(0x58, MCP4XXXMinimal.defaultAddress("MCP4632"));
        assertEquals(0x28, MCP4XXXMinimal.defaultAddress("MCP4661"));
        assertThrows(IllegalArgumentException.class, () -> new MCP4XXXMinimal(new MockConnection(), "MCP9999"));
    }

    @Test
    void initReadsOnly() throws Exception {
        ChipMock c = mock("MCP4662");
        MCP4XXXFull chip = new MCP4XXXFull(c, "MCP4662");
        chip.init();
        assertEquals(4, c.writes().size());
        assertArrayEquals(b(0x0C), c.writes().get(0));
        assertArrayEquals(b(0x1C), c.writes().get(1));
        assertArrayEquals(b(0x4C), c.writes().get(2));
        assertArrayEquals(b(0x5C), c.writes().get(3));
        assertEquals(2, chip.channels());
        assertEquals(257, chip.steps());
        assertFalse(chip.isPotentiometer());
        assertTrue(chip.isNonvolatile());
    }

    @Test
    void wiperFormats() throws Exception {
        ChipMock c = mock("MCP4531");
        MCP4XXXFull chip = new MCP4XXXFull(c, "MCP4531");
        chip.setWiper(100);
        assertArrayEquals(b(0x00, 100), last(c));
        chip.setWiper(999);
        assertArrayEquals(b(0x00, 128), last(c));
        chip.setWiper(-3);
        assertArrayEquals(b(0x00, 0), last(c));
        assertThrows(IllegalArgumentException.class, () -> chip.setWiper(1, 1));

        ChipMock c8 = mock("MCP4652");
        MCP4XXXFull chip8 = new MCP4XXXFull(c8, "MCP4652");
        chip8.setWiper(256, 1);
        assertArrayEquals(b(0x11, 0x00), last(c8));
        assertEquals(256, chip8.getWiper(1));
        c8.mem[0] = 0x3FF;
        assertEquals(256, chip8.getWiper(0));
    }

    @Test
    void resistanceAndRatio() throws Exception {
        ChipMock c = mock("MCP4531");
        MCP4XXXFull chip = new MCP4XXXFull(c, "MCP4531");
        chip.setWiper(64);
        assertEquals(5075.0, chip.getResistance(), 1e-9);
        assertEquals(5075.0, chip.getResistanceWa(0), 1e-9);
        chip.setResistance(1e9);
        assertEquals(128, chip.getWiper());
        chip.setResistanceWa(5075.0, 0);
        assertEquals(64, chip.getWiper());
        chip.setRatio(0.5, 0);
        assertEquals(0.5, chip.getRatio(0), 1e-9);
        assertEquals(10000.0 / 128, chip.getResistanceStep(), 1e-9);
        MCP4XXXFull rheo = new MCP4XXXFull(mock("MCP4532"), "MCP4532");
        assertThrows(UnsupportedOperationException.class, () -> rheo.getResistanceWa(0));
        assertThrows(UnsupportedOperationException.class, () -> rheo.setBoth(1, 2));
    }

    @Test
    void incrementDecrement() throws Exception {
        ChipMock c = mock("MCP4631");
        MCP4XXXFull chip = new MCP4XXXFull(c, "MCP4631");
        chip.setBoth(10, 20);
        assertEquals(10, c.mem[0]);
        assertEquals(20, c.mem[1]);
        chip.setWiper(120, 1);
        int n = c.writes().size();
        assertEquals(128, chip.increment(10, 1));
        assertEquals(n + 2, c.writes().size()); // one continuous write + the read-back
        byte[] run = c.writes().get(n);
        assertEquals(10, run.length);
        assertEquals(0x14, run[0] & 0xFF);
        chip.setWiper(5, 0);
        assertEquals(0, chip.decrement(10, 0));
        assertEquals(1, chip.increment(1, 0));
    }

    @Test
    void tcon() throws Exception {
        ChipMock c = mock("MCP4631");
        MCP4XXXFull chip = new MCP4XXXFull(c, "MCP4631");
        chip.shutdown(1);
        assertEquals(0x1FF & ~0x80, c.mem[4]);
        assertTrue(chip.isShutdown(1));
        assertFalse(chip.isShutdown(0));
        chip.resume(1);
        assertEquals(0x1FF, c.mem[4]);
        chip.shutdown(0);
        assertEquals(0x1FF & ~0x08, c.mem[4]);
        chip.resume(0);
        chip.setTerminals(true, false, true, 1);
        assertEquals(0x1FF & ~0x20, c.mem[4]);
        assertArrayEquals(new boolean[] {true, false, true}, chip.getTerminals(1));
        assertArrayEquals(new boolean[] {true, true, true}, chip.getTerminals(0));
        chip.setGeneralCall(false);
        assertEquals(0, chip.getTcon() & 0x100);
        chip.setGeneralCall(true);
        assertEquals(0x100, c.mem[4] & 0x100);
        chip.setWiper(3, 0);
        chip.shutdown(1);
        chip.reset();
        assertEquals(64, c.mem[0]);
        assertEquals(64, c.mem[1]);
        assertEquals(0x1FF, c.mem[4]);
    }

    @Test
    void rawAccess() throws Exception {
        ChipMock c = mock("MCP4532");
        MCP4XXXFull chip = new MCP4XXXFull(c, "MCP4532");
        assertEquals(0x1FF, chip.readRegister(4));
        chip.writeRegister(0, 77);
        assertEquals(77, c.mem[0]);
        assertThrows(IllegalArgumentException.class, () -> chip.writeRegister(5, 0));
        assertThrows(IllegalArgumentException.class, () -> chip.readRegister(2));
        assertThrows(IllegalArgumentException.class, () -> chip.readRegister(16));
    }

    @Test
    void nonvolatileUnsupportedOnVolatile() {
        MCP4XXXFull chip = new MCP4XXXFull(mock("MCP4532"), "MCP4532");
        assertThrows(UnsupportedOperationException.class, chip::readStatus);
        assertThrows(UnsupportedOperationException.class, () -> chip.waitReady(5));
        assertThrows(UnsupportedOperationException.class, () -> chip.getNvWiper(0));
        assertThrows(UnsupportedOperationException.class, () -> chip.storeWiper(0));
        assertThrows(UnsupportedOperationException.class, () -> chip.readData(0));
        assertThrows(UnsupportedOperationException.class, () -> chip.setWriteProtect(true));
    }

    @Test
    void nonvolatile() throws Exception {
        Pin pin = new Pin();
        ChipMock c = mock("MCP4662");
        MCP4XXXFull chip = new MCP4XXXFull(c, "MCP4662", 10000.0, 75.0, pin, 1);
        MCP4XXXFull.Status st = chip.readStatus();
        assertFalse(st.eepromBusy);
        assertFalse(st.writeProtect);
        c.status = 0x1F0 | 0x08 | 0x02 | 0x01;
        st = chip.readStatus();
        assertTrue(st.eepromBusy && st.wiperLock[0] && !st.wiperLock[1] && st.writeProtect);
        assertTrue(chip.isWiperLocked(0));
        assertFalse(chip.isWiperLocked(1));
        assertTrue(chip.isWriteProtected());
        assertThrows(IOException.class, () -> chip.waitReady(3));
        c.status = 0x1F0;

        chip.setNvWiper(200, 1);
        assertEquals(200, c.mem[3]);
        chip.setWiper(150, 0);
        assertEquals(150, chip.storeWiper(0));
        assertEquals(150, c.mem[2]);
        chip.writeData(3, 0x1AB);
        assertEquals(0x1AB, chip.readData(3));
        assertTrue(c.writes().stream().anyMatch(w -> java.util.Arrays.equals(w, b(0x91, 0xAB))));
        assertThrows(IllegalArgumentException.class, () -> chip.readData(10));
        chip.setWiper(7, 0);
        chip.reset();
        assertEquals(150, c.mem[0]);
        assertEquals(200, c.mem[1]);

        // High-voltage commands: exact single command bytes, pin asserted around the write.
        int n = c.writes().size();
        chip.setWiperLock(true, 0);
        assertArrayEquals(b(0x28), c.writes().get(n));
        assertEquals(List.of("HVC1", "HVC0"), pin.log.subList(pin.log.size() - 2, pin.log.size()));
        n = c.writes().size();
        chip.setWiperLock(false, 1);
        assertArrayEquals(b(0x34), c.writes().get(n));
        n = c.writes().size();
        chip.setWiperLock(true, 1);
        assertArrayEquals(b(0x38), c.writes().get(n));
        n = c.writes().size();
        chip.setWriteProtect(true);
        assertArrayEquals(b(0xF8), c.writes().get(n));
        n = c.writes().size();
        chip.setWriteProtect(false);
        assertArrayEquals(b(0xF4), c.writes().get(n));
        MCP4XXXFull nohv = new MCP4XXXFull(mock("MCP4562"), "MCP4562");
        assertThrows(UnsupportedOperationException.class, () -> nohv.setWiperLock(true, 0));
    }

    @Test
    void setRabHasNoTraffic() throws Exception {
        ChipMock c = mock("MCP4532");
        MCP4XXXFull chip = new MCP4XXXFull(c, "MCP4532");
        int n = c.writes().size();
        chip.setRab(50000.0, 100.0);
        assertEquals(n, c.writes().size());
        chip.setWiper(128);
        assertEquals(50100.0, chip.getResistance(), 1e-9);
    }
}
