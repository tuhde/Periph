package it.uhde.periph.chips.light;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Apds9930Test {

    private static int cw(int reg) { return Apds9930Minimal.CMD_WRITE | (reg & 0x1F); }
    private static int cr(int reg) { return Apds9930Minimal.CMD_READ  | (reg & 0x1F); }

    @Test
    void fullApi() throws Exception {
        MockConnection connection = new MockConnection();
        // APDS-9930's first byte written is a command byte (0x80|reg, 0xA0|reg).
        // MockConnection indexes writes by command-byte address, so preload
        // registers at the command-byte form.
        connection.setRegister(cr(Apds9930Minimal.REG_ID), 0x39);

        Apds9930Full sensor = new Apds9930Full(connection);

        assertEquals(Apds9930Minimal.ATIME_DEFAULT,
            connection.registers().get(cw(Apds9930Minimal.REG_ATIME)) & 0xFF);
        assertEquals(Apds9930Minimal.CONTROL_DEFAULT,
            connection.registers().get(cw(Apds9930Minimal.REG_CONTROL)) & 0xFF);
        assertEquals(Apds9930Minimal.ENABLE_DEFAULT,
            connection.registers().get(cw(Apds9930Minimal.REG_ENABLE)) & 0xFF);

        // Bad ID must reject construction.
        MockConnection badConnection = new MockConnection();
        badConnection.setRegister(cr(Apds9930Minimal.REG_ID), 0xAB);
        assertThrows(java.io.IOException.class, () -> new Apds9930Full(badConnection));

        // Lux: Ch0=0x0010 (LE), Ch1=0x0000 → IAc > 0 → lux > 0
        connection.setRegister(cr(Apds9930Minimal.REG_CH0DATAL), 0x10, 0x00);
        connection.setRegister(cr(Apds9930Minimal.REG_CH1DATAL), 0x00, 0x00);
        connection.setRegister(cr(Apds9930Minimal.REG_CONTROL), Apds9930Minimal.CONTROL_DEFAULT);
        connection.setRegister(cr(Apds9930Minimal.REG_CONFIG), 0x00);
        connection.setRegister(cr(Apds9930Minimal.REG_ATIME), Apds9930Minimal.ATIME_DEFAULT);
        assertTrue(sensor.lux() > 0.0f);

        // Dark → lux = 0
        connection.setRegister(cr(Apds9930Minimal.REG_CH0DATAL), 0x00, 0x00);
        connection.setRegister(cr(Apds9930Minimal.REG_CH1DATAL), 0x00, 0x00);
        assertEquals(0.0f, sensor.lux());

        // Proximity: 0x1234 (LE)
        connection.setRegister(cr(Apds9930Minimal.REG_PDATAL), 0x34, 0x12);
        assertEquals(0x1234, sensor.proximity());

        // configureAls(0xF6, 2, false)
        sensor.configureAls(0xF6, 2, false);
        assertEquals(0xF6, connection.registers().get(cw(Apds9930Minimal.REG_ATIME)) & 0xFF);
        assertEquals(2, connection.registers().get(cw(Apds9930Minimal.REG_CONTROL)) & 0x03);

        // configureProximity(8, 1, 2, false, 0xFF)
        sensor.configureProximity(8, 1, 2, false, 0xFF);
        assertEquals(8, connection.registers().get(cw(Apds9930Minimal.REG_PPULSE)) & 0xFF);
        assertEquals(0xFF, connection.registers().get(cw(Apds9930Minimal.REG_PTIME)) & 0xFF);

        // configureWait(0x80, true): WTIME=0x80, WLONG bit set
        sensor.configureWait(0x80, true);
        assertEquals(0x80, connection.registers().get(cw(Apds9930Minimal.REG_WTIME)) & 0xFF);
        assertEquals(0x02, connection.registers().get(cw(Apds9930Minimal.REG_CONFIG)) & 0x02);

        // disableWait: ENABLE WEN cleared
        sensor.disableWait();
        assertEquals(0, connection.registers().get(cw(Apds9930Minimal.REG_ENABLE)) & 0x08);

        // status: STATUS=0x01 → AVALID
        connection.setRegister(cr(Apds9930Minimal.REG_STATUS), 0x01);
        Apds9930Full.Status st = sensor.status();
        assertTrue(st.avalid);
        assertFalse(st.pvalid);

        // setAlsThresholds: AIEN bit set
        sensor.setAlsThresholds(100, 60000, 1);
        assertEquals(0x10, connection.registers().get(cw(Apds9930Minimal.REG_ENABLE)) & 0x10);

        // setProximityThresholds: PIEN bit set
        sensor.setProximityThresholds(10, 200, 1);
        assertEquals(0x20, connection.registers().get(cw(Apds9930Minimal.REG_ENABLE)) & 0x20);

        // setProximityOffset(-50): sign-magnitude → 0x32
        sensor.setProximityOffset(-50);
        assertEquals(0x32, connection.registers().get(cw(Apds9930Minimal.REG_POFFSET)) & 0xFF);

        // sleepAfterInterrupt(true): SAI bit set
        sensor.sleepAfterInterrupt(true);
        assertEquals(0x40, connection.registers().get(cw(Apds9930Minimal.REG_ENABLE)) & 0x40);
        sensor.sleepAfterInterrupt(false);
        assertEquals(0, connection.registers().get(cw(Apds9930Minimal.REG_ENABLE)) & 0x40);

        // clearInterrupt(0): last write is 0xE7 (special, both)
        sensor.clearInterrupt(0);
        var last = connection.writes().get(connection.writes().size() - 1);
        assertEquals(1, last.length);
        assertEquals(0xE7, last[0] & 0xFF);
        sensor.clearInterrupt(1);
        last = connection.writes().get(connection.writes().size() - 1);
        assertEquals(0xE6, last[0] & 0xFF);
        sensor.clearInterrupt(2);
        last = connection.writes().get(connection.writes().size() - 1);
        assertEquals(0xE5, last[0] & 0xFF);

        assertEquals(0x39, sensor.chipId());
    }
}