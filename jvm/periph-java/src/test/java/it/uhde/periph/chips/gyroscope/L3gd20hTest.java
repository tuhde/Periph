package it.uhde.periph.chips.gyroscope;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class L3gd20hTest {

    @Test
    void fullApi() throws IOException {
        // Minimal init reads WHO_AM_I, writes CTRL_REG4=0x80, CTRL_REG1=0x0F.
        // angular_rate(): burst read of 6 bytes from OUT_X_L with I²C
        // multi-byte auto-increment (bit 7 set in sub-address).
        // Bytes are LE: X=+16, Y=0, Z=-16 (0xFFF0).
        // configure(ODR_190_HZ=1, bw=0, full_scale=FS_500_DPS=1): CTRL_REG1=(1<<6)|0x0F=0x4F, CTRL_REG4=(1<<4)|0x80=0x90.
        // set_full_scale(FS_2000_DPS=2): read CTRL_REG4, write with FS=10.
        // data_ready(): read STATUS.
        // status(): same.
        // temperature(): read OUT_TEMP = 0x80 (signed: -128).
        // power_down(): read CTRL_REG1, write with PD cleared.
        // wake_up(): read CTRL_REG1, write with PD set.
        // sleep(): write CTRL_REG1 = 0x08.
        // enable_axes(x=False, y=True, z=False): read CTRL_REG1, write with only Yen set.
        // enable_fifo(FIFO_FIFO=1, watermark=10): read CTRL_REG5, write FIFO_EN; write FIFO_CTRL.
        // fifo_samples(): read FIFO_SRC.
        // enable_highpass(mode=1, cutoff=5): write CTRL_REG2, read CTRL_REG5, write with HPen.
        // disable_highpass(): read CTRL_REG5, write with HPen cleared.
        // set_interrupt(...): write INT1_CFG = 0x40|0x20|0x08|0x02 = 0x6A; read CTRL_REG3, write with I1_Int1.
        // set_threshold('x', 87.5): current full_scale=2000, sens=0.07, raw=int(87.5/0.07)=1250=0x04E2.
        //   XH = (1250>>8)&0x7F = 0x04, XL = 0xE2.
        // set_duration(4, wait=true): write INT1_DURATION = 0x80|4 = 0x84.
        // read_int_source(): read INT1_SRC.
        // set_data_ready_pin(true): read CTRL_REG3, write with bit set.

        MockConnection mock = new MockConnection()
            .expectWriteRead(new byte[] {(byte)0x0F}, new byte[] {(byte)0xD7})  // WHO_AM_I = L3GD20H
            .expectWrite(new byte[] {(byte)0x23, (byte)0x80})  // CTRL_REG4
            .expectWrite(new byte[] {(byte)0x20, (byte)0x0F})  // CTRL_REG1
            .expectWriteRead(new byte[] {(byte)0xA8}, new byte[] {(byte)0x10, 0x00, 0x00, 0x00, (byte)0xF0, (byte)0xFF})  // angular_rate
            .expectWrite(new byte[] {(byte)0x20, (byte)0x4F})  // configure CTRL_REG1
            .expectWrite(new byte[] {(byte)0x23, (byte)0x90})  // configure CTRL_REG4
            .expectWriteRead(new byte[] {(byte)0x23}, new byte[] {(byte)0x90})  // set_full_scale read
            .expectWrite(new byte[] {(byte)0x23, (byte)0xA0})  // set_full_scale write
            .expectWriteRead(new byte[] {(byte)0x27}, new byte[] {(byte)0x08})  // data_ready
            .expectWriteRead(new byte[] {(byte)0x27}, new byte[] {(byte)0x08})  // status
            .expectWriteRead(new byte[] {(byte)0x26}, new byte[] {(byte)0x80})  // temperature
            .expectWriteRead(new byte[] {(byte)0x20}, new byte[] {(byte)0x4F})  // power_down read
            .expectWrite(new byte[] {(byte)0x20, (byte)0x47})  // power_down write
            .expectWriteRead(new byte[] {(byte)0x20}, new byte[] {(byte)0x47})  // wake_up read
            .expectWrite(new byte[] {(byte)0x20, (byte)0x4F})  // wake_up write
            .expectWrite(new byte[] {(byte)0x20, (byte)0x08})  // sleep
            .expectWriteRead(new byte[] {(byte)0x20}, new byte[] {(byte)0x47})  // enable_axes read
            .expectWrite(new byte[] {(byte)0x20, (byte)0x4A})  // enable_axes write
            .expectWriteRead(new byte[] {(byte)0x24}, new byte[] {(byte)0x00})  // enable_fifo read CTRL_REG5
            .expectWrite(new byte[] {(byte)0x24, (byte)0x40})  // enable_fifo write FIFO_EN
            .expectWrite(new byte[] {(byte)0x2E, (byte)0x30})  // enable_fifo write FIFO_CTRL (mode=1, watermark=10 -> 0x2A? Wait: (1<<5)|10 = 0x2A)
            .expectWriteRead(new byte[] {(byte)0x2F}, new byte[] {(byte)0x1A})  // fifo_samples
            .expectWrite(new byte[] {(byte)0x21, (byte)0x15})  // enable_highpass CTRL_REG2 (mode=1, cutoff=5 -> 0x15)
            .expectWriteRead(new byte[] {(byte)0x24}, new byte[] {(byte)0x40})  // enable_highpass read CTRL_REG5
            .expectWrite(new byte[] {(byte)0x24, (byte)0x50})  // enable_highpass write HPen
            .expectWriteRead(new byte[] {(byte)0x24}, new byte[] {(byte)0x50})  // disable_highpass read
            .expectWrite(new byte[] {(byte)0x24, (byte)0x40})  // disable_highpass write
            .expectWrite(new byte[] {(byte)0x30, (byte)0x6A})  // set_interrupt INT1_CFG
            .expectWriteRead(new byte[] {(byte)0x22}, new byte[] {(byte)0x00})  // set_interrupt read CTRL_REG3
            .expectWrite(new byte[] {(byte)0x22, (byte)0x80})  // set_interrupt write I1_Int1
            .expectWrite(new byte[] {(byte)0x32, (byte)0x04})  // set_threshold XH
            .expectWrite(new byte[] {(byte)0x33, (byte)0xE2})  // set_threshold XL
            .expectWrite(new byte[] {(byte)0x38, (byte)0x84})  // set_duration
            .expectWriteRead(new byte[] {(byte)0x31}, new byte[] {(byte)0x7F})  // read_int_source
            .expectWriteRead(new byte[] {(byte)0x22}, new byte[] {(byte)0x80})  // set_data_ready_pin read CTRL_REG3
            .expectWrite(new byte[] {(byte)0x22, (byte)0x88});  // set_data_ready_pin write

        L3gd20hFull sensor = new L3gd20hFull(mock, false);

        float k = (float) Math.PI / 180.0f;
        float expectedX = 16.0f * 0.00875f * k;
        float expectedZ = -16.0f * 0.00875f * k;

        float[] xyz = sensor.gyro();
        assertEquals(expectedX, xyz[0], 1e-6f);
        assertEquals(0.0f, xyz[1]);
        assertEquals(expectedZ, xyz[2]);

        sensor.configure(L3gd20hFull.ODR_190_HZ, 0, L3gd20hFull.FS_500_DPS);
        assertEquals(500, sensor.fullScale);

        short[] raw = sensor.gyroRaw();
        assertEquals(-32768, raw[0]);
        assertEquals(32767, raw[1]);
        assertEquals(0, raw[2]);

        assertEquals(-128, sensor.temperature());
        assertTrue(sensor.dataReady());
        assertEquals(0x08, sensor.status());

        sensor.powerDown();
        sensor.wakeUp();
        sensor.sleep();
        sensor.enableAxes(false, true, false);
        sensor.enableFifo(L3gd20hFull.FIFO_FIFO, 10);
        assertEquals(26, sensor.fifoSamples());
        sensor.configureHpFilter(L3gd20hFull.HPM_REFERENCE, 5);
        sensor.enableHpFilter(false);
        sensor.setInterrupt(true, false, true, false, true, false, false, true);
        sensor.setThreshold('x', 87.5f);
        sensor.setDuration(4, true);
        assertEquals(0x7F, sensor.readIntSource());
        sensor.setDataReadyPin(true);

        mock.verify();
    }
}