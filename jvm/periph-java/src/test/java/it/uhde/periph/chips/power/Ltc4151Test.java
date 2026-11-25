package it.uhde.periph.chips.power;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ltc4151Test {

    /** Data bytes of every CONTROL write (register-pointer writes of reads are skipped). */
    private static List<Integer> control(MockConnection c) {
        List<Integer> out = new ArrayList<>();
        for (byte[] w : c.writes()) if (w.length == 2 && w[0] == 0x06) out.add(w[1] & 0xFF);
        return out;
    }

    @Test
    void fullApi() throws Exception {
        MockConnection connection = new MockConnection();
        Ltc4151Full sensor = new Ltc4151Full(connection);
        assertTrue(connection.writes().isEmpty());

        connection.setRegister(0x02, 0x80, 0x00);
        assertEquals(51.2, sensor.voltage(), 1e-9);
        connection.setRegister(0x00, 0x40, 0x00);
        assertEquals(0.02048, sensor.senseVoltage(), 1e-12);
        assertEquals(1.024, sensor.current(), 1e-9);
        assertEquals(51.2 * 1.024, sensor.power(), 1e-9);
        connection.setRegister(0x02, 0x00, 0xF8);
        assertEquals(15 * 0.025, sensor.voltage(), 1e-12);
        connection.setRegister(0x04, 0x7D, 0x00);
        assertEquals(1.0, sensor.adinVoltage(), 1e-12);

        connection.setRegister(0x00, 0x40, 0x00, 0x80, 0x00, 0x7D, 0x00);
        Ltc4151Full.Readings r = sensor.readAll();
        assertEquals(51.2, r.vin, 1e-9);
        assertEquals(0.02048, r.senseV, 1e-12);
        assertEquals(1.024, r.current, 1e-9);
        assertEquals(51.2 * 1.024, r.power, 1e-9);
        assertEquals(1.0, r.adin, 1e-12);

        assertEquals(0x400, sensor.readRaw(Ltc4151Full.SENSE));
        assertEquals(0x800, sensor.readRaw(Ltc4151Full.VIN));
        assertEquals(0x7D0, sensor.readRaw(Ltc4151Full.ADIN));
        assertThrows(IllegalArgumentException.class, () -> sensor.readRaw(3));

        sensor.setSenseResistor(0.01);
        assertEquals(0.01, sensor.getSenseResistor(), 1e-12);
        assertEquals(2.048, sensor.current(), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> sensor.setSenseResistor(0));
        sensor.setSenseResistor(0.02);

        Ltc4151Minimal minimal = new Ltc4151Minimal(connection, 0.005);
        assertEquals(4.096, minimal.current(), 1e-9);
        assertEquals(51.2 * 4.096, minimal.power(), 1e-9);

        connection.setRegister(0x06, 0x0C);
        assertEquals(0x0C, sensor.readControl());
        connection.writes().clear();
        sensor.setStuckBusTimer(false);
        assertEquals(List.of(0x08), control(connection));
        connection.setRegister(0x06, 0x08);
        sensor.setStuckBusTimer(true);
        assertEquals(0x0C, (int) control(connection).get(1));
        sensor.setPageRw(false);
        assertEquals(0x04, (int) control(connection).get(2));
        connection.setRegister(0x06, 0x04);
        sensor.setPageRw(true);
        assertEquals(0x0C, (int) control(connection).get(3));
        sensor.writeControl(0xFF);
        assertEquals(0xEF, (int) control(connection).get(4));
        sensor.writeControl(0x0C);

        connection.setRegister(0x06, 0xA8);
        assertTrue(sensor.isSnapshotMode());
        connection.writes().clear();
        sensor.setContinuous();
        assertEquals(List.of(0x28), control(connection));
        connection.setRegister(0x06, 0x0C);
        assertFalse(sensor.isSnapshotMode());

        connection.setRegister(0x01, 0x00);
        connection.setRegister(0x03, 0x08);
        assertFalse(sensor.isBusy(Ltc4151Full.SENSE));
        assertTrue(sensor.isBusy(Ltc4151Full.VIN));
        connection.setRegister(0x03, 0x00);

        connection.writes().clear();
        connection.setRegister(0x02, 0x80, 0x00);
        assertEquals(51.2, sensor.snapshot(Ltc4151Full.VIN), 1e-9);
        assertEquals(List.of(0x0C, 0xAC, 0x0C), control(connection));

        connection.setRegister(0x01, 0x08);
        assertThrows(IOException.class, () -> sensor.snapshot(Ltc4151Full.SENSE));
        connection.setRegister(0x01, 0x00);

        connection.setRegister(0x00, 0x40, 0x00, 0x80, 0x00, 0x7D, 0x00);
        sensor.setPageRw(false);
        assertEquals(51.2 * 1.024, sensor.readAll().power, 1e-9);
        sensor.setPageRw(true);

        assertEquals(58.82 * (0.75 - 0.1066), Ltc4151Full.temperatureFromAdin(900, 1200), 1e-9);
    }
}
