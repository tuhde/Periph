package it.uhde.periph.chips.power;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ina3221Test {

    @Test
    void fullApi() throws Exception {
        MockConnection connection = new MockConnection();

        // Construction (default rShunt=0.1 for all 3 channels) writes nothing.
        Ina3221Full sensor = new Ina3221Full(connection);
        assertTrue(connection.writes().isEmpty(), "init should write nothing");

        // --- Channel 1 ---
        // Bus1 raw=10000 (0x2710) -> (10000>>3)*8e-3 = 10.0 V
        connection.setRegister(Ina3221Minimal.REG_CH1_BUS_V, 0x27, 0x10);
        assertEquals(10.0, sensor.voltage(1));

        // Shunt1 raw signed = -400 (0xFE70) -> (-400>>3)*40e-6 = -0.002 V
        // (Java right-shifts the raw value by 3 rather than using the other
        // languages' ×5e-6 shortcut - mathematically equivalent, see
        // Ina3221Minimal.shuntVoltage()'s Javadoc.)
        connection.setRegister(Ina3221Minimal.REG_CH1_SHUNT_V, 0xFE, 0x70);
        double sv1 = sensor.shuntVoltage(1);
        assertEquals(((short) 0xFE70 >> 3) * 40e-6, sv1, 1e-9);
        assertEquals(sv1 / 0.1, sensor.current(1), 1e-9);

        // power(1): CH1_SHUNT_V (0x01) and CH1_BUS_V (0x02) are adjacent
        // registers, and the mock's byte-slot model can't hold two
        // independent 16-bit values across adjacent addresses at once
        // (writing one clobbers the shared byte slot) - so the Shunt1 low
        // byte and Bus1 high byte are chosen equal (0x10) to survive either
        // write order. Shunt1=0xFF10, Bus1=0x1000 (4096) -> 4.096 V.
        connection.setRegister(Ina3221Minimal.REG_CH1_SHUNT_V, 0xFF, 0x10);
        connection.setRegister(Ina3221Minimal.REG_CH1_BUS_V, 0x10, 0x00);
        double expectedShunt1 = ((short) 0xFF10 >> 3) * 40e-6;
        assertEquals(4.096 * (expectedShunt1 / 0.1), sensor.power(1), 1e-9);

        // --- Channel 2 ---
        // Bus2 raw=4096 (0x1000) -> (4096>>3)*8e-3 = 4.096 V
        connection.setRegister(Ina3221Minimal.REG_CH2_BUS_V, 0x10, 0x00);
        assertEquals(4.096, sensor.voltage(2));

        // Shunt2 raw=800 (0x0320) -> (800>>3)*40e-6
        connection.setRegister(Ina3221Minimal.REG_CH2_SHUNT_V, 0x03, 0x20);
        double sv2 = sensor.shuntVoltage(2);
        assertEquals((800 >> 3) * 40e-6, sv2, 1e-9);
        assertEquals(sv2 / 0.1, sensor.current(2), 1e-9);

        // Invalid channel throws.
        assertThrows(IllegalArgumentException.class, () -> sensor.voltage(4));

        // configure(3, 2, 1, 5) preserves channel-enable bits (0x7000) from
        // the current Configuration Register.
        connection.setRegister(Ina3221Minimal.REG_CONFIG, 0x71, 0x27);
        sensor.configure(3, 2, 1, 5);
        byte[] configWrite1 = connection.writes().stream()
                .filter(w -> w.length == 3 && (w[0] & 0xFF) == Ina3221Minimal.REG_CONFIG)
                .reduce((a, b) -> b).orElseThrow();
        assertEquals((byte) 0x76, configWrite1[1]);
        assertEquals((byte) 0x8D, configWrite1[2]);

        // enableChannel(2, true): CH2en is bit 13.
        connection.setRegister(Ina3221Minimal.REG_CONFIG, 0x01, 0x27);
        sensor.enableChannel(2, true);
        byte[] configWrite2 = connection.writes().stream()
                .filter(w -> w.length == 3 && (w[0] & 0xFF) == Ina3221Minimal.REG_CONFIG)
                .reduce((a, b) -> b).orElseThrow();
        assertEquals((byte) 0x21, configWrite2[1]);
        assertEquals((byte) 0x27, configWrite2[2]);

        // channelEnabled(1): CH1en is bit 14.
        connection.setRegister(Ina3221Minimal.REG_CONFIG, 0x41, 0x27);
        assertTrue(sensor.channelEnabled(1));

        // conversionReady(): CVRF is bit 0.
        connection.setRegister(Ina3221Minimal.REG_MASK_ENABLE, 0x00, 0x01);
        assertTrue(sensor.conversionReady());

        // setCriticalAlert(2, 0.048): raw = (round(0.048/40e-6) << 3) & 0xFFF8
        // = (1200 << 3) & 0xFFF8 = 0x2580. The Java API has no latch
        // parameter - it only writes the limit register.
        sensor.setCriticalAlert(2, 0.048);
        byte[] critWrite = connection.writes().stream()
                .filter(w -> w.length == 3 && (w[0] & 0xFF) == Ina3221Minimal.REG_CH2_CRIT)
                .reduce((a, b) -> b).orElseThrow();
        assertEquals((byte) 0x25, critWrite[1]);
        assertEquals((byte) 0x80, critWrite[2]);

        // setWarningAlert(1, 0.024): raw = (round(600) << 3) & 0xFFF8 = 0x12C0.
        sensor.setWarningAlert(1, 0.024);
        byte[] warnWrite = connection.writes().stream()
                .filter(w -> w.length == 3 && (w[0] & 0xFF) == Ina3221Minimal.REG_CH1_WARN)
                .reduce((a, b) -> b).orElseThrow();
        assertEquals((byte) 0x12, warnWrite[1]);
        assertEquals((byte) 0xC0, warnWrite[2]);

        // alertFlags(): raw Mask/Enable register.
        connection.setRegister(Ina3221Minimal.REG_MASK_ENABLE, 0x02, 0x41);
        assertEquals(0x0241, sensor.alertFlags());

        // setSummationChannels({1}, 0.1) with a stale SCC3 bit (0x1000)
        // already set: clearing must zero bits 14:12 (0x0FFF mask keeps the
        // rest), and channel 1 must map to bit 14 (SCC1).
        connection.setRegister(Ina3221Minimal.REG_MASK_ENABLE, 0x10, 0x00);
        sensor.setSummationChannels(new int[]{1}, 0.1);
        byte[] summationMaskWrite = connection.writes().stream()
                .filter(w -> w.length == 3 && (w[0] & 0xFF) == Ina3221Minimal.REG_MASK_ENABLE)
                .reduce((a, b) -> b).orElseThrow();
        assertEquals((byte) 0x40, summationMaskWrite[1]);
        assertEquals((byte) 0x00, summationMaskWrite[2]);
        byte[] summationLimitWrite = connection.writes().stream()
                .filter(w -> w.length == 3 && (w[0] & 0xFF) == Ina3221Minimal.REG_SV_SUM_LIMIT)
                .reduce((a, b) -> b).orElseThrow();
        assertEquals((byte) 0x13, summationLimitWrite[1]);
        assertEquals((byte) 0x88, summationLimitWrite[2]);

        // summationValue(): raw=0x2328 (9000) -> (9000>>1)*40e-6 = 0.18 V.
        connection.setRegister(Ina3221Minimal.REG_SV_SUM, 0x23, 0x28);
        assertEquals(0.18, sensor.summationValue(), 1e-9);

        // setPowerValidLimits(8.112, 4.096): rawUpper=(round(1014)<<3)&0x1FFF<<3... etc
        sensor.setPowerValidLimits(8.112, 4.096);
        byte[] pvUpperWrite = connection.writes().stream()
                .filter(w -> w.length == 3 && (w[0] & 0xFF) == Ina3221Minimal.REG_PV_UPPER)
                .reduce((a, b) -> b).orElseThrow();
        assertEquals((byte) 0x1F, pvUpperWrite[1]);
        assertEquals((byte) 0xB0, pvUpperWrite[2]);
        byte[] pvLowerWrite = connection.writes().stream()
                .filter(w -> w.length == 3 && (w[0] & 0xFF) == Ina3221Minimal.REG_PV_LOWER)
                .reduce((a, b) -> b).orElseThrow();
        assertEquals((byte) 0x10, pvLowerWrite[1]);
        assertEquals((byte) 0x00, pvLowerWrite[2]);

        // powerValid(): PVF is bit 2.
        connection.setRegister(Ina3221Minimal.REG_MASK_ENABLE, 0x00, 0x04);
        assertTrue(sensor.powerValid());

        // shutdown(): reads CONFIG, saves MODE bits, writes CONFIG & 0xFFF8.
        connection.setRegister(Ina3221Minimal.REG_CONFIG, 0x71, 0x27);
        sensor.shutdown();
        byte[] shutdownWrite = connection.writes().get(connection.writes().size() - 1);
        assertEquals((byte) 0x71, shutdownWrite[1]);
        assertEquals((byte) 0x20, shutdownWrite[2]);

        // wake(): reads CONFIG, restores saved MODE bits (7, from shutdown()).
        connection.setRegister(Ina3221Minimal.REG_CONFIG, 0x71, 0x20);
        sensor.wake();
        byte[] wakeWrite = connection.writes().get(connection.writes().size() - 1);
        assertEquals((byte) 0x71, wakeWrite[1]);
        assertEquals((byte) 0x27, wakeWrite[2]);

        // reset(): writes CONFIG=0x8000 (RST), then restores hardware
        // defaults (0x7127) with the saved MODE (7, from shutdown()) -
        // Java's reset() partially restores state, unlike the other 5
        // languages, which only write 0x8000.
        sensor.reset();
        byte[] resetWrite1 = connection.writes().get(connection.writes().size() - 2);
        byte[] resetWrite2 = connection.writes().get(connection.writes().size() - 1);
        assertEquals((byte) 0x80, resetWrite1[1]);
        assertEquals((byte) 0x00, resetWrite1[2]);
        assertEquals((byte) 0x71, resetWrite2[1]);
        assertEquals((byte) 0x27, resetWrite2[2]);

        // manufacturerId() / dieId()
        connection.setRegister(Ina3221Minimal.REG_MANUFACTURER, 0x54, 0x49);
        assertEquals(0x5449, sensor.manufacturerId());
        connection.setRegister(Ina3221Minimal.REG_DIE_ID, 0x32, 0x20);
        assertEquals(0x3220, sensor.dieId());
    }
}
