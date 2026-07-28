package it.uhde.periph.chips.gas;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ens160Test {

    @Test
    void fullApi() throws Exception {
        MockConnection connection = new MockConnection();
        // PART_ID read during construction must return 0x0160 (LE: 0x60, 0x01).
        connection.setRegister(Ens160Minimal.REG_PART_ID, 0x60, 0x01);

        Ens160Full sensor = new Ens160Full(connection);

        List<byte[]> opmodeWrites = connection.writes().stream()
                .filter(w -> w.length == 2 && (w[0] & 0xFF) == Ens160Minimal.REG_OPMODE)
                .toList();
        assertTrue(opmodeWrites.get(0)[1] == (byte) Ens160Minimal.OPMODE_IDLE
                && opmodeWrites.get(opmodeWrites.size() - 1)[1] == (byte) Ens160Minimal.OPMODE_STANDARD,
                "init should write IDLE then STANDARD");

        // DEVICE_STATUS: NEWDAT (bit1) set, VALIDITY_FLAG (bits 3:2) = 0 (OK).
        connection.setRegister(Ens160Minimal.REG_DEVICE_STATUS, 0x02);
        assertEquals(Ens160Full.VALIDITY_OK, sensor.status());

        // DATA_AQI block (5 bytes): aqi=2, tvocPpb=150 (0x0096 LE), eco2Ppm=500 (0x01F4 LE).
        connection.setRegister(Ens160Minimal.REG_DATA_AQI, 2, 0x96, 0x00, 0xF4, 0x01);

        double[] data = sensor.readAirQuality();
        assertArrayEquals(new double[]{2.0, 150.0, 500.0}, data);

        assertEquals(150.0, sensor.readTvoc());
        assertEquals(500.0, sensor.readEco2());
        assertEquals(2, sensor.readAqi());
        assertEquals(150.0, sensor.readEthanol());

        sensor.setCompensation(25.0, 50.0);
        int tempRaw = (int) Math.round((25.0 + 273.15) * 64);
        int rhRaw = (int) Math.round(50.0 * 512);
        assertEquals(tempRaw & 0xFF, connection.registers().get(Ens160Minimal.REG_TEMP_IN));
        assertEquals((tempRaw >> 8) & 0xFF, connection.registers().get(Ens160Minimal.REG_TEMP_IN + 1));
        assertEquals(rhRaw & 0xFF, connection.registers().get(Ens160Minimal.REG_RH_IN));
        assertEquals((rhRaw >> 8) & 0xFF, connection.registers().get(Ens160Minimal.REG_RH_IN + 1));

        // DATA_T/DATA_RH actuals (4 bytes): tempRaw=0x5202 (~55.0 degC), rhRaw=0x6400 (50.0 %RH).
        connection.setRegister(Ens160Minimal.REG_DATA_T, 0x02, 0x52, 0x00, 0x64);
        double[] actuals = sensor.readCompensationActuals();
        assertEquals((0x5202 / 64.0) - 273.15, actuals[0], 1e-9);
        assertEquals(0x6400 / 512.0, actuals[1], 1e-9);

        // GPR_READ sensor 1 (offset 0): raw=2048 -> resistance = 2**(2048/2048) = 2.0 Ohm.
        connection.setRegister(Ens160Minimal.REG_GPR_READ, 0x00, 0x08);
        assertEquals(2.0, sensor.readRawResistance(1));

        // GPR_READ sensor 4 (offset 6): raw=4096 -> resistance = 2**(4096/2048) = 4.0 Ohm.
        connection.setRegister(Ens160Minimal.REG_GPR_READ + 6, 0x00, 0x10);
        assertEquals(4.0, sensor.readRawResistance(4));

        assertThrows(IllegalArgumentException.class, () -> sensor.readRawResistance(2));

        // GET_APPVER response at GPR_READ+4: major=1, minor=2, release=3.
        connection.setRegister(Ens160Minimal.REG_GPR_READ + 4, 1, 2, 3);
        assertArrayEquals(new int[]{1, 2, 3}, sensor.getFirmwareVersion());

        sensor.configureInterrupt(true, true, true, true, true);
        List<byte[]> configWrites = connection.writes().stream()
                .filter(w -> w.length == 2 && (w[0] & 0xFF) == Ens160Minimal.REG_CONFIG)
                .toList();
        assertEquals((byte) 0x6B, configWrites.get(configWrites.size() - 1)[1]);

        sensor.sleep();
        byte[] lastWrite1 = connection.writes().get(connection.writes().size() - 1);
        assertEquals((byte) Ens160Minimal.OPMODE_DEEP_SLEEP, lastWrite1[1]);

        sensor.wake();
        byte[] lastWrite2 = connection.writes().get(connection.writes().size() - 1);
        assertEquals((byte) Ens160Minimal.OPMODE_STANDARD, lastWrite2[1]);
    }
}
