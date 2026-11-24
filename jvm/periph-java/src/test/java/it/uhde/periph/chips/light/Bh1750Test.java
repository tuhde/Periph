package it.uhde.periph.chips.light;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Bh1750Test {

    private static byte[] word(int raw) {
        return new byte[]{(byte) (raw >> 8), (byte) raw};
    }

    private static List<Integer> cmds(MockConnection c) {
        List<Integer> out = new ArrayList<>();
        for (byte[] w : c.writes()) out.add(w[0] & 0xFF);
        return out;
    }

    private MockConnection mock;
    private Bh1750Full s;

    private void make() {
        mock = new MockConnection();
        s = new Bh1750Full(mock);
    }

    @Test
    void constructorDoesNoBusAccessAndInitPowersOn() throws IOException {
        make();
        assertTrue(mock.writes().isEmpty());
        s.init();
        assertEquals(List.of(0x01), cmds(mock));
        assertNotNull(new Bh1750Minimal(new MockConnection()));
    }

    @Test
    void minimalReadConvertsDatasheetExample() throws IOException {
        MockConnection c = new MockConnection();
        Bh1750Minimal m = new Bh1750Minimal(c);
        c.queueRead(word(0x8342));
        assertEquals(0x8342 / 1.2, m.read(), 0.01);
        assertEquals(List.of(0x20), cmds(c));
    }

    @Test
    void measureOnceModes() throws IOException {
        make();
        mock.queueRead(word(0x12));
        assertEquals(15.0, s.measureOnce(Bh1750Full.MODE_H), 1e-3);
        assertEquals(List.of(0x20), cmds(mock));
        mock.writes().clear();
        mock.queueRead(word(0x12));
        assertEquals(7.5, s.measureOnce(Bh1750Full.MODE_H2), 1e-3);
        assertEquals(List.of(0x21), cmds(mock));
        mock.writes().clear();
        mock.queueRead(word(0x12));
        assertEquals(15.0, s.measureOnce(Bh1750Full.MODE_L), 1e-3);
        assertEquals(List.of(0x23), cmds(mock));
        assertThrows(IllegalArgumentException.class, () -> s.measureOnce(7));
    }

    @Test
    void readFollowsCurrentMode() throws IOException {
        make();
        mock.queueRead(word(0x12));
        s.measureOnce(Bh1750Full.MODE_H2);
        mock.writes().clear();
        mock.queueRead(word(0x12));
        assertEquals(7.5, s.read(), 1e-3);
        assertEquals(List.of(0x21), cmds(mock));
        mock.writes().clear();
        mock.queueRead(word(0x12));
        assertEquals(0x12, s.readRaw());
        assertEquals(List.of(0x21), cmds(mock));
    }

    @Test
    void continuousMode() throws IOException {
        make();
        s.startContinuous(Bh1750Full.MODE_H);
        s.startContinuous(Bh1750Full.MODE_H2);
        s.startContinuous(Bh1750Full.MODE_L);
        assertEquals(List.of(0x10, 0x11, 0x13), cmds(mock));
        s.startContinuous(Bh1750Full.MODE_H2);
        mock.writes().clear();
        mock.queueRead(word(0x12));
        assertEquals(7.5, s.readContinuous(), 1e-3);
        assertTrue(mock.writes().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> s.startContinuous(9));
    }

    @Test
    void powerAndReset() throws IOException {
        make();
        s.powerDown();
        s.powerOn();
        assertEquals(List.of(0x00, 0x01), cmds(mock));
        mock.writes().clear();
        s.reset();
        assertEquals(List.of(0x01, 0x07), cmds(mock));
    }

    @Test
    void measurementTimeRegister() throws IOException {
        make();
        assertEquals(69, s.getMeasurementTimeRegister());
        s.setMeasurementTime(69);
        assertEquals(List.of(0x42, 0x65), cmds(mock));
        mock.writes().clear();
        s.setMeasurementTime(254);
        assertEquals(List.of(0x47, 0x7E), cmds(mock));
        assertEquals(254, s.getMeasurementTimeRegister());
        mock.writes().clear();
        s.setMeasurementTime(31);
        assertEquals(List.of(0x40, 0x7F), cmds(mock));
        mock.writes().clear();
        assertThrows(IllegalArgumentException.class, () -> s.setMeasurementTime(30));
        assertThrows(IllegalArgumentException.class, () -> s.setMeasurementTime(255));
        assertTrue(mock.writes().isEmpty());
        assertEquals(31, s.getMeasurementTimeRegister());
    }

    @Test
    void sensitivityScalesEverything() throws IOException {
        make();
        assertEquals(1 / 1.2, s.luxPerCount(), 1e-9);
        assertEquals(0.5 / 1.2, s.luxPerCount(Bh1750Full.MODE_H2), 1e-9);
        assertEquals(0.180, s.measurementTime(), 1e-9);
        assertEquals(0.024, s.measurementTime(Bh1750Full.MODE_L), 1e-9);
        s.setSensitivity(2.0);
        assertEquals(138, s.getMeasurementTimeRegister());
        assertEquals(1 / 1.2 / 2, s.luxPerCount(), 1e-9);
        assertEquals(0.360, s.measurementTime(), 1e-9);
        mock.queueRead(word(0x12));
        assertEquals(7.5, s.measureOnce(Bh1750Full.MODE_H), 1e-3);
        assertThrows(IllegalArgumentException.class, () -> s.setSensitivity(0.4));
        assertThrows(IllegalArgumentException.class, () -> s.setSensitivity(3.7));
        s.setSensitivity(0.45);
        assertEquals(31, s.getMeasurementTimeRegister());
        s.setSensitivity(3.68);
        assertEquals(254, s.getMeasurementTimeRegister());
    }

    @Test
    void busErrorPropagates() {
        make();
        mock.failNextWrites(1, 0);
        assertThrows(IOException.class, () -> s.measureOnce(Bh1750Full.MODE_H));
    }
}
