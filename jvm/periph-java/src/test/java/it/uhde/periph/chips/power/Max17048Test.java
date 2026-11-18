package it.uhde.periph.chips.power;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Max17048Test {

    private static List<byte[]> words(MockConnection c) {
        List<byte[]> out = new ArrayList<>();
        for (byte[] w : c.writes()) if (w.length == 3) out.add(w);
        return out;
    }

    private static void assertLast(MockConnection c, int reg, int msb, int lsb) {
        List<byte[]> w = words(c);
        byte[] g = w.get(w.size() - 1);
        assertEquals(reg, g[0] & 0xFF);
        assertEquals(msb, g[1] & 0xFF);
        assertEquals(lsb, g[2] & 0xFF);
    }

    @Test
    void fullApi() throws Exception {
        MockConnection c = new MockConnection();
        Max17048Full g = new Max17048Full(c);
        assertTrue(c.writes().isEmpty());

        c.setRegister(0x02, 0xC3, 0x50);
        assertEquals(3.90625, g.voltage(), 1e-9);
        assertEquals(0xC350, g.rawVoltage());
        assertEquals(7.8125, new Max17048Minimal(c, 2).voltage(), 1e-9);

        c.setRegister(0x04, 0x4B, 0x80);
        assertEquals(75.5, g.percent(), 1e-9);
        assertEquals(0x4B80, g.rawSoc());
        c.setRegister(0x04, 0xFF, 0xFF);
        assertEquals(100.0, g.percent(), 1e-9);

        c.setRegister(0x16, 0xFF, 0xEC);
        assertEquals(-4.16, g.chargeRate(), 1e-9);
        c.setRegister(0x16, 0x00, 0x64);
        assertEquals(20.8, g.chargeRate(), 1e-9);
        c.setRegister(0x08, 0x00, 0x12);
        assertEquals(0x12, g.version());
        c.setRegister(0x18, 0x96, 0xAB);
        assertEquals(0xAB, g.chipId());

        c.setRegister(0x0C, 0x97, 0x1C);
        assertEquals(0x97, g.getRcomp());
        g.setRcomp(0x80);
        assertLast(c, 0x0C, 0x80, 0x1C);
        c.setRegister(0x0C, 0x97, 0x1C);
        g.compensateTemperature(35.0);
        assertLast(c, 0x0C, 0x90, 0x1C);
        g.compensateTemperature(5.0);
        assertLast(c, 0x0C, 0xE2, 0x1C);

        c.setRegister(0x0C, 0x97, 0x1C);
        g.setAlertSoc(10);
        assertLast(c, 0x0C, 0x97, 0x16);
        assertEquals(10, g.getAlertSoc());
        g.setSocChangeAlert(true);
        assertLast(c, 0x0C, 0x97, 0x56);
        g.setSocChangeAlert(false);
        assertLast(c, 0x0C, 0x97, 0x16);

        g.setVoltageAlert(3.3, 4.2);
        assertLast(c, 0x14, 0xA5, 0xD2);
        double[] w = g.getVoltageAlert();
        assertEquals(3.3, w[0], 1e-9);
        assertEquals(4.2, w[1], 1e-9);
        g.setVoltageAlert(0, 5.1);
        assertLast(c, 0x14, 0x00, 0xFF);
        assertThrows(IllegalArgumentException.class, () -> g.setVoltageAlert(4.0, 3.0));

        c.setRegister(0x18, 0x01, 0x5A);
        g.setResetVoltage(3.0);
        assertLast(c, 0x18, 0x97, 0x5A);
        assertEquals(3.0, g.getResetVoltage(), 1e-9);
        g.setComparatorDisabled(false);
        assertLast(c, 0x18, 0x96, 0x5A);
        g.setComparatorDisabled(true);
        assertLast(c, 0x18, 0x97, 0x5A);

        c.setRegister(0x1A, 0x01, 0x00);
        g.enableVoltageResetAlert(true);
        assertLast(c, 0x1A, 0x41, 0x00);
        g.enableVoltageResetAlert(false);
        assertLast(c, 0x1A, 0x01, 0x00);

        g.setHibernateThresholds(26.624, 0.06);
        assertLast(c, 0x0A, 0x80, 0x30);
        double[] h = g.getHibernateThresholds();
        assertEquals(26.624, h[0], 1e-9);
        assertEquals(0.06, h[1], 1e-9);
        g.disableHibernate();
        assertLast(c, 0x0A, 0x00, 0x00);
        g.forceHibernate();
        assertLast(c, 0x0A, 0xFF, 0xFF);

        c.setRegister(0x06, 0x10, 0x00);
        assertTrue(g.isHibernating());
        c.setRegister(0x06, 0x00, 0x00);
        assertFalse(g.isHibernating());
        g.enableSleep(true);
        assertLast(c, 0x06, 0x20, 0x00);
        g.quickStart();
        assertLast(c, 0x06, 0x60, 0x00);
        g.enableSleep(false);
        g.quickStart();
        assertLast(c, 0x06, 0x40, 0x00);

        c.setRegister(0x0C, 0x97, 0x1C);
        g.sleep();
        assertLast(c, 0x0C, 0x97, 0x9C);
        g.wake();
        assertLast(c, 0x0C, 0x97, 0x1C);
        g.writeConfig(0x9700);
        assertLast(c, 0x0C, 0x97, 0x00);
        assertEquals(0x9700, g.readConfig());

        c.setRegister(0x1A, 0x11, 0x00);
        assertEquals(0x1100, g.readStatus());
        c.setRegister(0x0C, 0x97, 0x3C);
        g.clearStatus(0x0100);
        assertEquals(0x10, c.registers().get(0x1A).intValue());
        assertEquals(0x1C, c.registers().get(0x0D).intValue());

        c.setRegister(0x1A, 0x51, 0x00);
        c.setRegister(0x0C, 0x97, 0x3C);
        assertEquals(0x1100, g.pollInterrupt());
        assertEquals(0x40, c.registers().get(0x1A).intValue());
        assertEquals(0x1C, c.registers().get(0x0D).intValue());
        int n = words(c).size();
        assertEquals(0, g.pollInterrupt());
        assertEquals(n, words(c).size());

        c.setRegister(0x0C, 0x97, 0x1C);
        g.enableInterrupt(Max17048Full.SOURCE_SOC_CHANGE);
        assertLast(c, 0x0C, 0x97, 0x5C);
        g.disableInterrupt(Max17048Full.SOURCE_SOC_CHANGE);
        assertLast(c, 0x0C, 0x97, 0x1C);
        c.setRegister(0x14, 0xA5, 0xD2);
        g.disableInterrupt(Max17048Full.SOURCE_UNDERVOLTAGE);
        assertLast(c, 0x14, 0x00, 0xD2);
        g.disableInterrupt(Max17048Full.SOURCE_OVERVOLTAGE);
        assertLast(c, 0x14, 0x00, 0xFF);
    }

    @Test
    void resetToleratesNack() throws Exception {
        MockConnection c = new MockConnection();
        Max17048Full g = new Max17048Full(c);
        c.failNextWrites(1, 0);
        g.reset();
        assertLast(c, 0xFE, 0x54, 0x00);
    }

    @Test
    void pollingCallback() throws Exception {
        MockConnection c = new MockConnection();
        Max17048Full g = new Max17048Full(c);
        c.setRegister(0x1A, 0x10, 0x00);
        AtomicInteger seen = new AtomicInteger();
        g.onInterrupt(seen::set);
        long end = System.currentTimeMillis() + 2000;
        while (seen.get() == 0 && System.currentTimeMillis() < end) Thread.sleep(20);
        g.offInterrupt();
        assertEquals(0x1000, seen.get());
    }
}
