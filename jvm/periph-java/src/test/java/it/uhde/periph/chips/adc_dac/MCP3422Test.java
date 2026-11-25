package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MCP3422Test {

    @BeforeAll
    static void noWaits() {
        MCP3422Minimal.realWaits = false;   // skip the conversion-time waits
    }

    private static byte[] frame(int raw, int bits, boolean rdy) {
        int n = bits == 18 ? 3 : 2;
        byte[] out = new byte[n + 1];
        for (int i = 0; i < n; i++) out[i] = (byte) (raw >> (8 * (n - 1 - i)));
        out[n] = (byte) (rdy ? 0x80 : 0x00);
        return out;
    }

    private static MockConnection mock(byte[]... frames) {
        MockConnection c = new MockConnection();
        for (byte[] f : frames) c.queueRead(f);
        return c;
    }

    private static int lastWrite(MockConnection c) {
        return c.writes().get(c.writes().size() - 1)[0] & 0xFF;
    }

    @Test
    void initAndConfigBytes() throws Exception {
        MockConnection c = mock(frame(0, 16, false), frame(0, 16, false), frame(0, 16, false), frame(0, 16, false));
        MCP3422Full chip = new MCP3422Full(c);
        assertEquals(1, c.writes().size());
        assertEquals(0x08, lastWrite(c));
        chip.readRaw(0);
        assertEquals(0x88, lastWrite(c));
        chip.readRaw(1);
        assertEquals(0xA8, lastWrite(c));
        chip.readRaw(7);
        assertEquals(0xA8, lastWrite(c));
        chip.readRaw(-3);
        assertEquals(0x88, lastWrite(c));
    }

    @Test
    void decoding() throws Exception {
        int[][] cases = {{12, -100}, {12, 2047}, {12, -2048}, {14, -8192}, {14, 8191}, {16, 12345}, {16, -12345},
                         {16, -32768}, {18, 131071}, {18, -131072}, {18, -1}};
        for (int[] cs : cases) {
            MCP3422Full chip = new MCP3422Full(mock(frame(cs[1], cs[0], false)));
            chip.setResolution(cs[0]);
            assertEquals(cs[1], chip.readRaw(0), cs[0] + " bit " + cs[1]);
        }
        MCP3422Full chip = new MCP3422Full(mock(new byte[]{(byte) 0xFC, 0x00, 0x05, 0x00}));
        chip.setResolution(18);
        assertEquals(5, chip.readRaw(0));   // byte0's upper six bits are not data
    }

    @Test
    void voltagesAndDerivedValues() throws Exception {
        MCP3422Full chip = new MCP3422Full(mock(frame(16384, 16, false), frame(-32768, 16, false)));
        assertEquals(16384 * 2 * 2.048 / 65536, chip.readVoltage(0), 1e-9);
        chip.setGain(8);
        assertEquals(-0.256, chip.readVoltage(0), 1e-9);
        assertEquals(0.256, chip.fullScale(), 1e-9);
        assertEquals(62.5e-6, chip.lsb(), 1e-12);
        chip.setResolution(12);
        assertEquals(1e-3, chip.lsb(), 1e-12);
        chip.setResolution(18);
        assertEquals(15.625e-6, chip.lsb(), 1e-12);
    }

    @Test
    void readAllAndScan() throws Exception {
        MockConnection c = mock(frame(100, 16, false), frame(200, 16, false), frame(10, 16, false), frame(20, 16, false), frame(30, 16, false));
        MCP3422Full chip = new MCP3422Full(c);
        double[] v = chip.readAll();
        assertEquals(100 * 2 * 2.048 / 65536, v[0], 1e-9);
        assertEquals(200 * 2 * 2.048 / 65536, v[1], 1e-9);
        assertEquals(0x88, c.writes().get(1)[0] & 0xFF);
        assertEquals(0xA8, c.writes().get(2)[0] & 0xFF);
        assertEquals(3, chip.readScan(new int[]{1, 0, 1}).length);
        assertEquals(0xA8, c.writes().get(3)[0] & 0xFF);
        assertEquals(0x88, c.writes().get(4)[0] & 0xFF);
        assertEquals(0xA8, c.writes().get(5)[0] & 0xFF);
    }

    @Test
    void pollingAndTimeout() throws Exception {
        MCP3422Full chip = new MCP3422Full(mock(frame(1, 16, true), frame(1, 16, true), frame(777, 16, false)));
        assertEquals(777, chip.readRaw(0));
        byte[][] stale = new byte[40][];
        for (int i = 0; i < stale.length; i++) stale[i] = frame(1, 16, true);
        MCP3422Full chip2 = new MCP3422Full(mock(stale));
        assertThrows(TimeoutException.class, () -> chip2.readRaw(0));
    }

    @Test
    void selection() throws Exception {
        MockConnection c = mock(frame(0, 18, false));
        MCP3422Full chip = new MCP3422Full(c);
        assertThrows(IllegalArgumentException.class, () -> chip.setResolution(10));
        assertThrows(IllegalArgumentException.class, () -> chip.setGain(3));
        chip.setResolution(18);
        chip.setGain(8);
        assertEquals(18, chip.getResolution());
        assertEquals(8, chip.getGain());
        chip.readRaw(0);
        assertEquals(0x8F, lastWrite(c));
    }

    @Test
    void continuousMode() throws Exception {
        MockConnection c = mock(frame(5, 12, false), frame(300, 12, false), frame(301, 12, false));
        MCP3422Full chip = new MCP3422Full(c);
        chip.setResolution(12);
        chip.startContinuous();
        assertEquals(0x10, lastWrite(c));
        assertTrue(chip.isContinuous());
        assertEquals(300, chip.readContinuousRaw());
        assertEquals(301, chip.readContinuousRaw());
        chip.startContinuous(1);
        assertEquals(0x30, lastWrite(c));
        chip.stopContinuous();
        assertEquals(0x20, lastWrite(c));
        assertFalse(chip.isContinuous());
        assertThrows(IllegalStateException.class, chip::readContinuousRaw);

        MockConnection c2 = mock(frame(1, 12, false), frame(2, 12, false), frame(3, 12, false), frame(4, 12, false));
        MCP3422Full chip2 = new MCP3422Full(c2);
        chip2.setResolution(12);
        chip2.startContinuous();
        chip2.readContinuousRaw();
        int n = c2.writes().size();
        chip2.setGain(2);
        assertEquals(n + 1, c2.writes().size());
        assertEquals(0x11, lastWrite(c2));
        assertEquals(4, chip2.readContinuousRaw());

        MCP3422Full chip3 = new MCP3422Full(mock(frame(5, 16, false), frame(16384, 16, false)));
        chip3.startContinuous();
        assertEquals(16384 * 2 * 2.048 / 65536, chip3.readContinuous(), 1e-9);
    }

    @Test
    void configAndGeneralCalls() throws Exception {
        MCP3422Full chip = new MCP3422Full(mock(frame(0, 16, true), frame(0, 16, false)));
        assertEquals(0x80, chip.readConfig());
        assertTrue(chip.isReady());

        MockConnection c = mock(frame(321, 16, false));
        MCP3422Full chip2 = new MCP3422Full(c);
        chip2.setResolution(18);
        chip2.setGain(8);
        chip2.generalCallReset();
        assertArrayEquals(new byte[]{0x00, 0x06}, c.writes().get(c.writes().size() - 1));
        assertEquals(12, chip2.getResolution());
        assertEquals(1, chip2.getGain());
        assertTrue(chip2.isContinuous());
        chip2.stopContinuous();
        chip2.setResolution(16);
        chip2.generalCallConvert();
        assertArrayEquals(new byte[]{0x00, 0x08}, c.writes().get(c.writes().size() - 1));
        assertEquals(321, chip2.readPendingRaw());
    }
}
