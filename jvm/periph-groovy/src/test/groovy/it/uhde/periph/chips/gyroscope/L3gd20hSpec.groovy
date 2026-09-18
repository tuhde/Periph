package it.uhde.periph.chips.gyroscope

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class L3gd20hSpec extends Specification {

    def "full API"() {
        given:
        def mock = new MockConnection()
            .expectWriteRead([0x0F] as byte[], [0xD7] as byte[])  // WHO_AM_I
            .expectWrite([0x23, 0x80] as byte[])  // CTRL_REG4
            .expectWrite([0x20, 0x0F] as byte[])  // CTRL_REG1
            .expectWriteRead([0xA8] as byte[], [0x10, 0x00, 0x00, 0x00, -16 & 0xFF, -16 >> 8] as byte[])  // gyro
            .expectWrite([0x20, 0x4F] as byte[])  // configure CTRL_REG1
            .expectWrite([0x23, 0x90] as byte[])  // configure CTRL_REG4
            .expectWriteRead([0x23] as byte[], [0x90] as byte[])  // set_full_scale read
            .expectWrite([0x23, 0xA0] as byte[])  // set_full_scale write
            .expectWriteRead([0x27] as byte[], [0x08] as byte[])  // data_ready
            .expectWriteRead([0x27] as byte[], [0x08] as byte[])  // status
            .expectWriteRead([0x26] as byte[], [-128] as byte[])  // temperature
            .expectWriteRead([0x20] as byte[], [0x4F] as byte[])  // power_down read
            .expectWrite([0x20, 0x47] as byte[])  // power_down write
            .expectWriteRead([0x20] as byte[], [0x47] as byte[])  // wake_up read
            .expectWrite([0x20, 0x4F] as byte[])  // wake_up write
            .expectWrite([0x20, 0x08] as byte[])  // sleep
            .expectWriteRead([0x20] as byte[], [0x47] as byte[])  // enable_axes read
            .expectWrite([0x20, 0x4A] as byte[])  // enable_axes write
            .expectWriteRead([0x24] as byte[], [0x00] as byte[])  // enable_fifo read CTRL_REG5
            .expectWrite([0x24, 0x40] as byte[])  // enable_fifo write FIFO_EN
            .expectWrite([0x2E, 0x2A] as byte[])  // enable_fifo write FIFO_CTRL
            .expectWriteRead([0x2F] as byte[], [0x1A] as byte[])  // fifo_samples
            .expectWrite([0x21, 0x15] as byte[])  // enable_highpass CTRL_REG2
            .expectWriteRead([0x24] as byte[], [0x40] as byte[])  // enable_highpass read CTRL_REG5
            .expectWrite([0x24, 0x50] as byte[])  // enable_highpass write HPen
            .expectWriteRead([0x24] as byte[], [0x50] as byte[])  // disable_highpass read
            .expectWrite([0x24, 0x40] as byte[])  // disable_highpass write
            .expectWrite([0x30, 0x6A] as byte[])  // set_interrupt INT1_CFG
            .expectWriteRead([0x22] as byte[], [0x00] as byte[])  // set_interrupt read CTRL_REG3
            .expectWrite([0x22, 0x80] as byte[])  // set_interrupt write I1_Int1
            .expectWrite([0x32, 0x04] as byte[])  // set_threshold XH
            .expectWrite([0x33, -30 & 0xFF] as byte[])  // set_threshold XL (0xE2)
            .expectWrite([0x38, 0x84] as byte[])  // set_duration
            .expectWriteRead([0x31] as byte[], [0x7F] as byte[])  // read_int_source
            .expectWriteRead([0x22] as byte[], [0x80] as byte[])  // set_data_ready_pin read CTRL_REG3
            .expectWrite([0x22, 0x88] as byte[])  // set_data_ready_pin write

        def sensor = new L3gd20hFull(mock, false)

        expect:
        def k = Math.PI / 180.0f
        def expectedX = 16.0f * 0.00875f * k
        def expectedZ = -16.0f * 0.00875f * k

        def xyz = sensor.gyro()
        (xyz[0] - expectedX).abs() < 1e-6f
        xyz[1] == 0.0f
        (xyz[2] - expectedZ).abs() < 1e-6f

        sensor.configure(L3gd20hFull.ODR_190_HZ, 0, L3gd20hFull.FS_500_DPS)
        sensor.fullScale == 500

        def raw = sensor.gyroRaw()
        raw[0] == -32768
        raw[1] == 32767
        raw[2] == 0

        sensor.temperature() == -128
        sensor.dataReady()
        sensor.status() == 0x08

        sensor.powerDown()
        sensor.wakeUp()
        sensor.sleep()
        sensor.enableAxes(false, true, false)
        sensor.enableFifo(L3gd20hFull.FIFO_FIFO, 10)
        sensor.fifoSamples() == 26
        sensor.configureHpFilter(L3gd20hFull.HPM_REFERENCE, 5)
        sensor.enableHpFilter(false)
        sensor.setInterrupt(true, false, true, false, true, false, false, true)
        sensor.setThreshold('x', 87.5f)
        sensor.setDuration(4, true)
        sensor.readIntSource() == 0x7F
        sensor.setDataReadyPin(true)

        mock.verify()
    }
}