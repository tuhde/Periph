package it.uhde.periph.chips.pressure;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Lps33hwTest {

    private static void preloadDefaults(MockConnection connection) {
        // chip ID
        connection.setRegister(Lps33hwMinimal.REG_WHO_AM_I, 0xB1);
    }

    @Test
    void fullApi() throws Exception {
        MockConnection connection = new MockConnection();
        preloadDefaults(connection);

        // Set up STATUS to immediately indicate both P_DA and T_DA so the
        // driver doesn't loop. Burst returns raw_pressure = 4096 (= 100 Pa)
        // and raw_temperature = 2500 (= 25 °C).
        connection.setRegister(Lps33hwMinimal.REG_STATUS, 0x03);
        connection.setRegister(Lps33hwMinimal.REG_PRESS_XL,
                0x00, 0x10, 0x00, 0xC4, 0x09);

        // RES_CONF default (used by configure())
        connection.setRegister(Lps33hwMinimal.REG_RES_CONF, 0x00);

        Lps33hwFull sensor = new Lps33hwFull(connection);

        // pressure() / temperature(): use burst read to return 100 Pa / 25 °C.
        double p = sensor.pressure();
        assertEquals(100.0, p, 1e-3, "pressure = 4096 raw = 100 Pa");
        double t = sensor.temperature();
        assertEquals(25.0, t, 1e-3, "temperature = 2500 raw = 25 °C");

        // status() returns 0x03.
        connection.setRegister(Lps33hwMinimal.REG_STATUS, 0x03);
        assertEquals(0x03, sensor.status());

        // interruptStatus() returns 0x04.
        connection.setRegister(Lps33hwMinimal.REG_INT_SOURCE, 0x04);
        assertEquals(0x04, sensor.interruptStatus());

        // configure(odr=2, bdu=true, enLpfp=true, lpfpCfg=1, lcEn=false, sim=false):
        // ctrl1 = (2<<4)|(1<<3)|(1<<2)|(1<<1)|0 = 0x2E
        sensor.configure(2, true, true, 1, false, false);
        byte[] ctrl1Write = connection.writes().stream()
                .filter(w -> w.length == 2 && (w[0] & 0xFF) == Lps33hwMinimal.REG_CTRL_REG1)
                .reduce((a, b) -> b).orElseThrow();
        assertEquals((byte) 0x2E, ctrl1Write[1]);

        // setPressureOffset(offsetHPa=16.0):
        // raw = 16 * 16 = 256 -> RPDS_L=0x00, RPDS_H=0x01
        sensor.setPressureOffset(16.0);
        byte[] rpdsL = connection.writes().stream()
                .filter(w -> w.length == 2 && (w[0] & 0xFF) == Lps33hwMinimal.REG_RPDS_L)
                .reduce((a, b) -> b).orElseThrow();
        byte[] rpdsH = connection.writes().stream()
                .filter(w -> w.length == 2 && (w[0] & 0xFF) == Lps33hwMinimal.REG_RPDS_H)
                .reduce((a, b) -> b).orElseThrow();
        assertEquals((byte) 0x00, rpdsL[1]);
        assertEquals((byte) 0x01, rpdsH[1]);

        // setAutozero() / clearAutozero() / setAutorifp() / clearAutorifp()
        sensor.setAutozero();
        sensor.clearAutozero();
        sensor.setAutorifp();
        sensor.clearAutorifp();

        // configureInterrupt(all true, intS=3): ctrl3 = 0xFF
        sensor.configureInterrupt(true, true, true, true, 3, true, true);
        byte[] ctrl3Write = connection.writes().stream()
                .filter(w -> w.length == 2 && (w[0] & 0xFF) == Lps33hwMinimal.REG_CTRL_REG3)
                .reduce((a, b) -> b).orElseThrow();
        assertEquals((byte) 0xFF, ctrl3Write[1]);

        // enableFifo(mode=1, watermark=16): fifo_ctrl = (1<<5)|16 = 0x30
        sensor.enableFifo(1, 16);
        byte[] fifoCtrlWrite = connection.writes().stream()
                .filter(w -> w.length == 2 && (w[0] & 0xFF) == Lps33hwMinimal.REG_FIFO_CTRL)
                .reduce((a, b) -> b).orElseThrow();
        assertEquals((byte) 0x30, fifoCtrlWrite[1]);

        // fifoStatus() returns the preloaded value
        connection.setRegister(Lps33hwMinimal.REG_FIFO_STATUS, 0x80);
        assertEquals(0x80, sensor.fifoStatus());

        // disableFifo() clears FIFO_EN and resets FIFO_CTRL
        sensor.disableFifo();

        // resetLpf(): read LPFP_RES
        connection.setRegister(Lps33hwMinimal.REG_LPFP_RES, 0x00);
        sensor.resetLpf();

        // reset(): writes SWRESET, polls, restores defaults
        sensor.reset();

        // reboot(): writes BOOT, polls INT_SOURCE
        sensor.reboot();
    }

    @Test
    void wrongChipIdThrows() {
        MockConnection connection = new MockConnection();
        connection.setRegister(Lps33hwMinimal.REG_WHO_AM_I, 0x00);  // wrong
        assertThrows(java.io.IOException.class, () -> new Lps33hwMinimal(connection));
    }
}