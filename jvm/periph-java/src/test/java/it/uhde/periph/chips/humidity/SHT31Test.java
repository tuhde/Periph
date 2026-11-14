package it.uhde.periph.chips.humidity;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SHT31Test {

    private static byte[] frame(int rawT, int rawRh) {
        byte[] f = new byte[6];
        f[0] = (byte) (rawT >> 8); f[1] = (byte) rawT;
        f[3] = (byte) (rawRh >> 8); f[4] = (byte) rawRh;
        f[2] = (byte) SHT31Minimal.crc8(f, 0, 2);
        f[5] = (byte) SHT31Minimal.crc8(f, 3, 2);
        return f;
    }

    private static List<Integer> cmds(MockConnection c) {
        List<Integer> out = new ArrayList<>();
        for (byte[] w : c.writes()) out.add(((w[0] & 0xFF) << 8) | (w[1] & 0xFF));
        return out;
    }

    private MockConnection mock;
    private SHT31Full s;

    private void make() throws IOException {
        mock = new MockConnection();
        s = new SHT31Full(mock);
    }

    @Test
    void crcReference() {
        assertEquals(0x92, SHT31Minimal.crc8(new byte[]{(byte) 0xBE, (byte) 0xEF}, 0, 2));
    }

    @Test
    void initAndRead() throws IOException {
        make();
        assertEquals(List.of(0x30A2), cmds(mock));
        mock.writes().clear();
        mock.queueRead(frame(0x6666, 0x8000));
        double[] v = s.read();
        assertEquals(List.of(0x2400), cmds(mock));
        assertEquals(-45.0 + 175.0 * 0x6666 / 65535.0, v[0], 1e-9);
        assertEquals(100.0 * 0x8000 / 65535.0, v[1], 1e-9);
        assertNotNull(new SHT31Minimal(new MockConnection()));
    }

    @Test
    void readPollsAndRetries() throws IOException {
        make();
        mock.failNextReads(3);
        mock.queueRead(frame(0, 65535));
        double[] v = s.read();
        assertEquals(-45.0, v[0], 1e-9);
        assertEquals(100.0, v[1], 1e-9);

        mock.writes().clear();
        byte[] bad = frame(0x1234, 0x5678);
        bad[2] ^= (byte) 0xFF;
        mock.queueRead(bad);
        mock.queueRead(frame(0x1234, 0x5678));
        s.read();
        assertEquals(List.of(0x2400, 0x2400), cmds(mock));
        mock.queueRead(bad);
        mock.queueRead(bad);
        assertThrows(IOException.class, () -> s.read());
    }

    @Test
    void rawAndSingleValues() throws IOException {
        make();
        mock.queueRead(frame(1000, 2000));
        assertArrayEquals(new int[]{1000, 2000}, s.readRaw());
        mock.queueRead(frame(0, 0));
        assertEquals(-45.0, s.readTemperature(), 1e-9);
        mock.queueRead(frame(0, 65535));
        assertEquals(100.0, s.readHumidity(), 1e-9);
    }

    @Test
    void repeatabilityAndStretching() throws IOException {
        make();
        mock.writes().clear();
        s.setRepeatability("low");
        mock.queueRead(frame(0, 0)); s.read();
        s.setRepeatability("medium");
        mock.queueRead(frame(0, 0)); s.read();
        s.setClockStretching(true);
        mock.queueRead(frame(0, 0)); s.read();
        s.setRepeatability("high");
        mock.queueRead(frame(0, 0)); s.read();
        assertEquals(List.of(0x2416, 0x240B, 0x2C0D, 0x2C06), cmds(mock));
        assertThrows(IllegalArgumentException.class, () -> s.setRepeatability("ultra"));
    }

    @Test
    void periodicMode() throws IOException {
        make();
        mock.writes().clear();
        s.startPeriodic(2, "high", false);
        s.startPeriodic(0.5, "low", false);
        s.startPeriodic(10, "medium", false);
        s.startPeriodic(1, "high", true);
        assertEquals(List.of(0x2236, 0x202F, 0x2721, 0x2B32), cmds(mock));
        assertThrows(IllegalArgumentException.class, () -> s.startPeriodic(3, "high", false));
        assertThrows(IllegalArgumentException.class, () -> s.startPeriodic(1, "x", false));
        mock.writes().clear();
        mock.queueRead(frame(0x4000, 0x4000));
        double[] v = s.fetch();
        assertEquals(List.of(0xE000), cmds(mock));
        assertEquals(-45.0 + 175.0 * 0x4000 / 65535.0, v[0], 1e-9);
        mock.failNextReads(1);
        assertNull(s.fetch());
        mock.writes().clear();
        s.stopPeriodic();
        assertEquals(List.of(0x3093), cmds(mock));
    }

    @Test
    void heaterStatusReset() throws IOException {
        make();
        mock.writes().clear();
        s.setHeater(true);
        s.setHeater(false);
        s.clearStatus();
        assertEquals(List.of(0x306D, 0x3066, 0x3041), cmds(mock));
        byte[] st = {(byte) 0x80, 0x10, 0};
        st[2] = (byte) SHT31Minimal.crc8(st, 0, 2);
        mock.queueRead(st);
        assertEquals(0x8010, s.readStatus());
        mock.queueRead(new byte[]{(byte) 0x80, 0x10, 0x00});
        assertThrows(IOException.class, () -> s.readStatus());
        mock.writes().clear();
        s.softReset();
        assertEquals(List.of(0x30A2), cmds(mock));
    }

    @Test
    void dewPoint() throws IOException {
        make();
        mock.queueRead(frame((int) Math.round((25.0 + 45.0) * 65535.0 / 175.0), (int) Math.round(50.0 * 65535.0 / 100.0)));
        assertEquals(13.86, s.dewPoint(), 0.05);
    }
}
