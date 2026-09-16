package it.uhde.periph.chips.gyroscope;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class L3g4200dTest {

    @Test
    void fullApi() throws Exception {
        MockConnection connection = new MockConnection();
        // Preload WHO_AM_I so init() doesn't throw.
        connection.setRegister(L3g4200dMinimal.REG_WHO_AM_I, 0xD3);

        L3g4200dFull sensor = new L3g4200dFull(connection, false);

        assertEquals(L3g4200dFull.CTRL_REG4_DEFAULT,
                (int) connection.registers().get(L3g4200dMinimal.REG_CTRL_REG4));
        assertEquals(L3g4200dFull.CTRL_REG1_DEFAULT,
                (int) connection.registers().get(L3g4200dMinimal.REG_CTRL_REG1));

        // raw X=+16 (LE: low=0x10, high=0x00), Y=0, Z=-16 (low=0xF0, high=0xFF).
        // Sub-address for I²C multi-byte auto-increment is reg | 0x80.
        connection.setRegister(L3g4200dMinimal.REG_OUT_X_L | 0x80,
                0x10, 0x00,    // X=+16
                0x00, 0x00,    // Y=0
                0xF0, 0xFF);  // Z=-16
        float[] xyz = sensor.angularRate();
        float k = (float) (Math.PI / 180.0);
        assertEquals(16.0f * 0.00875f * k, xyz[0], 1e-6f);
        assertEquals(0.0f, xyz[1], 1e-6f);
        assertEquals(-16.0f * 0.00875f * k, xyz[2], 1e-6f);

        // configure(ODR_200_HZ=1, bw=0, FS_500_DPS).
        sensor.configure(L3g4200dFull.ODR_200_HZ, 0, L3g4200dFull.FS_500_DPS);
        assertEquals(L3g4200dFull.CTRL_REG1_DEFAULT | (1 << 6),
                (int) connection.registers().get(L3g4200dMinimal.REG_CTRL_REG1));
        assertEquals(L3g4200dFull.CTRL_REG4_DEFAULT | (1 << 4),
                (int) connection.registers().get(L3g4200dMinimal.REG_CTRL_REG4));
        assertEquals(500, sensor.fullScale);

        // setFullScale(2000): FS bits = 0b10.
        connection.setRegister(L3g4200dMinimal.REG_CTRL_REG4,
                L3g4200dFull.CTRL_REG4_DEFAULT | (1 << 4));
        sensor.setFullScale(L3g4200dFull.FS_2000_DPS);
        assertEquals(L3g4200dFull.CTRL_REG4_DEFAULT | (2 << 4),
                (int) connection.registers().get(L3g4200dMinimal.REG_CTRL_REG4));
        assertEquals(2000, sensor.fullScale);

        // status / data_ready / temperature.
        connection.setRegister(L3g4200dMinimal.REG_STATUS, 0x08);
        assertEquals(0x08, sensor.status());
        assertTrue(sensor.dataReady());
        connection.setRegister(L3g4200dMinimal.REG_OUT_TEMP, 0x80);
        assertEquals(-128, sensor.temperature());

        // power_down / wake_up / sleep.
        connection.setRegister(L3g4200dMinimal.REG_CTRL_REG1, 0x4F);
        sensor.powerDown();
        assertEquals(0x47,
                (int) connection.registers().get(L3g4200dMinimal.REG_CTRL_REG1));
        sensor.wakeUp();
        assertEquals(0x4F,
                (int) connection.registers().get(L3g4200dMinimal.REG_CTRL_REG1));
        sensor.sleep();
        assertEquals(0x08,
                (int) connection.registers().get(L3g4200dMinimal.REG_CTRL_REG1));

        // enable_axes(x=False, y=True, z=False).
        connection.setRegister(L3g4200dMinimal.REG_CTRL_REG1, 0x08);
        sensor.enableAxes(false, true, false);
        assertEquals(0x0A,
                (int) connection.registers().get(L3g4200dMinimal.REG_CTRL_REG1));

        // enable_fifo(FIFO_STREAM=2, watermark=10).
        connection.setRegister(L3g4200dMinimal.REG_CTRL_REG5, 0x00);
        sensor.enableFifo(L3g4200dFull.FIFO_STREAM, 10);
        assertEquals(0x40,
                (int) connection.registers().get(L3g4200dMinimal.REG_CTRL_REG5));
        assertEquals((2 << 5) | 10,
                (int) connection.registers().get(L3g4200dMinimal.REG_FIFO_CTRL));

        // fifo_samples.
        connection.setRegister(L3g4200dMinimal.REG_FIFO_SRC, 0x1A);
        assertEquals(26, sensor.fifoSamples());

        // enable_highpass / disable_highpass.
        sensor.enableHighpass(2, 5);
        assertEquals((2 << 4) | 5,
                (int) connection.registers().get(L3g4200dMinimal.REG_CTRL_REG2));
        assertEquals(0x40 | 0x10,
                (int) connection.registers().get(L3g4200dMinimal.REG_CTRL_REG5));
        sensor.disableHighpass();
        assertEquals(0x40,
                (int) connection.registers().get(L3g4200dMinimal.REG_CTRL_REG5));

        // set_interrupt(latch=true, x_high, y_high, z_high): INT1_CFG = 0x40|0x20|0x08|0x02 = 0x6A.
        sensor.setInterrupt(true, false, true, false, true, false, false, true);
        assertEquals(0x6A,
                (int) connection.registers().get(L3g4200dMinimal.REG_INT1_CFG));

        // set_threshold('x', 87.5): current fullScale=2000, sens=0.07, raw=int(87.5/0.07)=1250=0x04E2.
        //   XH = (1250>>8)&0x7F = 0x04, XL = 0xE2.
        sensor.setThreshold('x', 87.5f);
        assertEquals(0x04,
                (int) connection.registers().get(L3g4200dMinimal.REG_INT1_THS_XH));
        assertEquals(0xE2,
                (int) connection.registers().get(L3g4200dMinimal.REG_INT1_THS_XL));

        // set_duration(4, wait=true): INT1_DURATION = 0x80|4 = 0x84.
        sensor.setDuration(4, true);
        assertEquals(0x84,
                (int) connection.registers().get(L3g4200dMinimal.REG_INT1_DURATION));

        // set_data_ready_pin(true).
        connection.setRegister(L3g4200dMinimal.REG_CTRL_REG3, 0x00);
        sensor.setDataReadyPin(true);
        assertEquals(0x08,
                (int) connection.registers().get(L3g4200dMinimal.REG_CTRL_REG3));
    }
}
